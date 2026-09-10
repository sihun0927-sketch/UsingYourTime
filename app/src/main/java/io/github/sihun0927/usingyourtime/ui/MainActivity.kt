package io.github.sihun0927.usingyourtime.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.sihun0927.usingyourtime.session.TrackingSettings
import io.github.sihun0927.usingyourtime.storage.SettingsStore
import io.github.sihun0927.usingyourtime.tracking.TrackingService
import io.github.sihun0927.usingyourtime.tracking.TrackingStatus
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 앱의 유일한 액티비티. 설정 화면 하나만 띄운다(스펙 5절). */
class MainActivity : ComponentActivity() {

    /**
     * 알림 권한 국면(스펙 5절). 초깃값은 첫 [onResume]이 바로 덮는다.
     *
     * 액티비티가 들고 있는 이유는 [onResume]마다 권한을 다시 판정해야 하기 때문이다. 사용자가
     * 안내 줄을 눌러 앱 정보 화면에서 권한을 켜고 돌아오면 여기서 [NotificationPermission.Granted]가 된다.
     */
    private var notificationPermission by mutableStateOf(NotificationPermission.Granted)

    /** 화면과 [onStart]가 함께 쓴다. DataStore 인스턴스는 프로세스에 하나뿐이라 만드는 값은 싸다. */
    private val settingsStore by lazy { SettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 거부는 화면 회전으로 잊히면 안 된다. 두 번 거부해 OS 신호가 사라진 뒤에도 이 기억이
        // 남는다. 권한이 켜졌는지는 어차피 onResume이 다시 판정한다.
        if (savedInstanceState?.getBoolean(STATE_NOTIFICATION_PERMISSION_DENIED) == true) {
            notificationPermission = NotificationPermission.Denied
        }

        setContent {
            UsingTimeTheme {
                val trackingOn by settingsStore.trackingOn.collectAsState(initial = false)
                val settings by settingsStore.settings.collectAsState(initial = TrackingSettings())
                val restartNoticeAtMillis by settingsStore.restartNoticeAtMillis
                    .collectAsState(initial = null)
                val sessionStartedAtMillis by TrackingStatus.sessionStartedAtMillis.collectAsState()
                val muted by TrackingStatus.muted.collectAsState()
                val lockScreenAbsent by TrackingStatus.lockScreenAbsent.collectAsState()
                val notificationPermissionRequest = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    // 거부되면 서비스를 띄우지 않는다(스펙 5절). 안내 줄과 비활성 버튼이 대신 뜬다.
                    notificationPermission = if (granted) {
                        NotificationPermission.Granted
                    } else {
                        NotificationPermission.Denied
                    }
                    if (granted) TrackingService.startTracking(this)
                }

                SettingsScreen(
                    trackingOn = trackingOn,
                    sessionStartedAtMillis = sessionStartedAtMillis,
                    notificationPermission = notificationPermission,
                    muted = muted,
                    restartNoticeAtMillis = restartNoticeAtMillis,
                    lockScreenAbsent = lockScreenAbsent,
                    thresholdMinutes = settings.thresholdMinutes,
                    graceMinutes = settings.graceMinutes,
                    reAlertMinutes = settings.reAlertMinutes,
                    thresholdAlertEnabled = settings.thresholdAlertEnabled,
                    onStartTracking = {
                        when (notificationPermission) {
                            NotificationPermission.Granted -> TrackingService.startTracking(this)
                            NotificationPermission.Required ->
                                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            // 버튼이 비활성이라 여기로 오지 않는다.
                            NotificationPermission.Denied -> Unit
                        }
                    },
                    onPause = { TrackingService.pause(this) },
                    // 저장이 끝나는 즉시 위의 흐름으로 새 값이 돌아오고, 서비스도 같은 흐름을
                    // 구독하고 있어 다음 판정부터 새 설정을 쓴다(스펙 3절).
                    onThresholdMinutesChange = { minutes ->
                        lifecycleScope.launch { settingsStore.setThresholdMinutes(minutes) }
                    },
                    onGraceMinutesChange = { minutes ->
                        lifecycleScope.launch { settingsStore.setGraceMinutes(minutes) }
                    },
                    onReAlertMinutesChange = { minutes ->
                        lifecycleScope.launch { settingsStore.setReAlertMinutes(minutes) }
                    },
                    onThresholdAlertEnabledChange = { enabled ->
                        lifecycleScope.launch { settingsStore.setThresholdAlertEnabled(enabled) }
                    },
                )
            }
        }
    }

    /**
     * 강제 종료·Task Manager "Stop"·OEM 절전으로 서비스만 사라진 뒤의 복구(스펙 7절).
     *
     * `tracking_on`은 켜져 있는데 서비스가 없으면 **확인 다이얼로그 없이** 다시 띄운다. 사용자는
     * 측정을 끈 적이 없으니 물어볼 것이 없다. 세션을 잇는지 닫는지는 서비스의 재동기화가 정한다.
     *
     * [onResume]이 아니라 [onStart]인 이유는 스펙이 적은 자리가 "액티비티 시작 시"이기 때문이다.
     * 화면을 잠갔다 열 때마다 되풀이할 일이 아니고, 무엇보다 사용자가 측정 중지를 누른 직후에 다시
     * 물으면 방금 내린 서비스를 되살릴 수 있다. 그 순간 [TrackingStatus.running]은 이미 거짓인데
     * DataStore의 `tracking_on`은 아직 참으로 읽힐 수 있어서다. 화면이 떠 있는 동안 일어나는
     * 측정 중지는 [onStart]를 다시 부르지 않는다.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            if (!TrackingStatus.running.value && settingsStore.trackingOn.first()) {
                TrackingService.restart(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationPermission = notificationPermissionState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            STATE_NOTIFICATION_PERMISSION_DENIED,
            notificationPermission == NotificationPermission.Denied,
        )
    }

    /**
     * 지금 권한 상태를 [NotificationPermission] 국면으로 옮긴다.
     *
     * 거부는 프로세스를 넘어 살아남아야 한다. 앱을 껐다 켠 뒤에 "측정 시작"이 다시 활성으로 보이면
     * 눌러도 다이얼로그 없이 거부되는 헛탭이 된다. 그래서 이번 프로세스의 기억뿐 아니라 OS가 들고
     * 있는 `shouldShowRequestPermissionRationale`도 함께 본다. 한 번 거부하거나 설정에서 권한을 끈
     * 뒤라야 참이 되므로, 참이면 곧 거부 상태다.
     *
     * 두 번 거부해 다이얼로그가 아예 뜨지 않게 된 뒤로는 OS 신호가 다시 거짓이 된다. 그때는
     * [NotificationPermission.Required]로 보이지만, 한 번 누르면 즉시 [NotificationPermission.Denied]가
     * 되어 안내 줄과 비활성 버튼으로 돌아온다.
     */
    private fun notificationPermissionState(): NotificationPermission = when {
        !needsNotificationPermission() -> NotificationPermission.Granted
        notificationPermission == NotificationPermission.Denied -> NotificationPermission.Denied
        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
            NotificationPermission.Denied

        else -> NotificationPermission.Required
    }

    /** API 33+에서 아직 알림 권한이 없으면 참. 32 이하는 요청 없이 진행한다(스펙 5절). */
    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STATE_NOTIFICATION_PERMISSION_DENIED = "notification_permission_denied"
    }
}
