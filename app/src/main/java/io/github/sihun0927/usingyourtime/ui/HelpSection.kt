package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/**
 * 설정 화면의 도움말 "측정이 자주 멈추면"(스펙 5절 3번, 7절 사용자 안내).
 *
 * 제조사 절전 정책이 서비스를 재우면 측정이 끊기고, 그때 사용자가 할 수 있는 일은 배터리 최적화에서
 * 이 앱을 "제한 없음"으로 두는 것뿐이다. 그 자리로 가는 [openAppDetailsSettings] 버튼 하나가 이
 * 섹션의 전부다.
 *
 * **제조사별 문구는 두지 않는다**(스펙 5절). 절전 화면의 위치와 이름이 제조사마다 다르고 기기별로
 * 갈라진 안내는 곧 낡는다. 대신 어느 기기에나 있는 앱 정보 화면으로 보내고 거기서부터는 OS의 말을
 * 따르게 한다.
 *
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`는 쓰지 않는다. 권한이 4개로 고정돼 있고(스펙 8절), 그
 * 권한을 요청하는 앱은 Play 정책에서 별도 정당화를 요구받는다. 사용자가 OS 화면에서 직접 바꾸는
 * 길만 안내한다.
 */
@Composable
internal fun HelpSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stringResource(R.string.help_label), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.help_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openAppDetailsSettings(context) }) {
            Text(stringResource(R.string.help_action_app_info))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HelpSectionPreview() {
    UsingTimeTheme {
        HelpSection()
    }
}
