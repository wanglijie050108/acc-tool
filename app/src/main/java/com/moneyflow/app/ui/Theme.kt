package com.moneyflow.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D52),
    onPrimary = Color.White,
    secondary = Color(0xFF546E7A),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FCF97),
    onPrimary = Color(0xFF00391F),
    secondary = Color(0xFFB0BEC5),
)

@Composable
fun MoneyFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
