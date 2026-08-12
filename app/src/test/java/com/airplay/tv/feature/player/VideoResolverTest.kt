package com.airplay.tv.feature.player

import com.airplay.tv.core.network.NetworkFactory
import com.airplay.tv.protocol.ControlCommand
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class VideoResolverTest {
    private lateinit var server: MockWebServer
    private lateinit var resolver: VideoResolver

    private val loadCommand = ControlCommand.LoadVideo(
        vid = "v1",
        pid = "p2",
        source = "s1",
        mode = "secret-value",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(NetworkFactory.okHttpClient(debug = false))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VideoApi::class.java)
        resolver = VideoResolver(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolvesSourceWithModeHeaderAndExpectedQuery() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"url":"https://cdn.example/v.m3u8","type":"hls"}}""",
            ),
        )

        val result = resolver.resolve(loadCommand)
        val request = server.takeRequest()

        assertEquals("secret-value", request.getHeader("X-Source-Mode"))
        assertEquals("airplayTV-android", request.getHeader("X-Client"))
        assertEquals("/api/video/source?vid=v1&pid=p2&_source=s1&_m3u8p=false", request.path)
        assertEquals("https://cdn.example/v.m3u8", result.url)
        assertEquals("v1", result.vid)
        assertEquals("p2", result.pid)
        assertEquals("s1", result.source)
    }

    @Test
    fun rejectsUnsafeMediaSchemeWithoutLeakingUrlOrMode() = runTest {
        val unsafeUrl = "file:///sdcard/private.mp4"
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"url":"$unsafeUrl"}}""",
            ),
        )

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.UNSAFE_MEDIA_URL, exception.code)
        assertFalse(exception.message.orEmpty().contains(unsafeUrl))
        assertFalse(exception.message.orEmpty().contains(loadCommand.mode))
    }

    @Test
    fun rejectsHttpSchemeWithoutNetworkAuthority() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"url":"http:relative-path"}}""",
            ),
        )

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.UNSAFE_MEDIA_URL, exception.code)
    }

    @Test
    fun exposesServiceMessageOnlyForBusinessFailure() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":403,"msg":"source unavailable","data":null}"""))

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.SERVICE_REJECTED, exception.code)
        assertTrue(exception.message.orEmpty().contains("source unavailable"))
        assertFalse(exception.message.orEmpty().contains(loadCommand.mode))
    }

    @Test
    fun removesModeWhenServiceMessageEchoesIt() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":403,"msg":"source unavailable: secret-value","data":null}""",
            ),
        )

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.SERVICE_REJECTED, exception.code)
        assertFalse(exception.message.orEmpty().contains(loadCommand.mode))
    }

    @Test
    fun removesModeWhenServiceMessageChangesItsCase() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":403,"msg":"source unavailable: SECRET-VALUE","data":null}""",
            ),
        )

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.SERVICE_REJECTED, exception.code)
        assertFalse(exception.message.orEmpty().contains("SECRET-VALUE"))
    }

    @Test
    fun removesModeWhenInvalidResponseMessageEchoesIt() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"missing URL for secret-value","data":{"url":""}}""",
            ),
        )

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.INVALID_RESPONSE, exception.code)
        assertFalse(exception.message.orEmpty().contains(loadCommand.mode))
    }

    @Test
    fun loadsEpisodesFromDetailResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"id":"v1","name":"Example","links":[{"id":"p1","name":"Episode 1"},{"id":"p2","name":"Episode 2"}]}}""",
            ),
        )

        val episodes = resolver.loadEpisodes(loadCommand)
        val request = server.takeRequest()

        assertEquals("/api/video/detail?id=v1&_source=s1", request.path)
        assertEquals(listOf(Episode("p1", "Episode 1"), Episode("p2", "Episode 2")), episodes)
    }

    @Test
    fun returnsNoEpisodesWhenDetailRequestFails() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"unavailable","data":null}"""))

        assertEquals(emptyList<Episode>(), resolver.loadEpisodes(loadCommand))
    }

    private suspend fun captureResolveException(block: suspend () -> Unit): ResolveVideoException =
        try {
            block()
            throw AssertionError("Expected ResolveVideoException")
        } catch (exception: ResolveVideoException) {
            exception
        }
}
