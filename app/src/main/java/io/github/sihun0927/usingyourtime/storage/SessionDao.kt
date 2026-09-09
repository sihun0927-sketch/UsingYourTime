package io.github.sihun0927.usingyourtime.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.sihun0927.usingyourtime.session.SessionEndReason

/**
 * `sessions` 테이블 접근. 리듀서가 낸 저장 효과를 그대로 받는다.
 *
 * 세션 기록에는 화면이 없다(스펙 1절 범위 밖). 읽기는 복구 티켓 #27이 들어올 때 붙는다.
 *
 * **열린 행은 최대 1개**다. 리듀서가 새 세션을 열기 전에 언제나 이전 세션을 먼저 닫아 이 불변식을
 * 지킨다. 프로세스가 죽어 닫지 못한 행만 예외로 남는데, 여기서는 그 행을 손대지 않는다. 스펙 7절의
 * 재동기화는 공백이 유예 안이면 그 행을 **되살려야** 하고(규칙 2·3), 넘으면 추정 종료 시각으로
 * 닫아야 한다(규칙 4). 여기서 미리 닫으면 #27이 판정할 재료가 사라진다.
 *
 * 그래서 갱신·종료는 `ended_at IS NULL`인 행 전부가 아니라 **가장 최근에 시작된 열린 행** 하나만
 * 겨냥한다. 앞의 예외로 남은 행이 지금 세션의 종료 시각으로 덮여 쓰이지 않는다.
 */
@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity)

    /** 지금 새 세션을 연다. heartbeat의 시작점은 세션 시작 시각이다(스펙 7절). */
    suspend fun open(startedAtMillis: Long) = insert(
        SessionEntity(startedAtMillis = startedAtMillis, lastAliveAtMillis = startedAtMillis),
    )

    @Query("UPDATE sessions SET locked_at = :lockedAtMillis WHERE id = $OPEN_SESSION_ID")
    suspend fun saveLockedAt(lockedAtMillis: Long?)

    @Query(
        "UPDATE sessions SET ended_at = :endedAtMillis, end_reason = :reason " +
            "WHERE id = $OPEN_SESSION_ID",
    )
    suspend fun close(endedAtMillis: Long, reason: SessionEndReason)

    companion object {
        /** 지금 열려 있는 세션 행의 id. */
        private const val OPEN_SESSION_ID =
            "(SELECT id FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1)"
    }
}
