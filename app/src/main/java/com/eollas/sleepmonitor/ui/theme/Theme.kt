package com.eollas.sleepmonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val accent = Color(0xFF9D8CF2)
    val accentDeep = Color(0xFF6E5FB0)
    val moon = Color(0xFFE9DDB8)
    val text = Color(0xFFF2F1F7)
    val textDim = Color(0xB3F2F1F7)
    val textFaint = Color(0x80F2F1F7)
    val good = Color(0xFF7FD1AE)
    val warn = Color(0xFFE9B872)
    val bad = Color(0xFFE57B7B)
    val awake = Color(0xFFE9B872)
    val light = Color(0xFF9D8CF2)
    val deep = Color(0xFF5B4BC4)
    val sound = Color(0xFF6FC3DF)
}

@Composable
fun SleepTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            secondary = Palette.accentDeep,
            surface = Color(0xFF161B30),
            onSurface = Palette.text,
            background = Color(0xFF0B1020),
            onBackground = Palette.text,
            surfaceContainerHigh = Color(0xFF1C2238),
        ),
        typography = Typography(),
        content = content,
    )
}
