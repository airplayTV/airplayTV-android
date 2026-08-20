package com.airplay.tv.protocol

import com.airplay.tv.feature.history.PlaybackRecord
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpSocketClientTest {
    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private var client: OkHttpSocketClient? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        okHttpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        client?.close()
        server.shutdown()
        okHttpClient.dispatcher.executorService.shutdownNow()
        okHttpClient.connectionPool.evictAll()
    }

    @Test
    fun joinsRoomAfterOpenAndEmitsOnlyParsedCommands() = runTest {
        val roomId = "room-1"
        val receivedJoin = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = JsonParser.parseString(text).asJsonObject
                        assertEquals(expectedJoin(roomId), json)
                        receivedJoin.countDown()
                        webSocket.send(joinAck(code = 200, roomId = roomId))
                        webSocket.send(controlMessage("/unknown", roomId))
                        webSocket.send(controlMessage("/ctl_play", roomId))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        val socketClient = createClient(StandardTestDispatcher(testScheduler))
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }

        socketClient.connect(roomId)

        assertEquals(ControlCommand.Play, command.await().command)
        assertTrue(receivedJoin.await(5, TimeUnit.SECONDS))
        assertEquals(SocketConnectionState.Connected, socketClient.states.value)
    }

    @Test
    fun openSocketRemainsConnectingUntilSuccessfulJoinAck() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }
        socketClient.connect("room-1")
        val connection = connector.connections.single()

        connection.open()
        connection.message(controlMessage("/ctl_play", "room-1"))

        assertEquals(SocketConnectionState.Connecting, socketClient.states.value)
        assertFalse(socketClient.sendPlaybackHistory(playbackHistoryMessage()))
        assertFalse(command.isCompleted)

        connection.message(joinAck(code = 200, roomId = "room-1"))
        connection.message(controlMessage("/ctl_pause", "room-1"))

        assertEquals(SocketConnectionState.Connected, socketClient.states.value)
        assertTrue(socketClient.sendPlaybackHistory(playbackHistoryMessage()))
        assertEquals(ControlCommand.Pause, command.await().command)
    }

    @Test
    fun rejectedJoinAckClosesSocketAndUsesReconnectBackoff() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.open()

        connection.message(joinAck(code = 409, roomId = "room-1"))

        assertEquals(SocketConnectionState.Reconnecting, socketClient.states.value)
        assertEquals(listOf(1000), connection.webSocket.closeCodes)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, connector.connections.size)
    }

    @Test
    fun emittedCommandCarriesTheCurrentConnectionGeneration() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val received = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }

        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.open()
        connection.acceptJoin("room-1")
        connection.message(controlMessage("/ctl_play", "room-1"))

        assertEquals(
            ReceivedControlCommand(ControlCommand.Play, socketClient.connectionGeneration.value),
            received.await(),
        )
    }

    @Test
    fun disconnectedSendReturnsFalseWithoutQueueing() {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher())

        assertFalse(socketClient.sendPlaybackHistory(playbackHistoryMessage()))
        assertTrue(connector.connections.isEmpty())

        socketClient.connect("room-1")
        val connection = connector.connections.single()
        assertFalse(socketClient.sendPlaybackHistory(playbackHistoryMessage()))
        assertTrue(connection.webSocket.sentTexts.isEmpty())

        connection.open()
        assertEquals(
            listOf(expectedJoin("room-1").toString()),
            connection.webSocket.sentTexts,
        )
    }

    @Test
    fun connectedSendWritesAllowlistedPlaybackHistoryDirectly() {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher())
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.open()
        connection.acceptJoin("room-1")

        assertTrue(socketClient.sendPlaybackHistory(playbackHistoryMessage()))

        assertEquals(
            PlaybackHistoryProtocol.toJson(playbackHistoryMessage()),
            connection.webSocket.sentTexts.last(),
        )
    }

    @Test
    fun playbackHistoryAckUsesIndependentFlowAndIsNotAControlCommand() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val ack = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.playbackHistoryAcks.first()
        }
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.open()
        connection.acceptJoin("room-1")

        connection.message(
            """{"event":"tv-playback-history-ack","data":{"request_id":"request-1","accepted":true,"recipient_count":2}}""",
        )

        assertEquals(PlaybackHistoryAck("request-1", true, 2), ack.await())
        assertFalse(command.isCompleted)
        command.cancel()
    }

    @Test
    fun malformedPlaybackHistoryAcksEnterNeitherAckNorControlFlow() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val ack = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.playbackHistoryAcks.first()
        }
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.open()
        connection.acceptJoin("room-1")

        listOf("\"2\"", "true", "null", "1.5", "-1", "2147483648").forEach {
            connection.message(
                """{"event":"tv-playback-history-ack","data":{"request_id":"request-1","accepted":true,"recipient_count":$it}}""",
            )
        }

        assertFalse(ack.isCompleted)
        assertFalse(command.isCompleted)
        ack.cancel()
        command.cancel()
    }

    @Test
    fun reconnectPolicyUsesExponentialScheduleCapsAndAppliesInjectedJitter() {
        val policy = ReconnectPolicy()
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        assertEquals(expected, (0..5).map { policy.delayForAttempt(it, randomUnit = 0.5) })
        assertEquals(30_000L, policy.delayForAttempt(99, randomUnit = 0.5))
        assertEquals((1_000L * 0.8).roundToLong(), policy.delayForAttempt(0, randomUnit = 0.0))
        assertEquals((1_000L * 1.2).roundToLong(), policy.delayForAttempt(0, randomUnit = 1.0))
        assertEquals(30_000L, policy.delayForAttempt(5, randomUnit = 1.0))
    }

    @Test
    fun joinPayloadEscapesSpecialRoomIdAsValidJson() {
        val roomId = "room-\"quoted\\path"
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher())
        socketClient.connect(roomId)

        connector.connections.single().open()

        val json = JsonParser.parseString(
            connector.connections.single().webSocket.sentTexts.single(),
        ).asJsonObject
        assertEquals(expectedJoin(roomId), json)
    }

    @Test
    fun synchronousOpenBeforeConnectorReturnsJoinsAndWaitsForAck() {
        val connector = RecordingWebSocketConnector(openBeforeReturn = true)
        val socketClient = createClient(connector, StandardTestDispatcher())

        socketClient.connect("room-1")

        val webSocket = connector.connections.single().webSocket
        assertEquals(listOf(expectedJoin("room-1").toString()), webSocket.sentTexts)
        assertTrue(webSocket.closeCodes.isEmpty())
        assertEquals(SocketConnectionState.Connecting, socketClient.states.value)
        connector.connections.single().acceptJoin("room-1")
        assertEquals(SocketConnectionState.Connected, socketClient.states.value)
    }

    @Test
    fun reentrantFailureDuringJoinDoesNotBecomeConnected() {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher())
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.webSocket.onTextSend = connection::fail

        connection.open()

        assertEquals(SocketConnectionState.Reconnecting, socketClient.states.value)
    }

    @Test
    fun staleListenerCannotJoinOrEmitAfterRoomSwitch() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }

        socketClient.connect("old-room")
        val oldConnection = connector.connections.single()
        socketClient.connect("new-room")
        val newConnection = connector.connections.last()

        oldConnection.open()
        oldConnection.message("""{"event":"/ctl_play","group":"old-room"}""")
        assertTrue(oldConnection.webSocket.sentTexts.isEmpty())
        assertFalse(command.isCompleted)

        newConnection.open()
        newConnection.acceptJoin("new-room")
        newConnection.message("""{"event":"/ctl_pause","group":"new-room"}""")
        assertEquals(ControlCommand.Pause, command.await().command)
    }

    @Test
    fun roomSwitchCannotPassAnInFlightJoinSend() {
        val blockingSocket = RecordingWebSocket(blockFirstSend = true)
        val connector = RecordingWebSocketConnector(firstWebSocket = blockingSocket)
        val socketClient = createClient(connector, StandardTestDispatcher())
        socketClient.connect("old-room")
        val oldConnection = connector.connections.single()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val openFuture = executor.submit { oldConnection.open() }
            assertTrue(blockingSocket.sendEntered.await(5, TimeUnit.SECONDS))
            val switchFuture = executor.submit { socketClient.connect("new-room") }

            try {
                switchFuture.get(200, TimeUnit.MILLISECONDS)
                throw AssertionError("room switch passed an in-flight join send")
            } catch (_: TimeoutException) {
                // Expected: send and generation switch share the same critical section.
            }

            blockingSocket.releaseSend.countDown()
            openFuture.get(5, TimeUnit.SECONDS)
            switchFuture.get(5, TimeUnit.SECONDS)
            assertEquals(1, blockingSocket.sentTexts.size)

            oldConnection.open()
            assertEquals(1, blockingSocket.sentTexts.size)
        } finally {
            blockingSocket.releaseSend.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeCannotPassAnInFlightJoinSend() = runTest {
        val blockingSocket = RecordingWebSocket(blockFirstSend = true)
        val connector = RecordingWebSocketConnector(firstWebSocket = blockingSocket)
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val openFuture = executor.submit { connection.open() }
            assertTrue(blockingSocket.sendEntered.await(5, TimeUnit.SECONDS))
            val closeFuture = executor.submit { socketClient.close() }

            try {
                closeFuture.get(200, TimeUnit.MILLISECONDS)
                throw AssertionError("close passed an in-flight join send")
            } catch (_: TimeoutException) {
                // Expected: send and close share the same critical section.
            }

            blockingSocket.releaseSend.countDown()
            openFuture.get(5, TimeUnit.SECONDS)
            closeFuture.get(5, TimeUnit.SECONDS)
            assertEquals(1, blockingSocket.sentTexts.size)

            connection.open()
            connection.message("""{"event":"/ctl_play","group":"room-1"}""")
            assertEquals(1, blockingSocket.sentTexts.size)
            assertFalse(command.isCompleted)
            assertEquals(SocketConnectionState.Closed, socketClient.states.value)
        } finally {
            command.cancel()
            blockingSocket.releaseSend.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun consecutiveFailuresUseFullBackoffAndSuccessResetsAttempt() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        socketClient.connect("room-1")

        expectedDelays.forEachIndexed { index, delayMs ->
            connector.connections[index].fail()
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(index + 1, connector.connections.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 2, connector.connections.size)
        }

        connector.connections.last().open()
        connector.connections.last().acceptJoin("room-1")
        connector.connections.last().fail()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(expectedDelays.size + 1, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(expectedDelays.size + 2, connector.connections.size)
    }

    @Test
    fun joinSendFailuresUseIncreasingBackoffUntilSuccessfulJoin() = runTest {
        val connector = RecordingWebSocketConnector(
            sendResults = listOf(false, false, false, true, true),
        )
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        socketClient.connect("room-1")

        connector.connections[0].open()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, connector.connections.size)

        connector.connections[1].open()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, connector.connections.size)

        connector.connections[2].open()
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(3, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, connector.connections.size)

        connector.connections[3].open()
        connector.connections[3].acceptJoin("room-1")
        assertEquals(SocketConnectionState.Connected, socketClient.states.value)
        connector.connections[3].fail()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(4, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5, connector.connections.size)
    }

    @Test
    fun blockedConsumerOverflowDropsOldestAndRetainsNewestLoad() = runTest {
        val connector = RecordingWebSocketConnector()
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        val releaseFirst = CompletableDeferred<Unit>()
        val received = mutableListOf<ControlCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            socketClient.commands.collect { command ->
                received += command.command
                if (received.size == 1) releaseFirst.await()
            }
        }
        socketClient.connect("room-1")
        val connection = connector.connections.single()
        connection.open()
        connection.acceptJoin("room-1")
        connection.message(controlMessage("/ctl_play", "room-1"))

        repeat(65) { index ->
            connection.message(loadVideoMessage("room-1", "v$index"))
        }
        releaseFirst.complete(Unit)
        runCurrent()

        val videoIds = received.filterIsInstance<ControlCommand.LoadVideo>().map { it.vid }
        assertEquals(64, videoIds.size)
        assertFalse("v0" in videoIds)
        assertTrue("v64" in videoIds)
    }

    @Test
    fun reentrantRoomSwitchWithSynchronousFailureKeepsNewReconnect() = runTest {
        val connector = RecordingWebSocketConnector(failBeforeReturnIndices = setOf(1))
        val socketClient = createClient(connector, StandardTestDispatcher(testScheduler))
        var switchedRoom = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            socketClient.states.collect { state ->
                if (state == SocketConnectionState.Reconnecting && !switchedRoom) {
                    switchedRoom = true
                    socketClient.connect("new-room")
                }
            }
        }
        socketClient.connect("old-room")

        connector.connections.single().fail()

        assertTrue(switchedRoom)
        assertEquals(2, connector.connections.size)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(2, connector.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, connector.connections.size)
        connector.connections.last().open()
        assertEquals(
            listOf(expectedJoin("new-room").toString()),
            connector.connections.last().webSocket.sentTexts,
        )
        collector.cancel()
    }

    @Test
    fun failureEntersReconnectingAndSchedulesNextConnection() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(webSocketResponse())
        val socketClient = createClient(StandardTestDispatcher(testScheduler))
        val reconnecting = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.states.first { it == SocketConnectionState.Reconnecting }
        }

        socketClient.connect("room-1")

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(SocketConnectionState.Reconnecting, reconnecting.await())
        val connectingAgain = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.states.first { it == SocketConnectionState.Connecting }
        }
        advanceTimeBy(999)
        runCurrent()
        assertFalse(connectingAgain.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(connectingAgain.isCompleted)
    }

    @Test
    fun remoteCloseEntersReconnecting() = runTest {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.close(1001, "server shutdown")
                    }
                },
            ),
        )
        val socketClient = createClient(StandardTestDispatcher(testScheduler))
        val reconnecting = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.states.first { it == SocketConnectionState.Reconnecting }
        }

        socketClient.connect("room-1")

        assertEquals(SocketConnectionState.Reconnecting, reconnecting.await())
    }

    @Test
    fun closeCancelsScheduledReconnect() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(webSocketResponse())
        val socketClient = createClient(StandardTestDispatcher(testScheduler))
        val reconnecting = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.states.first { it == SocketConnectionState.Reconnecting }
        }
        socketClient.connect("room-1")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        reconnecting.await()
        val connectingAgain = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.states.first { it == SocketConnectionState.Connecting }
        }

        socketClient.close()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(SocketConnectionState.Closed, socketClient.states.value)
        assertFalse(connectingAgain.isCompleted)
        connectingAgain.cancel()
    }

    @Test
    fun closeUsesNormalWebSocketClosureCode() = runTest {
        val closeCode = java.util.concurrent.atomic.AtomicInteger(-1)
        val closed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(joinAck(code = 200, roomId = "room-1"))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closeCode.set(code)
                        closed.countDown()
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        val socketClient = createClient(StandardTestDispatcher(testScheduler))
        val connected = async(start = CoroutineStart.UNDISPATCHED) {
            socketClient.states.first { it == SocketConnectionState.Connected }
        }
        socketClient.connect("room-1")
        connected.await()

        socketClient.close()

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(1000, closeCode.get())
        assertEquals(SocketConnectionState.Closed, socketClient.states.value)
    }

    private fun createClient(dispatcher: CoroutineDispatcher): OkHttpSocketClient =
        OkHttpSocketClient(
            okHttpClient = okHttpClient,
            webSocketUrl = server.url("/socket").toString(),
            coroutineDispatcher = dispatcher,
            reconnectPolicy = ReconnectPolicy(jitterRatio = 0.0),
            randomUnit = { 0.5 },
        ).also { client = it }

    private fun createClient(
        connector: WebSocketConnector,
        dispatcher: CoroutineDispatcher,
    ): OkHttpSocketClient =
        OkHttpSocketClient(
            connector = connector,
            webSocketUrl = "ws://localhost/socket",
            coroutineDispatcher = dispatcher,
            reconnectPolicy = ReconnectPolicy(jitterRatio = 0.0),
            randomUnit = { 0.5 },
        ).also { client = it }

    private fun webSocketResponse(): MockResponse =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            },
        )

    private class RecordingWebSocketConnector(
        private val firstWebSocket: RecordingWebSocket? = null,
        private val openBeforeReturn: Boolean = false,
        private val failBeforeReturnIndices: Set<Int> = emptySet(),
        private val sendResults: List<Boolean> = emptyList(),
    ) : WebSocketConnector {
        val connections = CopyOnWriteArrayList<RecordingConnection>()

        override fun connect(request: Request, listener: WebSocketListener): WebSocket {
            val connectionIndex = connections.size
            val webSocket = if (connections.isEmpty() && firstWebSocket != null) {
                firstWebSocket
            } else {
                RecordingWebSocket(sendResult = sendResults.getOrNull(connectionIndex) ?: true)
            }
            webSocket.attachRequest(request)
            val connection = RecordingConnection(request, listener, webSocket)
            connections += connection
            if (openBeforeReturn) {
                connection.open()
            }
            if (connectionIndex in failBeforeReturnIndices) {
                connection.fail()
            }
            return webSocket
        }
    }

    private data class RecordingConnection(
        val request: Request,
        val listener: WebSocketListener,
        val webSocket: RecordingWebSocket,
    ) {
        fun open() {
            listener.onOpen(
                webSocket,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build(),
            )
        }

        fun message(text: String) {
            listener.onMessage(webSocket, text)
        }

        fun acceptJoin(roomId: String) {
            message(joinAck(code = 200, roomId = roomId))
        }

        fun fail() {
            listener.onFailure(webSocket, IOException("test failure"), null)
        }
    }

    private class RecordingWebSocket(
        private val blockFirstSend: Boolean = false,
        private val sendResult: Boolean = true,
    ) : WebSocket {
        val sentTexts = CopyOnWriteArrayList<String>()
        val closeCodes = CopyOnWriteArrayList<Int>()
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        var onTextSend: (() -> Unit)? = null
        private var shouldBlock = blockFirstSend
        private lateinit var webSocketRequest: Request

        fun attachRequest(request: Request) {
            webSocketRequest = request
        }

        override fun request(): Request = webSocketRequest

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            if (shouldBlock) {
                shouldBlock = false
                sendEntered.countDown()
                assertTrue(releaseSend.await(5, TimeUnit.SECONDS))
            }
            onTextSend?.invoke()
            sentTexts += text
            return sendResult
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closeCodes += code
            return true
        }

        override fun cancel() = Unit
    }

    private companion object {
        fun playbackHistoryMessage(): PlaybackHistoryMessage = PlaybackHistoryMessage(
            requestId = "request-1",
            group = "room-1",
            record = PlaybackRecord(
                source = "source-1",
                vid = "vid-1",
                pid = "pid-1",
                title = "Title",
                episodeName = "Episode 1",
                thumb = "https://images.example/thumb.jpg",
                positionMs = 12_345L,
                durationMs = 60_000L,
                completed = false,
                updatedAtMs = 1_787_190_000_000L,
            ),
        )

        fun controlMessage(event: String, roomId: String): String =
            JsonObject().apply {
                addProperty("event", event)
                addProperty("group", roomId)
            }.toString()

        fun loadVideoMessage(roomId: String, vid: String): String =
            JsonObject().apply {
                addProperty("event", "/ctl_load_Video")
                addProperty("group", roomId)
                addProperty("vid", vid)
                addProperty("pid", "p1")
                addProperty("source", "source")
                addProperty("mode", "mode")
            }.toString()

        fun expectedJoin(roomId: String): JsonObject =
            JsonObject().apply {
                addProperty("event", "join-group")
                add(
                    "data",
                    JsonObject().apply { addProperty("group", roomId) },
                )
            }

        fun joinAck(code: Int, roomId: String): String =
            JsonObject().apply {
                addProperty("event", "join-group")
                add(
                    "data",
                    JsonObject().apply {
                        addProperty("code", code)
                        addProperty("group", roomId)
                    },
                )
            }.toString()
    }
}
