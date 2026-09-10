package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/** 임계값 프리셋(스펙 6절). 이 표에 없는 값은 직접 입력으로 넣는다. */
private val THRESHOLD_PRESET_MINUTES = listOf(15, 30, 45, 60, 90, 120)

/**
 * 직접 입력이 받는 범위와 단위(스펙 6절). 10분 ~ 4시간, 5분 단위.
 *
 * 사용자에게 보이는 같은 내용이 `setting_threshold_dialog_hint`에 문장으로 있다. 여기를 고치면
 * 그 문자열도 같이 고친다.
 */
private const val THRESHOLD_MIN_MINUTES = 10
private const val THRESHOLD_MAX_MINUTES = 240
private const val THRESHOLD_STEP_MINUTES = 5

/** 입력 칸이 받는 자릿수. 최댓값이 세 자리라 그 너머는 두드려도 들어가지 않는다. */
private const val THRESHOLD_INPUT_DIGITS = 3

/**
 * 설정 화면의 임계값 항목(스펙 5절 2번). 프리셋 칩과 직접 입력 칩이 한 줄에 이어진다.
 *
 * 고른 값은 곧바로 DataStore `threshold_min`에 저장되고, 서비스가 다음 판정부터 새 값을 쓴다.
 * 이미 지난 값으로 낮추면 다음 `1분 tick`이 임계값 도달로 처리한다(스펙 3절).
 */
@Composable
internal fun ThresholdSetting(
    thresholdMinutes: Int,
    onThresholdMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val custom = thresholdMinutes !in THRESHOLD_PRESET_MINUTES

    MinutesSetting(
        label = stringResource(R.string.setting_threshold_label),
        description = stringResource(R.string.setting_threshold_description),
        presetMinutes = THRESHOLD_PRESET_MINUTES,
        selectedMinutes = thresholdMinutes,
        onSelect = onThresholdMinutesChange,
        modifier = modifier,
    ) {
        // 직접 입력한 값은 프리셋 칩 어디에도 자리가 없어 이 칩이 대신 이고 있다.
        FilterChip(
            selected = custom,
            onClick = { editing = true },
            label = {
                Text(
                    if (custom) {
                        stringResource(R.string.setting_threshold_custom_selected, thresholdMinutes)
                    } else {
                        stringResource(R.string.setting_threshold_custom)
                    },
                )
            },
        )
    }

    if (editing) {
        ThresholdInputDialog(
            thresholdMinutes = thresholdMinutes,
            onDismiss = { editing = false },
            onConfirm = { minutes ->
                editing = false
                onThresholdMinutesChange(minutes)
            },
        )
    }
}

/**
 * 임계값 직접 입력 다이얼로그. 범위·단위를 벗어난 값은 확인 버튼이 눌리지 않아 저장되지 않는다.
 *
 * 안내 문구는 늘 보인다. 들어갈 수 있는 값이 무엇인지 먼저 알려주는 편이, 틀린 뒤에 알려주는 것보다
 * 두드릴 거리가 짧다.
 */
@Composable
private fun ThresholdInputDialog(
    thresholdMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(thresholdMinutes.toString()) }
    val minutes = validThresholdMinutes(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_threshold_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(THRESHOLD_INPUT_DIGITS) },
                label = { Text(stringResource(R.string.setting_threshold_dialog_field_label)) },
                supportingText = { Text(stringResource(R.string.setting_threshold_dialog_hint)) },
                isError = minutes == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (minutes != null) onConfirm(minutes) }, enabled = minutes != null) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 직접 입력이 스펙 6절의 범위·단위 안이면 그 분, 아니면 null. */
private fun validThresholdMinutes(text: String): Int? {
    val minutes = text.toIntOrNull() ?: return null
    if (minutes !in THRESHOLD_MIN_MINUTES..THRESHOLD_MAX_MINUTES) return null
    if (minutes % THRESHOLD_STEP_MINUTES != 0) return null
    return minutes
}

@Preview(showBackground = true)
@Composable
private fun ThresholdSettingPreview() {
    UsingTimeTheme {
        ThresholdSetting(thresholdMinutes = 30, onThresholdMinutesChange = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ThresholdSettingCustomPreview() {
    UsingTimeTheme {
        ThresholdSetting(thresholdMinutes = 35, onThresholdMinutesChange = {})
    }
}
