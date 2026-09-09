package io.github.sihun0927.usingyourtime.tracking

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.notification.PersistentDisplay
import io.github.sihun0927.usingyourtime.session.PersistentDisplayContent
import io.github.sihun0927.usingyourtime.session.SessionEffect
import io.github.sihun0927.usingyourtime.session.SessionEvent
import io.github.sihun0927.usingyourtime.session.SessionReducer
import io.github.sihun0927.usingyourtime.session.SessionState
import io.github.sihun0927.usingyourtime.session.TrackingSettings
import io.github.sihun0927.usingyourtime.storage.SessionStore
import io.github.sihun0927.usingyourtime.storage.SettingsStore
import io.github.sihun0927.usingyourtime.storage.UsingTimeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 상시 표시를 다시 그리는 주기. 스펙 4절대로 1분에 1회다. */
private const val TICK_MILLIS = 60_000L

/**
 * 측정이 켜진 동안 사는 포그라운드 서비스(스펙 8절). `specialUse` 타입, `START_STICKY`.
 *
 * 하는 일은 셋뿐이다. 잠금·잠금 해제·타이머를 [SessionEvent]로 바꿔 [SessionReducer]에 넣고,
 * 리듀서가 돌려준 효과를 Android API로 옮기고, 새 상태를 [TrackingStatus]에 흘려보낸다.
 * 세션 규칙은 하나도 여기에 없다.
 *
 * START_STICKY 재시작·부팅·업데이트에서 상태를 되살리는 일은 복구 티켓(#27·#28)이 맡는다.
 */
class TrackingService : Service() {

    /**
     * `Main.immediate`라 이미 메인 스레드면 첫 효과가 [onStartCommand] 안에서 그대로 실행된다.
     * 상시 표시가 첫 효과이므로 `startForeground`가 미뤄지지 않는다.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val persistentDisplay by lazy { PersistentDisplay(this) }
    private val settingsStore by lazy { SettingsStore(this) }
    private val sessionStore by lazy { SessionStore(UsingTimeDatabase.get(this).sessionDao()) }
    private val graceExpiryAlarm by lazy { GraceExpiryAlarm(this) }

    /**
     * 효과 실행 줄. 리시버·타이머가 이벤트를 넣기 시작하면서 효과 목록이 겹칠 수 있게 됐다.
     * 한 소비자가 넣은 순서대로 하나씩 끝내야 Room의 "열린 행 최대 1개"가 깨지지 않는다.
     */
    private val effects = Channel<SessionEffect>(Channel.UNLIMITED)

    private var state: SessionState = SessionState.Off

    /**
     * 마지막으로 읽은 설정. DataStore가 첫 값을 흘려보내기 전에는 스펙 6절의 기본값이다.
     * 잠금·잠금 해제는 사용자의 손이 필요해 그때는 이미 읽혀 있다.
     */
    private var settings = TrackingSettings()

    private var graceExpiryTimer: Job? = null

