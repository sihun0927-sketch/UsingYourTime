package io.github.sihun0927.usingyourtime.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 프로세스마다 하나여야 하는 DataStore 인스턴스. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 스펙 8절의 DataStore 키를 읽고 쓴다.
 *
 * 지금은 `tracking_on` 하나뿐이고, 임계값·유예 시간·재알림 주기는 설정 화면 티켓에서 들어온다.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    /** 측정이 켜져 있는지. 기본값 false. */
    val trackingOn: Flow<Boolean> = dataStore.data.map { it[TRACKING_ON] ?: false }

    suspend fun readTrackingOn(): Boolean = trackingOn.first()

    suspend fun setTrackingOn(trackingOn: Boolean) {
        dataStore.edit { it[TRACKING_ON] = trackingOn }
    }

    private companion object {
        val TRACKING_ON = booleanPreferencesKey("tracking_on")
    }
}
