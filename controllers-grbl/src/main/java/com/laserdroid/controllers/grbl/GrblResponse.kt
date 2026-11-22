package com.laserdroid.controllers.grbl

sealed interface GrblResponse {

    data object Ok : GrblResponse

    data class Error(
        val code: Int?
    ) : GrblResponse

    data class Alarm(
        val code: Int?
    ) : GrblResponse

    data class Status(
        val state: String,
        val machineX: Double?,
        val machineY: Double?,
        val machineZ: Double?,
        val feedRate: Int?,
        val spindleSpeed: Int?,
        val workX: Double? = null,
        val workY: Double? = null,
        val workZ: Double? = null,
        val offsetX: Double? = null,
        val offsetY: Double? = null,
        val offsetZ: Double? = null,
        val plannerBlocksFree: Int? = null,
        val serialBytesFree: Int? = null
    ) : GrblResponse

    data class Message(
        val content: String
    ) : GrblResponse

    data class Unknown(
        val raw: String
    ) : GrblResponse

    data class Setting(
        val number: Int,
        val value: String
    ) : GrblResponse

}