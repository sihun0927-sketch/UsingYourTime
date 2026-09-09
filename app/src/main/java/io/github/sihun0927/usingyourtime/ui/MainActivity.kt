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

        // 거부는 화면 회전으로 잊히면 안 된다. 켜졌는지는 어차피 onResume이 다시 판정한다.
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
     * 허용됐거나 요청이 없는 API 32 이하면 [NotificationPermission.Granted], 이번 프로세스에서 이미
     * 거부당했으면 [NotificationPermission.Denied]를 유지하고, 그 밖에는 [NotificationPermission.Required].
     *
     * 사용자가 앱 정보 화면에서 권한을 끄면 OS가 프로세스를 다시 시작하므로 거부 기억이 남지 않는다.
     */
    private fun notificationPermissionState(): NotificationPermission = when {
        !needsNotificationPermission() -> NotificationPermission.Granted
        notificationPermission == NotificationPermission.Denied -> NotificationPermission.Denied
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
