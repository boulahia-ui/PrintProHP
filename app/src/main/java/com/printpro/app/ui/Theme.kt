package com.printpro.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0067B1),
    secondary = Color(0xFF00A3E0),
    primaryContainer = Color(0xFFD3E4FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CCAFF),
    secondary = Color(0xFF7CD0F5),
)

@Composable
fun PrintProTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
