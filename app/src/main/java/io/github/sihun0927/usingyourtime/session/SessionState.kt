package io.github.sihun0927.usingyourtime.session

/** 측정 상태의 국면(스펙 3절, `CONTEXT.md`의 상태 4종). */
enum class Phase {
    /** 측정 꺼짐. 측정 시작을 누른 적이 없거나 측정 중지를 누른 뒤. */
    Off,

    /** 세션 없음. 측정은 켜져 있고 기기가 잠겨 있으며 열린 세션이 없다. */
    Idle,

    /** 세션 진행. 잠금 해제 상태이고 세션이 열려 있다. */
    Active,

    /** 유예 중. 잠겨 있지만 유예 시간이 아직 지나지 않아 세션이 열려 있다. */
    Grace,
}

/**
 * 열린 연속 사용 세션. 시각은 모두 epoch 밀리초다(스펙 8절).
 *
 * Room `sessions` 행 중 리듀서가 판정에 쓰는 열만 담는다. 알림 상태(첫 임계값 알림 시각·회차·
 * 세션 알림 끄기)는 이후 티켓(#23~#25)에서 들어온다.
 */
data class Session(
    val startedAtMillis: Long,
    /** 마지막 잠금 시각. 유예 중일 때만 있고, 유예가 만료되면 이 시각이 세션의 종료 시각이 된다. */
    val lockedAtMillis: Long? = null,
)

/**
 * 리듀서가 주고받는 상태 전체(스펙 3절). Android 타입을 담지 않는 순수 값이라 값 비교로 검증한다.
 */
data class SessionState(
    val phase: Phase,
    val session: Session? = null,
) {
    init {
        require((phase == Phase.Active || phase == Phase.Grace) == (session != null)) {
            "$phase 국면과 열린 세션 유무가 맞지 않는다: session=$session"
        }
        require((phase == Phase.Grace) == (session?.lockedAtMillis != null)) {
            "잠금 시각은 유예 중일 때만 있다: phase=$phase, session=$session"
        }
    }

    companion object {
        /** 측정 꺼짐. 앱을 처음 켰을 때의 상태다. */
        val Off = SessionState(Phase.Off)

        /** 세션 없음. 측정은 켜져 있고 다음 잠금 해제를 기다린다. */
        val Idle = SessionState(Phase.Idle)
    }
}
