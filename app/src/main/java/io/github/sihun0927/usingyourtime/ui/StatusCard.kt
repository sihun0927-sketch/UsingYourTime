package io.github.sihun0927.usingyourtime.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sihun0927.usingyourtime.R
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme
import kotlinx.coroutines.delay

/** 연속 사용 시간을 다시 그리는 주기. 1초 눈금이 초 경계에 맞춰 올라간다. */
private const val TICK_MILLIS = 1_000L

/**
 * 설정 화면 맨 위의 상태 카드(스펙 5절). 화면 하나뿐인 앱에서 온보딩까지 겸한다.
 *
 * 3줄(측정 상태·연속 사용 시간·버튼)에 꺼짐 설명 문단과 조건부 안내 줄이 붙는다.
 * [sessionStartedAtMillis]가 있으면 연속 사용 시간이 1초마다 올라가고, 없으면 "—"다.
 *
 * 조건부 안내 줄은 스펙 5절이 적은 순서대로 쌓인다. 지금 있는 것은 알림 권한과
 * [lockScreenAbsent]이고, 사이에 들어갈 세션 알림 끄기·재시작 안내는 각 티켓에서 온다.
 */
@Composable
internal fun StatusCard(
    trackingOn: Boolean,
    sessionStartedAtMillis: Long?,
    notificationPermission: NotificationPermission,
    lockScreenAbsent: Boolean,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(
                label = stringResource(R.string.status_card_tracking_label),
                value = stringResource(
                    if (trackingOn) R.string.status_card_tracking_on else R.string.status_card_tracking_off,
                ),
            )
            StatusRow(
                label = stringResource(R.string.status_card_session_duration_label),
                value = sessionDurationText(trackingOn, sessionStartedAtMillis),
            )
            TrackingButton(
                trackingOn = trackingOn,
                notificationPermission = notificationPermission,
                onStartTracking = onStartTracking,
                onPause = onPause,
            )
            if (!trackingOn) {
                SupportingText(
                    text = stringResource(R.string.status_card_off_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NotificationPermissionNotice(notificationPermission)
            if (lockScreenAbsent) {
                SupportingText(
                    text = stringResource(R.string.status_card_lock_screen_absent),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 상태 카드의 한 줄. 왼쪽에 항목 이름, 오른쪽에 값. */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 연속 사용 시간(`sessionDuration`) 표기(스펙 5절). 측정이 켜져 있는데 세션이 없으면 "세션 없음",
 * 측정이 꺼져 있으면 "—".
 *
 * 형식은 상시 표시의 chronometer와 같다(`Chronometer`도 [DateUtils.formatElapsedTime]을 쓴다).
 * 상태바에서 보던 숫자가 카드에서도 같은 모양으로 올라간다. 숫자뿐이라 `strings.xml`에 두지 않는다.
 */
@Composable
private fun sessionDurationText(trackingOn: Boolean, sessionStartedAtMillis: Long?): String = when {
    sessionStartedAtMillis != null -> DateUtils.formatElapsedTime(elapsedSeconds(sessionStartedAtMillis))
    trackingOn -> stringResource(R.string.status_card_session_duration_idle)
    else -> stringResource(R.string.status_card_session_duration_none)
}

/**
 * [sessionStartedAtMillis]로부터 지금까지 흐른 초. 카드가 살아 있는 동안 1초마다 갱신된다.
 *
 * 세션 시작 시각이 `System.currentTimeMillis()`라 경과도 같은 시계로 재야 한다(주의사항 1).
 */
@Composable
private fun elapsedSeconds(sessionStartedAtMillis: Long): Long {
    var nowMillis by remember(sessionStartedAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    // 창이 포커스를 잃으면(화면이 꺼지거나 다른 앱으로 가면) 눈금을 멈춘다. 보이지 않는 숫자를
    // 1초마다 다시 그릴 이유가 없다. 돌아오면 효과가 다시 돌며 지금 시각으로 맞춘다.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(sessionStartedAtMillis, windowFocused) {
        if (!windowFocused) return@LaunchedEffect

        nowMillis = System.currentTimeMillis()
        while (true) {
            // 초가 바뀌는 순간에 깨어나야 눈금이 밀리지 않는다.
            delay(TICK_MILLIS - System.currentTimeMillis() % TICK_MILLIS)
            nowMillis = System.currentTimeMillis()
        }
    }
    return ((nowMillis - sessionStartedAtMillis) / TICK_MILLIS).coerceAtLeast(0)
}

/**
 * 알림 권한 안내 줄(스펙 5절). 요청 전에는 왜 필요한지, 거부 뒤에는 어디서 켜는지 알린다.
 * 거부 줄은 줄 전체가 앱 정보 화면으로 가는 딥링크다.
 */
@Composable
private fun NotificationPermissionNotice(notificationPermission: NotificationPermission) {
    val context = LocalContext.current
    when (notificationPermission) {
        NotificationPermission.Granted -> Unit

        NotificationPermission.Required -> SupportingText(
            text = stringResource(R.string.status_card_notification_permission_required),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NotificationPermission.Denied -> SupportingText(
            text = stringResource(R.string.status_card_notification_permission_denied),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable(role = Role.Button) { openAppDetailsSettings(context) }
                .padding(vertical = 8.dp),
        )
    }
}

/** 카드의 설명 문단·안내 줄에 쓰는 공통 문구 스타일. */
@Composable
private fun SupportingText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 카드의 버튼 1개. 꺼짐이면 채운 "측정 시작", 켜짐이면 테두리 "측정 중지"(스펙 5절). */
@Composable
private fun TrackingButton(
    trackingOn: Boolean,
    notificationPermission: NotificationPermission,
    onStartTracking: () -> Unit,
    onPause: () -> Unit,
) {
    if (trackingOn) {
        OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_pause))
        }
    } else {
        Button(
            onClick = onStartTracking,
            enabled = notificationPermission.allowsStartTracking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_start_tracking))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardOffPreview() {
    UsingTimeTheme {
        StatusCard(
            trackingOn = false,
            sessionStartedAtMillis = null,
            notificationPermission = NotificationPermission.Granted,
            lockScreenAbsent = false,
            onStartTracking = {},
            onPause = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardActivePreview() {
    UsingTimeTheme {
        StatusCard(
            trackingOn = true,
            sessionStartedAtMillis = System.currentTimeMillis() - 12 * 60 * TICK_MILLIS,
            notificationPermission = NotificationPermission.Granted,
            lockScreenAbsent = false,
            onStartTracking = {},
            onPause = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardLockScreenAbsentPreview() {
    UsingTimeTheme {
        StatusCard(
            trackingOn = true,
            sessionStartedAtMillis = System.currentTimeMillis() - 12 * 60 * TICK_MILLIS,
            notificationPermission = NotificationPermission.Granted,
            lockScreenAbsent = true,
            onStartTracking = {},
            onPause = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardPermissionRequiredPreview() {
    UsingTimeTheme {
        StatusCard(
            trackingOn = false,
            sessionStartedAtMillis = null,
            notificationPermission = NotificationPermission.Required,
            lockScreenAbsent = false,
            onStartTracking = {},
            onPause = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCardPermissionDeniedPreview() {
    UsingTimeTheme {
        StatusCard(
            trackingOn = false,
            sessionStartedAtMillis = null,
            notificationPermission = NotificationPermission.Denied,
            lockScreenAbsent = false,
            onStartTracking = {},
            onPause = {},
        )
    }
}
