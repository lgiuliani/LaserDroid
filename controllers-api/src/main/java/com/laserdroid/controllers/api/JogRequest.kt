package com.laserdroid.controllers.api

data class JogRequest(
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val feedRate: Double
) {

    init {
        require(
            x != null ||
                    y != null ||
                    z != null
        ) {
            "At least one jog axis must be provided"
        }

        require(feedRate > 0.0) {
            "Jog feed rate must be greater than zero"
        }

        require(feedRate.isFinite()) {
            "Jog feed rate must be finite"
        }

        require(x?.isFinite() != false) {
            "Jog X distance must be finite"
        }

        require(y?.isFinite() != false) {
            "Jog Y distance must be finite"
        }

        require(z?.isFinite() != false) {
            "Jog Z distance must be finite"
        }

        require(
            x != 0.0 ||
                    y != 0.0 ||
                    z != 0.0
        ) {
            "At least one jog distance must be non-zero"
        }
    }
}