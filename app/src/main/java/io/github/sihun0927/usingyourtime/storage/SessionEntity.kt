package io.github.sihun0927.usingyourtime.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import io.github.sihun0927.usingyourtime.session.SessionEndReason

/**
 * Room `sessions` 한 행(스펙 8절). 시각은 모두 epoch 밀리초다.
 *
 * 열린 세션은 `ended_at`이 null인 행이고 항상 최대 1개다.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "started_at")
    val startedAtMillis: Long,

    /** heartbeat. 세션 진행 중 1분마다 덮어쓰는 일은 복구 티켓 #27이 붙인다(스펙 7절). */
    @ColumnInfo(name = "last_alive_at")
    val lastAliveAtMillis: Long,

    /** 마지막 잠금 시각. 유예 중일 때만 있다. */
    @ColumnInfo(name = "locked_at")
    val lockedAtMillis: Long? = null,

    /** 종료 시각. null이면 열린 세션이다. */
    @ColumnInfo(name = "ended_at")
    val endedAtMillis: Long? = null,

    @ColumnInfo(name = "end_reason")
    val endReason: SessionEndReason? = null,

    @ColumnInfo(name = "threshold_alerted_at")
    val thresholdAlertedAtMillis: Long? = null,

    @ColumnInfo(name = "last_alert_at")
    val lastAlertAtMillis: Long? = null,

    @ColumnInfo(name = "alert_count")
    val alertCount: Int = 0,

    /** 세션 알림 끄기. 세션에 속한 값이라 새 행은 늘 거짓에서 시작한다(스펙 3절). */
    @ColumnInfo(name = "muted")
    val muted: Boolean = false,
)

/** 종료 원인은 이름 그대로 저장한다. 값이 늘어도 기존 행을 읽는 데 문제가 없다. */
class SessionEndReasonConverter {

    @TypeConverter
    fun toName(reason: SessionEndReason?): String? = reason?.name

    @TypeConverter
    fun fromName(name: String?): SessionEndReason? = name?.let(SessionEndReason::valueOf)
}
