package com.laserdroid.controllers.api

/**
 * Features declared by a driver (specification 4).
 */
data class ControllerCapabilities(
    val supportsJog: Boolean = false,
    val supportsHoming: Boolean = false,
    val supportsRealtimeCommands: Boolean = false,
    val supportsOverrides: Boolean = false,
    val supportsPositionReporting: Boolean = false,
    val supportsSettings: Boolean = false
)
