package com.laserdroid.core.remote

import kotlinx.coroutines.flow.Flow

/**
 * How LaserGRBL is served over Wi-Fi.
 */
enum class RemoteMode {
    /**
     * Option B: LaserDroid behaves like GRBL, acknowledges and records
     * the program. The job is then started and followed on the phone.
     */
    RECEPTION,

    /**
     * Option A: transparent bridge, LaserGRBL drives the machine.
     */
    BRIDGE
}

/**
 * Real machine information returned to LaserGRBL in RECEPTION mode
 * (cached values: nothing is sent to the machine).
 */
interface MachineInfoProvider {
    /** e.g. "Grbl 1.1h ['$' for help]". */
    fun banner(): String

    /** "$0=10" ... (empty if unknown). */
    fun settingsLines(): List<String>

    /** "[VER:1.1h...]", "[OPT:...]". */
    fun buildInfoLines(): List<String>

    /** "[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]". */
    fun parserStateLine(): String

    /** "$#" response lines. */
    fun parameterLines(): List<String>

    /** "<state|MPos:...|FS:...|WCO:...>" with the real position. */
    fun statusReport(state: String): String
}

/**
 * Optional relay of manual commands from LaserGRBL to the real
 * machine (RECEPTION mode). Refused while a phone job is running.
 */
interface MachineRelay {
    /** "$J=..." -> "ok" or "error:N". */
    suspend fun jog(line: String): String

    suspend fun cancelJog()

    /** "$X" -> "ok" or "error:N". */
    suspend fun unlock(): String

    /** "Jog" or "Alarm" when meaningful for LaserGRBL, else null. */
    fun liveState(): String?
}

/**
 * Raw access to the serial link for BRIDGE mode.
 */
interface RawMachineLink {
    /** Bytes received from GRBL. */
    val incoming: Flow<ByteArray>

    /** Takes exclusive control. Returns a refusal reason or null. */
    fun acquire(): String?

    suspend fun write(data: ByteArray)

    /**
     * Releases control. [clientLost]: the PC disappeared without
     * closing the session (Wi-Fi loss).
     */
    suspend fun release(clientLost: Boolean)
}

/**
 * Configuration applied by [RemoteManager.apply].
 */
data class RemoteConfig(
    val enabled: Boolean = false,
    val mode: RemoteMode = RemoteMode.RECEPTION,
    val telnetPort: Int = 2323,
    val webSocketEnabled: Boolean = true,
    val webSocketPort: Int = 8181,
    val relayEnabled: Boolean = true,
    val idleTimeoutMillis: Long = 2_000L,
    val maxProgramBytes: Long = 200L * 1024L * 1024L
)

/**
 * State published for the UI.
 */
data class RemoteStatus(
    val enabled: Boolean = false,
    val mode: RemoteMode = RemoteMode.RECEPTION,
    val telnetPort: Int? = null,
    val webSocketPort: Int? = null,
    val clientAddress: String? = null,
    val clientProtocol: String? = null,
    val receiving: Boolean = false,
    val linesReceived: Long = 0L,
    val bytesReceived: Long = 0L,
    val programsReceived: Int = 0,
    val error: String? = null
) {
    val clientConnected: Boolean
        get() = clientAddress != null
}

/**
 * A complete program received from LaserGRBL.
 */
data class CapturedProgram(
    val file: java.io.File,
    val lines: Long,
    val bytes: Long,
    val motionLines: Long
)
