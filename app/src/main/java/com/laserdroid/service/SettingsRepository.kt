package com.laserdroid.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laserdroid.core.remote.RemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Application settings (DataStore, specification 2).
 */
data class AppSettings(
    /** Laser power while tracing the job frame, % of $30 (0 = off). */
    val framingPowerPercent: Double = 2.0,
    /** Frame tracing speed, mm/min. */
    val framingFeedRate: Double = 3_000.0,
    /** Laser power of the hold-to-fire LASER button, % of $30. */
    val pointerPowerPercent: Double = 2.0,
    val soundsEnabled: Boolean = true,
    val logLevel: LogLevel = LogLevel.INFO,
    /** LaserGRBL over Wi-Fi (Telnet 2323). */
    val remoteEnabled: Boolean = false,
    val remoteMode: RemoteMode = RemoteMode.RECEPTION,
    /** Also accept LaserGRBL "LaserWebESP8266" (WebSocket 8181). */
    val remoteWebSocket: Boolean = true,
    /** Relay $J= / jog cancel / $X from LaserGRBL (RECEPTION). */
    val remoteRelay: Boolean = true,
    /** BRIDGE: feed hold if the PC disappears while moving. */
    val remotePauseOnLoss: Boolean = true
)

private val Context.laserDroidSettings: DataStore<Preferences> by
preferencesDataStore(name = "laserdroid_settings")

class SettingsRepository(
    context: Context,
    scope: CoroutineScope
) {

    private object Keys {
        val FRAMING_POWER = doublePreferencesKey("framing_power_percent")
        val FRAMING_FEED = doublePreferencesKey("framing_feed_rate")
        val POINTER_POWER = doublePreferencesKey("pointer_power_percent")
        val SOUNDS = booleanPreferencesKey("sounds_enabled")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val REMOTE_ENABLED = booleanPreferencesKey("remote_enabled")
        val REMOTE_MODE = stringPreferencesKey("remote_mode")
        val REMOTE_WEBSOCKET = booleanPreferencesKey("remote_websocket")
        val REMOTE_RELAY = booleanPreferencesKey("remote_relay")
        val REMOTE_PAUSE = booleanPreferencesKey("remote_pause_on_loss")
    }

    private val dataStore = context.applicationContext.laserDroidSettings

    val settings: StateFlow<AppSettings> =
        dataStore.data
            .map { preferences -> fromPreferences(preferences) }
            .catch { emit(AppSettings()) }
            .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val updated = transform(fromPreferences(preferences))
            preferences[Keys.FRAMING_POWER] = updated.framingPowerPercent
            preferences[Keys.FRAMING_FEED] = updated.framingFeedRate
            preferences[Keys.POINTER_POWER] = updated.pointerPowerPercent
            preferences[Keys.SOUNDS] = updated.soundsEnabled
            preferences[Keys.LOG_LEVEL] = updated.logLevel.name
            preferences[Keys.REMOTE_ENABLED] = updated.remoteEnabled
            preferences[Keys.REMOTE_MODE] = updated.remoteMode.name
            preferences[Keys.REMOTE_WEBSOCKET] = updated.remoteWebSocket
            preferences[Keys.REMOTE_RELAY] = updated.remoteRelay
            preferences[Keys.REMOTE_PAUSE] = updated.remotePauseOnLoss
        }
    }

    private fun fromPreferences(preferences: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            framingPowerPercent = preferences[Keys.FRAMING_POWER] ?: defaults.framingPowerPercent,
            framingFeedRate = preferences[Keys.FRAMING_FEED] ?: defaults.framingFeedRate,
            pointerPowerPercent = preferences[Keys.POINTER_POWER] ?: defaults.pointerPowerPercent,
            soundsEnabled = preferences[Keys.SOUNDS] ?: defaults.soundsEnabled,
            logLevel = preferences[Keys.LOG_LEVEL]
                ?.let { name -> LogLevel.values().firstOrNull { it.name == name } }
                ?: defaults.logLevel,
            remoteEnabled = preferences[Keys.REMOTE_ENABLED] ?: defaults.remoteEnabled,
            remoteMode = preferences[Keys.REMOTE_MODE]
                ?.let { name -> RemoteMode.values().firstOrNull { it.name == name } }
                ?: defaults.remoteMode,
            remoteWebSocket = preferences[Keys.REMOTE_WEBSOCKET] ?: defaults.remoteWebSocket,
            remoteRelay = preferences[Keys.REMOTE_RELAY] ?: defaults.remoteRelay,
            remotePauseOnLoss = preferences[Keys.REMOTE_PAUSE] ?: defaults.remotePauseOnLoss
        )
    }
}
