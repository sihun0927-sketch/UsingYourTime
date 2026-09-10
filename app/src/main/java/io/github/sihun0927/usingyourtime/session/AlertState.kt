package io.github.sihun0927.usingyourtime.session

/**
 * 한 세션의 알림 상태(스펙 8절 `sessions`의 알림 열). 세션에 속하므로 세션이 닫히면 함께 사라지고,
 * 새 세션은 [None]에서 시작한다.
 *
 * 첫 임계값 알림이 세 열을 함께 채우고, 그 뒤 재알림이 [lastAlertAtMillis]를 옮기며 [count]를
 * 올린다. [thresholdAlertedAtMillis]는 그대로 남아 "이 세션에서 알린 적이 있나"에 답한다.
 */
data class AlertState(
    /** 첫 임계값 알림을 보낸 시각. null이면 이 세션에서 아직 알린 적이 없다. */
    val thresholdAlertedAtMillis: Long? = null,

    /** 마지막 임계값 알림·재알림 시각. 다음 재알림을 재는 기준점이다(스펙 4절). */
    val lastAlertAtMillis: Long? = null,

    /** 회차. 첫 알림이 1이고 재알림마다 하나씩 오른다. 상한이 없다. 알린 적이 없으면 0이다. */
    val count: Int = 0,
) {
    /** 이 세션에서 임계값 알림을 이미 보냈는지. 첫 알림은 세션당 한 번뿐이다(스펙 4절). */
    val alerted: Boolean get() = thresholdAlertedAtMillis != null

    companion object {
        /** 아직 아무것도 알리지 않은 세션의 알림 상태. */
        val None = AlertState()
    }
}
