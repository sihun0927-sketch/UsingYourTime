package io.github.sihun0927.usingyourtime.tracking

import io.github.sihun0927.usingyourtime.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 설정 화면이 열린 세션을 읽는 창구(스펙 5절 상태 카드). [TrackingService]가 리듀서에서 나온 새
 * 상태를 여기에 흘려보내고, 같은 프로세스의 화면이 [sessionStartedAtMillis]를 구독한다.
 *
 * 열린 세션은 Room `sessions`에도 남지만(#21), 그 행을 읽어 상태를 되살리는 것은 복구 티켓 #27의
 * 재동기화다. 그래서 서비스가 죽고 화면만 새로 뜬 경우에는 `tracking_on`이 켜진 채여도 여기는
 * null이고, 상태 카드가 연속 사용 시간을 "세션 없음"으로 둔다. 그 어긋남을 되돌리는 일은 #27·#28.
 *
 * 결정 배경은 `docs/adr/0001-ui-reads-open-session-from-process-holder.md`.
 */
object TrackingStatus {

    private val openSessionStartedAtMillis = MutableStateFlow<Long?>(null)

    /** 열린 세션의 시작 시각. 세션이 없거나 서비스가 이 프로세스에 없으면 null. */
    val sessionStartedAtMillis: StateFlow<Long?> = openSessionStartedAtMillis.asStateFlow()

    private val openSessionMuted = MutableStateFlow(false)

    /**
     * 열린 세션이 세션 알림 끄기 상태인지(스펙 5절 상태 카드 안내 줄). 세션에 속한 값이라 세션이
     * 닫히면 함께 거짓으로 돌아간다.
     */
    val muted: StateFlow<Boolean> = openSessionMuted.asStateFlow()

    private val lockScreenAbsentState = MutableStateFlow(false)

    /**
     * 마지막 `화면 켜짐`에서 잠금 화면이 '없음'으로 판정됐는지(스펙 5절). 상태 카드의 안내 줄이
     * 이 값을 구독한다.
     *
     * 판정은 화면이 켜지는 순간에만 할 수 있어(그때만 잠금 화면이 있는지 물을 수 있다) 서비스가
     * 살아 있는 동안 쌓인 마지막 판정이 곧 이 값이다. 아직 한 번도 화면이 켜지지 않았거나 서비스가
     * 없으면 false이고, 안내 줄은 보이지 않는다.
     */
    val lockScreenAbsent: StateFlow<Boolean> = lockScreenAbsentState.asStateFlow()

    /** [TrackingService]만 부른다. 리듀서가 상태를 바꾼 직후 메인 스레드에서. */
    internal fun publish(state: SessionState) {
        openSessionStartedAtMillis.value = state.session?.startedAtMillis
        openSessionMuted.value = state.session?.muted == true
    }

    /** [TrackingService]만 부른다. `ACTION_SCREEN_ON`마다 다시 판정한 결과다(스펙 5절). */
    internal fun publishLockScreenAbsent(absent: Boolean) {
        lockScreenAbsentState.value = absent
    }

    /**
     * 서비스가 사라질 때 창구를 비운다. 화면이 없는 세션도, 아무도 다시 판정하지 않는 잠금 화면
     * 안내도 남기지 않는다.
     */
    internal fun clear() {
        publish(SessionState.Off)
        publishLockScreenAbsent(absent = false)
    }
}
