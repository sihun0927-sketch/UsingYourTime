package io.github.sihun0927.usingyourtime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 앱 테마. 화면 색은 Material 3 기본 색을 그대로 쓴다. 스펙이 정한 화면 팔레트가 없어서다.
 *
 * 알림의 앱 accent·경고색(스펙 4절)은 화면과 따로 `colors.xml`에 있다. 알림은 시스템 배경 위에
 * 얹히고 대비 보정도 시스템이 하므로, 색 두 개를 리소스로 두고 런처 아이콘과 맞췄다.
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
