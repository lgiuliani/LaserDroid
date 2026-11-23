package com.laserdroid.controllers.api

sealed interface ControllerEvent {

    data object Connected : ControllerEvent

    data object Disconnected : ControllerEvent

    data class MessageReceived(
        val message: String
    ) : ControllerEvent

    data class ErrorReceived(
        val code: Int?,
        val message: String
    ) : ControllerEvent

    data class AlarmReceived(
        val code: Int?,
        val message: String
    ) : ControllerEvent

    /**
     * Startup banner received ("Grbl 1.1h ['$' for help]"):
     * the controller restarted and its buffers were flushed.
     */
    data class ResetDetected(
        val banner: String
    ) : ControllerEvent

    data class SettingReceived(
        val number: Int,
        val value: String
    ) : ControllerEvent
}