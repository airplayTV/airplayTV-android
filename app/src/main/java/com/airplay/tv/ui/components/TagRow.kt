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
            ,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { tag ->
            val isSelected = tag.value == selectedTag
            Button(
                onClick = { onTagSelected(tag.value) },
                colors = ButtonDefaults.colors(
                    containerColor = if (isSelected) Color(0xFF6C63FF) else Color(0xFF2B2B35)
                )
            ) {
                Text(
                    text = tag.name,
                    fontSize = if (isSelected) 15.sp else 14.sp,
                    color = Color.White
                )
            }
        }
    }
}
