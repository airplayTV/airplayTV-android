package com.airplay.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.airplay.tv.data.api.Video

@Composable
fun VideoCard(
    video: Video,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(isFocused) }
    Column(
        modifier = modifier
            .padding(6.dp)
            .focusable()
            .clickable { onClick() }
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier
                    .scale(1.12f)
                    .shadow(10.dp, RoundedCornerShape(8.dp), clip = false)
                else Modifier
            )
    ) {
        AsyncImage(
            model = video.thumb,
            contentDescription = video.name,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (focused) Modifier
                        .background(Color(0xFF7C73FF), RoundedCornerShape(8.dp))
                    else Modifier
                )
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (focused) Modifier.border(3.dp, Color(0xFF7C73FF), RoundedCornerShape(8.dp))
                    else Modifier
                )
                .background(Color(0xFF2B2B35)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.name ?: "",
            fontSize = if (focused) 16.sp else 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = if (focused) Color.White else Color(0xFFE0E0E0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
