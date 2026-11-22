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

                field.startsWith("FS:") -> {

                    val values = field
                        .substringAfter("FS:")
                        .split(",")

                    feedRate =
                        values.getOrNull(0)?.toIntOrNull()

                    spindleSpeed =
                        values.getOrNull(1)?.toIntOrNull()
                }
            }
        }

        return GrblResponse.Status(
            state = state,
            machineX = x,
            machineY = y,
            machineZ = z,
            feedRate = feedRate,
            spindleSpeed = spindleSpeed
        )
    }
}