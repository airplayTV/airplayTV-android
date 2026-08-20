package com.airplay.tv.protocol

import com.airplay.tv.core.config.AppConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal fun interface WebSocketConnector {
    fun connect(request: Request, listener: WebSocketListener): WebSocket
}

class OkHttpSocketClient internal constructor(
    private val connector: WebSocketConnector,
    private val parser: SocketMessageParser = SocketMessageParser(),
    private val webSocketUrl: String = AppConfig.WEBSOCKET_URL,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val randomUnit: () -> Double = { Random.nextDouble() },
) : SocketClient {
    constructor(
        okHttpClient: OkHttpClient,
        parser: SocketMessageParser = SocketMessageParser(),
        webSocketUrl: String = AppConfig.WEBSOCKET_URL,
        coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        randomUnit: () -> Double = { Random.nextDouble() },
    ) : this(
        connector = WebSocketConnector(okHttpClient::newWebSocket),
        parser = parser,
        webSocketUrl = webSocketUrl,
        coroutineDispatcher = coroutineDispatcher,
        reconnectPolicy = reconnectPolicy,
        randomUnit = randomUnit,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val mutableStates = MutableStateFlow(SocketConnectionState.Closed)
    private val mutableConnectionGeneration = MutableStateFlow(0L)
    private val mutableCommands = MutableSharedFlow<ReceivedControlCommand>(
        extraBufferCapacity = COMMAND_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutablePlaybackHistoryAcks = MutableSharedFlow<PlaybackHistoryAck>(
        extraBufferCapacity = ACK_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val states: StateFlow<SocketConnectionState> = mutableStates.asStateFlow()
    override val connectionGeneration: StateFlow<Long> = mutableConnectionGeneration.asStateFlow()
    override val commands: Flow<ReceivedControlCommand> = mutableCommands.asSharedFlow()
    override val playbackHistoryAcks: Flow<PlaybackHistoryAck> =
        mutablePlaybackHistoryAcks.asSharedFlow()

    private var activeConnection: ConnectionContext? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var manuallyClosed = false

    override fun connect(roomId: String) {
        require(roomId.isNotBlank()) { "roomId must not be blank" }

        val previousSocket: WebSocket?
        val connection: ConnectionContext
        synchronized(lock) {
            check(!manuallyClosed) { "Socket client is closed" }
            reconnectJob?.cancel()
            reconnectJob = null
            activeConnection?.joinTimeoutJob?.cancel()
            previousSocket = activeConnection?.webSocket
            reconnectAttempt = 0
            connection = ConnectionContext(advanceGeneration(), roomId)
            activeConnection = connection
            updateState(SocketConnectionState.Connecting)
        }
        previousSocket?.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
        openWebSocket(connection)
    }

    override fun sendPlaybackHistory(message: PlaybackHistoryMessage): Boolean {
        val payload = PlaybackHistoryProtocol.toJson(message)
        return synchronized(lock) {
            val connection = activeConnection ?: return@synchronized false
            val webSocket = connection.webSocket ?: return@synchronized false
            if (
                !isCurrent(connection) ||
                connection.phase != ConnectionPhase.Connected
            ) {
                return@synchronized false
            }
            webSocket.send(payload)
        }
    }

    override fun close() {
        val socketToClose: WebSocket?
        synchronized(lock) {
            if (manuallyClosed) {
                return
            }
            manuallyClosed = true
            advanceGeneration()
            reconnectJob?.cancel()
            reconnectJob = null
            activeConnection?.joinTimeoutJob?.cancel()
            socketToClose = activeConnection?.webSocket
            activeConnection = null
            updateState(SocketConnectionState.Closed)
        }
        socketToClose?.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
        scope.cancel()
    }

    private fun openWebSocket(connection: ConnectionContext) {
        val request = Request.Builder().url(webSocketUrl).build()
        val webSocket = connector.connect(request, listener(connection))
        val shouldClose = synchronized(lock) {
            if (!isCurrent(connection)) {
                true
            } else {
                bindSocket(connection, webSocket).not()
            }
        }
        if (shouldClose) {
            webSocket.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
        }
    }

    private fun listener(connection: ConnectionContext): WebSocketListener {
        val disconnected = AtomicBoolean(false)
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val joinMessage = joinGroupMessage(connection.roomId)
                val result = synchronized(lock) {
                    if (
                        !isCurrent(connection) ||
                        !bindSocket(connection, webSocket) ||
                        connection.phase != ConnectionPhase.Connecting
                    ) {
                        OpenResult.Stale
                    } else {
                        reconnectJob = null
                        val sent = webSocket.send(joinMessage)
                        if (
                            !isCurrent(connection) ||
                            connection.webSocket !== webSocket ||
                            connection.phase != ConnectionPhase.Connecting
                        ) {
                            OpenResult.Stale
                        } else if (sent) {
                            OpenResult.JoinSent
                        } else {
                            OpenResult.SendFailed
                        }
                    }
                }
                when (result) {
                    OpenResult.Stale -> webSocket.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
                    OpenResult.SendFailed -> {
                        webSocket.cancel()
                        handleDisconnect(connection, webSocket, disconnected)
                    }
                    OpenResult.JoinSent -> scheduleJoinTimeout(connection, webSocket, disconnected)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val phase = synchronized(lock) {
                    if (isCurrent(connection) && connection.webSocket === webSocket) {
                        connection.phase
                    } else {
                        null
                    }
                }
                if (phase == null) {
                    return
                }
                if (phase == ConnectionPhase.Connecting) {
                    when (parseJoinAck(text, connection.roomId)) {
                        JoinAck.Accepted -> synchronized(lock) {
                            if (
                                isCurrent(connection) &&
                                connection.webSocket === webSocket &&
                                connection.phase == ConnectionPhase.Connecting
                            ) {
                                reconnectAttempt = 0
                                connection.joinTimeoutJob?.cancel()
                                connection.joinTimeoutJob = null
                                connection.phase = ConnectionPhase.Connected
                                updateState(SocketConnectionState.Connected)
                            }
                        }
                        JoinAck.Rejected -> disconnectPendingJoin(
                            connection,
                            webSocket,
                            disconnected,
                            JOIN_REJECTED_REASON,
                        )
                        JoinAck.NotJoinAck -> Unit
                    }
                    return
                }
                if (phase != ConnectionPhase.Connected) return
                val ack = PlaybackHistoryProtocol.parseAck(text)
                if (ack != null) {
                    synchronized(lock) {
                        if (
                            isCurrent(connection) &&
                            connection.webSocket === webSocket &&
                            connection.phase == ConnectionPhase.Connected
                        ) {
                            mutablePlaybackHistoryAcks.tryEmit(ack)
                        }
                    }
                    return
                }
                val command = parser.parse(text, connection.roomId) ?: return
                synchronized(lock) {
                    if (
                        isCurrent(connection) &&
                        connection.webSocket === webSocket &&
                        connection.phase == ConnectionPhase.Connected
                    ) {
                        mutableCommands.tryEmit(
                            ReceivedControlCommand(command, connection.generation),
                        )
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(connection, webSocket, disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LOGGER.warning("WebSocket failure: ${t.javaClass.name}")
                handleDisconnect(connection, webSocket, disconnected)
            }
        }
    }

    private fun handleDisconnect(
        connection: ConnectionContext,
        webSocket: WebSocket,
        disconnected: AtomicBoolean,
    ) {
        val scheduledReconnect = synchronized(lock) {
            if (
                !isCurrent(connection) ||
                !bindSocket(connection, webSocket) ||
                !disconnected.compareAndSet(false, true)
            ) {
                return
            }
            advanceGeneration()
            val reconnectGeneration = generation
            connection.joinTimeoutJob?.cancel()
            connection.joinTimeoutJob = null
            connection.webSocket = null
            connection.phase = ConnectionPhase.Reconnecting
            val attempt = reconnectAttempt++
            reconnectJob?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(reconnectPolicy.delayForAttempt(attempt, randomUnit()))
                val nextConnection = synchronized(lock) {
                    if (
                        manuallyClosed ||
                        activeConnection !== connection ||
                        generation != reconnectGeneration
                    ) {
                        return@launch
                    }
                    val replacement = ConnectionContext(
                        generation = advanceGeneration(),
                        roomId = connection.roomId,
                    )
                    activeConnection = replacement
                    updateState(SocketConnectionState.Connecting)
                    replacement
                }
                openWebSocket(nextConnection)
            }
            reconnectJob = job
            updateState(SocketConnectionState.Reconnecting)
            job
        }
        scheduledReconnect.start()
    }

    private fun isCurrent(connection: ConnectionContext): Boolean =
        !manuallyClosed &&
            activeConnection === connection &&
            generation == connection.generation

    private fun bindSocket(connection: ConnectionContext, webSocket: WebSocket): Boolean {
        val registered = connection.webSocket
        return if (registered == null) {
            connection.webSocket = webSocket
            true
        } else {
            registered === webSocket
        }
    }

    private fun updateState(state: SocketConnectionState) {
        mutableStates.value = state
    }

    private fun advanceGeneration(): Long {
        generation += 1
        mutableConnectionGeneration.value = generation
        return generation
    }

    private fun joinGroupMessage(roomId: String): String {
        val data = JsonObject().apply { addProperty("group", roomId) }
        return JsonObject().apply {
            addProperty("event", "join-group")
            add("data", data)
        }.toString()
    }

    private fun scheduleJoinTimeout(
        connection: ConnectionContext,
        webSocket: WebSocket,
        disconnected: AtomicBoolean,
    ) {
        val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(JOIN_ACK_TIMEOUT_MS)
            disconnectPendingJoin(
                connection,
                webSocket,
                disconnected,
                JOIN_TIMEOUT_REASON,
            )
        }
        val shouldStart = synchronized(lock) {
            if (
                isCurrent(connection) &&
                connection.webSocket === webSocket &&
                connection.phase == ConnectionPhase.Connecting
            ) {
                connection.joinTimeoutJob?.cancel()
                connection.joinTimeoutJob = timeoutJob
                true
            } else {
                false
            }
        }
        if (shouldStart) timeoutJob.start() else timeoutJob.cancel()
    }

    private fun disconnectPendingJoin(
        connection: ConnectionContext,
        webSocket: WebSocket,
        disconnected: AtomicBoolean,
        reason: String,
    ) {
        val claimed = synchronized(lock) {
            if (
                isCurrent(connection) &&
                connection.webSocket === webSocket &&
                connection.phase == ConnectionPhase.Connecting
            ) {
                connection.phase = ConnectionPhase.Reconnecting
                connection.joinTimeoutJob?.cancel()
                connection.joinTimeoutJob = null
                true
            } else {
                false
            }
        }
        if (!claimed) return
        webSocket.close(NORMAL_CLOSURE_CODE, reason)
        handleDisconnect(connection, webSocket, disconnected)
    }

    private fun parseJoinAck(text: String, roomId: String): JoinAck {
        return try {
            val root = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?: return JoinAck.NotJoinAck
            if (root.get("event")?.takeIf { it.isJsonPrimitive }?.asString != "join-group") {
                return JoinAck.NotJoinAck
            }
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return JoinAck.Rejected
            val codeValue = data.get("code")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isNumber }?.asBigDecimal ?: return JoinAck.Rejected
            val code = try {
                codeValue.intValueExact()
            } catch (_: ArithmeticException) {
                return JoinAck.Rejected
            }
            val ackRoom = data.get("group")?.takeIf { it.isJsonPrimitive }?.asString
            if (ackRoom != null && ackRoom != roomId) return JoinAck.Rejected
            if (code == JOIN_ACCEPTED_CODE) JoinAck.Accepted else JoinAck.Rejected
        } catch (_: RuntimeException) {
            JoinAck.NotJoinAck
        }
    }

    private companion object {
        const val ACK_BUFFER_CAPACITY = 64
        const val COMMAND_BUFFER_CAPACITY = 64
        const val NORMAL_CLOSURE_CODE = 1000
        const val NORMAL_CLOSURE_REASON = "client closed"
        const val JOIN_ACCEPTED_CODE = 200
        const val JOIN_ACK_TIMEOUT_MS = 10_000L
        const val JOIN_REJECTED_REASON = "join rejected"
        const val JOIN_TIMEOUT_REASON = "join timeout"
        val LOGGER: Logger = Logger.getLogger(OkHttpSocketClient::class.java.name)
    }

    private enum class OpenResult {
        Stale,
        SendFailed,
        JoinSent,
    }

    private enum class JoinAck {
        Accepted,
        Rejected,
        NotJoinAck,
    }

    private class ConnectionContext(
        val generation: Long,
        val roomId: String,
        var webSocket: WebSocket? = null,
        var phase: ConnectionPhase = ConnectionPhase.Connecting,
        var joinTimeoutJob: Job? = null,
    )

    private enum class ConnectionPhase {
        Connecting,
        Connected,
        Reconnecting,
    }
}
