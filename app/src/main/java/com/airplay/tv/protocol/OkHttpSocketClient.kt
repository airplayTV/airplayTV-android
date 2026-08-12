package com.airplay.tv.protocol

import com.airplay.tv.core.config.AppConfig
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    private val mutableCommands = MutableSharedFlow<ControlCommand>(extraBufferCapacity = COMMAND_BUFFER_CAPACITY)

    override val states: StateFlow<SocketConnectionState> = mutableStates.asStateFlow()
    override val commands: Flow<ControlCommand> = mutableCommands.asSharedFlow()

    private var activeWebSocket: WebSocket? = null
    private var activeRoomId: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var manuallyClosed = false

    override fun connect(roomId: String) {
        require(roomId.isNotBlank()) { "roomId must not be blank" }

        val previousSocket: WebSocket?
        val connectionGeneration: Long
        synchronized(lock) {
            check(!manuallyClosed) { "Socket client is closed" }
            reconnectJob?.cancel()
            reconnectJob = null
            previousSocket = activeWebSocket
            activeWebSocket = null
            activeRoomId = roomId
            reconnectAttempt = 0
            connectionGeneration = ++generation
            updateState(SocketConnectionState.Connecting)
        }
        previousSocket?.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
        openWebSocket(roomId, connectionGeneration)
    }

    override fun close() {
        val socketToClose: WebSocket?
        synchronized(lock) {
            if (manuallyClosed) {
                return
            }
            manuallyClosed = true
            generation++
            reconnectJob?.cancel()
            reconnectJob = null
            socketToClose = activeWebSocket
            activeWebSocket = null
            updateState(SocketConnectionState.Closed)
        }
        socketToClose?.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
        scope.cancel()
    }

    private fun openWebSocket(roomId: String, connectionGeneration: Long) {
        val request = Request.Builder().url(webSocketUrl).build()
        val webSocket = connector.connect(request, listener(roomId, connectionGeneration))
        val shouldClose = synchronized(lock) {
            if (manuallyClosed || generation != connectionGeneration) {
                true
            } else {
                activeWebSocket = webSocket
                false
            }
        }
        if (shouldClose) {
            webSocket.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
        }
    }

    private fun listener(roomId: String, connectionGeneration: Long): WebSocketListener {
        val disconnected = AtomicBoolean(false)
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val joinMessage = joinGroupMessage(roomId)
                val result = synchronized(lock) {
                    if (
                        manuallyClosed ||
                        generation != connectionGeneration ||
                        activeWebSocket !== webSocket
                    ) {
                        OpenResult.Stale
                    } else {
                        reconnectAttempt = 0
                        reconnectJob = null
                        if (webSocket.send(joinMessage)) {
                            updateState(SocketConnectionState.Connected)
                            OpenResult.Connected
                        } else {
                            OpenResult.SendFailed
                        }
                    }
                }
                when (result) {
                    OpenResult.Stale -> webSocket.close(NORMAL_CLOSURE_CODE, NORMAL_CLOSURE_REASON)
                    OpenResult.SendFailed -> {
                        webSocket.cancel()
                        handleDisconnect(roomId, connectionGeneration, disconnected)
                    }
                    OpenResult.Connected -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val isCurrent = synchronized(lock) {
                    !manuallyClosed &&
                        generation == connectionGeneration &&
                        activeWebSocket === webSocket
                }
                if (!isCurrent) {
                    return
                }
                val command = parser.parse(text, roomId) ?: return
                synchronized(lock) {
                    if (
                        !manuallyClosed &&
                        generation == connectionGeneration &&
                        activeWebSocket === webSocket
                    ) {
                        mutableCommands.tryEmit(command)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(roomId, connectionGeneration, disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LOGGER.warning("WebSocket failure: ${t.javaClass.name}")
                handleDisconnect(roomId, connectionGeneration, disconnected)
            }
        }
    }

    private fun handleDisconnect(
        roomId: String,
        connectionGeneration: Long,
        disconnected: AtomicBoolean,
    ) {
        if (!disconnected.compareAndSet(false, true)) {
            return
        }

        synchronized(lock) {
            if (manuallyClosed || generation != connectionGeneration || activeRoomId != roomId) {
                return
            }
            generation++
            val reconnectGeneration = generation
            activeWebSocket = null
            updateState(SocketConnectionState.Reconnecting)
            val attempt = reconnectAttempt++
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(reconnectPolicy.delayForAttempt(attempt, randomUnit()))
                val nextGeneration = synchronized(lock) {
                    if (
                        manuallyClosed ||
                        activeRoomId != roomId ||
                        generation != reconnectGeneration
                    ) {
                        return@launch
                    }
                    updateState(SocketConnectionState.Connecting)
                    ++generation
                }
                openWebSocket(roomId, nextGeneration)
            }
        }
    }

    private fun updateState(state: SocketConnectionState) {
        mutableStates.value = state
    }

    private fun joinGroupMessage(roomId: String): String {
        val data = JsonObject().apply { addProperty("group", roomId) }
        return JsonObject().apply {
            addProperty("event", "join-group")
            add("data", data)
        }.toString()
    }

    private companion object {
        const val COMMAND_BUFFER_CAPACITY = 64
        const val NORMAL_CLOSURE_CODE = 1000
        const val NORMAL_CLOSURE_REASON = "client closed"
        val LOGGER: Logger = Logger.getLogger(OkHttpSocketClient::class.java.name)
    }

    private enum class OpenResult {
        Stale,
        SendFailed,
        Connected,
    }
}
