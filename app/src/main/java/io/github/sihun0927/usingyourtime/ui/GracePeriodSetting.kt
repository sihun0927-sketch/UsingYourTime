package io.github.sihun0927.usingyourtime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/** 유예 시간 프리셋(스펙 6절). 직접 입력은 없다. 0은 잠그면 즉시 세션이 끝난다는 뜻이다. */
private val GRACE_PRESET_MINUTES = listOf(0, 1, 3, 5, 10, 15)

/**
 * 설정 화면의 유예 시간 항목(스펙 5절 2번). 고른 값은 곧바로 DataStore `grace_min`에 저장되고,
 * 서비스가 다음 판정부터 새 값을 쓴다. 진행 중인 세션은 끊기지 않는다(스펙 3절).
 */
@Composable
internal fun GracePeriodSetting(
    graceMinutes: Int,
    onGraceMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    MinutesSetting(
        label = stringResource(R.string.setting_grace_period_label),
        description = stringResource(R.string.setting_grace_period_description),
        presetMinutes = GRACE_PRESET_MINUTES,
        selectedMinutes = graceMinutes,
        onSelect = onGraceMinutesChange,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun GracePeriodSettingPreview() {
    UsingTimeTheme {
        GracePeriodSetting(graceMinutes = 3, onGraceMinutesChange = {})
    }
}
