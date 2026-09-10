package io.github.sihun0927.usingyourtime.tracking

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.notification.PersistentDisplay
import io.github.sihun0927.usingyourtime.notification.ThresholdAlert
import io.github.sihun0927.usingyourtime.session.PersistentDisplayContent
import io.github.sihun0927.usingyourtime.session.SessionEffect
import io.github.sihun0927.usingyourtime.session.SessionEvent
import io.github.sihun0927.usingyourtime.session.SessionReducer
import io.github.sihun0927.usingyourtime.session.SessionState
import io.github.sihun0927.usingyourtime.session.ThresholdAlertContent
import io.github.sihun0927.usingyourtime.session.TrackingSettings
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
     * 효과는 모두 메인 스레드에서 돈다. `immediate`라 이미 메인 스레드면 [effects]에 넣는 순간
     * 대기 중인 소비자가 그 자리에서 이어 달린다.
     *
     * 그래서 서비스를 띄우는 첫 이벤트의 상시 표시는 [onStartCommand] 안에서 `startForeground`까지
     * 간다. 줄이 비어 있고 상시 표시가 늘 첫 효과여서, 앞에서 기다리는 저장이 없기 때문이다.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val persistentDisplay by lazy { PersistentDisplay(this) }
    private val thresholdAlert by lazy { ThresholdAlert(this) }
    private val settingsStore by lazy { SettingsStore(this) }
    private val sessionDao by lazy { UsingTimeDatabase.get(this).sessionDao() }
    private val graceExpiryAlarm by lazy { GraceExpiryAlarm(this) }

    /**
     * 효과 실행 줄. 리시버·타이머가 이벤트를 넣기 시작하면서 효과 목록이 겹칠 수 있게 됐다.
     * 한 소비자가 넣은 순서대로 하나씩 끝내야 Room의 "열린 행 최대 1개"가 깨지지 않는다.
     */
    private val effects = Channel<SessionEffect>(Channel.UNLIMITED)

    private var state: SessionState = SessionState.Off

    /**
     * 마지막으로 읽은 설정. DataStore가 첫 값을 흘려보내기 전에는 스펙 6절의 기본값이다.
     *
     * 서비스를 띄우는 `측정 시작`은 그 첫 값보다 먼저 올 수 있다. 그 전이가 보는 설정은 임계값
     * 하나뿐이고, 그마저도 어긋나면 첫 `1분 tick`이 새 값으로 다시 판정한다.
     */
    private var settings = TrackingSettings()

    private var graceExpiryTimer: Job? = null

    /**
     * 임계값 도달로 깨우는 타이머. 세션을 열 때마다 새로 걸어 앞의 것을 덮는다.
     *
     * 유예 만료와 달리 알람을 함께 걸지 않는다. 임계값 알림은 잠금 해제 상태에서만 나가고, 그때는
     * 화면이 켜져 있어 프로세스가 잠들지 않는다. 화면이 꺼진 사이 임계값을 넘겼다면 다음 잠금
     * 해제 직후에 판정한다(#26).
     */
    private var thresholdAlertTimer: Job? = null

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
        thresholdAlert.ensureChannel()
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
        // 서비스를 시스템이 내렸다면 유예 만료 예약이 남아 있다. 받을 리시버가 사라져 아무 일도
        // 일어나지 않지만, 기기를 깨우기만 하는 알람을 남길 이유가 없다.
        graceExpiryAlarm.cancel()
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
        reduction.effects.forEach { effect ->
            // 줄이 닫힌 뒤(= 서비스가 내려간 뒤) 늦게 온 이벤트만 여기로 온다. 세션을 닫는 저장이
            // 이렇게 사라졌다면 열린 행이 남고, 그 재동기화는 스펙 7절대로 #27의 몫이다.
            if (effects.trySend(effect).isFailure) {
                Log.w(TAG, "서비스가 내려가 효과를 버린다: $effect")
            }
        }
    }

    private suspend fun execute(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.UpdatePersistentDisplay -> showPersistentDisplay(effect.content)
            is SessionEffect.SaveTrackingOn -> settingsStore.setTrackingOn(effect.trackingOn)
            is SessionEffect.OpenSession -> sessionDao.open(effect.startedAtMillis)
            is SessionEffect.SaveLockedAt -> sessionDao.saveLockedAt(effect.lockedAtMillis)
            is SessionEffect.CloseSession -> sessionDao.close(effect.endedAtMillis, effect.reason)
            is SessionEffect.SaveAlertState -> sessionDao.saveAlerts(effect.alerts)
            is SessionEffect.ScheduleGraceExpiry -> scheduleGraceExpiry(effect.atMillis)
            SessionEffect.CancelGraceExpiry -> cancelGraceExpiry()
            is SessionEffect.PostThresholdAlert -> postThresholdAlert(effect.content)
            SessionEffect.DismissThresholdAlert -> dismissThresholdAlert()
            is SessionEffect.ScheduleThresholdAlert -> scheduleThresholdAlert(effect.atMillis)
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

    /**
     * 임계값 알림 게시. 상시 표시와 달리 포그라운드 서비스의 알림이 아니라 따로 띄운다.
     *
     * 권한이 없으면 시스템이 조용히 버린다. 측정은 알림 권한을 받은 뒤에만 시작되지만(스펙 5절),
     * 그 뒤 사용자가 설정에서 권한을 거둘 수 있다.
     */
    private fun postThresholdAlert(content: ThresholdAlertContent) {
        NotificationManagerCompat.from(this)
            .notify(ThresholdAlert.NOTIFICATION_ID, thresholdAlert.build(content))
    }

    private fun dismissThresholdAlert() {
        NotificationManagerCompat.from(this).cancel(ThresholdAlert.NOTIFICATION_ID)
    }

    /**
     * 임계값 도달로 깨우기. 세션을 열 때 그 세션의 임계값 시각으로 한 번 건다.
     *
     * 세션이 사는 동안 임계값을 바꾸면 이 예약이 어긋나지만, 리듀서가 연속 사용 시간으로 다시
     * 판정하고 어긋난 만큼은 `1분 tick`이 메운다(스펙 3절 설정 변경 중 동작).
     */
    private fun scheduleThresholdAlert(atMillis: Long) {
        thresholdAlertTimer?.cancel()
        thresholdAlertTimer = serviceScope.launch {
            delay(atMillis - System.currentTimeMillis())
            dispatch(SessionEvent.ThresholdReached)
        }
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
     *
     * 메인 디스패처의 `delay`는 `Handler.postDelayed`, 곧 `uptimeMillis` 기준이라 기기가 깊이
     * 잠든 동안 **멈춰 있다**. 그래서 같은 시각에 `setAndAllowWhileIdle` 알람을 함께 건다. 둘 중
     * 어느 쪽이 먼저 와도, 늦게 와도 리듀서가 잠금 시각으로 다시 판정하므로 결과는 같다.
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
