package com.airplay.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.compose.material3.OutlinedTextField
import coil.compose.AsyncImage
import com.airplay.tv.data.api.SearchResult
import com.airplay.tv.data.api.Video
import com.airplay.tv.data.repository.VideoRepository
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    repo: VideoRepository,
    source: String = "",
    onVideoClick: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchAll by remember { mutableStateOf(source.isEmpty()) }

    fun doSearch() {
        if (keyword.isBlank()) return
        scope.launch {
            loading = true
            val s = if (searchAll) "" else source
            results = repo.search(keyword, s)
            loading = false
        }
    }

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
            Row(Modifier.padding(horizontal=20.dp), verticalAlignment=Alignment.CenterVertically) {
                var btnFocus by remember { mutableStateOf(false) }
                Button(
                    onClick = { doSearch() },
                    modifier = Modifier.onFocusChanged { btnFocus = it.isFocused }
                        .then(if (btnFocus) Modifier.scale(1.12f) else Modifier),
                    colors = ButtonDefaults.colors(containerColor = if (btnFocus) Color(0xFF7C73FF) else Color(0xFF6C63FF))
                ) { Text("搜索", color = Color.White, fontSize = 14.sp) }
                Spacer(Modifier.width(16.dp))
                if (source.isNotEmpty()) {
                    var allFocus by remember { mutableStateOf(false) }
                    Button(
                        onClick = { searchAll = !searchAll },
                        modifier = Modifier.onFocusChanged { allFocus = it.isFocused }
                            .then(if (allFocus) Modifier.scale(1.12f) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (searchAll) Color(0xFF7C73FF) else if (allFocus) Color(0xFF4A4A5A) else Color(0xFF2B2B35)
                        )
                    ) { Text(if (searchAll) "搜索全部源" else "仅当前源", color = Color.White, fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("搜索中...", color = Color(0xFF888888), fontSize = 20.sp)
                }
            } else if (results.isEmpty() && keyword.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未找到结果", color = Color(0xFF888888), fontSize = 18.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.id + (it.source ?: "") }) { r ->
                        var itemFocus by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onVideoClick(r.id, "", r.source ?: source) }
                                .onFocusChanged { itemFocus = it.isFocused }
                                .then(if (itemFocus) Modifier.scale(1.05f) else Modifier)
                                .background(
                                    if (itemFocus) Color(0xFF2A2A3A) else Color(0xFF1A1A28),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = r.thumb,
                                contentDescription = r.name,
                                modifier = Modifier
                                    .width(60.dp).aspectRatio(0.7f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .then(if (itemFocus) Modifier.border(1.dp, Color(0xFF7C73FF), RoundedCornerShape(4.dp)) else Modifier)
                                    .background(Color(0xFF2B2B35)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.name ?: "",
                                    fontSize = if (itemFocus) 16.sp else 15.sp,
                                    fontWeight = if (itemFocus) FontWeight.Bold else FontWeight.Normal,
                                    color = if (itemFocus) Color(0xFF7C73FF) else Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (r.source != null && r.source.isNotEmpty()) {
                                    Text(r.source, fontSize = 12.sp, color = Color(0xFF999999),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
