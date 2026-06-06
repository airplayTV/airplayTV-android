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
import coil.compose.AsyncImage
import com.airplay.tv.data.db.AppDatabase
import com.airplay.tv.data.db.HistoryEntity

@Composable
fun HistoryScreen(
    db: AppDatabase,
    onVideoClick: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    var list by remember { mutableStateOf<List<HistoryEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        list = db.historyDao().list(limit = 100)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF121218)).onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Back) { onBack(); true } else false
    }) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(16.dp))
            Text("播放历史", color = Color.White, fontSize = 22.sp,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无播放记录", color = Color(0xFF888888), fontSize = 18.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.id }) { item ->
                        var isFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isFocused) Color(0xFF7C73FF) else Color(0xFF1A1A28),
                                    RoundedCornerShape(10.dp)
                                )
                                .then(if (isFocused) Modifier.scale(1.05f) else Modifier)
                                .clickable {
                                    onVideoClick(item.vid, item.pid, item.source)
                                }
                                .onFocusChanged { isFocused = it.isFocused }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 缩略图
                                AsyncImage(
                                    model = item.thumb,
                                    contentDescription = item.name,
                                    modifier = Modifier
                                        .width(80.dp)
                                        .aspectRatio(0.7f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF2B2B35)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name.ifEmpty { "未知" },
                                        fontSize = if (isFocused) 16.sp else 15.sp,
                                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isFocused) Color.White else Color(0xFFE0E0E0),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.pname.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            item.pname,
                                            fontSize = 13.sp,
                                            color = Color(0xFF999999),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val progress = if (item.duration > 0)
                                            (item.lastTime.toFloat() / item.duration.toFloat() * 100).toInt() else 0
                                        Box(
                                            Modifier.weight(1f).height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(Color(0xFF3D3D4A))
                                        ) {
                                            Box(
                                                Modifier.fillMaxWidth(progress / 100f).fillMaxHeight()
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(Color(0xFF7C73FF))
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "%",
                                            fontSize = 12.sp,
                                            color = Color(0xFF7C73FF)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}


