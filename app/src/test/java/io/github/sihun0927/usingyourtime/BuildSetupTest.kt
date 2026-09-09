package io.github.sihun0927.usingyourtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 골격 스모크: 이후 티켓의 세션 상태 머신 테스트가 올라탈 테스트 실행 환경을 확인한다. */
class BuildSetupTest {

    @Test
    fun `JUnit 5 테스트가 실행된다`() {
        assertTrue(true)
    }

    @Test
    fun `coroutines-test의 runTest가 동작한다`() = runTest {
        assertTrue(true)
    }
}
