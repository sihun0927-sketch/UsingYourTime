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
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.storage.SettingsStore
import io.github.sihun0927.usingyourtime.tracking.TrackingService
import io.github.sihun0927.usingyourtime.ui.theme.UsingTimeTheme

/** 앱의 유일한 액티비티. 설정 화면 하나만 띄운다(스펙 5절). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)
        setContent {
            UsingTimeTheme {
                val trackingOn by settingsStore.trackingOn.collectAsState(initial = false)
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    // 거부되면 서비스를 띄우지 않는다. 안내 줄과 버튼 비활성은 상태 카드 티켓에서.
                    if (granted) TrackingService.startTracking(this)
                }

                SettingsScreen(
                    trackingOn = trackingOn,
                    onStartTracking = {
                        if (needsNotificationPermission()) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            TrackingService.startTracking(this)
                        }
                    },
                    onPause = { TrackingService.pause(this) },
                )
            }
        }
    }

    /** API 33+에서 아직 알림 권한이 없으면 참. 32 이하는 요청 없이 진행한다(스펙 5절). */
    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}
