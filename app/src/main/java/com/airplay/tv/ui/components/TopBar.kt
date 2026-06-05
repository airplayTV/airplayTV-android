package com.airplay.tv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.airplay.tv.data.api.Source

@Composable
fun TopBar(
    sources: List<Source>,
    currentSource: String,
    onSourceSelected: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            ,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { source ->
                val isSelected = source.name == currentSource
                Button(
                    onClick = { onSourceSelected(source.name) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) Color(0xFF6C63FF) else Color(0xFF2B2B35)
                    )
                ) {
                    Text(source.name, fontSize = 14.sp, color = Color.White)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSearchClick, colors = ButtonDefaults.colors(containerColor = Color(0xFF2B2B35))) {
                Text("搜索", fontSize = 14.sp, color = Color.White)
            }
            Button(onClick = onSettingsClick, colors = ButtonDefaults.colors(containerColor = Color(0xFF2B2B35))) {
                Text("设置", fontSize = 14.sp, color = Color.White)
            }
        }
    }
}
