package com.example.meetingtranscript.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.meetingtranscript.data.platform.ThemeMode

private val MeetingLightColors = lightColorScheme(
    primary = Color(0xFF1B5E9E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8FF),
    onPrimaryContainer = Color(0xFF00315C),
    secondary = Color(0xFF4C7FAF),
    secondaryContainer = Color(0xFFD6E8F9),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFF7F9FC),
    surfaceVariant = Color(0xFFE1EAF2),
)

private val MeetingDarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF194878),
    onPrimaryContainer = Color(0xFFD7E8FF),
    secondary = Color(0xFFB2CCE5),
    secondaryContainer = Color(0xFF314A61),
    background = Color(0xFF10161E),
    surface = Color(0xFF10161E),
    surfaceVariant = Color(0xFF404A56),
)

@Composable
fun MeetingAppTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) MeetingDarkColors else MeetingLightColors, content = content)
}
