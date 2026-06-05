
package com.airplay.tv.ui.screens

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.*
import com.airplay.tv.data.api.VideoLink
import com.airplay.tv.data.db.AppDatabase
import com.airplay.tv.data.db.TimelineEntity
import com.airplay.tv.data.preferences.AppPreferences
import com.airplay.tv.data.repository.VideoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    vid: String, pid: String,
    repo: VideoRepository, db: AppDatabase,
    prefs: AppPreferences, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<com.airplay.tv.data.api.Video?>(null) }
    var links by remember { mutableStateOf<List<VideoLink>>(emptyList()) }
    var currPid by remember { mutableStateOf(pid) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }

    fun loadPlay(vid: String, pid: String) {
        scope.launch {
            val v = repo.getVideoDetail(vid, "") ?: return@launch
            video = v; links = v.links ?: emptyList()
            val s = repo.getVideoSource(vid, pid, "")
            if (s?.url != null) {
                player.stop(); player.clearMediaItems()
                player.setMediaItem(MediaItem.fromUri(s.url))
                player.prepare()
                val tl = db.timelineDao().findBySourceAndVid("", vid, pid)
                if (tl?.lastTime ?: 0 > 0) player.seekTo(tl!!.lastTime)
                player.play(); playing = true
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { loadPlay(vid, pid) }
    DisposableEffect(Unit) { onDispose { player.stop(); player.release() } }

    LaunchedEffect(playing) {
        while (playing) {
            delay(5000)
            val ct = player.currentPosition; val dur = player.duration
            if (ct > 0 && dur > 0) db.timelineDao().upsert(
                TimelineEntity(source="",vid=vid,pid=currPid,lastTime=ct,duration=dur,updatedAt=System.currentTimeMillis()))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (it.key) {
            Key.DirectionCenter -> { if (playing) player.pause() else player.play(); playing = !playing; true }
            Key.DirectionLeft -> { player.seekTo((player.currentPosition - 15000).coerceAtLeast(0)); true }
            Key.DirectionRight -> { player.seekTo(player.currentPosition + 15000); true }
            Key.DirectionUp -> {
                val i = links.indexOfFirst { l -> l.id == currPid }
                if (i > 0) { currPid = links[i-1].id; loadPlay(vid, currPid) }; true }
            Key.DirectionDown -> {
                val i = links.indexOfFirst { l -> l.id == currPid }
                if (i < links.size-1) { currPid = links[i+1].id; loadPlay(vid, currPid) }; true }
            Key.Back -> { player.stop(); onBack(); true }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                Text("", Modifier.align(Alignment.Center), color = Color.Gray, fontSize = 24.sp)
            }
            Box(Modifier.fillMaxWidth().height(60.dp).background(Color(0xFF1A1A1A)).padding(horizontal=16.dp)) {
                Row(Modifier.fillMaxSize(), verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(if (playing) "playing" else "paused", color=Color.White, fontSize=14.sp)
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("上一集", color=Color(0xFF6C63FF), fontSize=12.sp)
                        Text("下一集", color=Color(0xFF6C63FF), fontSize=12.sp)
                    }
                    Text(formatTime(player.currentPosition)+" / "+formatTime(player.duration),
                        color=Color.Gray, fontSize=12.sp)
                }
            }
            if (links.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().height(200.dp).background(Color(0xFF121218))) {
                    item { Text("播放列表", color=Color.White, modifier=Modifier.padding(12.dp,8.dp)) }
                    itemsIndexed(links) { idx, link ->
                        val sel = link.id == currPid
                        Text(
                            (idx+1).toString() + "." + (link.name?:""),
                            modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp),
                            fontSize=13.sp,
                            color=if(sel)Color(0xFF6C63FF)else Color(0xFFB0B0B8),
                            maxLines=1
                        )
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).toInt()
    val m = s / 60; val sec = s % 60
    return String.format("%02d:%02d", m, sec)
}
