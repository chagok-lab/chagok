package com.chagok.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0A111E)
    val Panel = Color(0xFF101A2C)
    val Card = Color(0xFF16233A)
    val Card2 = Color(0xFF1B2B46)
    val Line = Color(0xFF26385A)
    val Text = Color(0xFFEAF1FB)
    val Sub = Color(0xFF9FB0C8)
    val Mute = Color(0xFF6C7E99)
    val Mint = Color(0xFF36D399)
    val MintBg = Color(0xFF10291F)
    val Amber = Color(0xFFFFB547)
    val Sky = Color(0xFF5AB0FF)
    val Violet = Color(0xFFCDB8FF)
}

@Composable
fun ChagokTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Mint,
            onPrimary = C.Bg,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Panel,
            onSurface = C.Text,
        ),
        content = content,
    )
}
