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
        val serviceMessage = response.msg.withoutMode(command.mode)

        if (response.code != SUCCESS_CODE) {
            throw ResolveVideoException(
                ResolveErrorCode.SERVICE_REJECTED,
                serviceMessage,
            )
        }

        val url = response.data?.url?.trim().orEmpty()
        if (url.isEmpty()) {
            throw ResolveVideoException(ResolveErrorCode.INVALID_RESPONSE, serviceMessage)
        }
        if (!url.isHttpOrHttps()) {
            throw ResolveVideoException(ResolveErrorCode.UNSAFE_MEDIA_URL)
        }

        return ResolvedVideo(
            vid = command.vid,
            pid = command.pid,
            source = command.source,
            url = url,
        )
    }

    suspend fun loadEpisodes(command: ControlCommand.LoadVideo): List<Episode> = try {
        val response = api.detail(vid = command.vid, source = command.source)
        if (response.code != SUCCESS_CODE) {
            emptyList()
        } else {
            response.data?.links.orEmpty()
                .mapNotNull { link ->
                    val id = link.id?.trim().orEmpty()
                    val name = link.name?.trim().orEmpty()
                    if (id.isEmpty() || name.isEmpty()) null else Episode(id, name)
                }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        emptyList()
    }

    private fun String.isHttpOrHttps(): Boolean = try {
        URI(this).let { uri ->
            uri.scheme?.lowercase(Locale.US) in MEDIA_SCHEMES && !uri.host.isNullOrBlank()
        }
    } catch (_: Exception) {
        false
    }

    private fun String?.withoutMode(mode: String): String? = takeUnless { message ->
        mode.isNotEmpty() && message.orEmpty().contains(mode, ignoreCase = true)
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
    serviceMessage: String? = null,
) : RuntimeException(buildMessage(code, serviceMessage)) {
    private companion object {
        private val SENSITIVE_MESSAGE_MARKERS = listOf(
            "://",
            "authorization",
            "cookie",
            "x-source-mode",
        )

        fun buildMessage(code: ResolveErrorCode, serviceMessage: String?): String {
            val safeMessage = serviceMessage
                ?.trim()
                ?.take(MAX_SERVICE_MESSAGE_LENGTH)
                ?.takeUnless { message ->
                    SENSITIVE_MESSAGE_MARKERS.any { marker ->
                        message.contains(marker, ignoreCase = true)
                    }
                }
                .orEmpty()
            return if (safeMessage.isEmpty()) code.name else "${code.name}: $safeMessage"
        }

        const val MAX_SERVICE_MESSAGE_LENGTH = 256
    }
}
