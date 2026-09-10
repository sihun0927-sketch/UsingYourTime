package io.github.sihun0927.usingyourtime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * 위에서부터 상태 카드, 설정, 도움말, 개인정보처리방침 행이다. 스펙 5절 구성의 순서 그대로다.
 *
 * 상태 카드·설정·도움말은 함께 스크롤하고, 개인정보처리방침 행만 아래에 붙어 있다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    trackingOn: Boolean,
    sessionStartedAtMillis: Long?,
    notificationPermission: NotificationPermission,
    muted: Boolean,
    lockScreenAbsent: Boolean,
    thresholdMinutes: Int,
    graceMinutes: Int,
    reAlertMinutes: Int,
    thresholdAlertEnabled: Boolean,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    onThresholdMinutesChange: (Int) -> Unit,
    onGraceMinutesChange: (Int) -> Unit,
    onReAlertMinutesChange: (Int) -> Unit,
    onThresholdAlertEnabledChange: (Boolean) -> Unit,
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
            // 상태 카드·설정·도움말만 스크롤한다. 개인정보처리방침 행은 스펙 5절의 마지막 항목이라
            // 아래에 고정으로 붙여 둔다. 위가 길어져도 화면 밖으로 밀려나지 않는다.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                StatusCard(
                    trackingOn = trackingOn,
                    sessionStartedAtMillis = sessionStartedAtMillis,
                    notificationPermission = notificationPermission,
                    muted = muted,
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
                ReAlertSetting(
                    reAlertMinutes = reAlertMinutes,
                    onReAlertMinutesChange = onReAlertMinutesChange,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                ThresholdAlertSetting(
                    thresholdAlertEnabled = thresholdAlertEnabled,
                    onThresholdAlertEnabledChange = onThresholdAlertEnabledChange,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                HelpSection(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
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
            muted = false,
            lockScreenAbsent = false,
            thresholdMinutes = 30,
            graceMinutes = 3,
            reAlertMinutes = 15,
            thresholdAlertEnabled = true,
            onStartTracking = {},
            onPause = {},
            onThresholdMinutesChange = {},
            onGraceMinutesChange = {},
            onReAlertMinutesChange = {},
            onThresholdAlertEnabledChange = {},
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
            muted = false,
            lockScreenAbsent = false,
            thresholdMinutes = 30,
            graceMinutes = 3,
            reAlertMinutes = 15,
            thresholdAlertEnabled = true,
            onStartTracking = {},
            onPause = {},
            onThresholdMinutesChange = {},
            onGraceMinutesChange = {},
            onReAlertMinutesChange = {},
            onThresholdAlertEnabledChange = {},
        )
    }
}
