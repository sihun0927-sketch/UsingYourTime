package io.github.sihun0927.usingyourtime.session

/** 1분. 설정은 분 단위이고 리듀서의 판정은 epoch 밀리초라 이 상수로 오간다. */
internal const val MINUTE_MILLIS = 60_000L

/**
 * 리듀서가 판정에 쓰는 사용자 설정(스펙 6절). 기본값은 스펙 8절 DataStore 키의 기본값과 같다.
 *
 * 값이 바뀌면 다음 판정부터 새 값을 쓴다(스펙 3절 설정 변경 중 동작). 진행 중인 세션은 건드리지
 * 않으므로 서비스는 DataStore에서 읽은 새 값을 다음 이벤트에 실어 보내기만 하면 된다.
 */
data class TrackingSettings(
    val thresholdMinutes: Int = 30,
    val graceMinutes: Int = 3,
    val reAlertMinutes: Int = 15,
    val thresholdAlertEnabled: Boolean = true,
) {
    /** 유예 시간(`gracePeriod`)을 밀리초로. 0이면 잠그는 즉시 세션이 끝난다. */
    val gracePeriodMillis: Long get() = graceMinutes * MINUTE_MILLIS

    /** 임계값(`threshold`)을 밀리초로. 연속 사용 시간이 이만큼 되면 임계값 알림이 나간다. */
    val thresholdMillis: Long get() = thresholdMinutes * MINUTE_MILLIS

    /** 재알림 주기(`reAlertInterval`)를 밀리초로. 직전 알림 시각부터 잰다(스펙 4절). */
    val reAlertIntervalMillis: Long get() = reAlertMinutes * MINUTE_MILLIS
}
