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
 * 위에서부터 상태 카드, 설정, 개인정보처리방침 행이다. 남은 설정(재알림 주기)과 도움말 섹션은
 * 각 티켓(#24·#29)에서 채운다.
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
    thresholdAlertEnabled: Boolean,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    onThresholdMinutesChange: (Int) -> Unit,
    onGraceMinutesChange: (Int) -> Unit,
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
            // 설정만 스크롤한다. 개인정보처리방침 행은 스펙 5절의 마지막 항목이라 아래에 붙여 둔다.
            // 항목이 늘어도(#24 재알림 주기, #29 도움말) 화면 밖으로 밀려나지 않는다.
            SettingsList(
                trackingOn = trackingOn,
                sessionStartedAtMillis = sessionStartedAtMillis,
                notificationPermission = notificationPermission,
                muted = muted,
                lockScreenAbsent = lockScreenAbsent,
                thresholdMinutes = thresholdMinutes,
                graceMinutes = graceMinutes,
                thresholdAlertEnabled = thresholdAlertEnabled,
                onStartTracking = onStartTracking,
                onPause = onPause,
                onThresholdMinutesChange = onThresholdMinutesChange,
                onGraceMinutesChange = onGraceMinutesChange,
                onThresholdAlertEnabledChange = onThresholdAlertEnabledChange,
                modifier = Modifier.weight(1f),
            )
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

/** 상태 카드와 설정 항목들. 화면보다 길어지면 이 안에서 스크롤한다(스펙 5절 구성 1·2번). */
@Composable
private fun SettingsList(
    trackingOn: Boolean,
    sessionStartedAtMillis: Long?,
    notificationPermission: NotificationPermission,
    muted: Boolean,
    lockScreenAbsent: Boolean,
    thresholdMinutes: Int,
    graceMinutes: Int,
    thresholdAlertEnabled: Boolean,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    onThresholdMinutesChange: (Int) -> Unit,
    onGraceMinutesChange: (Int) -> Unit,
    onThresholdAlertEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
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
        ThresholdAlertSetting(
            thresholdAlertEnabled = thresholdAlertEnabled,
            onThresholdAlertEnabledChange = onThresholdAlertEnabledChange,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
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
            thresholdAlertEnabled = true,
            onStartTracking = {},
            onPause = {},
            onThresholdMinutesChange = {},
            onGraceMinutesChange = {},
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
            thresholdAlertEnabled = true,
            onStartTracking = {},
            onPause = {},
            onThresholdMinutesChange = {},
            onGraceMinutesChange = {},
            onThresholdAlertEnabledChange = {},
        )
    }
}
