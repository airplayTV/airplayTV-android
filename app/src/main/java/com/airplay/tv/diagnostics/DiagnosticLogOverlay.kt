package com.airplay.tv.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiagnosticLogOverlay(
    logs: List<DiagnosticLogEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .background(Color(0xD90B111A), MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("diagnostic-log-overlay"),
    ) {
        logs.takeLast(MAX_VISIBLE_DIAGNOSTIC_LOGS).forEach { entry ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = entry.stage,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = entry.message,
                    modifier = Modifier.padding(start = 10.dp),
                    color = Color(0xFFD6DEE8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val MAX_VISIBLE_DIAGNOSTIC_LOGS = 8
