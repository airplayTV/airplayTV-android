
package com.airplay.tv.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.compose.material3.OutlinedTextField
import com.airplay.tv.data.db.AppDatabase
import com.airplay.tv.data.preferences.AppPreferences
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    prefs: AppPreferences,
    db: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var secret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var currentSource by remember { mutableStateOf("") }
    var currentTag by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        prefs.source.collect { currentSource = it }
        prefs.tag.collect { currentTag = it }
        prefs.secret.collect { secret = it }
        prefs.username.collect { username = it }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF121218)).onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Back) { onBack(); true } else false
    }) {
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
            Text("设置", color = Color.White, fontSize = 24.sp)
            Spacer(Modifier.height(24.dp))

            Text("当前源: ", color = Color.Gray, fontSize = 16.sp)
            Text(currentSource, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))

            Text("分类: ", color = Color.Gray, fontSize = 16.sp)
            Text(currentTag, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))

            Text("兑换码", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secret, onValueChange = { secret = it; scope.launch { prefs.setSecret(it) } },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("输入兑换码解锁更多资源", color = Color.Gray) }
            )
            Spacer(Modifier.height(16.dp))

            Text("账号", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it; scope.launch { prefs.setUsername(it) } },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("输入账号同步收藏夹", color = Color.Gray) }
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    scope.launch {
                        db.historyDao().clearAll()
                        db.timelineDao().clearAll()
                    }
                },
                colors = ButtonDefaults.colors(containerColor = Color(0xFFFF6B6B))
            ) { Text("清除播放历史", color = Color.White) }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { },
                colors = ButtonDefaults.colors(containerColor = Color(0xFFFF6B6B))
            ) { Text("清除所有缓存", color = Color.White) }
            Spacer(Modifier.height(32.dp))
            Text("AirPlay TV v1.0.0", color = Color(0xFF6C63FF), fontSize = 14.sp)
            Text("基于 android.software.leanback 构建", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
