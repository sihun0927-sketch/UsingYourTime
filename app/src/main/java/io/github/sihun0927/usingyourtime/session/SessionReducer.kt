package io.github.sihun0927.usingyourtime.session

/** 리듀서가 돌려주는 결과. 새 상태와 서비스가 실행할 효과 목록이다. */
data class Reduction(
    val state: SessionState,
    val effects: List<SessionEffect> = emptyList(),
)

/**
 * 세션 상태 머신(스펙 3절 전이표). `android.*`를 import하지 않는 순수 함수라 시간도 저장도 모른다.
 * 지금 시각은 [reduce]의 인자로 받고, 바깥 세상에 할 일은 [SessionEffect] 값으로 돌려준다.
 *
 * 전이표에 없는 (상태, 이벤트) 짝은 상태를 그대로 두고 효과도 내지 않는다. 예외가 둘 있다.
 *
 * 1. `서비스 재시작`([serviceRestart]). 프로세스가 죽었다 살아난 자리라 들고 있던 상태가 이미
 *    사라졌고, 그래서 들어온 상태 대신 이벤트가 실어 온 저장된 세계로 상태를 다시 세운다
 *    (스펙 7절 재동기화).
 * 2. 세션 없음 · `1분 tick`([refreshPersistentDisplay]). 상태는 그대로지만 상시 표시를 다시
 *    게시한다. Android 14+에서 스와이프로 지워진 대기 문구가 돌아오는 길이 그 갱신뿐이기
 *    때문이다(스펙 4절, `docs/adr/0005-minute-tick-reposts-the-idle-persistent-display.md`).
 *
 * 유예 만료를 정하는 것은 타이머가 아니라 **잠금 시각과 지금 시각의 차이**다. 유예 중에 들어오는
 * 모든 이벤트가 그 차이를 다시 재므로, 알람이 Doze로 늦게 오거나 유예 설정이 바뀌어 이르게 와도
 * 결과가 같다.
 */
object SessionReducer {

