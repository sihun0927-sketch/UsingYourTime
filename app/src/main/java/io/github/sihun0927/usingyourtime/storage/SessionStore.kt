package io.github.sihun0927.usingyourtime.storage

import io.github.sihun0927.usingyourtime.session.SessionEndReason

/**
 * 세션 기록 창구. 리듀서가 낸 저장 효과(세션 열기·잠금 시각·세션 닫기)를 그대로 받는다.
 *
 * 서비스가 Room을 직접 만지지 않도록 [SessionDao] 앞에 둔다. 쓰기는 모두 열린 행 하나를 겨냥한다.
 */
class SessionStore(private val sessionDao: SessionDao) {

    suspend fun open(startedAtMillis: Long) = sessionDao.open(startedAtMillis)

    suspend fun saveLockedAt(lockedAtMillis: Long?) = sessionDao.saveLockedAt(lockedAtMillis)

    suspend fun close(endedAtMillis: Long, reason: SessionEndReason) =
        sessionDao.close(endedAtMillis, reason)
}
