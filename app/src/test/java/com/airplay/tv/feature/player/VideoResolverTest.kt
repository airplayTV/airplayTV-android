package com.airplay.tv.feature.player

import com.airplay.tv.core.network.NetworkFactory
import com.airplay.tv.protocol.ControlCommand
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
            .client(NetworkFactory.apiClient(debug = false))
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
                """{"code":200,"msg":"ok","data":{"url":"https://cdn.example/opaque-token","type":"hls"}}""",
            ),
        )

        val result = resolver.resolve(loadCommand)
        val request = server.takeRequest()

        assertEquals("secret-value", request.getHeader("X-Source-Mode"))
        assertEquals("airplayTV-android", request.getHeader("X-Client"))
        assertEquals("/api/video/source?vid=v1&pid=p2&_source=s1&_m3u8p=false", request.path)
        assertEquals("https://cdn.example/opaque-token", result.url)
        assertEquals("v1", result.vid)
        assertEquals("p2", result.pid)
        assertEquals("s1", result.source)
        assertEquals(ResolvedMediaType.HLS, result.mediaType)
    }

    @Test
    fun normalizesKnownMp4AndLeavesUnknownTypeForInference() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"url":"https://cdn.example/opaque","type":" video/mp4 "}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"url":"https://cdn.example/stream","type":"custom"}}""",
            ),
        )

        val mp4 = resolver.resolve(loadCommand)
        val unknown = resolver.resolve(loadCommand)

        assertEquals(ResolvedMediaType.MP4, mp4.mediaType)
        assertEquals(ResolvedMediaType.UNKNOWN, unknown.mediaType)
    }

    @Test
    fun apiCredentialsNeverFollowRedirects() = runTest {
        val redirectTarget = MockWebServer()
        redirectTarget.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", redirectTarget.url("/credential-target")),
            )

            val exception = captureResolveException { resolver.resolve(loadCommand) }
            val sourceRequest = server.takeRequest()

            assertEquals(ResolveErrorCode.NETWORK_FAILURE, exception.code)
            assertEquals("secret-value", sourceRequest.getHeader("X-Source-Mode"))
            assertNull(redirectTarget.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally {
            redirectTarget.shutdown()
        }
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
    fun rejectsMediaUrlWithRawUserInfo() = runTest {
        val unsafeUrl = "http://user:pass@cdn.example/private.m3u8"
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"url":"$unsafeUrl"}}""",
            ),
        )

        val exception = captureResolveException { resolver.resolve(loadCommand) }

        assertEquals(ResolveErrorCode.UNSAFE_MEDIA_URL, exception.code)
        assertFalse(exception.message.orEmpty().contains(unsafeUrl))
    }

    @Test
    fun doesNotExposeServerControlledMessage() = runTest {
        val sensitiveMessages = listOf(
            "X-Client=airplayTV-android",
            "token=abc",
            "mode=secret-value",
            "https://cdn.example/private.m3u8",
        )
        sensitiveMessages.forEach { serverMessage ->
            server.enqueue(
                MockResponse().setBody(
                    """{"code":403,"msg":"$serverMessage","data":null}""",
                ),
            )

            val exception = captureResolveException { resolver.resolve(loadCommand) }

            assertEquals(ResolveErrorCode.SERVICE_REJECTED, exception.code)
            assertEquals(ResolveErrorCode.SERVICE_REJECTED.name, exception.message)
            assertFalse(exception.message.orEmpty().contains(serverMessage))
        }
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
        assertEquals("secret-value", request.getHeader("X-Source-Mode"))
        assertEquals("airplayTV-android", request.getHeader("X-Client"))
        assertEquals(listOf(Episode("p1", "Episode 1"), Episode("p2", "Episode 2")), episodes)
    }

    @Test
    fun loadsTitleAndEpisodesFromDetailResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"id":"v1","name":"Example Series","links":[{"id":"p1","name":"Episode 1"},{"id":"p2","name":"Episode 2"}]}}""",
            ),
        )

        val details = resolver.loadDetails(loadCommand)

        assertEquals(
            VideoDetails(
                title = "Example Series",
                episodes = listOf(Episode("p1", "Episode 1"), Episode("p2", "Episode 2")),
            ),
            details,
        )
    }

    @Test
    fun detailMapsThumbWithoutFetchingIt() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"id":"v1","name":"Example","thumb":"https://img.test/a.jpg","links":[]}}""",
            ),
        )

        val details = resolver.loadDetails(loadCommand)
        val request = server.takeRequest()

        assertEquals("/api/video/detail?id=v1&_source=s1", request.path)
        assertEquals("https://img.test/a.jpg", details.thumb)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun returnsNoEpisodesWhenDetailRequestFails() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"unavailable","data":null}"""))

        assertEquals(emptyList<Episode>(), resolver.loadEpisodes(loadCommand))
    }

    @Test
    fun returnsNoEpisodesWhenDetailConnectionDrops() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(emptyList<Episode>(), resolver.loadEpisodes(loadCommand))
    }

    @Test
    fun rethrowsCancellationFromDetailRequest() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        val cancellingResolver = VideoResolver(object : VideoApi {
            override suspend fun source(
                vid: String,
                pid: String,
                source: String,
                proxy: Boolean,
                mode: String,
                client: String,
            ): ApiResponse<VideoSourceDto> = throw cancellation

            override suspend fun detail(
                vid: String,
                source: String,
                mode: String,
                client: String,
            ): ApiResponse<VideoDetailDto> = throw cancellation
        })

        val thrown = try {
            cancellingResolver.loadEpisodes(loadCommand)
            throw AssertionError("Expected CancellationException")
        } catch (exception: kotlinx.coroutines.CancellationException) {
            exception
        }

        assertSame(cancellation, thrown)
    }

    private suspend fun captureResolveException(block: suspend () -> Unit): ResolveVideoException =
        try {
            block()
            throw AssertionError("Expected ResolveVideoException")
        } catch (exception: ResolveVideoException) {
            exception
        }
}
