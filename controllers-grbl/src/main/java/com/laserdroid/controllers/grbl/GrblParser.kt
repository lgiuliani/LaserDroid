package com.laserdroid.controllers.grbl

class GrblParser {

    fun parse(line: String): GrblResponse {

        val value = line.trim()

        if (value == "ok") {
            return GrblResponse.Ok
        }

        if (value.startsWith("error:")) {
            return GrblResponse.Error(
                code = value
                    .substringAfter("error:")
                    .toIntOrNull()
            )
        }

        if (value.startsWith("ALARM:")) {
            return GrblResponse.Alarm(
                code = value
                    .substringAfter("ALARM:")
                    .toIntOrNull()
            )
        }

        if (value.startsWith("<") && value.endsWith(">")) {
            return parseStatus(value)
        }

        if (value.startsWith("[") && value.endsWith("]")) {
            return GrblResponse.Message(value)
        }
        if (
            value.startsWith("$") &&
            value.contains("=")
        ) {

            val number =
                value
                    .substringAfter("$")
                    .substringBefore("=")
                    .toIntOrNull()

            val settingValue =
                value.substringAfter("=")

            if (number != null) {

                return GrblResponse.Setting(
                    number = number,
                    value = settingValue
                )
            }
        }

        return GrblResponse.Unknown(value)
    }

    private fun parseStatus(line: String): GrblResponse.Status {

        val content = line
            .removePrefix("<")
            .removeSuffix(">")

        val fields = content.split("|")

        val state = fields.firstOrNull()
            ?: "Unknown"

        var x: Double? = null
        var y: Double? = null
        var z: Double? = null

        var feedRate: Int? = null
        var spindleSpeed: Int? = null

        var workCoordinates: List<Double?>? = null
        var offsetCoordinates: List<Double?>? = null
        var bufferValues: List<Int?>? = null

        fields.drop(1).forEach { field ->

            when {

                field.startsWith("MPos:") -> {

                    val coordinates = field
                        .substringAfter("MPos:")
                        .split(",")

                    x = coordinates.getOrNull(0)?.toDoubleOrNull()
                    y = coordinates.getOrNull(1)?.toDoubleOrNull()
                    z = coordinates.getOrNull(2)?.toDoubleOrNull()
                }

                field.startsWith("WPos:") -> {
                    workCoordinates =
                        parseCoordinates(field.substringAfter("WPos:"))
                }

                field.startsWith("WCO:") -> {
                    offsetCoordinates =
                        parseCoordinates(field.substringAfter("WCO:"))
                }

                field.startsWith("Bf:") -> {
                    bufferValues = field
                        .substringAfter("Bf:")
                        .split(",")
                        .map { it.trim().toIntOrNull() }
                }

                field.startsWith("F:") -> {
                    feedRate = field
                        .substringAfter("F:")
                        .toDoubleOrNull()
                        ?.toInt()
                }

                field.startsWith("FS:") -> {

                    val values = field
                        .substringAfter("FS:")
                        .split(",")

                    feedRate =
                        values.getOrNull(0)?.toDoubleOrNull()?.toInt()

                    spindleSpeed =
                        values.getOrNull(1)?.toDoubleOrNull()?.toInt()
                }
            }
        }

        return GrblResponse.Status(
            state = state,
            machineX = x,
            machineY = y,
            machineZ = z,
            feedRate = feedRate,
            spindleSpeed = spindleSpeed,
            workX = workCoordinates?.getOrNull(0),
            workY = workCoordinates?.getOrNull(1),
            workZ = workCoordinates?.getOrNull(2),
            offsetX = offsetCoordinates?.getOrNull(0),
            offsetY = offsetCoordinates?.getOrNull(1),
            offsetZ = offsetCoordinates?.getOrNull(2),
            plannerBlocksFree = bufferValues?.getOrNull(0),
            serialBytesFree = bufferValues?.getOrNull(1)
        )
    }

    private fun parseCoordinates(
        value: String
    ): List<Double?> {
        return value
            .split(",")
            .map { it.trim().toDoubleOrNull() }
    }
}