package com.rollingrecorder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Always dark: the app is a listening tool that lives next to a music player, not a form.
val Green = Color(0xFF1DB954)
val Ink = Color(0xFF121212)
val Panel = Color(0xFF181818)
val Raised = Color(0xFF282828)
val Mist = Color(0xFFB3B3B3)
val Amber = Color(0xFFF5A524)

private val Scheme = darkColorScheme(
    primary = Green,
    onPrimary = Color.Black,
    secondary = Mist,
    background = Ink,
    onBackground = Color.White,
    surface = Panel,
    onSurface = Color.White,
    surfaceVariant = Raised,
    onSurfaceVariant = Mist,
    outline = Color(0xFF3E3E3E),
    error = Color(0xFFFF6B6B),
)

// Four sizes: 28 display, 18 title, 15 body, 12 caption.
private val Type = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 15.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp, color = Mist),
    labelLarge = Typography().labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
    labelSmall = Typography().labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
