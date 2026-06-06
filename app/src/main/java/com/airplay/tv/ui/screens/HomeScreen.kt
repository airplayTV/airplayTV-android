package com.airplay.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.airplay.tv.data.api.Source
import com.airplay.tv.data.api.Tag
import com.airplay.tv.data.api.Video
import com.airplay.tv.data.preferences.AppPreferences
import com.airplay.tv.data.repository.VideoRepository
import com.airplay.tv.ui.components.TopBar
import com.airplay.tv.ui.components.TagRow
import com.airplay.tv.ui.components.VideoCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val COLUMNS = 5

@Composable
fun HomeScreen(
    prefs: AppPreferences,
    repo: VideoRepository,
    onVideoClick: (String, String, String) -> Unit,
    onSearchClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var currentSource by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var currentTag by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()

    fun loadFirstPage() {
        scope.launch {
            loading = true; page = 1
            try {
                if (currentSource.isNotEmpty()) {
                    val (list, pages) = repo.getVideoList(currentTag, 1, currentSource)
                    videos = list; totalPages = pages
                }
            } catch (_: Exception) {}
            loading = false
        }
    }
    fun loadNextPage() {
        if (loading || page >= totalPages) return
        scope.launch {
            loading = true
            val nextPage = page + 1
            try {
                val (list, _) = repo.getVideoList(currentTag, nextPage, currentSource)
                videos = videos + list; page = nextPage
            } catch (_: Exception) {}
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            sources = repo.getSourceList()
            if (sources.isEmpty()) { loading = false; return@LaunchedEffect }
            val savedSource = try { prefs.source.first() } catch (e: Exception) { "" }
            currentSource = if (savedSource.isNotEmpty() && sources.any { it.name == savedSource }) savedSource else sources[0].name
            val src = sources.find { it.name == currentSource }
            tags = src?.tags ?: emptyList()
            if (tags.isNotEmpty()) {
                val savedTag = try { prefs.tag.first() } catch (e: Exception) { "" }
                currentTag = if (savedTag.isNotEmpty() && tags.any { it.value == savedTag }) savedTag else tags[0].value
                loadFirstPage()
            } else loading = false
        } catch (e: Exception) {
            android.util.Log.e("HomeScreen", "init error", e); loading = false
        }
    }

    LaunchedEffect(scrollState.value) {
        if (!loading && page < totalPages) {
            if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 300) {
                loadNextPage()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF121218))) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            TopBar(sources, currentSource, { s ->
                currentSource = s; scope.launch { prefs.setSource(s) }
                val src = sources.find { it.name == s }
                tags = src?.tags ?: emptyList(); page = 1; loadFirstPage()
            }, onSearchClick, onHistoryClick, onSettingsClick)

            TagRow(tags, currentTag, { t ->
                currentTag = t; scope.launch { prefs.setTag(t) }
                page = 1; loadFirstPage()
            })

            Spacer(Modifier.height(12.dp))

            if (videos.isEmpty() && !loading) {
                Text("暂无数据", color = Color.Gray, fontSize = 16.sp,
                    modifier = Modifier.padding(20.dp))
            }

            val chunked = videos.chunked(COLUMNS)
            chunked.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { video ->
                        VideoCard(
                            video = video, isFocused = false,
                            onClick = { onVideoClick(video.id, "", currentSource) },
                            modifier = Modifier.weight(1f))
                    }
                    repeat(COLUMNS - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (loading && videos.isNotEmpty()) {
                Text("加载更多...", color = Color(0xFF888888), fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }

            Spacer(Modifier.height(120.dp))
        }

        if (loading && videos.isEmpty()) {
            Text("加载中...", color = Color(0xFF888888), fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center))
        }
    }
}
