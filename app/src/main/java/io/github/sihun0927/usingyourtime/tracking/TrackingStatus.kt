package io.github.sihun0927.usingyourtime.tracking

import io.github.sihun0927.usingyourtime.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 설정 화면이 열린 세션을 읽는 창구(스펙 5절 상태 카드). [TrackingService]가 리듀서에서 나온 새
 * 상태를 여기에 흘려보내고, 같은 프로세스의 화면이 [sessionStartedAtMillis]를 구독한다.
 *
 * 열린 세션은 아직 어디에도 저장되지 않아(스펙 8절 Room `sessions`는 복구 티켓 #27) 프로세스
 * 안에만 있다. 그래서 서비스가 죽고 화면만 새로 뜬 경우에는 `tracking_on`이 켜진 채여도 null이고
 * 상태 카드가 연속 사용 시간을 "—"로 둔다. 그 어긋남을 되돌리는 일은 재기동 티켓(#28)이 한다.
 *
 * 결정 배경은 `docs/adr/0001-ui-reads-open-session-from-process-holder.md`.
 */
object TrackingStatus {

    private val openSessionStartedAtMillis = MutableStateFlow<Long?>(null)

    /** 열린 세션의 시작 시각. 세션이 없거나 서비스가 이 프로세스에 없으면 null. */
    val sessionStartedAtMillis: StateFlow<Long?> = openSessionStartedAtMillis.asStateFlow()

    /** [TrackingService]만 부른다. 리듀서가 상태를 바꾼 직후 메인 스레드에서. */
    internal fun publish(state: SessionState) {
        openSessionStartedAtMillis.value = state.session?.startedAtMillis
    }
}
