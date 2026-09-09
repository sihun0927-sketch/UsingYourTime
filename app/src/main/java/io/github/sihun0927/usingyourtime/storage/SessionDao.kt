package io.github.sihun0927.usingyourtime.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import io.github.sihun0927.usingyourtime.session.SessionEndReason

/**
 * `sessions` 테이블 접근. 갱신·종료는 모두 "열린 행"(`ended_at IS NULL`) 하나를 겨냥한다.
 *
 * 세션 기록에는 화면이 없다(스펙 1절 범위 밖). 읽기는 복구 티켓 #27이 쓸 [openSession] 하나다.
 */
@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET locked_at = :lockedAtMillis WHERE ended_at IS NULL")
    suspend fun saveLockedAt(lockedAtMillis: Long?)

    @Query(
        "UPDATE sessions SET ended_at = :endedAtMillis, end_reason = :reason WHERE ended_at IS NULL",
    )
    suspend fun close(endedAtMillis: Long, reason: SessionEndReason)

    /**
     * 남아 있는 열린 행을 추정 종료 시각(스펙 7절: `max(last_alive_at, locked_at)`)으로 닫는다.
     *
     * 정상 흐름에서는 리듀서가 새 세션을 열기 전에 이전 세션을 닫으므로 부딪히지 않는다. 프로세스가
     * 죽어 세션을 닫지 못한 뒤 다시 측정을 시작한 경우에만 걸리며, "열린 행은 최대 1개"를 지킨다.
     * 이 행을 세션으로 되살리는 재동기화는 복구 티켓 #27이 맡는다.
     */
    @Query(
        """
        UPDATE sessions
        SET ended_at = MAX(last_alive_at, IFNULL(locked_at, 0)), end_reason = 'ESTIMATED'
        WHERE ended_at IS NULL
        """,
    )
    suspend fun closeStrayOpenSessions()

    /** 열린 세션을 새로 연다. 앞선 세션이 닫히지 않은 채 남아 있으면 추정 종료 시각으로 닫는다. */
    @Transaction
    suspend fun open(startedAtMillis: Long) {
        closeStrayOpenSessions()
        insert(
            SessionEntity(
                startedAtMillis = startedAtMillis,
                lastAliveAtMillis = startedAtMillis,
            ),
        )
    }
}
