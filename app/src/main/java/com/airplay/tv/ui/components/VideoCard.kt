package com.airplay.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    Column(
        modifier = modifier
            .width(180.dp)
            .padding(4.dp)
            .clickable { onClick() }
            .then(
                if (isFocused) Modifier.background(
                    Color(0xFF6C63FF).copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
    ) {
        AsyncImage(
            model = video.thumb,
            contentDescription = video.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2B2B35)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = video.name ?: "",
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 2
        )
    }
}
