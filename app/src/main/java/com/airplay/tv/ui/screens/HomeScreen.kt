
package com.airplay.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
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

@Composable
fun HomeScreen(
    prefs: AppPreferences,
    repo: VideoRepository,
    onVideoClick: (String, String) -> Unit,
    onSearchClick: () -> Unit,
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

    fun loadVideos() {
        scope.launch {
            loading = true
            try {
                if (currentSource.isNotEmpty() && currentTag.isNotEmpty()) {
                    videos = repo.getVideoList(currentTag, page, currentSource)
                }
            } catch (_: Exception) {}
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        sources = repo.getSourceList()
        if (sources.isEmpty()) { loading = false; return@LaunchedEffect }
        
        val savedSource = prefs.source.first()
        currentSource = if (savedSource.isNotEmpty() && sources.any { it.name == savedSource })
            savedSource else sources[0].name
        
        val src = sources.find { it.name == currentSource }
        tags = src?.tags ?: emptyList()
        
        if (tags.isNotEmpty()) {
            val savedTag = prefs.tag.first()
            currentTag = if (savedTag.isNotEmpty() && tags.any { it.value == savedTag })
                savedTag else tags[0].value
            loadVideos()
        } else {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121218))
            
    ) {
        item {
            TopBar(sources, currentSource, { s ->
                currentSource = s
                scope.launch { prefs.setSource(s) }
                val src = sources.find { it.name == s }
                tags = src?.tags ?: emptyList()
                page = 1; loadVideos()
            }, onSearchClick, onSettingsClick)
        }
        item {
            TagRow(tags, currentTag, { t ->
                currentTag = t; scope.launch { prefs.setTag(t) }
                page = 1; loadVideos()
            })
        }
        item { Spacer(Modifier.height(8.dp)) }
        if (loading) {
            item { Text("加载中...", color = Color.Gray, fontSize = 16.sp,
                modifier = Modifier.padding(20.dp)) }
        } else if (videos.isEmpty()) {
            item { Text("暂无数据", color = Color.Gray, fontSize = 16.sp,
                modifier = Modifier.padding(20.dp)) }
        } else {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    items(videos, key = { it.id }) { video ->
                        VideoCard(
                            video = video,
                            isFocused = false,
                            onClick = { onVideoClick(video.id, "") }
                        )
                    }
                }
            }
        }
    }
}
