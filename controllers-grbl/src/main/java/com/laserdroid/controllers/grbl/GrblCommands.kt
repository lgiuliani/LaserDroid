package com.laserdroid.controllers.grbl

/**
 * GRBL 1.1 commands (specification 6).
 *
 * Line commands end with a newline; realtime commands are single
 * bytes processed immediately by GRBL.
 */
object GrblCommands {

    const val STATUS = "?"
    const val PAUSE = "!"
    const val RESUME = "~"

    const val SETTINGS = "$$"
    const val HOMING = $$"$H"
    const val UNLOCK = $$"$X"
    const val MODAL_STATE = $$"$G"
    const val BUILD_INFO = $$"$I"

    const val SOFT_RESET: Byte = 0x18

    const val JOG_PREFIX = $$"$J="

    const val JOG_CANCEL: Byte = 0x85.toByte()
}