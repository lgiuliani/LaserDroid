package com.laserdroid.controllers.api

/**
 * Cartesian position in millimeters.
 */
data class MachinePosition(
    val x: Double,
    val y: Double,
    val z: Double
) {
    companion object {
        val ZERO = MachinePosition(
            x = 0.0,
            y = 0.0,
            z = 0.0
        )
    }
}