    /**
     * 잠금·잠금 해제·화면 켜짐과 유예 만료 깨우기. manifest로는 받을 수 없어 서비스가 살아 있는
     * 동안만 런타임 등록한다(스펙 8절).
     */
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> SessionEvent.Unlock
                Intent.ACTION_SCREEN_OFF -> SessionEvent.Lock
                Intent.ACTION_SCREEN_ON -> SessionEvent.ScreenOn
                GraceExpiryAlarm.ACTION -> SessionEvent.GraceExpired
                else -> return
            }
            dispatch(event)
        }
    }

    override fun onCreate() {
        super.onCreate()
        persistentDisplay.ensureChannel()
        serviceScope.launch {
            for (effect in effects) execute(effect)
        }
        serviceScope.launch {
            settingsStore.settings.collect { settings = it }
        }
        registerEventReceiver()
        startMinuteTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val event = eventOf(intent?.action)) {
            // `intent == null`인 START_STICKY 재시작과 알 수 없는 요청이 여기로 온다. 넣을 이벤트가
            // 없으니 상시 표시도 띄울 수 없어 서비스를 내린다. 저장된 `tracking_on`은 건드리지 않는다.
            // 측정은 강제 종료·재부팅을 넘어 이어져야 하고(`CONTEXT.md` 측정 시작), 그 재동기화는
            // 스펙 7절대로 복구 티켓에서 붙는다.
            null -> {
                Log.i(TAG, "넣을 이벤트가 없어 서비스를 내린다: action=${intent?.action}")
                stopService()
            }

            else -> dispatch(event)
        }
        return START_STICKY
    }

    /**
     * 서비스가 사라지면 열린 세션도 프로세스에서 사라진다. 스스로 내려간 경우든 시스템이 죽인
     * 경우든 화면이 없는 세션을 계속 보여주지 않도록 창구를 비운다.
     */
    override fun onDestroy() {
        TrackingStatus.publish(SessionState.Off)
        unregisterReceiver(eventReceiver)
        effects.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 이벤트를 리듀서에 넣고 새 상태를 붙든다. 이벤트 출처(리시버·타이머·[onStartCommand])가 모두
     * 메인 스레드라 [state] 읽기·쓰기가 겹치지 않는다. 효과는 줄에 세워 순서대로 실행한다.
     */
    private fun dispatch(event: SessionEvent) {
        val reduction = SessionReducer.reduce(state, event, System.currentTimeMillis(), settings)
        // 잠금·잠금 해제·타이머는 화면에 자국을 남기지 않아 로그가 유일한 관측 창구다
        // (에뮬레이터 스모크, AGENTS.md의 `adb logcat -s UsingTime:*`).
        Log.i(TAG, "$event: ${state.phase} → ${reduction.state.phase}")
        state = reduction.state
        TrackingStatus.publish(state)
        reduction.effects.forEach(effects::trySend)
    }

    private suspend fun execute(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.UpdatePersistentDisplay -> showPersistentDisplay(effect.content)
            is SessionEffect.SaveTrackingOn -> settingsStore.setTrackingOn(effect.trackingOn)
            is SessionEffect.OpenSession -> sessionStore.open(effect.startedAtMillis)
            is SessionEffect.SaveLockedAt -> sessionStore.saveLockedAt(effect.lockedAtMillis)
            is SessionEffect.CloseSession -> sessionStore.close(effect.endedAtMillis, effect.reason)
            is SessionEffect.ScheduleGraceExpiry -> scheduleGraceExpiry(effect.atMillis)
            SessionEffect.CancelGraceExpiry -> cancelGraceExpiry()
            SessionEffect.StopService -> stopService()
        }
    }

    /** 상시 표시는 포그라운드 서비스의 알림이라 갱신도 `startForeground`로 한다. */
    private fun showPersistentDisplay(content: PersistentDisplayContent) {
        ServiceCompat.startForeground(
            this,
            PersistentDisplay.NOTIFICATION_ID,
            persistentDisplay.build(content),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun registerEventReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(GraceExpiryAlarm.ACTION)
        }
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * 측정이 켜진 동안 계속 도는 1분 타이머. 상시 표시의 본문·막대가 이 tick으로 갱신된다.
     * 서비스와 수명이 같아 따로 멈출 일이 없다.
     */
    private fun startMinuteTicker() {
        serviceScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                dispatch(SessionEvent.MinuteTick)
            }
        }
    }

    /**
     * 유예 만료 깨우기. 프로세스가 깨어 있는 동안은 coroutine 타이머가, 잠들면 알람이 깨운다.
     * `delay`는 `elapsedRealtime` 기준이라 남은 시간을 지금 한 번만 재면 된다(주의사항 1).
     */
    private fun scheduleGraceExpiry(atMillis: Long) {
        graceExpiryTimer?.cancel()
        graceExpiryTimer = serviceScope.launch {
            delay(atMillis - System.currentTimeMillis())
            dispatch(SessionEvent.GraceExpired)
        }
        graceExpiryAlarm.schedule(atMillis)
    }

    private fun cancelGraceExpiry() {
        graceExpiryTimer?.cancel()
        graceExpiryTimer = null
        graceExpiryAlarm.cancel()
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
