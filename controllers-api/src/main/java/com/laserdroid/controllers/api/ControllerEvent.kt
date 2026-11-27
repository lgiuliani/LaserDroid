package com.laserdroid.controllers.api

/**
 * Asynchronous notification published by a [CncController].
 */
sealed interface ControllerEvent {

    data object Connected : ControllerEvent

    data object Disconnected : ControllerEvent

    /** Informative line, e.g. `[MSG:...]`, `[VER:...]`. */
    data class MessageReceived(
        val message: String
    ) : ControllerEvent

    /** `error:N` received. */
    data class ErrorReceived(
        val code: Int?,
        val message: String
    ) : ControllerEvent

    /** `ALARM:N` received. */
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

    /** One `$N=value` line of the `$$` answer. */
    data class SettingReceived(
        val number: Int,
        val value: String
    ) : ControllerEvent
}