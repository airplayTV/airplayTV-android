package com.airplay.tv.feature.player

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val POSITION_UPDATE_INTERVAL_MS = 500L
private const val PLAYBACK_ERROR_MESSAGE = "播放失败，请稍后重试"

@MainThread
@androidx.annotation.OptIn(UnstableApi::class)
class Media3PlayerController(context: Context) : PlayerController {
    init {
        checkMainThread()
    }

    override val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLooper(Looper.getMainLooper())
        .build()

    private val audioManager = context.applicationContext
        .getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlayerState())
    private val retryGate = SingleRetryGate()
    private val lifecycle = ControllerLifecycleGate()

    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private var lastAudibleVolume: Int? = currentVolume().takeIf { it > 0 }

    private val positionUpdater = object : Runnable {
        override fun run() {
            if (lifecycle.isReleased || !player.isPlaying) return
            publishPlaybackState()
            handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishPlaybackState(isPlaying)
            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishPlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            stopPositionUpdates()
            if (retryGate.tryAcquire()) {
                mutableState.value = mutableState.value.copy(error = null)
                retryPlayer(player)
            } else {
                mutableState.value = mutableState.value.copy(
                    isPlaying = false,
                    error = PLAYBACK_ERROR_MESSAGE,
                )
            }
        }
    }

    init {
        player.addListener(listener)
    }

    @MainThread
    override fun load(url: String, mediaType: ResolvedMediaType) {
        checkUsable()
        stopPositionUpdates()
        retryGate.reset()
        lifecycle.onLoad()
        mutableState.value = PlayerState()
        player.setMediaItem(buildMediaItem(url, mediaType))
        player.prepare()
        player.play()
    }

    @MainThread
    override fun play() {
        checkUsable()
        player.play()
    }

    @MainThread
    override fun pause() {
        checkUsable()
        player.pause()
    }

    @MainThread
    override fun seekBy(deltaMs: Long) {
        checkUsable()
        val targetMs = calculateSeekTarget(
            currentPositionMs = player.currentPosition,
            deltaMs = deltaMs,
            durationMs = player.duration,
        )
        player.seekTo(targetMs)
        publishPlaybackState(positionMs = targetMs)
    }

    @MainThread
    override fun adjustVolume(direction: Int) {
        checkUsable()
        val adjustment = when {
            direction > 0 -> AudioManager.ADJUST_RAISE
            direction < 0 -> AudioManager.ADJUST_LOWER
            else -> return
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0)
        rememberCurrentAudibleVolume()
    }

    @MainThread
    override fun toggleMute() {
        checkUsable()
        val currentVolume = currentVolume()
        if (currentVolume > 0) {
            lastAudibleVolume = currentVolume
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            return
        }

        val restoredVolume = calculateRestoreVolume(
            lastAudibleVolume = lastAudibleVolume ?: 1,
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoredVolume, 0)
        if (restoredVolume > 0) lastAudibleVolume = restoredVolume
    }

    @MainThread
    override fun clear() {
        checkMainThread()
        if (!lifecycle.tryClear()) return
        stopPositionUpdates()
        retryGate.reset()
        player.stop()
        player.clearMediaItems()
        mutableState.value = PlayerState()
    }

    @MainThread
    override fun release() {
        checkMainThread()
        if (!lifecycle.tryRelease()) return
        stopPositionUpdates()
        player.removeListener(listener)
        player.release()
        mutableState.value = PlayerState()
    }

    private fun checkUsable() {
        checkMainThread()
        check(!lifecycle.isReleased) { "PlayerController is released" }
    }

    private fun startPositionUpdates() {
        handler.removeCallbacks(positionUpdater)
        handler.post(positionUpdater)
    }

    private fun stopPositionUpdates() {
        handler.removeCallbacks(positionUpdater)
    }

    private fun publishPlaybackState(
        isPlaying: Boolean = player.isPlaying,
        positionMs: Long = player.currentPosition.coerceAtLeast(0L),
    ) {
        val durationMs = player.duration
            .takeUnless { it == C.TIME_UNSET }
            ?.coerceAtLeast(0L)
            ?: 0L
        mutableState.value = mutableState.value.copy(
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    private fun currentVolume(): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun rememberCurrentAudibleVolume() {
        val volume = currentVolume()
        if (volume > 0) lastAudibleVolume = volume
    }
}

internal fun buildMediaItem(url: String, mediaType: ResolvedMediaType): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .apply { mediaType.media3MimeType()?.let(::setMimeType) }
        .build()

internal fun ResolvedMediaType.media3MimeType(): String? = when (this) {
    ResolvedMediaType.HLS -> MimeTypes.APPLICATION_M3U8
    ResolvedMediaType.MP4 -> MimeTypes.VIDEO_MP4
    ResolvedMediaType.UNKNOWN -> null
}

internal fun retryPlayer(player: Player) {
    val shouldPlay = player.playWhenReady
    player.prepare()
    if (shouldPlay) {
        player.play()
    } else {
        player.pause()
    }
}

internal fun calculateSeekTarget(
    currentPositionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val targetMs = saturatingAdd(currentPositionMs, deltaMs).coerceAtLeast(0L)
    return if (durationMs == C.TIME_UNSET) {
        targetMs
    } else {
        targetMs.coerceAtMost(durationMs.coerceAtLeast(0L))
    }
}

internal fun calculateRestoreVolume(lastAudibleVolume: Int, maxVolume: Int): Int =
    lastAudibleVolume.coerceAtLeast(0).coerceAtMost(maxVolume.coerceAtLeast(0))

internal class SingleRetryGate {
    private var retryUsed = false

    fun tryAcquire(): Boolean {
        if (retryUsed) return false
        retryUsed = true
        return true
    }

    fun reset() {
        retryUsed = false
    }
}

internal class ControllerLifecycleGate {
    private var isCleared = false

    var isReleased: Boolean = false
        private set

    fun onLoad() {
        check(!isReleased)
        isCleared = false
    }

    fun tryClear(): Boolean {
        if (isReleased || isCleared) return false
        isCleared = true
        return true
    }

    fun tryRelease(): Boolean {
        if (isReleased) return false
        isReleased = true
        isCleared = true
        return true
    }
}

private fun saturatingAdd(left: Long, right: Long): Long = when {
    right > 0 && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0 && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

private fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "PlayerController must be accessed on the main thread"
    }
}
