package com.feri.aolon_cyberpunk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val WATCHFACES = listOf("violet" to "Violet", "blue" to "Blue", "plain" to "Mono")

/** Round face matching the watch's display, so the style pick is judged at real proportions. */
@Composable
fun WatchfacePreview(style: String, time: String) {
    val accent = when (style) {
        "blue" -> Neon.Blue
        "plain" -> Neon.White
        else -> Neon.Purple
    }
    Box(
        Modifier
            .size(170.dp)
            .background(Neon.Void, CircleShape)
            .border(4.dp, accent, CircleShape)
            .semantics { contentDescription = "Watch face preview, $time" },
        contentAlignment = Alignment.Center,
    ) {
        Text(time, style = Telemetry.copy(fontSize = 38.sp), color = accent)
    }
}
