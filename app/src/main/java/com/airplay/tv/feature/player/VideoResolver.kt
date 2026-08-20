package com.airplay.tv.feature.player

import com.airplay.tv.protocol.ControlCommand
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

class VideoResolver(
    private val api: VideoApi,
) {
    suspend fun resolve(command: ControlCommand.LoadVideo): ResolvedVideo {
        val response = try {
            api.source(
                vid = command.vid,
                pid = command.pid,
                source = command.source,
                mode = command.mode,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            throw ResolveVideoException(ResolveErrorCode.NETWORK_FAILURE)
        }

        if (response.code != SUCCESS_CODE) {
            throw ResolveVideoException(ResolveErrorCode.SERVICE_REJECTED)
        }

        val url = response.data?.url?.trim().orEmpty()
        if (url.isEmpty()) {
            throw ResolveVideoException(ResolveErrorCode.INVALID_RESPONSE)
        }
        if (!url.isHttpOrHttps()) {
            throw ResolveVideoException(ResolveErrorCode.UNSAFE_MEDIA_URL)
        }

        return ResolvedVideo(
            vid = command.vid,
            pid = command.pid,
            source = command.source,
            url = url,
            mediaType = response.data?.type.toResolvedMediaType(),
        )
    }

    suspend fun loadDetails(command: ControlCommand.LoadVideo): VideoDetails = try {
        val response = api.detail(
            vid = command.vid,
            source = command.source,
            mode = command.mode,
        )
        if (response.code != SUCCESS_CODE) {
            VideoDetails()
        } else {
            VideoDetails(
                title = response.data?.name?.trim().orEmpty(),
                thumb = response.data?.thumb?.trim().orEmpty(),
                episodes = response.data?.links.orEmpty()
                    .mapNotNull { link ->
                        val id = link.id?.trim().orEmpty()
                        val name = link.name?.trim().orEmpty()
                        if (id.isEmpty() || name.isEmpty()) null else Episode(id, name)
                    },
            )
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        VideoDetails()
    }

    suspend fun loadEpisodes(command: ControlCommand.LoadVideo): List<Episode> =
        loadDetails(command).episodes

    private fun String.isHttpOrHttps(): Boolean = try {
        URI(this).let { uri ->
            uri.scheme?.lowercase(Locale.US) in MEDIA_SCHEMES &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
        }
    } catch (_: Exception) {
        false
    }

    private fun String?.toResolvedMediaType(): ResolvedMediaType = when (
        this?.trim()?.lowercase(Locale.US)
    ) {
        "hls", "m3u8", "application/vnd.apple.mpegurl", "application/x-mpegurl" ->
            ResolvedMediaType.HLS
        "mp4", "video/mp4" -> ResolvedMediaType.MP4
        else -> ResolvedMediaType.UNKNOWN
    }

    private companion object {
        const val SUCCESS_CODE = 200
        val MEDIA_SCHEMES = setOf("http", "https")
    }
}

enum class ResolveErrorCode {
    NETWORK_FAILURE,
    SERVICE_REJECTED,
    INVALID_RESPONSE,
    UNSAFE_MEDIA_URL,
}

class ResolveVideoException(
    val code: ResolveErrorCode,
) : RuntimeException(code.name)
