package io.github.sihun0927.usingyourtime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 앱 테마. 지금은 Material 3 기본 색을 그대로 쓴다.
 * 앱 accent와 경고색(스펙 4절)은 알림·설정 화면 티켓에서 함께 정한다.
 */
@Composable
fun UsingTimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
