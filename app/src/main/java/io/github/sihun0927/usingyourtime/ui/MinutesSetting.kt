package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sihun0927.usingyourtime.R

/**
 * 분 단위 설정 한 항목의 공통 뼈대(스펙 5절 2번). 제목·설명 아래에 프리셋 칩이 한 줄로 흐른다.
 *
 * 임계값·유예 시간·재알림 주기가 모두 이 모양이고 프리셋 목록과 문구만 다르다. 임계값처럼 프리셋
 * 밖의 값을 받는 항목은 [trailingChip]으로 칩을 하나 더 붙인다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MinutesSetting(
    label: String,
    description: String,
    presetMinutes: List<Int>,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailingChip: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presetMinutes.forEach { minutes ->
                FilterChip(
                    selected = minutes == selectedMinutes,
                    onClick = { onSelect(minutes) },
                    label = { Text(stringResource(R.string.setting_minutes_chip, minutes)) },
                )
            }
            trailingChip()
        }
    }
}
