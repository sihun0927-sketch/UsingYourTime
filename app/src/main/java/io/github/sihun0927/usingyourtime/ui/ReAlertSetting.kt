package io.github.sihun0927.usingyourtime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/** 재알림 주기 프리셋(스펙 6절). 임계값과 달리 직접 입력이 없다. */
private val RE_ALERT_PRESET_MINUTES = listOf(5, 10, 15, 30)

/**
 * 설정 화면의 재알림 주기 항목(스펙 5절 2번). 고른 값은 곧바로 DataStore `realert_min`에 저장되고,
 * 서비스가 다음 판정부터 새 값을 쓴다.
 *
 * 이미 지난 값으로 낮추면 다음 `1분 tick`이 재알림으로 처리한다. 걸어 둔 깨우기는 옛 주기로 잡혀
 * 있어 아직 오지 않기 때문이다(스펙 3절 설정 변경 중 동작).
 */
@Composable
internal fun ReAlertSetting(
    reAlertMinutes: Int,
    onReAlertMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    MinutesSetting(
        label = stringResource(R.string.setting_re_alert_label),
        description = stringResource(R.string.setting_re_alert_description),
        presetMinutes = RE_ALERT_PRESET_MINUTES,
        selectedMinutes = reAlertMinutes,
        onSelect = onReAlertMinutesChange,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun ReAlertSettingPreview() {
    UsingTimeTheme {
        ReAlertSetting(reAlertMinutes = 15, onReAlertMinutesChange = {})
    }
}