    fun reduce(
        state: SessionState,
        event: SessionEvent,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = when (event) {
        SessionEvent.StartTracking -> startTracking(state, nowMillis, settings)
        SessionEvent.Pause -> pause(state, nowMillis)
        SessionEvent.Unlock -> unlock(state, nowMillis, settings)
        SessionEvent.Lock -> lock(state, nowMillis, settings)
        // 전이표대로 재판정만 하고, 아직 유예가 남았으면 아무 일도 하지 않는다.
        SessionEvent.ScreenOn -> closeExpiredGrace(state, nowMillis, settings) ?: Reduction(state)
        SessionEvent.GraceExpired -> closeExpiredGrace(state, nowMillis, settings)
            ?: rescheduleGraceExpiry(state, nowMillis, settings)

        SessionEvent.ThresholdReached -> thresholdAlert(state, nowMillis, settings)
            ?: repaintSuppressedThreshold(state, nowMillis, settings)
            ?: Reduction(state)

        SessionEvent.ReAlertIntervalElapsed -> reAlert(state, nowMillis, settings)
            ?: rescheduleReAlert(state, nowMillis, settings)

        SessionEvent.MuteSession -> muteSession(state, nowMillis, settings)

        SessionEvent.MinuteTick -> minuteTick(state, nowMillis, settings)

        is SessionEvent.ServiceRestart -> serviceRestart(event, nowMillis, settings)
    }

    /**
     * `1분 tick`. 상시 표시를 다시 그리고, 세션 진행 중이면 heartbeat를 남긴다(스펙 7절).
     *
     * 밀린 알림을 갚는 자리이기도 하다. 임계값이나 재알림 주기를 이미 지난 값으로 낮췄으면 이 tick이
     * 곧 그 깨우기다(스펙 3절 설정 변경 중 동작). 걸어 둔 깨우기는 옛 값으로 잡혀 있어 아직 오지
     * 않기 때문이다.
     *
     * heartbeat는 **세션 진행 상태에서만** 나간다(스펙 7절). 유예 중에는 잠금 시각이 이미 적혀
     * 있어 추정 종료 시각이 그것으로 정해지고, 잠긴 뒤로도 계속 살아 있었다고 적으면 유예가 끝난
     * 세션을 지금까지 이어진 것으로 만든다. 세션 없음·측정 꺼짐에는 적을 행이 없다.
     */
    private fun minuteTick(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val reduction = closeExpiredGrace(state, nowMillis, settings)
            ?: alertOrRepaint(state, nowMillis, settings)

        if (state.phase != Phase.Active) return reduction
        return reduction.copy(
            effects = reduction.effects + SessionEffect.SaveLastAliveAt(nowMillis),
        )
    }

    /**
     * `서비스 재시작`. 저장된 세계와 지금 잠금 상태만으로 상태를 다시 세운다(스펙 7절 재동기화).
     *
     * 들어온 [state]를 보지 않는 유일한 전이다. 이 이벤트가 오는 자리는 프로세스가 죽었다 살아난
     * 직후라 리듀서가 들고 있던 상태가 이미 사라졌고, 정본은 Room·DataStore와 기기의 지금 상태뿐이다.
     *
     * 규칙 1~4가 여기 한 곳에 있다. 재부팅도, START_STICKY 재시작도, 앱 실행 시 재기동도 같은
     * 길을 탄다(스펙 7절 "모든 경로 단일 규칙").
     */
    private fun serviceRestart(
        event: SessionEvent.ServiceRestart,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        // 측정 꺼짐 + `서비스 재시작` → 측정 꺼짐(전이표). 남은 열린 세션 행도 손대지 않는다.
        // 사용자가 측정 중지를 눌렀다면 그때 이미 닫혔고, 아니라면 다음 `측정 시작`이 아니라
        // 다음 재시작이 판정할 재료다.
        if (!event.trackingOn) return Reduction(SessionState.Off, listOf(SessionEffect.StopService))

        val stored = event.storedSession
            ?: return resumeWithoutSession(event.unlocked, nowMillis, settings)

        // 규칙 1. 공백을 재는 기준은 지금 시각과 추정 종료 시각의 차이다.
        val gapMillis = nowMillis - stored.estimatedEndTimeMillis

        return when {
            // 규칙 4.
            gapMillis > settings.gracePeriodMillis ->
                closeEstimated(stored, event.unlocked, nowMillis, settings)

            // 규칙 2. 유예 안에 잠금 해제된 세션과 같은 자리다.
            event.unlocked -> continueSession(stored.toSession(stored.lockedAtMillis), nowMillis, settings)

            // 유예 0분에는 유예 중이라는 상태 자체가 없다(전이표). 공백이 0이라 "gap ≤ 유예"가
            // 참이 된 이 한 순간도 [lock]과 같이 곧바로 세션 없음으로 간다. 그러지 않으면 만료
            // 시각이 곧 지금인 유예 중이 되어, 깨우기가 스스로를 되부르며 제자리를 맴돈다.
            settings.gracePeriodMillis == 0L ->
                closeEstimated(stored, unlocked = false, nowMillis = nowMillis, settings = settings)

            // 규칙 3.
            else -> resumeGrace(stored, nowMillis, settings)
        }
    }

    /**
     * 열린 세션이 없을 때. "현재 상태만 읽어 잠금 해제면 새 세션 시작, 아니면 세션 없음"(스펙 7절).
     *
     * 측정을 켠 채 한 번도 세션이 열리지 않았거나, 마지막 세션이 제대로 닫힌 뒤 끊긴 경우다.
     */
    private fun resumeWithoutSession(
        unlocked: Boolean,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = if (unlocked) {
        startNewSession(nowMillis, settings, cancelGraceExpiry = true)
    } else {
        Reduction(
            SessionState.Idle,
            listOf(
                SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle),
                SessionEffect.CancelGraceExpiry,
            ),
        )
    }

    /**
     * 규칙 3. 잠금 상태이고 공백이 유예 안이라 **추정 종료 시각에 잠긴 것으로 보고** 유예 중으로
     * 복원한다. 유예 타이머도 그 추정치 기준이다(스펙 7절).
     *
     * 잠금 시각을 저장까지 다시 하는 이유는 세션 진행 중에 끊긴 경우다. 그때 행에는 잠금 시각이
     * 없어, 여기서 적어 두지 않으면 다음 재시작이 같은 추정을 처음부터 다시 하게 된다. 이미
     * 잠금 시각이 있던 행이면 같은 값이라 덮어써도 그대로다.
     *
     * 알림은 걸지 않는다. 잠금 중에는 임계값 알림·재알림이 절대 나가지 않고(스펙 3·4절), 밀린
     * 것은 다음 잠금 해제가 [continueSession]에서 1회만 갚는다.
     */
    private fun resumeGrace(
        stored: StoredSession,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val estimatedEndTimeMillis = stored.estimatedEndTimeMillis
        val session = stored.toSession(lockedAtMillis = estimatedEndTimeMillis)

        return Reduction(
            state = SessionState(Phase.Grace, session),
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(graceContent(session, nowMillis, settings)),
                SessionEffect.SaveLockedAt(lockedAtMillis = estimatedEndTimeMillis),
                SessionEffect.ScheduleGraceExpiry(
                    atMillis = estimatedEndTimeMillis + settings.gracePeriodMillis,
                ),
            ),
        )
    }

