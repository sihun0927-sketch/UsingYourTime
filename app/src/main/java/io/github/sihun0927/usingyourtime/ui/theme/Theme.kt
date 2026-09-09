package io.github.sihun0927.usingyourtime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 앱 accent. 상시 표시 막대의 초과 전 색과 같은 계열이다(스펙 4절). */
private val Accent = Color(0xFF1B4B7A)

/** 임계값 초과를 알리는 경고색(스펙 4절). */
private val Warning = Color(0xFFF5B547)

private val LightColors = lightColorScheme(
    primary = Accent,
    tertiary = Warning,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC7EE),
    tertiary = Warning,
)

@Composable
fun UsingTimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
