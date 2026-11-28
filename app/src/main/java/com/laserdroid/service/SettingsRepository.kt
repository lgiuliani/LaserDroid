package com.laserdroid.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    /**
     * Low laser power, % of $30: job frame (FRAME) and
     * hold-to-fire LASER button share the same value.
     */
    val lowPowerPercent: Double = 2.0,
    /** FRAME and CENTER speed, mm/min. */
    val framingFeedRate: Double = 3_000.0,
    /** Optional Z axis: Z+ / Z- buttons, Z in SET ZERO and status. */
    val zAxisEnabled: Boolean = false,
    /** Start the service and the screen when the phone boots. */
    val startOnBoot: Boolean = false,
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
    val remotePauseOnLoss: Boolean = true,
    /** Live camera stream on http://phone:8080/?key=... */
    val cameraEnabled: Boolean = false,
    val cameraBack: Boolean = true,
    /** false: 640x480, true: 1280x720. */
    val cameraHd: Boolean = false,
    val cameraFps: Int = 10,
    /** Display rotation in the browser: 0, 90, 180, 270. */
    val cameraRotation: Int = 0,
    /** Exposure compensation in EV (-2..+2), laser glare. */
    val cameraExposure: Int = 0,
    /** Access key required by the web page (generated). */
    val cameraKey: String = ""
)

private val Context.laserDroidSettings: DataStore<Preferences> by
preferencesDataStore(name = "laserdroid_settings")

/**
 * Persists [AppSettings] in DataStore and exposes them as a StateFlow
 * (defaults until the first read completes).
 */
class SettingsRepository(
    context: Context,
    scope: CoroutineScope
) {

    private object Keys {
        val FRAMING_POWER = doublePreferencesKey("framing_power_percent")
        val FRAMING_FEED = doublePreferencesKey("framing_feed_rate")
        val Z_AXIS = booleanPreferencesKey("z_axis_enabled")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val SOUNDS = booleanPreferencesKey("sounds_enabled")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val REMOTE_ENABLED = booleanPreferencesKey("remote_enabled")
        val REMOTE_MODE = stringPreferencesKey("remote_mode")
        val REMOTE_WEBSOCKET = booleanPreferencesKey("remote_websocket")
        val REMOTE_RELAY = booleanPreferencesKey("remote_relay")
        val REMOTE_PAUSE = booleanPreferencesKey("remote_pause_on_loss")
        val CAMERA_ENABLED = booleanPreferencesKey("camera_enabled")
        val CAMERA_BACK = booleanPreferencesKey("camera_back")
        val CAMERA_HD = booleanPreferencesKey("camera_hd")
        val CAMERA_FPS = intPreferencesKey("camera_fps")
        val CAMERA_ROTATION = intPreferencesKey("camera_rotation")
        val CAMERA_EXPOSURE = intPreferencesKey("camera_exposure")
        val CAMERA_KEY = stringPreferencesKey("camera_key")
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
            preferences[Keys.FRAMING_POWER] = updated.lowPowerPercent
            preferences[Keys.FRAMING_FEED] = updated.framingFeedRate
            preferences[Keys.Z_AXIS] = updated.zAxisEnabled
            preferences[Keys.START_ON_BOOT] = updated.startOnBoot
            preferences[Keys.SOUNDS] = updated.soundsEnabled
            preferences[Keys.LOG_LEVEL] = updated.logLevel.name
            preferences[Keys.REMOTE_ENABLED] = updated.remoteEnabled
            preferences[Keys.REMOTE_MODE] = updated.remoteMode.name
            preferences[Keys.REMOTE_WEBSOCKET] = updated.remoteWebSocket
            preferences[Keys.REMOTE_RELAY] = updated.remoteRelay
            preferences[Keys.REMOTE_PAUSE] = updated.remotePauseOnLoss
            preferences[Keys.CAMERA_ENABLED] = updated.cameraEnabled
            preferences[Keys.CAMERA_BACK] = updated.cameraBack
            preferences[Keys.CAMERA_HD] = updated.cameraHd
            preferences[Keys.CAMERA_FPS] = updated.cameraFps
            preferences[Keys.CAMERA_ROTATION] = updated.cameraRotation
            preferences[Keys.CAMERA_EXPOSURE] = updated.cameraExposure
            preferences[Keys.CAMERA_KEY] = updated.cameraKey
        }
    }

    private fun fromPreferences(preferences: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            lowPowerPercent = (preferences[Keys.FRAMING_POWER] ?: defaults.lowPowerPercent)
                .takeIf { it > 0.0 } ?: defaults.lowPowerPercent,
            framingFeedRate = preferences[Keys.FRAMING_FEED] ?: defaults.framingFeedRate,
            zAxisEnabled = preferences[Keys.Z_AXIS] ?: defaults.zAxisEnabled,
            startOnBoot = preferences[Keys.START_ON_BOOT] ?: defaults.startOnBoot,
            soundsEnabled = preferences[Keys.SOUNDS] ?: defaults.soundsEnabled,
            logLevel = preferences[Keys.LOG_LEVEL]
                ?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
                ?: defaults.logLevel,
            remoteEnabled = preferences[Keys.REMOTE_ENABLED] ?: defaults.remoteEnabled,
            remoteMode = preferences[Keys.REMOTE_MODE]
                ?.let { name -> RemoteMode.entries.firstOrNull { it.name == name } }
                ?: defaults.remoteMode,
            remoteWebSocket = preferences[Keys.REMOTE_WEBSOCKET] ?: defaults.remoteWebSocket,
            remoteRelay = preferences[Keys.REMOTE_RELAY] ?: defaults.remoteRelay,
            remotePauseOnLoss = preferences[Keys.REMOTE_PAUSE] ?: defaults.remotePauseOnLoss,
            cameraEnabled = preferences[Keys.CAMERA_ENABLED] ?: defaults.cameraEnabled,
            cameraBack = preferences[Keys.CAMERA_BACK] ?: defaults.cameraBack,
            cameraHd = preferences[Keys.CAMERA_HD] ?: defaults.cameraHd,
            cameraFps = preferences[Keys.CAMERA_FPS] ?: defaults.cameraFps,
            cameraRotation = preferences[Keys.CAMERA_ROTATION] ?: defaults.cameraRotation,
            cameraExposure = preferences[Keys.CAMERA_EXPOSURE] ?: defaults.cameraExposure,
            cameraKey = preferences[Keys.CAMERA_KEY] ?: defaults.cameraKey
        )
    }
}
