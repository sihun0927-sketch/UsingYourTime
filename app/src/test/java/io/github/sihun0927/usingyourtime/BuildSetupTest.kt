package io.github.sihun0927.usingyourtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 골격 스모크. 세션 상태 머신 테스트가 올라탈 실행 환경(JUnit 5 + 가상 시간)을 확인한다.
 * 세션 전이표 테스트는 `session` 패키지 티켓에서 들어온다.
 */
class BuildSetupTest {

    @Test
    fun `가상 시간으로 코루틴 테스트가 돈다`() = runTest {
        val startedAtMillis = currentTime

        delay(60_000)

        assertEquals(60_000, currentTime - startedAtMillis)
    }
}
