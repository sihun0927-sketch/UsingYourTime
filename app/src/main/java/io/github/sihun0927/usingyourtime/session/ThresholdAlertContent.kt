package io.github.sihun0927.usingyourtime.session

/**
 * 임계값 알림이 지금 보여줄 내용(스펙 4절 표). 상시 표시와 마찬가지로 리듀서가 값으로 돌려주고,
 * `notification`이 문구로 옮긴다. 여기에는 문자열도 리소스 id도 없다.
 *
 * 첫 알림과 재알림이 같은 값이다. 알림 id가 하나뿐이라 알림 창에 남는 것도 하나이고, 둘을 가르는
 * 것은 [count] 하나다(스펙 4절).
 */
data class ThresholdAlertContent(
    /** 제목의 "N분째 연속 사용 중". 연속 사용 시간을 분으로 내린 값이다. */
    val elapsedMinutes: Int,

    /** 본문의 "N분 뒤 다시 알려요". 지금 설정된 재알림 주기다. */
    val reAlertMinutes: Int,

    /**
     * 회차. 첫 알림이 1이고 재알림마다 하나씩 오른다. 상한이 없다.
     *
     * 부제(`setSubText`)의 "2번째", "3번째"가 이 값이고, 첫 알림에는 부제가 없다. 본문도 이 값으로
     * 갈린다(스펙 4절 표).
     */
    val count: Int,
) {
    /**
     * 첫 알림인지. 본문이 "잠깐 눈을 쉬어 주세요"로 길어지고 부제가 없는 쪽이다.
     *
     * 회차 1이 첫 알림이다. 부제는 2번째부터 붙으므로 그 아래는 모두 첫 알림과 같이 그린다.
     */
    val isFirstAlert: Boolean get() = count <= 1
}
