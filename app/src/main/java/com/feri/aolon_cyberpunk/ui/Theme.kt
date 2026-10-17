package com.feri.aolon_cyberpunk.ui

import android.provider.Settings
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Tokens from DESIGN.md; every text pair clears WCAG AA on Void, Panel and PanelLight (lowest: Soft 5.8:1).
object Neon {
    val Void = Color(0xFF070810)
    val Panel = Color(0xFF101321)
    val PanelLight = Color(0xFF171A2B)
    val Line = Color(0xFF5D6A87)
    val White = Color(0xFFF3F5FF)
    val Muted = Color(0xFFB4BDD4)
    val Soft = Color(0xFF8C96B0)
    val Blue = Color(0xFF53DDFF)
    val Purple = Color(0xFFCE82FF)
    val Red = Color(0xFFFF9BB5)
}

/** Cut corners are the identity motif: cards, buttons and chips share the same notch. */
val CardShape = CutCornerShape(topEnd = 16.dp, bottomStart = 16.dp)
val ControlShape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)

/** Readings use tabular figures so digits don't jitter while a timer or count updates. */
val Telemetry = TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

@Composable
fun AolonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Purple,
            onPrimary = Neon.Void,
            primaryContainer = Neon.Purple.copy(alpha = 0.18f),
            onPrimaryContainer = Neon.White,
            secondary = Neon.Blue,
            onSecondary = Neon.Void,
            secondaryContainer = Neon.PanelLight,
            onSecondaryContainer = Neon.White,
            background = Neon.Void,
            onBackground = Neon.White,
            surface = Neon.Void,
            onSurface = Neon.White,
            surfaceVariant = Neon.PanelLight,
            onSurfaceVariant = Neon.Muted,
            surfaceContainerLowest = Neon.Void,
            surfaceContainerLow = Neon.Panel,
            surfaceContainer = Neon.Panel,
            surfaceContainerHigh = Neon.PanelLight,
            surfaceContainerHighest = Neon.PanelLight,
            outline = Neon.Line,
            outlineVariant = Neon.Line.copy(alpha = 0.4f),
            error = Neon.Red,
            onError = Neon.Void,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = ControlShape,
            medium = CardShape,
            large = CardShape,
        ),
        content = content,
    )
}

/** True when the user turned animations off in developer or accessibility settings. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
