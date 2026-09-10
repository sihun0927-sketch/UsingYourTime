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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sihun0927.usingyourtime.notification.ACTION_MUTE_SESSION
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

    /** 화면이 켜지는 순간 잠금 화면이 있는지 묻는 곳(스펙 5절). 권한이 필요 없다. */
    private val keyguardManager by lazy {
        requireNotNull(getSystemService(KeyguardManager::class.java))
    }

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
     * 재알림 주기로 깨우는 타이머. 알림을 하나 보낼 때마다 새로 걸어 앞의 것을 덮는다.
     *
     * [thresholdAlertTimer]와 같은 이유로 알람을 함께 걸지 않는다. 재알림도 잠금 해제 상태에서만
     * 나가므로 기기가 잠든 동안 깨울 이유가 없다.
     */
    private var reAlertTimer: Job? = null

    /**
     * 잠금·잠금 해제·화면 켜짐과 유예 만료 깨우기, 그리고 임계값 알림의 "이번 세션 알림 끄기"
     * 버튼. manifest로는 받을 수 없어 서비스가 살아 있는 동안만 런타임 등록한다(스펙 8절).
     * 끌 세션은 서비스가 들고 있으므로 서비스가 없으면 버튼을 눌러도 받을 곳이 없는 것이 맞다.
     *
     * `ACTION_SCREEN_ON`만 화면이 켜진 직후의 잠금 상태를 읽어 간다. 그 자리에서 답해야 하는
     * 질문이 둘이기 때문이다(스펙 5절, ADR 0002).
     *
     * **어떤 이벤트로 넣나** — `isKeyguardLocked()`가 정한다. 잠금 화면이 '없음'인 기기에서는
     * 화면이 켜지는 순간이 곧 잠금 해제라 `ACTION_USER_PRESENT`를 보낼 주체가 없다. 그 기기에서도
     * 세션이 열리도록 잠겨 있지 않으면 `잠금 해제`로 옮긴다. 세션 규칙에 예외를 두는 것이 아니라
     * 이벤트를 옮기는 것이고, 리듀서는 이 분기를 모른다. 잠금 화면이 있는 기기는 화면이 켜지는
     * 순간 아직 잠겨 있어 `화면 켜짐` 그대로 가고, 곧이어 오는 진짜 `ACTION_USER_PRESENT`가
     * 세션을 연다.
     *
     * **잠금 화면이 없는 기기인가** — 안내 줄의 질문이고, `isKeyguardLocked()`만으로는 답이 되지
     * 않는다. 잠금 지연("화면이 꺼지고 N초 뒤 잠금")이나 Smart Lock으로 잠기지 않은 채 화면이
     * 켜지는 기기에서도 거짓이라, PIN을 쓰는 사용자에게 "잠금 화면이 없어…"가 상주하게 된다.
     * 잠금 수단이 있는지는 `isDeviceSecure()`가 답한다. 그 경우에도 이벤트는 `잠금 해제`가 맞다.
     * 그 순간 기기는 스펙 3절의 정의대로 실제 잠금 해제 상태다.
     */
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> SessionEvent.Unlock
                Intent.ACTION_SCREEN_OFF -> SessionEvent.Lock
                Intent.ACTION_SCREEN_ON -> {
                    val unlocked = !keyguardManager.isKeyguardLocked
                    TrackingStatus.publishLockScreenAbsent(
                        absent = unlocked && !keyguardManager.isDeviceSecure,
                    )
                    if (unlocked) SessionEvent.Unlock else SessionEvent.ScreenOn
                }

                GraceExpiryAlarm.ACTION -> SessionEvent.GraceExpired
                ACTION_MUTE_SESSION -> SessionEvent.MuteSession
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
            is SessionEffect.SaveAlertState -> sessionDao.saveAlerts(effect.alerts)
            is SessionEffect.SaveMuted -> sessionDao.saveMuted(effect.muted)
            is SessionEffect.ScheduleGraceExpiry -> scheduleGraceExpiry(effect.atMillis)
            SessionEffect.CancelGraceExpiry -> cancelGraceExpiry()
            is SessionEffect.PostThresholdAlert -> postThresholdAlert(effect.content)
            SessionEffect.DismissThresholdAlert -> dismissThresholdAlert()
            is SessionEffect.ScheduleThresholdAlert -> scheduleThresholdAlert(effect.atMillis)
            is SessionEffect.ScheduleReAlert -> scheduleReAlert(effect.atMillis)
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
     * 판정하고 어긋난 만큼은 `1분 tick`이 메운다(스펙 3절 설정 변경 중 동작). 그래서 예약을
     * 취소하거나 다시 걸 일이 없다. 세션이 닫힌 뒤 남은 타이머가 깨워도 리듀서가 무시한다.
     */
    private fun scheduleThresholdAlert(atMillis: Long) {
        thresholdAlertTimer?.cancel()
        thresholdAlertTimer = wakeUpAt(atMillis, SessionEvent.ThresholdReached)
    }

    /**
     * 재알림 주기로 깨우기. 알림이 나갈 때마다 그 시각 + 재알림 주기로 다시 건다.
     *
     * 사용자가 알림을 지워도 이 타이머는 그대로다. 지우는 것은 알림 창에서 일어나는 일이라 서비스에
     * 닿지 않고, 다음 재알림이 예정대로 온다(스펙 4절).
     *
     * 세션이 사는 동안 재알림 주기를 바꾸면 이 예약이 어긋나지만, [scheduleThresholdAlert]과 같이
     * 리듀서가 직전 알림 시각으로 다시 판정하고 어긋난 만큼은 `1분 tick`이 메운다.
     */
    private fun scheduleReAlert(atMillis: Long) {
        reAlertTimer?.cancel()
        reAlertTimer = wakeUpAt(atMillis, SessionEvent.ReAlertIntervalElapsed)
    }

    /**
     * [atMillis]에 [event]를 넣는 타이머 하나. 세 예약이 모두 이 모양이라 여기 한 번만 적는다.
     *
     * [atMillis]는 epoch이고 기다리는 길이는 여기서 한 번만 잰다. 그 뒤의 `delay`는 프로세스
     * 시계를 쓰므로 벽시계가 흔들려도 늘어나거나 줄지 않는다(주의사항 1).
     *
     * 부르는 쪽이 앞의 타이머를 취소하고 돌려받은 [Job]을 자기 자리에 넣는다. 예약마다 붙들 자리가
     * 달라서(유예 만료·임계값 도달·재알림) 그것까지 여기서 하지 않는다.
     */
    private fun wakeUpAt(atMillis: Long, event: SessionEvent): Job = serviceScope.launch {
        delay(atMillis - System.currentTimeMillis())
        dispatch(event)
    }

    private fun registerEventReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(GraceExpiryAlarm.ACTION)
            addAction(ACTION_MUTE_SESSION)
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
        graceExpiryTimer = wakeUpAt(atMillis, SessionEvent.GraceExpired)
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
