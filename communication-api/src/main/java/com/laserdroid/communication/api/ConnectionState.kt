package com.laserdroid.communication.api

/**
 * State of a [SerialTransport].
 */
sealed interface ConnectionState {

    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    /** Open failure or link lost ([cause] describes it). */
    data class Error(
        val cause: Throwable
    ) : ConnectionState
}