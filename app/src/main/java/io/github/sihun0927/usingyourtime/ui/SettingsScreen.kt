package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/**
 * 설정 화면(스펙 5절). 앱의 유일한 화면이다. Scaffold가 edge-to-edge 인셋을 처리한다.
 *
 * 위에서부터 상태 카드, 설정, 개인정보처리방침 행이다. 나머지 설정(재알림 주기·임계값 알림 토글)과
 * 도움말 섹션은 각 티켓에서 채운다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    trackingOn: Boolean,
    sessionStartedAtMillis: Long?,
    notificationPermission: NotificationPermission,
    lockScreenAbsent: Boolean,
    thresholdMinutes: Int,
    graceMinutes: Int,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    onThresholdMinutesChange: (Int) -> Unit,
    onGraceMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StatusCard(
                trackingOn = trackingOn,
                sessionStartedAtMillis = sessionStartedAtMillis,
                notificationPermission = notificationPermission,
                lockScreenAbsent = lockScreenAbsent,
                onStartTracking = onStartTracking,
                onPause = onPause,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            ThresholdSetting(
                thresholdMinutes = thresholdMinutes,
                onThresholdMinutesChange = onThresholdMinutesChange,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            GracePeriodSetting(
                graceMinutes = graceMinutes,
                onGraceMinutesChange = onGraceMinutesChange,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider()
            Text(
                text = stringResource(R.string.privacy_policy),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { openPrivacyPolicy(context) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenOffPreview() {
    UsingTimeTheme {
        SettingsScreen(
            trackingOn = false,
            sessionStartedAtMillis = null,
            notificationPermission = NotificationPermission.Granted,
            lockScreenAbsent = false,
            thresholdMinutes = 30,
            graceMinutes = 3,
            onStartTracking = {},
            onPause = {},
            onThresholdMinutesChange = {},
            onGraceMinutesChange = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenOnPreview() {
    UsingTimeTheme {
        SettingsScreen(
            trackingOn = true,
            sessionStartedAtMillis = System.currentTimeMillis() - 42 * 60 * 1_000L,
            notificationPermission = NotificationPermission.Granted,
            lockScreenAbsent = false,
            thresholdMinutes = 30,
            graceMinutes = 3,
            onStartTracking = {},
            onPause = {},
            onThresholdMinutesChange = {},
            onGraceMinutesChange = {},
        )
    }
}
