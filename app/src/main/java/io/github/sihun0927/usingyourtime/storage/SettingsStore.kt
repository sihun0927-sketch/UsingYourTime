package io.github.sihun0927.usingyourtime.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.sihun0927.usingyourtime.session.TrackingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 프로세스마다 하나여야 하는 DataStore 인스턴스. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 스펙 8절의 DataStore 키를 읽고 쓴다. */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    /** 측정이 켜져 있는지. 기본값 false. */
    val trackingOn: Flow<Boolean> = dataStore.data.map { it[TRACKING_ON] ?: false }

    /**
     * 측정이 끊겼다가 재동기화 규칙 4로 세션이 닫힌 시각(스펙 7절 사용자 안내). null이면 안내가
     * 없다.
     *
     * 상태 카드가 이 값을 "HH:MM에 측정이 중단됐다가 지금 다시 시작했어요"로 그리는 일은 티켓
     * #28이 맡는다.
     */
    val restartNoticeAt: Flow<Long?> = dataStore.data.map { it[RESTART_NOTICE_AT] }

    /**
     * 리듀서가 판정에 쓰는 설정. 저장이 끝나는 즉시 새 값이 흘러와 다음 판정부터 적용된다
     * (스펙 3절 설정 변경 중 동작).
     */
    val settings: Flow<TrackingSettings> = dataStore.data.map { preferences ->
        DEFAULTS.copy(
            thresholdMinutes = preferences[THRESHOLD_MIN] ?: DEFAULTS.thresholdMinutes,
            graceMinutes = preferences[GRACE_MIN] ?: DEFAULTS.graceMinutes,
            reAlertMinutes = preferences[REALERT_MIN] ?: DEFAULTS.reAlertMinutes,
            thresholdAlertEnabled = preferences[THRESHOLD_ALERT_ENABLED]
                ?: DEFAULTS.thresholdAlertEnabled,
        )
    }

    suspend fun setTrackingOn(trackingOn: Boolean) {
        dataStore.edit { it[TRACKING_ON] = trackingOn }
    }

    /** 재시작 안내 시각을 적거나([atMillis]가 null이면) 지운다. 리듀서가 낸 효과만 부른다. */
    suspend fun setRestartNoticeAt(atMillis: Long?) {
        dataStore.edit { preferences ->
            if (atMillis == null) {
                preferences.remove(RESTART_NOTICE_AT)
            } else {
                preferences[RESTART_NOTICE_AT] = atMillis
            }
        }
    }

    suspend fun setThresholdMinutes(thresholdMinutes: Int) {
        dataStore.edit { it[THRESHOLD_MIN] = thresholdMinutes }
    }

    suspend fun setGraceMinutes(graceMinutes: Int) {
        dataStore.edit { it[GRACE_MIN] = graceMinutes }
    }

    suspend fun setReAlertMinutes(reAlertMinutes: Int) {
        dataStore.edit { it[REALERT_MIN] = reAlertMinutes }
    }

    /**
     * 임계값 알림 토글(스펙 6절). 끄면 상시 표시만 남고 임계값 알림·재알림이 오지 않는다.
     *
     * 진행 중인 세션을 끊지 않는다. 서비스가 같은 흐름을 구독하고 있어 다음 판정부터 새 값을
     * 쓴다(스펙 3절 설정 변경 중 동작).
     */
    suspend fun setThresholdAlertEnabled(enabled: Boolean) {
        dataStore.edit { it[THRESHOLD_ALERT_ENABLED] = enabled }
    }

    private companion object {
        /** 저장된 값이 없을 때 쓰는 기본값. 스펙 6절 표와 같다. */
        val DEFAULTS = TrackingSettings()

        val TRACKING_ON = booleanPreferencesKey("tracking_on")
        val RESTART_NOTICE_AT = longPreferencesKey("restart_notice_at")
        val THRESHOLD_MIN = intPreferencesKey("threshold_min")
        val GRACE_MIN = intPreferencesKey("grace_min")
        val REALERT_MIN = intPreferencesKey("realert_min")
        val THRESHOLD_ALERT_ENABLED = booleanPreferencesKey("threshold_alert_enabled")
    }
}
