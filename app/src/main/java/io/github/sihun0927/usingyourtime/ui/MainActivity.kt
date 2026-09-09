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
import io.github.sihun0927.usingyourtime.storage.SettingsStore
import io.github.sihun0927.usingyourtime.tracking.TrackingService
import io.github.sihun0927.usingyourtime.tracking.TrackingStatus
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/** 앱의 유일한 액티비티. 설정 화면 하나만 띄운다(스펙 5절). */
class MainActivity : ComponentActivity() {

    /**
     * 알림 권한 국면(스펙 5절). 초깃값은 첫 [onResume]이 바로 덮는다.
     *
     * 액티비티가 들고 있는 이유는 [onResume]마다 권한을 다시 판정해야 하기 때문이다. 사용자가
     * 안내 줄을 눌러 앱 정보 화면에서 권한을 켜고 돌아오면 여기서 [NotificationPermission.Granted]가 된다.
     */
    private var notificationPermission by mutableStateOf(NotificationPermission.Granted)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 거부는 화면 회전으로 잊히면 안 된다. 두 번 거부해 OS 신호가 사라진 뒤에도 이 기억이
        // 남는다. 권한이 켜졌는지는 어차피 onResume이 다시 판정한다.
        if (savedInstanceState?.getBoolean(STATE_NOTIFICATION_PERMISSION_DENIED) == true) {
            notificationPermission = NotificationPermission.Denied
        }

        val settingsStore = SettingsStore(this)
        setContent {
            UsingTimeTheme {
                val trackingOn by settingsStore.trackingOn.collectAsState(initial = false)
                val sessionStartedAtMillis by TrackingStatus.sessionStartedAtMillis.collectAsState()
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
                )
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
