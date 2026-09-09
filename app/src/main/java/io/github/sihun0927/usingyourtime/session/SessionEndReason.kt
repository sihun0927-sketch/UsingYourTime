package io.github.sihun0927.usingyourtime.session

/** 세션이 닫힌 까닭. Room `sessions.end_reason`에 그대로 저장된다(스펙 8절). */
enum class SessionEndReason {
    /** 유예 시간이 지나 잠금 시각으로 닫혔다. */
    GRACE_EXPIRED,

    /** 사용자가 측정 중지를 눌러 유예 없이 지금 닫혔다. */
    PAUSED,

    /** 측정이 끊겨 추정 종료 시각으로 닫혔다(스펙 7절). 복구 티켓 #27에서 쓴다. */
    ESTIMATED,
}
