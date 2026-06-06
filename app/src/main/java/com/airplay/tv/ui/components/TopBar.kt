package com.airplay.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.airplay.tv.data.api.Source

@Composable
fun TopBar(
    sources: List<Source>,
    currentSource: String,
    onSourceSelected: (String) -> Unit,
    onSearchClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 源列表区域 + 渐隐
        Box(modifier = Modifier.weight(1f, fill = false)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEach { source ->
                    val isSelected = source.name == currentSource
                    var isFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { onSourceSelected(source.name) },
                        modifier = Modifier
                            .onFocusChanged { isFocused = it.isFocused }
                            .then(if (isFocused || isSelected) Modifier.scale(1.12f) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected || isFocused) Color(0xFF7C73FF) else Color(0xFF2B2B35)
                        )
                    ) {
                        Text(
                            source.name,
                            fontSize = if (isSelected || isFocused) 15.sp else 14.sp,
                            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected || isFocused) Color.White else Color(0xFFCCCCCC)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(48.dp)
                    .fillMaxHeight()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color(0xFF121218))
                        )
                    )
            )
        }

        Spacer(Modifier.width(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var searchFocused by remember { mutableStateOf(false) }
            Button(onClick = onSearchClick,
                modifier = Modifier.onFocusChanged { searchFocused = it.isFocused }
                    .then(if (searchFocused) Modifier.scale(1.12f) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = if (searchFocused) Color(0xFF7C73FF) else Color(0xFF2B2B35)
                )) {
                Text("搜索", fontSize = 14.sp,
                    fontWeight = if (searchFocused) FontWeight.Bold else FontWeight.Normal,
                    color = if (searchFocused) Color.White else Color(0xFFCCCCCC))
            }
            var histFocused by remember { mutableStateOf(false) }
            Button(onClick = onHistoryClick,
                modifier = Modifier.onFocusChanged { histFocused = it.isFocused }
                    .then(if (histFocused) Modifier.scale(1.12f) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = if (histFocused) Color(0xFF7C73FF) else Color(0xFF2B2B35)
                )) {
                Text("历史", fontSize = 14.sp,
                    fontWeight = if (histFocused) FontWeight.Bold else FontWeight.Normal,
                    color = if (histFocused) Color.White else Color(0xFFCCCCCC))
            }
            var setFocused by remember { mutableStateOf(false) }
            Button(onClick = onSettingsClick,
                modifier = Modifier.onFocusChanged { setFocused = it.isFocused }
                    .then(if (setFocused) Modifier.scale(1.12f) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = if (setFocused) Color(0xFF7C73FF) else Color(0xFF2B2B35)
                )) {
                Text("设置", fontSize = 14.sp,
                    fontWeight = if (setFocused) FontWeight.Bold else FontWeight.Normal,
                    color = if (setFocused) Color.White else Color(0xFFCCCCCC))
            }
        }
    }
}
