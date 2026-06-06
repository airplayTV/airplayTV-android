package com.airplay.tv.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.airplay.tv.data.api.Tag

@Composable
fun TagRow(
    tags: List<Tag>,
    selectedTag: String,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { tag ->
            val isSelected = tag.value == selectedTag
            var isFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { onTagSelected(tag.value) },
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .then(if (isFocused || isSelected) Modifier.scale(1.12f) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = if (isSelected || isFocused) Color(0xFF7C73FF) else Color(0xFF2B2B35)
                )
            ) {
                Text(
                    text = tag.name,
                    fontSize = if (isSelected || isFocused) 16.sp else 14.sp,
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected || isFocused) Color.White else Color(0xFF999999)
                )
            }
        }
    }
}