    /**
     * 규칙 4. 공백이 유예를 넘었다. 세션을 추정 종료 시각으로 닫고(종료 원인 `ESTIMATED`), 지금
     * 잠금 해제 상태면 **지금** 새 세션을 열고 잠금 상태면 세션 없음이 된다(스펙 7절).
     *
     * 재시작 안내 시각도 여기서 적는다. 사용자가 보는 "HH:MM에 측정이 중단됐다가"의 HH:MM이 곧
     * 추정 종료 시각이다. 규칙 4로 닫힌 세션에만 적으므로, 유예 만료나 측정 중지로 제대로 닫힌
     * 세션은 안내를 남기지 않는다.
     *
     * 새로 여는 세션은 방금 적은 안내를 지우지 않는다([startNewSession]의 기본 동작과 다르다).
     * 안내는 "그 다음 세션이 시작될 때" 사라지는 것이지 재시작이 연 이 세션에서 사라지는 것이
     * 아니다. 그러지 않으면 잠금 해제 상태로 살아난 경우에 안내가 한 번도 보이지 않는다.
     */
    private fun closeEstimated(
        stored: StoredSession,
        unlocked: Boolean,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val estimatedEndTimeMillis = stored.estimatedEndTimeMillis
        val closing = closeSession(
            endedAtMillis = estimatedEndTimeMillis,
            reason = SessionEndReason.ESTIMATED,
            alerted = stored.alerts.alerted,
        ) + SessionEffect.SaveRestartNotice(atMillis = estimatedEndTimeMillis)

        if (unlocked) {
            return startNewSession(
                nowMillis = nowMillis,
                settings = settings,
                closing = closing,
                cancelGraceExpiry = true,
                clearRestartNotice = false,
            )
        }

        return Reduction(
            state = SessionState.Idle,
            effects = buildList {
                add(SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle))
                addAll(closing)
                add(SessionEffect.CancelGraceExpiry)
            },
        )
    }

    /** 측정 꺼짐 → 세션 진행. 버튼을 누른 시점은 잠금 해제 상태이므로 **지금** 세션을 연다. */
    private fun startTracking(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Off) return Reduction(state)

        val started = startNewSession(nowMillis, settings)
        return started.copy(effects = started.effects + SessionEffect.SaveTrackingOn(trackingOn = true))
    }

    /**
     * 세션 없음·세션 진행·유예 중 → 측정 꺼짐. 열린 세션은 유예 없이 **지금** 닫힌다.
     *
     * 이미 측정 꺼짐이어도 `tracking_on`을 끄고 서비스를 내린다. 프로세스가 죽어 서비스의 상태만
     * 사라진 뒤에도 저장된 `tracking_on`은 켜진 채일 수 있어, 두 효과가 그 어긋남을 되돌린다.
     */
    private fun pause(state: SessionState, nowMillis: Long): Reduction = Reduction(
        state = SessionState.Off,
        effects = buildList {
            if (state.session != null) {
                addAll(
                    closeSession(
                        endedAtMillis = nowMillis,
                        reason = SessionEndReason.PAUSED,
                        alerted = state.session.alerts.alerted,
                    ),
                )
            }
            add(SessionEffect.SaveTrackingOn(trackingOn = false))
            if (state.phase == Phase.Grace) add(SessionEffect.CancelGraceExpiry)
            add(SessionEffect.StopService)
        },
    )

    /**
     * `잠금 해제` 수신.
     *
     * - 세션 없음 → 지금 새 세션을 연다.
     * - 유예 중 → 유예가 남았으면 같은 세션이 이어지고, 이미 지났으면 잠금 시각으로 닫고 새 세션.
     * - 세션 진행 → 없음. 잠겨 있지 않은 채 화면이 켜지면(잠금 화면 '없음'·잠금 지연·Smart Lock)
     *   화면이 켜질 때마다 이 이벤트가 온다.
     */
    private fun unlock(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = when (state.phase) {
        Phase.Idle -> startNewSession(nowMillis, settings)

        Phase.Grace -> {
            val session = state.openSession()
            if (gracePeriodRemains(session, nowMillis, settings)) {
                continueSession(session, nowMillis, settings)
            } else {
                startNewSession(
                    nowMillis = nowMillis,
                    settings = settings,
                    closing = closeExpiredSession(session),
                    cancelGraceExpiry = true,
                )
            }
        }

        Phase.Off, Phase.Active -> Reduction(state)
    }

    /**
     * 유예 중 → 세션 진행. 유예 안에 잠금 해제되어 **같은 세션이 이어진다**(전이표, `CONTEXT.md`
     * 연속 사용 세션). 측정을 다시 켜는 것이 아니라 열려 있던 세션을 그대로 잇는 자리다.
     *
     * 잠금 중에는 임계값 알림·재알림을 **절대** 보내지 않으므로(스펙 3·4절) 유예를 지나며 넘긴
     * 임계값과 지나간 재알림 주기가 여기에 밀려 있다. 전이표가 이 칸에 "즉시 판정"을 적어 둔
     * 자리이고, 판정은 잠긴 적 없는 세션 진행에서 하는 것과 같다([alertOrRepaint]를 지금 시각으로
     * **한 번**). 그 한 번이 곧 전이표의 "밀린 횟수 몰아 보내기 없음"이다. 주기가 유예 중에 두 번
     * 지났어도 판정이 한 번이라 알림도 한 번이고, [postAlert]가 직전 알림 시각을 지금으로 옮기며
     * 재알림 타이머를 그 알림부터 다시 건다.
     *
     * 연속 사용 시간이 유예 구간을 포함하므로(스펙 3절) 잠긴 동안 임계값을 넘겼다는 사실은 그
     * 판정에 이미 들어 있다. 알림이 막힌 세션(세션 알림 끄기·임계값 알림 토글 꺼짐)은 판정에
     * 걸리지 않아 잠금 해제도 조용하다.
     *
     * 잠금 시각 지우기와 유예 만료 깨우기 취소는 판정 결과와 무관하게 늘 낸다. 알림 효과보다
     * 뒤에 두어, 상시 표시와 임계값 알림이 앞선 저장을 기다리지 않게 한다.
     *
     * 재동기화 규칙 2도 이 자리다(스펙 7절). 유예 안에 되살아난 서비스가 하는 일이 유예 안에
     * 잠금 해제된 세션에 하는 일과 같기 때문이다. 프로세스가 죽어 있었든 잠겨 있었든 그동안
     * 알림이 나가지 않은 것도, 그래서 밀린 것을 여기서 한 번에 갚는 것도 똑같다.
     */
    private fun continueSession(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val reduction = judgeAndRearm(session.copy(lockedAtMillis = null), nowMillis, settings)

        return reduction.copy(
            effects = reduction.effects + listOf(
                SessionEffect.SaveLockedAt(lockedAtMillis = null),
                SessionEffect.CancelGraceExpiry,
            ),
        )
    }

    /**
     * 세션 진행으로 돌아오는 자리의 판정. 지금 보낼 알림이 있으면 보내고, 없으면 다음 알림으로
     * 깨울 예약을 다시 건다.
     *
     * 예약을 다시 거는 이유는 이 자리에 오는 두 길 모두 깨울 것을 잃은 뒤이기 때문이다. 재시작은
     * 프로세스와 함께 타이머가 통째로 사라졌고([resumeSession]), 유예 중 잠금 해제는 잠긴 동안
     * 임계값 깨우기가 이미 왔다 가며 소모됐을 수 있다(그때 리듀서는 잠금 중이라 알림을 보내지
     * 않았고, 다시 걸지도 않았다). 둘 다 `1분 tick`이 결국 메우지만 최대 1분이 늦는다.
     *
     * 알림이 나갔으면 [postAlert]가 다음 재알림을 이미 걸어 두었으므로 더 걸지 않는다. 알림 상태가
     * 움직였는지가 곧 그 답이다.
     */
    private fun judgeAndRearm(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val judged = alertOrRepaint(SessionState(Phase.Active, session), nowMillis, settings)
        val judgedSession = judged.state.openSession()
        if (judgedSession.alerts != session.alerts) return judged

        val wakeUp = nextAlertWakeUp(judgedSession, nowMillis, settings) ?: return judged
        return judged.copy(effects = judged.effects + wakeUp)
    }

    /**
     * 재시작한 세션에 다시 걸 알림 깨우기 하나, 걸 것이 없으면 null.
     *
     * 세션에 남은 자취가 어느 깨우기를 걸지 정한다. 아직 알린 적이 없으면 임계값 시각으로, 알린
     * 적이 있으면 직전 알림 + 재알림 주기로.
     *
     * 그 시각이 이미 지났으면 걸지 않는다([rescheduleReAlert]와 같은 이유로 곧바로 돌아오는 깨우기가
     * 되기 때문이다). 지난 시각은 방금의 판정이 이미 갚았거나, 알림이 막혀 갚을 수 없는 경우뿐이고
     * 후자는 `1분 tick`이 다시 본다.
     */
    private fun nextAlertWakeUp(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): SessionEffect? {
        if (!alertsAllowed(session, settings)) return null

        val lastAlertAtMillis = session.alerts.lastAlertAtMillis
        val atMillis = lastAlertAtMillis?.plus(settings.reAlertIntervalMillis)
            ?: (session.startedAtMillis + settings.thresholdMillis)
        if (atMillis <= nowMillis) return null

        return if (lastAlertAtMillis == null) {
            SessionEffect.ScheduleThresholdAlert(atMillis)
        } else {
            SessionEffect.ScheduleReAlert(atMillis)
        }
    }

    /**
     * 지금 보낼 알림이 있으면 보내고, 없으면 상시 표시만 다시 그린다.
     *
     * 첫 알림이 재알림보다 앞이다. 아직 알린 적 없는 세션에는 [reAlert]가 셀 기준점이 없어 어차피
     * null이고, 첫 알림이 이 자리에서 나갔으면 방금 보낸 것이 곧 직전 알림이라 주기가 지났을 리
     * 없다. 그래서 한 번의 판정에서 알림은 많아야 하나다.
     *
     * 밀린 알림을 갚는 자리가 둘이라 차례를 여기 한 번만 적는다. `1분 tick`과 유예 안 잠금
     * 해제([continueSession])가 어느 쪽에서 갚든 결과가 같아야 하기 때문이다.
     */
    private fun alertOrRepaint(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction = thresholdAlert(state, nowMillis, settings)
        ?: reAlert(state, nowMillis, settings)
        ?: refreshPersistentDisplay(state, nowMillis, settings)

    /**
     * 세션 진행 → 유예 중. 잠금 시각을 남기고 유예가 끝날 시각에 깨우도록 예약한다.
     *
     * 유예 시간이 0이면 잠그는 순간이 곧 유예 만료라 세션 없음으로 곧장 간다(전이표).
     */
    private fun lock(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Active) return Reduction(state)
        val locked = state.openSession().copy(lockedAtMillis = nowMillis)

        // 유예 0분은 경계 비교로 풀리지 않는다. gap이 0이라 "gap ≤ 유예"가 참이 되기 때문이다.
        // 전이표가 이 칸을 따로 적은 대로("유예 시간이 0이면 곧바로 세션 없음") 여기서 가른다.
        if (settings.gracePeriodMillis == 0L) {
            return Reduction(
                state = SessionState.Idle,
                effects = buildList {
                    add(SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle))
                    addAll(closeExpiredSession(locked))
                    add(SessionEffect.CancelGraceExpiry)
                },
            )
        }

        return Reduction(
            state = SessionState(Phase.Grace, locked),
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(graceContent(locked, nowMillis, settings)),
                SessionEffect.SaveLockedAt(lockedAtMillis = nowMillis),
                SessionEffect.ScheduleGraceExpiry(atMillis = nowMillis + settings.gracePeriodMillis),
            ),
        )
    }

    /**
     * 세션 진행·유예 중 → 그대로. 이 세션이 닫힐 때까지 임계값 알림·재알림을 멈춘다(전이표).
     *
     * 국면은 바뀌지 않으므로 상시 표시도 지금 국면의 것을 다시 그린다. 세션 진행이면 본문이
     * "임계값 초과 · 이번 세션 알림 꺼짐"으로 바뀐다.
     *
     * **유예 중이면 남은 유예 그대로다.** 전이표는 이 칸의 하는 일을 "상시 표시 본문에 '이번 세션
     * 알림 꺼짐'"이라 적었지만, 본문을 정하는 것은 4절 표이고 그 표의 유예 중 행은 본문을
     * "잠금 중 · 유예 m:ss 남음" 하나로 못박았다. 알림 꺼짐 본문은 표에서 **세션 진행 · 초과**
     * 행에만 있다. 더 구체적인 쪽을 따른다. 유예 안에 잠금 해제되어 세션 진행으로 돌아오는 순간
     * 그 본문이 나온다.
     *
     * 이미 꺼진 세션에 또 오면 아무 일도 하지 않는다. 되돌리는 길이 없어 두 번째 탭은 첫 번째와
     * 같은 뜻이고, 알림은 이미 걷혔다.
     */
    private fun muteSession(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Active && state.phase != Phase.Grace) return Reduction(state)
        val session = state.openSession()
        if (session.muted) return Reduction(state)

        val muted = session.copy(muted = true)
        return Reduction(
            state = SessionState(state.phase, muted),
            effects = buildList {
                add(
                    SessionEffect.UpdatePersistentDisplay(
                        openContent(state.phase, muted, nowMillis, settings),
                    ),
                )
                if (muted.alerts.alerted) add(SessionEffect.DismissThresholdAlert)
                add(SessionEffect.SaveMuted(muted = true))
            },
        )
    }

    /**
     * 지금 첫 임계값 알림을 보낼 상황이면 그 전이, 아니면 null.
     *
     * 발송 조건은 스펙 4절이다. 세션 진행 상태 · 연속 사용 시간 ≥ 임계값 · 임계값 알림 토글 켜짐 ·
     * 세션의 `muted == false`([alertsAllowed]), 그리고 이 세션에서 아직 알린 적이 없어야 한다.
     * 뒤의 두 조건은 [reAlert]도 같이 본다.
     *
     * 잠금 중에는 임계값 알림을 **절대** 보내지 않으므로(스펙 3절) 유예 중이면 null이다. 그렇게
     * 밀린 알림은 다음 잠금 해제가 [continueSession]에서 1회만 판정한다. 걸어 둔 깨우기가 늦게 왔든
     * 이르게 왔든 판정은 연속 사용 시간이 하므로 결과가 같다.
     */
    private fun thresholdAlert(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction? {
        if (state.phase != Phase.Active) return null
        val session = state.openSession()
        if (!alertsAllowed(session, settings)) return null
        if (session.alerts.alerted) return null
        if (nowMillis - session.startedAtMillis < settings.thresholdMillis) return null

        return postAlert(
            session = session,
            alerts = AlertState(
                thresholdAlertedAtMillis = nowMillis,
                lastAlertAtMillis = nowMillis,
                count = 1,
            ),
            nowMillis = nowMillis,
            settings = settings,
        )
    }

    /**
     * 지금 재알림을 보낼 상황이면 그 전이, 아니면 null.
     *
     * 첫 알림에 더해지는 조건은 하나다. **직전 알림 시각**에서 재알림 주기가 지났어야 한다. 사용자가
     * 알림을 지웠는지는 조건에 없다. 지운 것은 알림 창에서 치운 것일 뿐이고, 리듀서는 그 사실을
     * 알지도 못한다. 그래서 지워도 다음 재알림은 예정대로 오고, 그때까지만 알림이 보이지 않는다
     * (스펙 4절).
     *
     * 회차에 상한이 없어 세션이 이어지는 한 주기마다 무한히 반복한다(스펙 4절). 연속 사용 시간은
     * 다시 재지 않는다. 첫 알림이 임계값에서 나갔으니 그 뒤로는 늘 임계값을 넘긴 상태다.
     */
    private fun reAlert(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction? {
        if (state.phase != Phase.Active) return null
        val session = state.openSession()
        if (!alertsAllowed(session, settings)) return null
        val lastAlertAtMillis = session.alerts.lastAlertAtMillis ?: return null
        if (nowMillis - lastAlertAtMillis < settings.reAlertIntervalMillis) return null

        return postAlert(
            session = session,
            alerts = session.alerts.copy(
                lastAlertAtMillis = nowMillis,
                count = session.alerts.count + 1,
            ),
            nowMillis = nowMillis,
            settings = settings,
        )
    }

    /**
     * 아직 재알림 주기가 남았는데 `재알림 주기 경과`로 깨어났을 때. 새 전이가 아니라 같은 세션 진행
     * 칸이고, 전이표가 그 칸에 적어 둔 "재알림 타이머 재시작"만 한다.
     *
     * 재알림 주기를 늘리면 걸어 둔 깨우기가 이르게 온다. 그때 다시 걸지 않으면 이 세션에 재알림을
     * 깨울 것이 남지 않아 `1분 tick`이 메울 때까지 밀린다([rescheduleGraceExpiry]와 같은 자리다).
     *
     * 셀 기준점이 없으면(세션이 닫혔거나 잠겼거나 아직 알린 적이 없다) 걸 시각도 없다. 잠금 중에
     * 밀린 알림은 다음 잠금 해제가 판정한다([continueSession]).
     */
    private fun rescheduleReAlert(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Active) return Reduction(state)
        val session = state.openSession()
        val lastAlertAtMillis = session.alerts.lastAlertAtMillis ?: return Reduction(state)

        // 지난 시각으로는 걸지 않는다. 그 시각이 지났는데도 재알림이 나가지 않았다면 막은 것이
        // 무엇이든 다시 걸 이유가 없고, 서비스의 깨우기가 음수만큼 기다려 곧바로 돌아오므로 같은
        // 자리를 맴돌게 된다. 막는 이유를 하나씩 세는 대신 여기서 종류째 닫는다.
        val atMillis = lastAlertAtMillis + settings.reAlertIntervalMillis
        if (atMillis <= nowMillis) return Reduction(state)

        // 알림이 막혀 있으면 기다릴 것도 없다. 위의 비교가 이미 맴돌기를 막지만 왜 걸지 않는지는
        // 이쪽이 말한다. 토글을 다시 켜면 `1분 tick`이 밀린 재알림을 집어 올린다.
        if (!alertsAllowed(session, settings)) return Reduction(state)

        return Reduction(state, listOf(SessionEffect.ScheduleReAlert(atMillis)))
    }

    /**
     * 첫 임계값 알림과 재알림이 함께 쓰는 전이. 둘은 [alerts]를 어떻게 옮기느냐만 다르고, 알림 id도
     * 게시하는 효과도 같다(스펙 4절).
     *
     * 상시 표시가 같은 자리에서 다시 그려진다. 초과 상태로 바뀌는 순간이기도 하고(제목의 "초과"·가득
     * 찬 막대·경고색이 알림과 함께 움직여야 한다, 스펙 4절 경고색 규칙), 본문의 "다음 알림 N분 후"가
     * 방금 옮긴 기준점에서 다시 세어져야 하기 때문이다.
     *
     * 다음 재알림 예약이 이 전이가 내는 마지막 효과다. 알림이 나간 시각에서 주기를 재므로 앞의
     * 효과가 무엇이든 결과가 같지만, 걸 시각을 이 전이가 이미 정해 두었다는 것이 순서로도 보인다.
     * 유예 안 잠금 해제는 이 뒤에 세션을 잇는 효과를 더 붙인다([continueSession]).
     */
    private fun postAlert(
        session: Session,
        alerts: AlertState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        val alerting = session.copy(alerts = alerts)
        return Reduction(
            state = SessionState(Phase.Active, alerting),
            effects = listOf(
                SessionEffect.UpdatePersistentDisplay(activeContent(alerting, nowMillis, settings)),
                SessionEffect.PostThresholdAlert(
                    ThresholdAlertContent(
                        elapsedMinutes = elapsedMinutes(alerting, nowMillis),
                        reAlertMinutes = settings.reAlertMinutes,
                        count = alerts.count,
                    ),
                ),
                SessionEffect.SaveAlertState(alerts),
                SessionEffect.ScheduleReAlert(atMillis = nowMillis + settings.reAlertIntervalMillis),
            ),
        )
    }

    /**
     * 이 세션에 임계값 알림·재알림을 보내도 되는지(스펙 4절 발송 조건 중 세션·설정 몫).
     *
     * 둘 다 알림만 막고 측정과 상시 표시는 그대로 둔다. 토글이 꺼져 있어도 상시 표시의 초과 표기와
     * 경고색은 유지된다(스펙 4절).
     *
     * 토글은 저장 즉시 적용된다(스펙 3절 설정 변경 중 동작). 꺼진 동안 임계값을 넘겨도 알림 상태를
     * 적지 않으므로, 세션 중에 다시 켜면 다음 판정에서 첫 임계값 알림이 나간다. 세션 알림 끄기는
     * 반대로 세션에 새겨져 세션이 닫힐 때까지 풀리지 않는다.
     */
    private fun alertsAllowed(session: Session, settings: TrackingSettings): Boolean =
        settings.thresholdAlertEnabled && !session.muted

    /**
     * 알림이 막힌 채 임계값을 넘긴 순간의 상시 표시 갱신, 아니면 null.
     *
     * 임계값 알림 토글이 꺼져 있거나 세션 알림 끄기 뒤라 알림은 나가지 않지만, 상시 표시의 초과
     * 표기와 경고색은 토글과 무관하게 그대로다(스펙 4절). 알림이 나가는 쪽은 게시와 같은 자리에서
     * 상시 표시를 초과로 바꾸므로([thresholdAlert]), 막힌 쪽에도 같은 순간을 준다. 그러지 않으면
     * 토글 하나로 초과 표기가 최대 1분 늦어져, "제목의 초과와 경고색은 함께 움직인다"는 4절의
     * 경고색 규칙이 토글에 따라 달라진다.
     *
     * 아직 임계값 전이면 여기 오지 않는다. 다시 그릴 것이 바뀌지 않았다. 알림을 이미 보낸 세션을
     * 끈 뒤에는 올 수 있지만, 그때는 [muteSession]이 이미 같은 내용으로 그려 둔 뒤라 이 갱신이
     * 화면을 바꾸지 않는다.
     */
    private fun repaintSuppressedThreshold(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction? {
        if (state.phase != Phase.Active) return null
        val session = state.openSession()
        if (alertsAllowed(session, settings)) return null
        if (nowMillis - session.startedAtMillis < settings.thresholdMillis) return null

        return refreshPersistentDisplay(state, nowMillis, settings)
    }

    /**
     * 유예 중이고 잠금 시각으로부터 유예가 이미 지났으면 유예 중 → 세션 없음 전이, 아니면 null.
     *
     * 유예 중에 오는 이벤트마다 이 재판정을 먼저 한다. 깨우기 취소는 예약이 남아 있든 아니든 낸다.
     */
    private fun closeExpiredGrace(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction? {
        if (state.phase != Phase.Grace) return null
        val session = state.openSession()
        if (gracePeriodRemains(session, nowMillis, settings)) return null

        return Reduction(
            state = SessionState.Idle,
            effects = buildList {
                add(SessionEffect.UpdatePersistentDisplay(PersistentDisplayContent.Idle))
                addAll(closeExpiredSession(session))
                add(SessionEffect.CancelGraceExpiry)
            },
        )
    }

    /**
     * 아직 유예가 남았는데 `유예 만료`로 깨어났을 때. 새 전이가 아니라 같은 유예 중 칸이다.
     * `유예 만료`는 스펙 3절에서 이미 "타임스탬프 재판정" 신호이고, 재판정 결과가 "아직"이면
     * 유예 중에 머문다.
     *
     * 깨우기만 다시 건다. 방금 그 예약을 써 버렸는데 다시 걸지 않으면 기기가 깊이 잠든 동안
     * 만료를 깨울 것이 남지 않는다. 상시 표시는 `1분 tick`이 어차피 다시 그린다.
     */
    private fun rescheduleGraceExpiry(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase != Phase.Grace) return Reduction(state)
        val lockedAtMillis = state.openSession().lockedAt()

        return Reduction(
            state = state,
            effects = listOf(
                SessionEffect.ScheduleGraceExpiry(lockedAtMillis + settings.gracePeriodMillis),
            ),
        )
    }

    /**
     * `1분 tick`. 열린 세션이 있으면 상시 표시를 다시 그린다. 유예 중이면 남은 유예까지.
     *
     * 세션 없음의 문구는 고정이라 바뀔 것이 없는데도 같은 내용을 다시 낸다. Android 14+에서
     * 사용자가 스와이프로 지운 상시 표시가 다시 올라오는 길이 이 갱신뿐이기 때문이다
     * (스펙 4절 "다음 갱신(`1분 tick`)에 다시 게시한다",
     * `docs/adr/0005-minute-tick-reposts-the-idle-persistent-display.md`).
     *
     * 측정 꺼짐에는 낼 것이 없다. 게시할 상시 표시가 애초에 없고, 서비스도 없거나 내려가는 중이다.
     */
    private fun refreshPersistentDisplay(
        state: SessionState,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Reduction {
        if (state.phase == Phase.Off) return Reduction(state)

        // 열린 세션이 없는 국면은 측정 꺼짐 아니면 세션 없음뿐이다([SessionState]의 불변식).
        val content = state.session
            ?.let { openContent(state.phase, it, nowMillis, settings) }
            ?: PersistentDisplayContent.Idle
        return Reduction(state, listOf(SessionEffect.UpdatePersistentDisplay(content)))
    }

    /**
     * 열린 세션의 상시 표시 내용. 국면이 본문을 가르는 유일한 자리다(스펙 4절 표).
     *
     * 여기 오는 국면은 세션 진행과 유예 중뿐이다. 열린 세션이 있다는 것이 곧 그 둘 중 하나라는
     * 뜻이기 때문이다([SessionState]의 불변식).
     */
    private fun openContent(
        phase: Phase,
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): PersistentDisplayContent.Open = if (phase == Phase.Grace) {
        graceContent(session, nowMillis, settings)
    } else {
        activeContent(session, nowMillis, settings)
    }

    /**
     * 지금 새 세션을 여는 전이. [closing]이 있으면 이전 세션을 먼저 닫는다(열린 행은 최대 1개다).
     *
     * 상시 표시가 늘 첫 효과다. 포그라운드 서비스의 알림이라 저장보다 뒤로 밀리면 안 된다.
     *
     * 새 세션의 알림 상태는 비어 있다. 임계값 알림은 세션마다 처음부터 다시 센다(스펙 3절 전이표).
     *
     * 재시작 안내도 여기서 사라진다. 스펙 7절이 "그 다음 세션이 시작될 때 사라진다"고 적은 자리이고,
     * 전이표의 세션 없음 · `잠금 해제` 칸도 같은 말을 한다. 세션이 열리는 길이 모두 이 함수를
     * 지나므로 여는 자리마다 따로 적지 않는다. 예외는 규칙 4가 스스로 여는 세션 하나뿐이다
     * ([closeEstimated]). 두 문서의 넓이가 달라 한쪽을 고른 자리라
     * `docs/adr/0004-restart-notice-clears-when-any-session-opens.md`에 남겼다.
     */
    private fun startNewSession(
        nowMillis: Long,
        settings: TrackingSettings,
        closing: List<SessionEffect> = emptyList(),
        cancelGraceExpiry: Boolean = false,
        clearRestartNotice: Boolean = true,
    ): Reduction {
        val session = Session(startedAtMillis = nowMillis)
        val effects = buildList {
            add(SessionEffect.UpdatePersistentDisplay(activeContent(session, nowMillis, settings)))
            addAll(closing)
            add(SessionEffect.OpenSession(startedAtMillis = session.startedAtMillis))
            if (cancelGraceExpiry) add(SessionEffect.CancelGraceExpiry)
            add(
                SessionEffect.ScheduleThresholdAlert(
                    atMillis = session.startedAtMillis + settings.thresholdMillis,
                ),
            )
            if (clearRestartNotice) add(SessionEffect.SaveRestartNotice(atMillis = null))
        }
        return Reduction(SessionState(Phase.Active, session), effects)
    }

    /**
     * 유예가 지난 세션을 **잠금 시각**으로 닫는다. 잠겨 있던 구간은 세션에 넣지 않는다.
     */
    private fun closeExpiredSession(session: Session): List<SessionEffect> = closeSession(
        endedAtMillis = session.lockedAt(),
        reason = SessionEndReason.GRACE_EXPIRED,
        alerted = session.alerts.alerted,
    )

    /**
     * 열린 세션을 닫는 효과. 닫는 세 길(유예 만료·측정 중지·추정 종료)이 종료 시각과 원인만
     * 다르고 나머지는 같아 여기 한 번만 적는다.
     *
     * 알림을 보낸 세션이면 임계값 알림도 함께 걷는다. 알림 상태는 세션에 속해 있어, 세션이 닫히면
     * 알림 창에 남은 알림도 가리킬 세션이 없다(스펙 4절 제거 시점).
     */
    private fun closeSession(
        endedAtMillis: Long,
        reason: SessionEndReason,
        alerted: Boolean,
    ): List<SessionEffect> = buildList {
        add(SessionEffect.CloseSession(endedAtMillis, reason))
        if (alerted) add(SessionEffect.DismissThresholdAlert)
    }

    /**
     * 잠금 시각으로부터 유예 시간이 아직 남았는지. 경계는 스펙 3절·7절의 `gap ≤ 유예 시간`이라
     * 딱 유예만큼 지난 순간까지는 세션이 이어진다.
     *
     * 유예 0분은 이 비교에 걸리지 않는다. 그 설정에서는 유예 중이 될 일 자체가 없다([lock]).
     */
    private fun gracePeriodRemains(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Boolean = nowMillis - session.lockedAt() <= settings.gracePeriodMillis

    private fun activeContent(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): PersistentDisplayContent.Active = PersistentDisplayContent.Active(
        sessionStartedAtMillis = session.startedAtMillis,
        elapsedMinutes = elapsedMinutes(session, nowMillis),
        thresholdMinutes = settings.thresholdMinutes,
        nextAlertMinutes = nextAlertMinutes(session, nowMillis, settings),
        muted = session.muted,
    )

    /**
     * 다음 알림까지 남은 분. 초과 상태의 본문 "다음 알림 N분 후"가 된다(스펙 4절).
     *
     * 다음 알림은 직전 알림 시각으로부터 재알림 주기 뒤다. 셀 기준점이 없거나(이 세션에서 아직
     * 알린 적이 없다) 그 시각이 이미 지났으면 null이고, 그때 본문은 초과했다는 사실만 적는다.
     *
     * "이미 지났다"는 쪽에 닿는 길은 지금 [reduce]에 없다. 알림이 나가는 세션은 주기가 지나는
     * 순간 [reAlert]가 기준점을 옮기고, 잠금 중에 밀린 주기도 [continueSession]이 갚기 때문이다.
     * 그래서 이 분기에는 테스트가 없다. 그래도 남겨 둔다. 이 값의 뜻이 "예고할 다음 알림까지 남은
     * 분"이라 0이나 음수는 값이 아니고, ADR 0003이 "다음 알림 0분 후"를 오지 않을 알림을 예고하는
     * 거짓말이라며 이미 물린 자리다.
     *
     * 알림이 막혀 있으면([alertsAllowed]) 예고할 다음 알림 자체가 없어 null이다. 스펙 4절 표가 토글
     * 꺼짐의 초과 본문을 "임계값 30분 초과"로 정한 자리이고, 세션 알림 끄기는 그 자리에
     * "이번 세션 알림 꺼짐"을 적는다.
     *
     * 남은 분은 올림한다. "0분 후"가 아니라 남은 분이 그대로 보이게.
     */
    private fun nextAlertMinutes(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): Int? {
        if (!alertsAllowed(session, settings)) return null
        val lastAlertAtMillis = session.alerts.lastAlertAtMillis ?: return null
        val remainingMillis = lastAlertAtMillis + settings.reAlertIntervalMillis - nowMillis
        if (remainingMillis <= 0) return null
        return ((remainingMillis + MINUTE_MILLIS - 1) / MINUTE_MILLIS).toInt()
    }

    private fun graceContent(
        session: Session,
        nowMillis: Long,
        settings: TrackingSettings,
    ): PersistentDisplayContent.Grace = PersistentDisplayContent.Grace(
        sessionStartedAtMillis = session.startedAtMillis,
        elapsedMinutes = elapsedMinutes(session, nowMillis),
        thresholdMinutes = settings.thresholdMinutes,
        graceRemainingMillis = (session.lockedAt() + settings.gracePeriodMillis - nowMillis)
            .coerceAtLeast(0),
    )

    /**
     * 연속 사용 시간(`sessionDuration`)의 분 단위. 유예 구간도 포함한다(스펙 3절).
     * 상시 표시의 본문·막대가 이 값을 쓴다.
     */
    private fun elapsedMinutes(session: Session, nowMillis: Long): Int =
        ((nowMillis - session.startedAtMillis) / MINUTE_MILLIS).coerceAtLeast(0).toInt()

    /** 세션 진행·유예 중에는 열린 세션이 있다([SessionState]의 불변식). */
    private fun SessionState.openSession(): Session =
        requireNotNull(session) { "$phase 국면에는 열린 세션이 있다" }

    /** 유예 중에는 잠금 시각이 있다([SessionState]의 불변식). */
    private fun Session.lockedAt(): Long =
        requireNotNull(lockedAtMillis) { "유예 중에는 잠금 시각이 있다" }
}
