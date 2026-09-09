package io.github.sihun0927.usingyourtime.tracking

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.notification.PersistentDisplay
import io.github.sihun0927.usingyourtime.session.Phase
import io.github.sihun0927.usingyourtime.session.SessionEffect
import io.github.sihun0927.usingyourtime.session.SessionEvent
import io.github.sihun0927.usingyourtime.session.SessionReducer
import io.github.sihun0927.usingyourtime.session.SessionState
import io.github.sihun0927.usingyourtime.session.TrackingSettings
import io.github.sihun0927.usingyourtime.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 측정이 켜진 동안 사는 포그라운드 서비스(스펙 8절). `specialUse` 타입, `START_STICKY`.
 *
 * 세션 상태는 [SessionReducer]가 값으로만 정하고, 이 서비스는 리듀서가 돌려준 효과를
 * Android API로 옮기기만 한다. 잠금·잠금 해제 리시버와 타이머는 이후 티켓에서 들어온다.
 */
class TrackingService : Service() {

    /**
     * `Main.immediate`라 이미 메인 스레드면 첫 효과가 [onStartCommand] 안에서 그대로 실행된다.
     * 상시 표시가 첫 효과이므로 `startForeground`가 미뤄지지 않는다.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val persistentDisplay by lazy { PersistentDisplay(this) }
    private val settingsStore by lazy { SettingsStore(this) }

    private var state: SessionState = SessionState.Off

    /** 설정은 아직 기본값 고정이다. DataStore와 잇는 일은 설정 화면 티켓에서 한다. */
    private val settings = TrackingSettings()

    override fun onCreate() {
        super.onCreate()
        persistentDisplay.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (eventOf(intent?.action)) {
            SessionEvent.StartTracking -> dispatch(SessionEvent.StartTracking)

            SessionEvent.Pause ->
                if (state.phase == Phase.Off) pauseWithoutSession() else dispatch(SessionEvent.Pause)

            // `intent == null`인 START_STICKY 재시작과 알 수 없는 요청이 여기로 온다.
            // 재동기화(스펙 7절)는 복구 티켓에서 붙으므로, 그때까지는 상시 표시 없이 남지 않도록 내린다.
            null -> {
                Log.i(TAG, "재동기화 규칙이 아직 없어 서비스를 내린다: action=${intent?.action}")
                stopService()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 프로세스가 죽어 서비스가 새로 만들어졌는데 "측정 중지"가 온 경우다. 리듀서는 이미 측정 꺼짐이라
     * 돌려줄 효과가 없지만 저장된 `tracking_on`은 켜진 채이므로 여기서 끄고 서비스를 내린다.
     */
    private fun pauseWithoutSession() {
        Log.i(TAG, "열린 세션 없이 측정 중지가 왔다. tracking_on만 끄고 서비스를 내린다.")
        serviceScope.launch {
            settingsStore.setTrackingOn(false)
            stopService()
        }
    }

    private fun dispatch(event: SessionEvent) {
        val reduction = SessionReducer.reduce(state, event, System.currentTimeMillis(), settings)
        state = reduction.state
        serviceScope.launch {
            reduction.effects.forEach { execute(it) }
        }
    }

    private suspend fun execute(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.UpdatePersistentDisplay -> showPersistentDisplay(effect.sessionStartedAtMillis)
            is SessionEffect.SaveTrackingOn -> settingsStore.setTrackingOn(effect.trackingOn)
            SessionEffect.StopService -> stopService()
        }
    }

    /** 상시 표시는 포그라운드 서비스의 알림이라 갱신도 `startForeground`로 한다. */
    private fun showPersistentDisplay(sessionStartedAtMillis: Long) {
        ServiceCompat.startForeground(
            this,
            PersistentDisplay.NOTIFICATION_ID,
            persistentDisplay.buildActive(sessionStartedAtMillis),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun eventOf(action: String?): SessionEvent? = when (action) {
        ACTION_START_TRACKING -> SessionEvent.StartTracking
        ACTION_PAUSE -> SessionEvent.Pause
        else -> null
    }

    companion object {
        private const val TAG = "UsingTime"
        private const val ACTION_START_TRACKING = "io.github.sihun0927.usingyourtime.action.START_TRACKING"
        private const val ACTION_PAUSE = "io.github.sihun0927.usingyourtime.action.PAUSE"

        /** 사용자가 "측정 시작"을 눌렀을 때. 서비스의 첫 시작은 이 경로뿐이다(스펙 8절). */
        fun startTracking(context: Context) {
            ContextCompat.startForegroundService(context, intentFor(context, ACTION_START_TRACKING))
        }

        /**
         * 사용자가 "측정 중지"를 눌렀을 때.
         *
         * 서비스가 이미 죽어 있을 수도 있어 `startForegroundService`를 쓰지 않는다. 그 경우
         * 상시 표시를 띄울 일이 없는데도 `startForeground`를 강요받기 때문이다. 화면이 떠 있는
         * 동안만 부르므로 백그라운드 시작 제한에 걸리지 않는다.
         */
        fun pause(context: Context) {
            context.startService(intentFor(context, ACTION_PAUSE))
        }

        private fun intentFor(context: Context, action: String): Intent =
            Intent(context, TrackingService::class.java).setAction(action)
    }
}
