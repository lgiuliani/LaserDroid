package com.laserdroid.core.machine

/**
 * Connection state derived from the controller state.
 */
enum class MachineConnectionState {

    DISCONNECTED,

    CONNECTING,

    CONNECTED,

    ERROR
}