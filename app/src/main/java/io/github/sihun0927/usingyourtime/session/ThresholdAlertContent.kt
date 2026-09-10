package io.github.sihun0927.usingyourtime.session

/**
 * 임계값 알림이 지금 보여줄 내용(스펙 4절 표). 상시 표시와 마찬가지로 리듀서가 값으로 돌려주고,
 * `notification`이 문구로 옮긴다. 여기에는 문자열도 리소스 id도 없다.
 *
 * 재알림 본문과 부제 회차는 다음 티켓(#24)에서 들어온다.
 */
data class ThresholdAlertContent(
    /** 제목의 "N분째 연속 사용 중". 연속 사용 시간을 분으로 내린 값이다. */
    val elapsedMinutes: Int,

    /** 본문의 "계속 쓰면 N분 뒤 다시 알려요". 지금 설정된 재알림 주기다. */
    val reAlertMinutes: Int,
)
