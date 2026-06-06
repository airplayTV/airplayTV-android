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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.airplay.tv.data.api.VideoLink
import com.airplay.tv.data.db.AppDatabase
import com.airplay.tv.data.db.HistoryEntity
import com.airplay.tv.data.db.TimelineEntity
import com.airplay.tv.data.preferences.AppPreferences
import com.airplay.tv.data.repository.VideoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    vid: String, pid: String, source: String,
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
    var curPos by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var showEpisodes by remember { mutableStateOf(false) }
    var episodeIndex by remember { mutableStateOf(0) }
    var isFullscreen by remember { mutableStateOf(false) }
    var currentName by remember { mutableStateOf("") }
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    fun loadPlay(vid: String, pid: String) {
        scope.launch {
            loading = true
            val detailResp = repo.getVideoDetailResponse(vid, source)
            if (detailResp.code != 200 || detailResp.data == null) {
                Toast.makeText(ctx, detailResp.msg ?: "获取视频详情失败", Toast.LENGTH_LONG).show()
                loading = false; return@launch
            }
            val v = detailResp.data!!
            video = v; currentName = v.name ?: ""
            links = v.links ?: emptyList()
            if (links.isNotEmpty() && pid.isEmpty()) currPid = links[0].id
            else if (pid.isNotEmpty()) currPid = pid
            episodeIndex = links.indexOfFirst { it.id == currPid }.coerceAtLeast(0)
            val sourceResp = repo.getVideoSourceResponse(vid, currPid, source)
            if (sourceResp.code != 200 || sourceResp.data?.url == null) {
                Toast.makeText(ctx, sourceResp.msg ?: "无法获取播放地址", Toast.LENGTH_LONG).show()
                loading = false; return@launch
            }
            val s = sourceResp.data!!
            player.stop(); player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(s.url))
            player.prepare()
            val tl = db.timelineDao().findBySourceAndVid(source, vid, currPid)
            if (tl?.lastTime ?: 0 > 0) player.seekTo(tl!!.lastTime)
            player.play(); playing = true; loading = false
            db.historyDao().upsert(HistoryEntity(source=source, vid=vid, pid=currPid, name=currentName, pname=(links.find{it.id==currPid}?.name ?: ""), thumb=v.thumb ?: "", url=s.url ?: "", type="", duration=player.duration, lastTime=player.currentPosition, updatedAt=System.currentTimeMillis()))
        }
    }
    LaunchedEffect(Unit) { loadPlay(vid, pid) }
    DisposableEffect(Unit) { onDispose { player.stop(); player.release() } }
    LaunchedEffect(playing) {
        while (true) {
            delay(3000)
            if (playing) {
                val ct = player.currentPosition; val dur = player.duration
                if (ct > 0 && dur > 0) {
                    db.timelineDao().upsert(TimelineEntity(source=source, vid=vid, pid=currPid, lastTime=ct, duration=dur, updatedAt=System.currentTimeMillis()))
                }
            }
        }
    }
    LaunchedEffect(playing) { while (true) { delay(500); curPos = player.currentPosition; duration = player.duration } }
    val progress = if (duration > 0) (curPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxSize().background(Color.Black).onKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (isFullscreen) {
            when (it.key) {
                Key.DirectionLeft -> { player.seekTo((curPos - 15000).coerceAtLeast(0)); true }
                Key.DirectionRight -> { player.seekTo((curPos + 15000).coerceAtMost(duration)); true }
                Key.DirectionCenter -> { if (playing) player.pause() else player.play(); playing = !playing; true }
                Key.Back -> { isFullscreen = false; true }
                else -> false
            }
        } else {
            when (it.key) {
                Key.Back -> { if (showEpisodes) showEpisodes = false else { player.stop(); onBack() }; true }
                Key.Menu -> { if (links.isNotEmpty()) showEpisodes = !showEpisodes; true }
                else -> false
            }
        }
    }) {
        if (isFullscreen) {
            AndroidView(factory={val e=player;PlayerView(it).apply{this.player=e;useController=false}}, modifier=Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                        AndroidView(factory={val e=player;PlayerView(it).apply{this.player=e;useController=false}}, modifier=Modifier.fillMaxSize())
                        if (loading) Text("加载中...", color=Color.Gray, fontSize=20.sp, modifier=Modifier.align(Alignment.Center))
                    }
                    Box(Modifier.fillMaxWidth().background(Color(0xFF1A1A28)).padding(horizontal=16.dp, vertical=8.dp)) {
                        Column {
                            var seekFocus by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(if(seekFocus)Color(0xFF7C73FF)else Color(0xFF3D3D4A)).onFocusChanged{seekFocus=it.isFocused}.onKeyEvent{if(it.type==KeyEventType.KeyDown){when(it.key){Key.DirectionLeft->{player.seekTo((curPos-10000).coerceAtLeast(0));true};Key.DirectionRight->{player.seekTo((curPos+10000).coerceAtMost(duration));true};else->false}}else false}) {
                                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(Color(0xFF7C73FF)))
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                                var playFocus by remember { mutableStateOf(false) }
                                Button(onClick={if(playing)player.pause()else player.play();playing=!playing}, modifier=Modifier.onFocusChanged{playFocus=it.isFocused}.then(if(playFocus)Modifier.scale(1.12f)else Modifier), colors=ButtonDefaults.colors(containerColor=if(playFocus)Color(0xFF7C73FF)else Color(0xFF2B2B35))){Text(if(playing)"暂停"else"播放",fontSize=14.sp,fontWeight=if(playFocus)FontWeight.Bold else FontWeight.Normal,color=Color.White)}
                                Spacer(Modifier.width(12.dp))
                                Text(formatTime(curPos), color=Color(0xFFB0B0B8), fontSize=13.sp)
                                Text(" / ", color=Color(0xFF7C73FF), fontSize=13.sp)
                                Text(formatTime(duration), color=Color(0xFFB0B0B8), fontSize=13.sp)
                                Spacer(Modifier.weight(1f))
                                Text(currentName.ifEmpty{"未知视频"}, color=Color.White, fontSize=13.sp, fontWeight=FontWeight.Bold, maxLines=1, overflow=TextOverflow.Ellipsis, modifier=Modifier.widthIn(max=200.dp))
                                if (links.isNotEmpty() && episodeIndex < links.size) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(links[episodeIndex].name ?: ("第" + (episodeIndex+1).toString() + "集"), color=Color(0xFFB0B0FF), fontSize=12.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(12.dp))
                                var fsFocus by remember { mutableStateOf(false) }
                                Button(onClick={isFullscreen=!isFullscreen}, modifier=Modifier.onFocusChanged{fsFocus=it.isFocused}.then(if(fsFocus)Modifier.scale(1.12f)else Modifier), colors=ButtonDefaults.colors(containerColor=if(fsFocus)Color(0xFF7C73FF)else Color(0xFF2B2B35))){Text(if(isFullscreen)"退出全屏"else"全屏",fontSize=14.sp,fontWeight=if(fsFocus)FontWeight.Bold else FontWeight.Normal,color=Color.White)}
                                Spacer(Modifier.width(8.dp))
                                if (links.isNotEmpty()) {
                                    var epFocus by remember { mutableStateOf(false) }
                                    Button(onClick={showEpisodes=!showEpisodes}, modifier=Modifier.onFocusChanged{epFocus=it.isFocused}.then(if(epFocus)Modifier.scale(1.12f)else Modifier), colors=ButtonDefaults.colors(containerColor=if(epFocus||showEpisodes)Color(0xFF7C73FF)else Color(0xFF2B2B35))){Text(if(showEpisodes)"收起"else"选集",fontSize=14.sp,fontWeight=if(epFocus)FontWeight.Bold else FontWeight.Normal,color=Color.White)}
                                }
                            }
                        }
                    }
                }
                if (showEpisodes && links.isNotEmpty()) {
                    Column(Modifier.width(300.dp).fillMaxHeight().background(Color(0xFF121218))) {
                        Text("播放列表", color=Color.White, fontSize=15.sp, modifier=Modifier.padding(horizontal=14.dp, vertical=8.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(links) { idx, link ->
                                val sel = idx == episodeIndex
                                var itemFocus by remember { mutableStateOf(false) }
                                Box(Modifier.fillMaxWidth().clickable{currPid=link.id;episodeIndex=idx;showEpisodes=false;loadPlay(vid,currPid)}.onFocusChanged{itemFocus=it.isFocused}.then(if(itemFocus)Modifier.scale(1.03f)else Modifier).background(if(sel)Color(0xFF3A3A4A)else if(itemFocus)Color(0xFF7C73FF)else Color.Transparent).padding(horizontal=14.dp, vertical=10.dp)) {
                                    Text((idx+1).toString() + ". " + (link.name ?: ""), fontSize=if(sel||itemFocus)14.sp else 13.sp, fontWeight=if(sel)FontWeight.Bold else FontWeight.Normal, color=if(sel)Color.White else if(itemFocus)Color.White else Color(0xFFB0B0B8), maxLines=1, overflow=TextOverflow.Ellipsis)
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}


fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
