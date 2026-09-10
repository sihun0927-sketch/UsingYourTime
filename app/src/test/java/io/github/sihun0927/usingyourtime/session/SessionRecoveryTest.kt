package io.github.sihun0927.usingyourtime.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 1분. 스펙 7절의 시간도 분 단위라 테스트를 분으로 읽는다. */
private const val MINUTE_MILLIS = 60_000L

/**
 * 스펙 7절 복구 정책을 검증한다. heartbeat와 추정 종료 시각, 그리고 재시작 재동기화 규칙 1~4다.
 *
 * 전이표(스펙 3절)는 `SessionReducerTest`에 있다. 이 파일은 `서비스 재시작` 한 이벤트가 저장된
 * 세션과 지금 잠금 상태만으로 상태를 다시 세우는 자리만 본다.
 */
class SessionRecoveryTest {

    private val settings = TrackingSettings()

    private val nowMillis = 1_700_000_000_000L

    // --- heartbeat (스펙 7절) ---

    @Test
    fun `세션 진행 중 1분 tick은 마지막 생존 시각을 지금으로 덮어쓴다`() {
        val startedAtMillis = nowMillis - 12 * MINUTE_MILLIS

        val reduction = SessionReducer.reduce(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            SessionEvent.MinuteTick,
            nowMillis,
            settings,
        )

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(startedAtMillis, elapsedMinutes = 12)),
                SessionEffect.SaveLastAliveAt(lastAliveAtMillis = nowMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `유예 중 1분 tick은 마지막 생존 시각을 쓰지 않는다`() {
        val startedAtMillis = nowMillis - 12 * MINUTE_MILLIS
        val lockedAtMillis = nowMillis - MINUTE_MILLIS

        val reduction = SessionReducer.reduce(
            SessionState(
                Phase.Grace,
                Session(startedAtMillis = startedAtMillis, lockedAtMillis = lockedAtMillis),
            ),
            SessionEvent.MinuteTick,
            nowMillis,
            settings,
        )

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    PersistentDisplayContent.Grace(
                        sessionStartedAtMillis = startedAtMillis,
                        elapsedMinutes = 12,
                        thresholdMinutes = settings.thresholdMinutes,
                        graceRemainingMillis = 2 * MINUTE_MILLIS,
                    ),
                ),
            ),
            reduction.effects,
        )
    }

    // --- 추정 종료 시각 (스펙 7절) ---

    @Test
    fun `추정 종료 시각은 마지막 생존 시각과 마지막 잠금 시각 중 늦은 쪽이다`() {
        val lastAliveIsLater = StoredSession(
            startedAtMillis = nowMillis - 30 * MINUTE_MILLIS,
            lastAliveAtMillis = nowMillis - 2 * MINUTE_MILLIS,
            lockedAtMillis = nowMillis - 9 * MINUTE_MILLIS,
        )
        val lockedIsLater = StoredSession(
            startedAtMillis = nowMillis - 30 * MINUTE_MILLIS,
            lastAliveAtMillis = nowMillis - 9 * MINUTE_MILLIS,
            lockedAtMillis = nowMillis - 2 * MINUTE_MILLIS,
        )

        assertEquals(nowMillis - 2 * MINUTE_MILLIS, lastAliveIsLater.estimatedEndAtMillis)
        assertEquals(nowMillis - 2 * MINUTE_MILLIS, lockedIsLater.estimatedEndAtMillis)
    }

    @Test
    fun `잠긴 적 없는 세션의 추정 종료 시각은 마지막 생존 시각이다`() {
        val stored = StoredSession(
            startedAtMillis = nowMillis - 30 * MINUTE_MILLIS,
            lastAliveAtMillis = nowMillis - 2 * MINUTE_MILLIS,
        )

        assertEquals(nowMillis - 2 * MINUTE_MILLIS, stored.estimatedEndAtMillis)
    }

    // --- 측정 꺼짐 (스펙 3절 전이표) ---

    @Test
    fun `측정 꺼짐에서 서비스 재시작은 아무것도 띄우지 않는다`() {
        val reduction = restart(storedSession = null, unlocked = true, trackingOn = false)

        assertEquals(SessionState.Off, reduction.state)
        assertEquals(listOf(SessionEffect.StopService), reduction.effects)
    }

    @Test
    fun `측정 꺼짐에서 서비스 재시작은 열린 세션이 남아 있어도 손대지 않는다`() {
        val stored = openSince(nowMillis - 30 * MINUTE_MILLIS, lastAliveMinutesAgo = 20)

        val reduction = restart(storedSession = stored, unlocked = true, trackingOn = false)

        assertEquals(SessionState.Off, reduction.state)
        assertEquals(listOf(SessionEffect.StopService), reduction.effects)
    }

    // --- 규칙 1·2: 잠금 해제 + gap ≤ 유예 시간 ---

    @Test
    fun `잠금 해제 상태에서 공백이 유예 이내면 같은 세션이 이어진다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 2)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            reduction.state,
        )
    }

    @Test
    fun `이어진 세션의 연속 사용 시간에는 측정이 끊겼던 공백도 들어간다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 2)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(startedAtMillis, elapsedMinutes = 20)),
                SessionEffect.ScheduleThresholdAlert(atMillis = startedAtMillis + 30 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `공백이 딱 유예 시간이면 아직 같은 세션이다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 3)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(Phase.Active, reduction.state.phase)
        assertEquals(startedAtMillis, reduction.state.session?.startedAtMillis)
    }

    @Test
    fun `유예 중에 끊긴 세션이 잠금 해제 상태로 살아나면 잠금 시각을 지운다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 5, lockedMinutesAgo = 2)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(startedAtMillis, elapsedMinutes = 20)),
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.ScheduleThresholdAlert(atMillis = startedAtMillis + 30 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    // --- 규칙 3: 잠금 + gap ≤ 유예 시간 ---

    @Test
    fun `잠금 상태에서 공백이 유예 이내면 추정 종료 시각에 잠긴 유예 중으로 복원된다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 2)

        val reduction = restart(storedSession = stored, unlocked = false)

        assertEquals(
            SessionState(
                Phase.Grace,
                Session(
                    startedAtMillis = startedAtMillis,
                    lockedAtMillis = nowMillis - 2 * MINUTE_MILLIS,
                ),
            ),
            reduction.state,
        )
    }

    @Test
    fun `유예 중으로 복원하면 유예 만료 깨우기를 추정 종료 시각 기준으로 다시 건다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val estimatedEndAtMillis = nowMillis - 2 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 2)

        val reduction = restart(storedSession = stored, unlocked = false)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    PersistentDisplayContent.Grace(
                        sessionStartedAtMillis = startedAtMillis,
                        elapsedMinutes = 20,
                        thresholdMinutes = settings.thresholdMinutes,
                        graceRemainingMillis = MINUTE_MILLIS,
                    ),
                ),
                SessionEffect.SaveLockedAt(lockedAtMillis = estimatedEndAtMillis),
                SessionEffect.ScheduleGraceExpiry(
                    atMillis = estimatedEndAtMillis + settings.gracePeriodMillis,
                ),
            ),
            reduction.effects,
        )
    }

    // --- 규칙 4: gap > 유예 시간 ---

    @Test
    fun `공백이 유예를 넘고 잠금 해제 상태면 추정 종료 시각으로 닫고 지금 새 세션을 연다`() {
        val estimatedEndAtMillis = nowMillis - 5 * MINUTE_MILLIS
        val stored = openSince(nowMillis - 40 * MINUTE_MILLIS, lastAliveMinutesAgo = 5)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(nowMillis, elapsedMinutes = 0)),
                SessionEffect.CloseSession(estimatedEndAtMillis, SessionEndReason.ESTIMATED),
                SessionEffect.SaveRestartNotice(atMillis = estimatedEndAtMillis),
                SessionEffect.OpenSession(startedAtMillis = nowMillis),
                SessionEffect.ScheduleThresholdAlert(atMillis = nowMillis + 30 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `공백이 유예를 넘고 잠금 상태면 추정 종료 시각으로 닫고 세션 없음이 된다`() {
        val estimatedEndAtMillis = nowMillis - 5 * MINUTE_MILLIS
        val stored = openSince(nowMillis - 40 * MINUTE_MILLIS, lastAliveMinutesAgo = 5)

        val reduction = restart(storedSession = stored, unlocked = false)

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(estimatedEndAtMillis, SessionEndReason.ESTIMATED),
                SessionEffect.SaveRestartNotice(atMillis = estimatedEndAtMillis),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `추정 종료 시각으로 닫을 때 남아 있던 임계값 알림을 걷는다`() {
        val stored = openSince(
            startedAtMillis = nowMillis - 60 * MINUTE_MILLIS,
            lastAliveMinutesAgo = 5,
            alerts = alertedMinutesAgo(20),
        )

        val reduction = restart(storedSession = stored, unlocked = false)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CloseSession(nowMillis - 5 * MINUTE_MILLIS, SessionEndReason.ESTIMATED),
                SessionEffect.DismissThresholdAlert,
                SessionEffect.SaveRestartNotice(atMillis = nowMillis - 5 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `공백이 유예를 한 순간이라도 넘으면 세션이 닫힌다`() {
        val stored = openSince(
            startedAtMillis = nowMillis - 40 * MINUTE_MILLIS,
            lastAliveAtMillis = nowMillis - 3 * MINUTE_MILLIS - 1,
        )

        val reduction = restart(storedSession = stored, unlocked = false)

        assertEquals(SessionState.Idle, reduction.state)
    }

    // --- 열린 세션이 없을 때 ---

    @Test
    fun `열린 세션이 없고 잠금 해제 상태면 지금 새 세션을 연다`() {
        val reduction = restart(storedSession = null, unlocked = true)

        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = nowMillis)),
            reduction.state,
        )
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(nowMillis, elapsedMinutes = 0)),
                SessionEffect.OpenSession(startedAtMillis = nowMillis),
                SessionEffect.ScheduleThresholdAlert(atMillis = nowMillis + 30 * MINUTE_MILLIS),
                SessionEffect.SaveRestartNotice(atMillis = null),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `열린 세션이 없고 잠금 상태면 세션 없음이 된다`() {
        val reduction = restart(storedSession = null, unlocked = false)

        assertEquals(SessionState.Idle, reduction.state)
        assertEquals(
            listOf(SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle)),
            reduction.effects,
        )
    }

    // --- 알림 상태 복원 (스펙 7절) ---

    @Test
    fun `이어진 세션에서는 이미 보낸 임계값 알림이 다시 울리지 않는다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val stored = openSince(
            startedAtMillis = startedAtMillis,
            lastAliveMinutesAgo = 1,
            alerts = alertedMinutesAgo(10),
        )

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(alertedMinutesAgo(10), reduction.state.session?.alerts)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 45, nextAlertMinutes = 5),
                ),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 5 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `이어진 세션이 임계값을 넘겼는데 아직 알린 적이 없으면 즉시 알린다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 2)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 45, nextAlertMinutes = 15),
                ),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(elapsedMinutes = 45, reAlertMinutes = 15, count = 1),
                ),
                SessionEffect.SaveAlertState(
                    AlertState(
                        thresholdAlertedAtMillis = nowMillis,
                        lastAlertAtMillis = nowMillis,
                        count = 1,
                    ),
                ),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + 15 * MINUTE_MILLIS),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `이어진 세션에는 세션 알림 끄기도 함께 복원된다`() {
        val startedAtMillis = nowMillis - 45 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 1, muted = true)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(true, reduction.state.session?.muted)
        assertEquals(
            listOf(
                SessionEffect.UpdatePersistentDisplay(
                    activeContent(startedAtMillis, elapsedMinutes = 45, muted = true),
                ),
            ),
            reduction.effects,
        )
    }

    @Test
    fun `새 세션은 이전 세션의 알림 상태를 물려받지 않는다`() {
        val stored = openSince(
            startedAtMillis = nowMillis - 60 * MINUTE_MILLIS,
            lastAliveMinutesAgo = 5,
            alerts = alertedMinutesAgo(20),
            muted = true,
        )

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(Session(startedAtMillis = nowMillis), reduction.state.session)
    }

    // --- 재부팅 (스펙 7절) ---

    @Test
    fun `사용 중 재부팅한 뒤 1분 만에 잠금 해제하면 같은 세션이 이어진다`() {
        val startedAtMillis = nowMillis - 20 * MINUTE_MILLIS
        val stored = openSince(startedAtMillis, lastAliveMinutesAgo = 1)

        // 부팅 직후에는 잠겨 있다. 규칙 3으로 유예 중이 된다.
        val restored = restart(storedSession = stored, unlocked = false)
        // 사용자가 PIN을 풀면 유예 안이라 같은 세션이 이어진다.
        val unlocked = SessionReducer.reduce(
            restored.state,
            SessionEvent.Unlock,
            nowMillis,
            settings,
        )

        assertEquals(Phase.Grace, restored.state.phase)
        assertEquals(
            SessionState(Phase.Active, Session(startedAtMillis = startedAtMillis)),
            unlocked.state,
        )
    }

    // --- 재시작 안내 (스펙 7절) ---

    @Test
    fun `세션 없음에서 잠금 해제로 새 세션이 시작되면 재시작 안내가 사라진다`() {
        val reduction = SessionReducer.reduce(
            SessionState.Idle,
            SessionEvent.Unlock,
            nowMillis,
            settings,
        )

        assertEquals(
            SessionEffect.SaveRestartNotice(atMillis = null),
            reduction.effects.last(),
        )
    }

    @Test
    fun `규칙 4가 연 새 세션은 방금 적은 재시작 안내를 지우지 않는다`() {
        val stored = openSince(nowMillis - 40 * MINUTE_MILLIS, lastAliveMinutesAgo = 5)

        val reduction = restart(storedSession = stored, unlocked = true)

        assertEquals(
            listOf(SessionEffect.SaveRestartNotice(atMillis = nowMillis - 5 * MINUTE_MILLIS)),
            reduction.effects.filterIsInstance<SessionEffect.SaveRestartNotice>(),
        )
    }

    private fun restart(
        storedSession: StoredSession?,
        unlocked: Boolean,
        trackingOn: Boolean = true,
        settings: TrackingSettings = this.settings,
    ): Reduction = SessionReducer.reduce(
        state = SessionState.Off,
        event = SessionEvent.ServiceRestart(
            trackingOn = trackingOn,
            storedSession = storedSession,
            unlocked = unlocked,
        ),
        nowMillis = nowMillis,
        settings = settings,
    )

    /** 프로세스가 죽으며 닫히지 못하고 남은 열린 세션 행. */
    private fun openSince(
        startedAtMillis: Long,
        lastAliveMinutesAgo: Int,
        lockedMinutesAgo: Int? = null,
        alerts: AlertState = AlertState.None,
        muted: Boolean = false,
    ): StoredSession = openSince(
        startedAtMillis = startedAtMillis,
        lastAliveAtMillis = nowMillis - lastAliveMinutesAgo * MINUTE_MILLIS,
        lockedAtMillis = lockedMinutesAgo?.let { nowMillis - it * MINUTE_MILLIS },
        alerts = alerts,
        muted = muted,
    )

    private fun openSince(
        startedAtMillis: Long,
        lastAliveAtMillis: Long,
        lockedAtMillis: Long? = null,
        alerts: AlertState = AlertState.None,
        muted: Boolean = false,
    ): StoredSession = StoredSession(
        startedAtMillis = startedAtMillis,
        lastAliveAtMillis = lastAliveAtMillis,
        lockedAtMillis = lockedAtMillis,
        alerts = alerts,
        muted = muted,
    )

    /** [minutesAgo]분 전에 임계값 알림을 1회 보낸 세션의 알림 상태. */
    private fun alertedMinutesAgo(minutesAgo: Int): AlertState = AlertState(
        thresholdAlertedAtMillis = nowMillis - minutesAgo * MINUTE_MILLIS,
        lastAlertAtMillis = nowMillis - minutesAgo * MINUTE_MILLIS,
        count = 1,
    )

    private fun activeContent(
        startedAtMillis: Long,
        elapsedMinutes: Int,
        nextAlertMinutes: Int? = null,
        muted: Boolean = false,
    ): PersistentDisplayContent = PersistentDisplayContent.Active(
        sessionStartedAtMillis = startedAtMillis,
        elapsedMinutes = elapsedMinutes,
        thresholdMinutes = settings.thresholdMinutes,
        nextAlertMinutes = nextAlertMinutes,
        muted = muted,
    )
}
