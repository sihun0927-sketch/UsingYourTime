package io.github.sihun0927.usingyourtime.tracking

import android.app.KeyguardManager
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
    private val settingsStore by lazy { SettingsStore(this) }
    private val sessionDao by lazy { UsingTimeDatabase.get(this).sessionDao() }
    private val graceExpiryAlarm by lazy { GraceExpiryAlarm(this) }

    /** 화면이 켜지는 순간 잠금 화면이 있는지 묻는 곳(스펙 5절). 권한이 필요 없다. */
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    /**
     * 효과 실행 줄. 리시버·타이머가 이벤트를 넣기 시작하면서 효과 목록이 겹칠 수 있게 됐다.
     * 한 소비자가 넣은 순서대로 하나씩 끝내야 Room의 "열린 행 최대 1개"가 깨지지 않는다.
     */
    private val effects = Channel<SessionEffect>(Channel.UNLIMITED)

    private var state: SessionState = SessionState.Off

    /**
     * 마지막으로 읽은 설정. DataStore가 첫 값을 흘려보내기 전에는 스펙 6절의 기본값이다.
     *
     * 서비스를 띄우는 `측정 시작`은 그 첫 값보다 먼저 올 수 있지만, 그 전이가 보는 설정은 아직
     * 사용자가 고칠 수 없는 임계값뿐이다(#23). 유예 시간을 보는 `잠금`부터는 사용자의 손이 한 번
     * 더 필요해 그때는 이미 읽혀 있다.
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
                Intent.ACTION_SCREEN_ON -> screenOnEvent()
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
        TrackingStatus.clear()
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

    /**
     * `ACTION_SCREEN_ON`을 리듀서에 넣을 이벤트로 옮긴다. 화면이 켜진 직후의 잠금 화면 판정이
     * 안내 줄(스펙 5절)과 이 번역을 함께 정한다.
     *
     * 잠금 화면이 '없음'인 기기에서는 화면이 켜지는 순간이 곧 잠금 해제라 `ACTION_USER_PRESENT`를
     * 보낼 주체가 없다. 그 기기에서도 세션이 열리도록 여기서 `잠금 해제`로 옮긴다. 세션 규칙에
     * 예외를 두는 것이 아니라 이벤트를 옮기는 것이고, 리듀서는 이 분기를 모른다(ADR 0002).
     *
     * 잠금 화면이 있는 기기는 화면이 켜지는 순간 아직 잠겨 있어 `화면 켜짐` 그대로 가고, 곧이어
     * 오는 진짜 `ACTION_USER_PRESENT`가 세션을 연다. "화면이 꺼지고 N초 뒤 잠금" 설정의 그 N초
     * 안에 다시 켜면 잠겨 있지 않아 여기서도 `잠금 해제`가 되는데, 그 순간 기기는 실제로 잠금
     * 해제 상태다(스펙 3절의 정의). 안내 줄만 한 번 잘못 켜지고 다음 `화면 켜짐`에 사라진다.
     */
    private fun screenOnEvent(): SessionEvent {
        val locked = keyguardManager.isKeyguardLocked
        TrackingStatus.publishLockScreenAbsent(absent = !locked)
        return if (locked) SessionEvent.ScreenOn else SessionEvent.Unlock
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
