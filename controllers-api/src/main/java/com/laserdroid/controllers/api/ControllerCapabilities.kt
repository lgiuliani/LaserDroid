package com.laserdroid.controllers.api

data class ControllerCapabilities(
    val supportsJog: Boolean = false,
    val supportsHoming: Boolean = false,
    val supportsRealtimeCommands: Boolean = false,
    val supportsOverrides: Boolean = false,
    val supportsPositionReporting: Boolean = false,
    val supportsSettings: Boolean = false
)
