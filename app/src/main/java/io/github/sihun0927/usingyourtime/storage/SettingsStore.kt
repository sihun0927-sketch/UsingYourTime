package io.github.sihun0927.usingyourtime.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.sihun0927.usingyourtime.session.TrackingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 프로세스마다 하나여야 하는 DataStore 인스턴스. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 스펙 8절의 DataStore 키를 읽고 쓴다.
 *
 * 임계값 알림 토글·재시작 안내는 이후 티켓(#25·#28)에서 들어온다.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    /** 측정이 켜져 있는지. 기본값 false. */
    val trackingOn: Flow<Boolean> = dataStore.data.map { it[TRACKING_ON] ?: false }

    /**
     * 리듀서가 판정에 쓰는 설정. 저장이 끝나는 즉시 새 값이 흘러와 다음 판정부터 적용된다
     * (스펙 3절 설정 변경 중 동작).
     */
    val settings: Flow<TrackingSettings> = dataStore.data.map { preferences ->
        DEFAULTS.copy(
            thresholdMinutes = preferences[THRESHOLD_MIN] ?: DEFAULTS.thresholdMinutes,
            graceMinutes = preferences[GRACE_MIN] ?: DEFAULTS.graceMinutes,
            reAlertMinutes = preferences[REALERT_MIN] ?: DEFAULTS.reAlertMinutes,
        )
    }

    suspend fun setTrackingOn(trackingOn: Boolean) {
        dataStore.edit { it[TRACKING_ON] = trackingOn }
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

    private companion object {
        /** 저장된 값이 없을 때 쓰는 기본값. 스펙 6절 표와 같다. */
        val DEFAULTS = TrackingSettings()

        val TRACKING_ON = booleanPreferencesKey("tracking_on")
        val THRESHOLD_MIN = intPreferencesKey("threshold_min")
        val GRACE_MIN = intPreferencesKey("grace_min")
        val REALERT_MIN = intPreferencesKey("realert_min")
    }
}
