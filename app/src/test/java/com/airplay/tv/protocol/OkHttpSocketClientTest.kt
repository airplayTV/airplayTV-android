package com.airplay.tv.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        val receivedJoin = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        assertTrue(text.contains("\"event\":\"join-group\""))
                        assertTrue(text.contains("\"group\":\"room-1\""))
                        receivedJoin.countDown()
                        webSocket.send("""{"event":"/unknown","group":"room-1"}""")
                        webSocket.send("""{"event":"/ctl_play","group":"room-1"}""")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        val socketClient = createClient(StandardTestDispatcher(testScheduler))
        val command = async(start = CoroutineStart.UNDISPATCHED) { socketClient.commands.first() }

        socketClient.connect("room-1")

        assertEquals(ControlCommand.Play, command.await())
        assertTrue(receivedJoin.await(5, TimeUnit.SECONDS))
        assertEquals(SocketConnectionState.Connected, socketClient.states.value)
    }

    @Test
    fun reconnectPolicyUsesExponentialScheduleCapsAndAppliesInjectedJitter() {
        val policy = ReconnectPolicy()
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        assertEquals(expected, (0..5).map { policy.delayForAttempt(it, randomUnit = 0.5) })
        assertEquals(30_000L, policy.delayForAttempt(99, randomUnit = 0.5))
        assertEquals((1_000L * 0.8).roundToLong(), policy.delayForAttempt(0, randomUnit = 0.0))
        assertEquals((1_000L * 1.2).roundToLong(), policy.delayForAttempt(0, randomUnit = 1.0))
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
                    override fun onMessage(webSocket: WebSocket, text: String) = Unit

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

    private fun webSocketResponse(): MockResponse =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            },
        )
}
