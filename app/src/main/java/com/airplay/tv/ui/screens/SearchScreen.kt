
package com.airplay.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.compose.material3.OutlinedTextField
import com.airplay.tv.data.api.SearchResult
import com.airplay.tv.data.repository.VideoRepository
import com.airplay.tv.ui.components.VideoCard
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    repo: VideoRepository,
    onVideoClick: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xFF121218)).onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Back) { onBack(); true } else false
    }) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))
            Text("搜索", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(horizontal=20.dp))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal=20.dp),
                placeholder = { Text("输入关键词搜索", color = Color.Gray) },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (keyword.isNotBlank()) {
                        scope.launch {
                            loading = true
                            results = repo.search(keyword)
                            loading = false
                        }
                    }
                },
                modifier = Modifier.padding(horizontal=20.dp),
                colors = ButtonDefaults.colors(containerColor = Color(0xFF6C63FF))
            ) { Text("搜索", color = Color.White) }
            Spacer(Modifier.height(16.dp))
            if (loading) {
                Text("搜索中...", color = Color.Gray, modifier = Modifier.padding(20.dp))
            } else if (results.isEmpty() && keyword.isNotEmpty()) {
                Text("未找到结果", color = Color.Gray, modifier = Modifier.padding(20.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().padding(horizontal=8.dp)
                ) {
                    items(results, key = { it.id }) { r ->
                        VideoCard(
                            video = com.airplay.tv.data.api.Video(id=r.id,name=r.name,thumb=r.thumb),
                            isFocused = false,
                            onClick = { onVideoClick(r.id, "") }
                        )
                    }
                }
            }
        }
    }
}
