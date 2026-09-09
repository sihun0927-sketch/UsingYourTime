package io.github.sihun0927.usingyourtime.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 스펙 3절 전이표 중 이 티켓이 다루는 두 상태(측정 꺼짐·세션 진행)와
 * 두 이벤트(측정 시작·측정 중지)의 네 칸을 값 비교로 검증한다(#19).
 */
class SessionReducerTest {

    private val settings = TrackingSettings()
    private val nowMillis = 1_700_000_000_000L

    @Test
    fun `측정 꺼짐에서 측정 시작을 누르면 지금 시각으로 세션이 열린다`() {
        val reduction = reduce(SessionState.Off, SessionEvent.StartTracking)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reduction.state,
        )
    }

    @Test
    fun `측정 꺼짐에서 측정 시작을 누르면 상시 표시를 게시하고 tracking_on을 켠다`() {
        val reduction = reduce(SessionState.Off, SessionEvent.StartTracking)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(sessionStartedAtMillis = nowMillis),
                SessionEffect.SaveTrackingOn(trackingOn = true),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `세션 진행에서 측정 중지를 누르면 측정 꺼짐이 되고 열린 세션이 닫힌다`() {
        val reduction = reduce(activeSince(nowMillis - 60_000), SessionEvent.Pause)

        assertEquals(SessionState.Off, reduction.state)
    }

    @Test
    fun `세션 진행에서 측정 중지를 누르면 tracking_on을 끄고 서비스를 종료한다`() {
        val reduction = reduce(activeSince(nowMillis - 60_000), SessionEvent.Pause)

        assertEquals(
            listOf(
                SessionEffect.SaveTrackingOn(trackingOn = false),
                SessionEffect.StopService,
            ),
            reduction.effects,
        )
    }

    @Test
    fun `측정 꺼짐에서 측정 중지는 아무 일도 하지 않는다`() {
        val reduction = reduce(SessionState.Off, SessionEvent.Pause)

        assertEquals(SessionState.Off, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    @Test
    fun `세션 진행에서 측정 시작은 세션을 새로 열지 않는다`() {
        val active = activeSince(nowMillis - 60_000)

        val reduction = reduce(active, SessionEvent.StartTracking)

        assertEquals(active, reduction.state)
        assertEquals(emptyList<SessionEffect>(), reduction.effects)
    }

    private fun reduce(state: SessionState, event: SessionEvent): Reduction =
        SessionReducer.reduce(state, event, nowMillis, settings)

    private fun activeSince(startedAtMillis: Long): SessionState =
        SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis))
}
