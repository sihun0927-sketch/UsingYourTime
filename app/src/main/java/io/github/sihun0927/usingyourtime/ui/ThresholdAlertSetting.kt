package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/**
 * 설정 화면의 임계값 알림 토글(스펙 5절 2번, 6절). 기본은 켜짐이고, 끄면 임계값 알림·재알림이
 * 오지 않는다.
 *
 * 끄더라도 측정과 상시 표시는 그대로다. 상시 표시의 초과 표기와 경고색도 유지되고, 본문만
 * 예고 없는 "임계값 N분 초과"가 된다(스펙 4절).
 *
 * 고른 값은 곧바로 DataStore `threshold_alert_enabled`에 저장되고, 서비스가 다음 판정부터 새 값을
 * 쓴다. 진행 중인 세션은 끊기지 않는다(스펙 3절).
 */
@Composable
internal fun ThresholdAlertSetting(
    thresholdAlertEnabled: Boolean,
    onThresholdAlertEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // 줄 전체가 토글이다. 스위치만 한 자리보다 두드릴 거리가 짧고, 낭독기에도 제목·설명과
        // 켜짐 여부가 한 덩어리로 읽힌다.
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = thresholdAlertEnabled,
                role = Role.Switch,
                onValueChange = onThresholdAlertEnabledChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_threshold_alert_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setting_threshold_alert_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = thresholdAlertEnabled, onCheckedChange = null)
    }
}

@Preview(showBackground = true)
@Composable
private fun ThresholdAlertSettingOnPreview() {
    UsingTimeTheme {
        ThresholdAlertSetting(thresholdAlertEnabled = true, onThresholdAlertEnabledChange = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ThresholdAlertSettingOffPreview() {
    UsingTimeTheme {
        ThresholdAlertSetting(thresholdAlertEnabled = false, onThresholdAlertEnabledChange = {})
    }
}
