package io.github.sihun0927.usingyourtime.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 재부팅과 앱 업데이트에서 측정을 되살리는 manifest 리시버(스펙 8절 서비스·리시버).
 *
 * 서비스가 런타임 등록하는 리시버([TrackingService])와 역할이 다르다. 저쪽은 서비스가 살아 있는
 * 동안의 잠금·잠금 해제를 받고, 이쪽은 **서비스가 없는 동안** 오는 두 브로드캐스트를 받는다.
 * 그래서 manifest에 있어야 하고, 앱이 죽어 있어도 시스템이 깨워 준다.
 *
 * 하는 일은 두 액션을 가려내 [TrackingService.restartIfTrackingOn]에 넘기는 것뿐이다.
 * `tracking_on`을 보는 것도, 세션을 어떻게 이을지 정하는 것도 서비스 쪽이다
 * (스펙 7절 "모든 경로 단일 규칙").
 *
 * `MY_PACKAGE_REPLACED`는 이 앱이 업데이트됐을 때만 오고 별도 권한이 없다. `BOOT_COMPLETED`는
 * `RECEIVE_BOOT_COMPLETED`가 필요하고 이미 선언돼 있다(스펙 8절 권한 4개). Android 15+에서
 * Stopped 상태를 벗어날 때 오는 `BOOT_COMPLETED`도 같은 길로 들어온다(스펙 7절).
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // DataStore 읽기는 suspend라 onReceive가 돌아온 뒤에 끝난다. goAsync()로 그때까지 프로세스가
        // 살아 있도록 붙들지 않으면 읽기가 끝나기 전에 시스템이 앱을 걷어 갈 수 있다.
        val pendingResult = goAsync()
        val applicationContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val started = TrackingService.restartIfTrackingOn(applicationContext)
                Log.i(
                    LOG_TAG,
                    if (started) {
                        "${intent.action}: 측정이 켜져 있어 서비스를 다시 띄운다"
                    } else {
                        "${intent.action}: 측정이 꺼져 있어 아무것도 하지 않는다"
                    },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
