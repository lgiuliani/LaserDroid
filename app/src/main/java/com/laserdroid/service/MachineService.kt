package com.laserdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import com.laserdroid.MainActivity
import com.laserdroid.R
import com.laserdroid.communication.usb.UsbSerialConnection
import com.laserdroid.communication.usb.UsbSerialTransport
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.controllers.grbl.GrblController
import com.laserdroid.core.gcode.FileGCodeSource
import com.laserdroid.core.gcode.GCodeAnalysis
import com.laserdroid.core.gcode.MachineProfile
import com.laserdroid.core.jobs.JobManager
import com.laserdroid.core.jobs.JobSnapshot
import com.laserdroid.core.jobs.JobState
import com.laserdroid.core.jobs.WorkshopCommands
import com.laserdroid.core.machine.MachineEvent
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.machine.MachineOperationResult
import com.laserdroid.core.machine.MachineSnapshot
import com.laserdroid.core.remote.CapturedProgram
import com.laserdroid.core.remote.MachineInfoProvider
import com.laserdroid.core.remote.MachineRelay
import com.laserdroid.core.remote.RawMachineLink
import com.laserdroid.core.remote.RemoteConfig
import com.laserdroid.core.remote.RemoteManager
import com.laserdroid.core.remote.RemoteStatus
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service owning the machine backend:
 * USB transport, GRBL controller, MachineManager, JobManager,
 * logs, sounds, settings and job history.
 *
 * Survives Activity recreation and keeps running screen off.
 * Never reconnects or resumes a job automatically.
 */
class MachineService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "laserdroid_machine"
        private const val NOTIFICATION_CHANNEL_NAME = "LaserDroid machine connection"
        private const val NOTIFICATION_ID = 1001
        private const val LIBRARY_DIRECTORY = "library"
        private const val LOG_DIRECTORY = "logs"
        private const val WAKE_LOCK_TAG = "LaserDroid:job"

        /* The LASER button never stays on longer than this. */
        private const val POINTER_MAX_MILLIS = 30_000L

        /* LaserGRBL over Wi-Fi (ports >= 1024: no root needed). */
        const val TELNET_PORT = 2323
        const val WEBSOCKET_PORT = 8181
        private const val REMOTE_DIRECTORY = "remote"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MachineService = this@MachineService
    }

    private val binder = LocalBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /*
     * ----------------------------------------------------
     * BACKEND
     * ----------------------------------------------------
     */

    private lateinit var usbConnection: UsbSerialConnection
    private var usbTransport: UsbSerialTransport? = null
    private var grblController: GrblController? = null
    private var machineManager: MachineManager? = null
    private var connectedDeviceId: Int? = null

    /* Observers of the current MachineManager. */
    private var backendObserverJobs: List<Job> = emptyList()

    private val _snapshot = MutableStateFlow(MachineSnapshot.initial())
    val snapshot: StateFlow<MachineSnapshot> = _snapshot.asStateFlow()

    val manager: MachineManager?
        get() = machineManager

    val isMachineConnected: Boolean
        get() = snapshot.value.isConnected

    /** Work position = machine position - offset. */
    val workCoordinateOffset: MachinePosition
        get() = grblController?.workCoordinateOffset?.value ?: MachinePosition.ZERO

    /* Firmware identification ([VER:...]) for the history. */
    private var firmwareVersion: String? = null

    /* [VER:...] / [OPT:...] lines, returned to LaserGRBL ($I). */
    @Volatile
    private var cachedBuildInfo: List<String> = emptyList()

    /*
     * ----------------------------------------------------
     * JOBS, LOGS, SOUNDS, SETTINGS, HISTORY
     * ----------------------------------------------------
     */

    val jobManager = JobManager()

    val jobSnapshot: StateFlow<JobSnapshot>
        get() = jobManager.snapshot

    lateinit var logBook: LogBook
        private set

    private val soundPlayer = SoundPlayer()

    lateinit var settingsRepository: SettingsRepository
        private set

    val settings: StateFlow<AppSettings>
        get() = settingsRepository.settings

    private lateinit var historyStore: JobHistoryStore

    private var jobWakeLock: PowerManager.WakeLock? = null

    /* LASER button requests, applied in order (latest wins). */
    private val pointerRequests = Channel<Boolean>(Channel.CONFLATED)
    private var pointerWatchdog: Job? = null

    private var framingJob: Job? = null

    /* LaserGRBL over Wi-Fi. */
    private lateinit var remoteManager: RemoteManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var remoteWakeLock: PowerManager.WakeLock? = null

    val remoteStatus: StateFlow<RemoteStatus>
        get() = remoteManager.status

    /*
     * ----------------------------------------------------
     * USB DETACH
     * ----------------------------------------------------
     */

    private val usbDetachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device = getUsbDeviceFromIntent(intent) ?: return
                val activeId = connectedDeviceId ?: return
                if (device.deviceId != activeId) return
                serviceScope.launch { handlePhysicalDisconnect(device) }
            }
        }

    /*
     * ----------------------------------------------------
     * LIFECYCLE
     * ----------------------------------------------------
     */

    override fun onCreate() {
        super.onCreate()

        usbConnection = UsbSerialConnection(applicationContext)
        logBook = LogBook(File(filesDir, LOG_DIRECTORY))
        settingsRepository = SettingsRepository(applicationContext, serviceScope)
        historyStore = JobHistoryStore(applicationContext)

        registerUsbDetachReceiver()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification("LaserDroid", "Machine service ready")
        )

        remoteManager = RemoteManager(
            info = remoteMachineInfo,
            relay = remoteRelay,
            link = remoteLink,
            captureDirectory = File(cacheDir, REMOTE_DIRECTORY),
            onProgramReceived = { program -> onRemoteProgram(program) },
            log = { message -> logBook.info(message) }
        )

        observeSettings()
        observeJob()
        observeRemote()
        processPointerRequests()

        logBook.info("LaserDroid service started.")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbDetachReceiver) }
        remoteManager.close()
        releaseRemoteLocks()
        closeMachineBackend("Service stopped")
        releaseJobWakeLock()
        jobManager.close()
        soundPlayer.close()
        logBook.info("LaserDroid service stopped.")
        logBook.close()
        runCatching { historyStore.close() }
        _snapshot.value = MachineSnapshot.initial()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerUsbDetachReceiver() {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbDetachReceiver, filter)
        }
    }

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    /*
     * ----------------------------------------------------
     * SETTINGS
     * ----------------------------------------------------
     */

    private fun observeSettings() {
        serviceScope.launch {
            settings.collect { value ->
                logBook.level = value.logLevel
                soundPlayer.enabled = value.soundsEnabled
                // Read by BootReceiver, before DataStore is available.
                BootReceiver.setStartOnBoot(applicationContext, value.startOnBoot)
                remoteManager.apply(
                    RemoteConfig(
                        enabled = value.remoteEnabled,
                        mode = value.remoteMode,
                        telnetPort = TELNET_PORT,
                        webSocketEnabled = value.remoteWebSocket,
                        webSocketPort = WEBSOCKET_PORT,
                        relayEnabled = value.remoteRelay
                    )
                )
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        serviceScope.launch { settingsRepository.update(transform) }
    }

    fun playTestSound() {
        soundPlayer.play(SoundPlayer.Sound.SUCCESS)
    }

    /*
     * ----------------------------------------------------
     * CONNECTION
     * ----------------------------------------------------
     */

    suspend fun connect(device: UsbDevice): Result<Unit> {

        if (machineManager != null && snapshot.value.isConnected) {
            return Result.success(Unit)
        }

        if (machineManager != null || usbTransport != null || grblController != null) {
            closeMachineBackend("Stale backend")
            _snapshot.value = MachineSnapshot.initial()
        }

        _snapshot.value = MachineSnapshot.initial().copy(machineState = MachineState.Connecting)
        logBook.info("Connecting to ${device.vendorId}:${device.productId}...")

        return runCatching {
            val transport = UsbSerialTransport(connection = usbConnection, device = device)
            val controller = GrblController(transport)
            val manager = MachineManager(controller = controller, statusPollIntervalMillis = 200L)

            usbTransport = transport
            grblController = controller
            machineManager = manager
            connectedDeviceId = device.deviceId

            observeBackend(manager)
            updateNotification("LaserDroid", "Connecting to CNC...")

            manager.connect()

            jobManager.attachMachine(controller = controller, machine = manager)

            /*
             * Read $$ once: travel, max rates and $30 are used by
             * the analysis and the laser functions. GRBL may answer
             * with its startup banner first (USB reset): retry once.
             */
            var settingsResult = runCatching { manager.requestSettings() }.getOrNull()
            if (settingsResult != CommandResult.Acknowledged) {
                delay(1_500L)
                settingsResult = runCatching { manager.requestSettings() }.getOrNull()
            }
            if (settingsResult != CommandResult.Acknowledged) {
                logBook.warning("GRBL settings not read: $settingsResult")
            }
            // $I: firmware identification (history, LaserGRBL).
            runCatching { manager.requestBuildInfo() }
            Unit
        }.onSuccess {
            logBook.info("Machine connected.")
            soundPlayer.play(SoundPlayer.Sound.CONNECT)
            if (jobSnapshot.value.isRecoverable) {
                logBook.warning("Interrupted job found: Resume, Restart or Cancel in JOB.")
            }
            updateNotificationFromSnapshot(snapshot.value)
        }.onFailure { exception ->
            closeMachineBackend("Connection failed")
            _snapshot.value = MachineSnapshot.initial()
            logBook.error("Connection failed: ${exception.message ?: exception.javaClass.simpleName}")
            soundPlayer.play(SoundPlayer.Sound.ERROR)
            updateNotification("LaserDroid", "Machine connection failed")
        }
    }

    suspend fun disconnect() {
        machineManager?.let { manager -> runCatching { manager.disconnect() } }
        closeMachineBackend("Disconnected by user")
        _snapshot.value = MachineSnapshot.initial()
        logBook.info("Machine disconnected.")
        soundPlayer.play(SoundPlayer.Sound.DISCONNECT)
        updateNotification("LaserDroid", "Machine disconnected")
    }

    private fun handlePhysicalDisconnect(device: UsbDevice) {
        _snapshot.value = MachineSnapshot.initial()

        /*
         * Destroy the whole session. Reconnection always builds a
         * new transport, controller and MachineManager.
         * Never reconnect or resume automatically.
         */
        closeMachineBackend("USB disconnected")

        logBook.error("USB disconnected (${device.vendorId}:${device.productId}).")
        soundPlayer.play(SoundPlayer.Sound.DISCONNECT)
        updateNotification("LaserDroid", "USB disconnected")
    }

    private fun closeMachineBackend(reason: String) {
        // An active job becomes DISCONNECTED, never resumed automatically.
        jobManager.detachMachine(reason)

        // A bridged PC loses its machine.
        if (remoteBridgeActive) {
            remoteManager.disconnectClient()
        }

        pointerWatchdog?.cancel()
        framingJob?.cancel()

        backendObserverJobs.forEach { it.cancel() }
        backendObserverJobs = emptyList()

        machineManager?.close()
        machineManager = null
        grblController = null
        usbTransport = null
        connectedDeviceId = null
        firmwareVersion = null

        // Tolerates a device that already disappeared.
        runCatching { usbConnection.close() }
    }

    /*
     * ----------------------------------------------------
     * BACKEND OBSERVATION: SNAPSHOT, LOGS, SOUNDS
     * ----------------------------------------------------
     */

    private fun observeBackend(manager: MachineManager) {
        backendObserverJobs.forEach { it.cancel() }

        var previousState: MachineState? = null

        val snapshotJob = serviceScope.launch {
            manager.snapshot.collect { value ->
                /*
                 * MachineManager publishes DISCONNECTED before
                 * connect() completes: never destroy the backend
                 * here. Physical removal is handled by the receiver.
                 */
                _snapshot.value = value
                updateNotificationFromSnapshot(value)

                val state = value.machineState
                if (state != previousState) {
                    if (state == MachineState.Alarm && previousState != null) {
                        logBook.error("Machine in ALARM: tap the state or UNLOCK.")
                        if (!jobManager.snapshot.value.state.isActive) {
                            soundPlayer.play(SoundPlayer.Sound.ERROR)
                        }
                    }
                    previousState = state
                }
            }
        }

        val machineEventsJob = serviceScope.launch {
            manager.events.collect { event ->
                when (event) {
                    MachineEvent.Connected -> Unit
                    MachineEvent.Disconnected -> Unit
                    is MachineEvent.JogStarted -> logBook.debug(event.description)
                    is MachineEvent.PositionUpdated -> Unit
                    is MachineEvent.JogCompleted -> logBook.debug(
                        "Move completed: X=${formatCoordinate(event.position.x)}, " +
                                "Y=${formatCoordinate(event.position.y)}"
                    )
                    MachineEvent.JogCancelled -> logBook.debug("Jog cancelled.")
                    is MachineEvent.Error -> logBook.error("Machine error: ${event.message}")
                }
            }
        }

        /* Detailed GRBL output: $0=10, [VER:...], error:..., ALARM:... */
        val controllerEventsJob = serviceScope.launch {
            manager.controllerEvents.collect { event ->
                when (event) {
                    is ControllerEvent.SettingReceived ->
                        logBook.info("\$${event.number}=${event.value}")
                    is ControllerEvent.MessageReceived -> {
                        if (event.message.startsWith("[VER:")) {
                            firmwareVersion = event.message.removePrefix("[VER:").removeSuffix("]")
                            cachedBuildInfo = listOf(event.message)
                        } else if (event.message.startsWith("[OPT:")) {
                            cachedBuildInfo = cachedBuildInfo.filter { it.startsWith("[VER:") } +
                                    event.message
                        }
                        logBook.info(event.message)
                    }
                    is ControllerEvent.ErrorReceived ->
                        logBook.error("GRBL error:${event.code ?: "?"}")
                    is ControllerEvent.AlarmReceived ->
                        logBook.error("GRBL ALARM:${event.code ?: "?"}")
                    is ControllerEvent.ResetDetected ->
                        logBook.warning("GRBL restarted: ${event.banner}")
                    ControllerEvent.Connected,
                    ControllerEvent.Disconnected -> Unit
                }
            }
        }

        backendObserverJobs = listOf(snapshotJob, machineEventsJob, controllerEventsJob)
    }

    /*
     * ----------------------------------------------------
     * JOB OBSERVATION: WAKE LOCK, NOTIFICATION, LOG, SOUND, HISTORY
     * ----------------------------------------------------
     */

    private fun observeJob() {
        serviceScope.launch {
            var lastState: JobState? = null
            var lastPercent = -1
            var lastSnapshot: JobSnapshot? = null

            jobManager.snapshot.collect { job ->
                updateJobWakeLock(job.state.isActive)

                val previous = lastSnapshot
                if (job.state != lastState) {
                    onJobStateChanged(previous, job)
                }

                val percent = job.percent.toInt()
                if (job.state != lastState || percent != lastPercent) {
                    lastState = job.state
                    lastPercent = percent
                    updateNotificationFromSnapshot(snapshot.value)
                }
                lastSnapshot = job
            }
        }
    }

    private fun onJobStateChanged(previous: JobSnapshot?, job: JobSnapshot) {
        if (previous == null) return

        val message = job.message?.let { ": $it" }.orEmpty()
        when (job.state) {
            JobState.READY -> job.analysis?.let { analysis ->
                logBook.info(
                    "Loaded ${analysis.name}: ${analysis.executableCommands} commands, " +
                            "errors ${analysis.errorCount}, warnings ${analysis.warningCount}"
                )
                analysis.warnings.take(10).forEach { warning ->
                    logBook.log(
                        if (warning.severity.name == "ERROR") LogLevel.ERROR else LogLevel.WARNING,
                        "  L${warning.lineNumber}: ${warning.message}"
                    )
                }
                when {
                    analysis.errorCount > 0 -> soundPlayer.play(SoundPlayer.Sound.ERROR)
                    analysis.warningCount > 0 -> soundPlayer.play(SoundPlayer.Sound.WARNING)
                }
            }
            JobState.RUNNING ->
                if (previous.state == JobState.STARTING) {
                    logBook.info(
                        "Job ${job.name} started" + if (job.laserOff) " (LASER OFF)" else " (LASER ON)"
                    )
                } else {
                    logBook.info("Job running")
                }
            JobState.PAUSED -> {
                logBook.warning("Job paused (feed hold)")
                soundPlayer.play(SoundPlayer.Sound.WARNING)
            }
            JobState.COMPLETED -> {
                logBook.info("JOB COMPLETED$message")
                soundPlayer.play(SoundPlayer.Sound.SUCCESS)
            }
            JobState.ERROR -> {
                logBook.error("JOB ERROR$message")
                soundPlayer.play(SoundPlayer.Sound.ERROR)
            }
            JobState.ABORTED -> {
                logBook.warning("JOB ABORTED$message")
                soundPlayer.play(SoundPlayer.Sound.WARNING)
            }
            JobState.DISCONNECTED -> logBook.error("JOB INTERRUPTED$message")
            else -> logBook.debug("Job ${job.state.name}$message")
        }

        if (previous.state.isActive && !job.state.isActive) {
            recordHistory(job)
        }
    }

    private fun recordHistory(job: JobSnapshot) {
        val started = job.startedAtMillis ?: return
        val analysis = job.analysis
        val bounds = analysis?.workBounds ?: analysis?.totalBounds
        val entry = JobHistoryEntry(
            name = job.name ?: "?",
            sizeBytes = analysis?.sizeBytes ?: -1L,
            commands = job.totalCommands,
            width = bounds?.width,
            height = bounds?.height,
            estimatedMillis = analysis?.estimatedDurationMillis ?: 0L,
            machine = firmwareVersion,
            startedAt = started,
            endedAt = job.endedAtMillis ?: System.currentTimeMillis(),
            state = job.state.name,
            percent = job.percent,
            laserOff = job.laserOff,
            message = job.message
        )
        serviceScope.launch {
            runCatching { historyStore.insert(entry) }
                .onFailure { logBook.error("History not saved: ${it.message}") }
        }
    }

    suspend fun loadHistory(): List<JobHistoryEntry> =
        withContext(Dispatchers.IO) { runCatching { historyStore.recent() }.getOrDefault(emptyList()) }

    suspend fun clearHistory() {
        withContext(Dispatchers.IO) { runCatching { historyStore.clear() } }
        logBook.info("History cleared.")
    }

    private fun updateJobWakeLock(active: Boolean) {
        if (!active) {
            releaseJobWakeLock()
            return
        }
        if (jobWakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        jobWakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseJobWakeLock() {
        runCatching { jobWakeLock?.takeIf { it.isHeld }?.release() }
        jobWakeLock = null
    }

    /*
     * ----------------------------------------------------
     * WORKSHOP: FRAMING, LASER POINTER, WORK ZERO
     * ----------------------------------------------------
     */

    /**
     * Traces the rectangle of the loaded program with the laser at
     * the configured low power. Returns a refusal reason or null.
     */
    fun startFraming(): String? {
        val activeManager = machineManager ?: return "Machine is not connected"
        val job = jobSnapshot.value
        if (job.state.isActive) return "A job is running"
        val analysis = job.analysis ?: return "No program loaded"
        val bounds = analysis.workBounds ?: analysis.totalBounds ?: return "Program has no moves"
        if (!activeManager.snapshot.value.canJog) {
            return "Machine is not Idle"
        }
        if (framingJob?.isActive == true) return "Framing already running"

        val current = settings.value
        val power = WorkshopCommands.powerValue(
            current.lowPowerPercent,
            activeManager.maxLaserPower
        )
        val commands = WorkshopCommands.framing(bounds, current.framingFeedRate, power)

        logBook.info(
            String.format(
                Locale.US,
                "Framing %.1f x %.1f mm, laser %.0f%% (S%s)",
                bounds.width,
                bounds.height,
                current.lowPowerPercent,
                WorkshopCommands.format(power)
            )
        )

        framingJob = serviceScope.launch {
            val result = activeManager.runFraming(
                commands = commands,
                pathLengthMillimeters = WorkshopCommands.framingLength(bounds),
                feedRate = current.framingFeedRate
            )
            when (result) {
                is MachineOperationResult.Completed -> logBook.info("Framing completed.")
                else -> {
                    logBook.warning("Framing interrupted: $result")
                    soundPlayer.play(SoundPlayer.Sound.WARNING)
                }
            }
        }
        return null
    }

    val isFraming: Boolean
        get() = machineManager?.framingActive?.value == true

    fun stopFraming() {
        val activeManager = machineManager ?: return
        serviceScope.launch {
            runCatching { activeManager.stopFraming() }
            logBook.warning("Framing stopped.")
        }
    }

    /**
     * Hold-to-fire LASER button: on while pressed, at the configured
     * power, with a safety limit of 30 s.
     */
    fun laserPointer(on: Boolean) {
        pointerRequests.trySend(on)
    }

    private fun processPointerRequests() {
        serviceScope.launch {
            for (on in pointerRequests) {
                val activeManager = machineManager ?: continue
                if (on) {
                    val percent = settings.value.lowPowerPercent
                    val result = runCatching { activeManager.laserPointerOn(percent) }.getOrNull()
                    if (result == CommandResult.Acknowledged) {
                        logBook.warning(
                            String.format(Locale.US, "LASER ON %.0f%%", percent)
                        )
                        pointerWatchdog?.cancel()
                        pointerWatchdog = serviceScope.launch {
                            delay(POINTER_MAX_MILLIS)
                            logBook.warning("LASER safety timeout: off.")
                            pointerRequests.trySend(false)
                        }
                    } else {
                        logBook.warning("LASER refused: $result")
                    }
                } else {
                    pointerWatchdog?.cancel()
                    pointerWatchdog = null
                    runCatching { activeManager.laserPointerOff() }
                    logBook.info("LASER OFF")
                }
            }
        }
    }

    /**
     * Moves the laser (off) to the center of the loaded program,
     * X/Y only. Returns a refusal reason or null.
     */
    fun moveToJobCenter(): String? {
        val activeManager = machineManager ?: return "Machine is not connected"
        val job = jobSnapshot.value
        if (job.state.isActive) return "A job is running"
        val analysis = job.analysis ?: return "No program loaded"
        val bounds = analysis.workBounds ?: analysis.totalBounds ?: return "Program has no moves"
        if (!activeManager.snapshot.value.canJog) return "Machine is not Idle"

        val (x, y) = WorkshopCommands.center(bounds)
        val feed = settings.value.framingFeedRate
        logBook.info(String.format(Locale.US, "Move to job center X%.3f Y%.3f", x, y))
        serviceScope.launch {
            val result = runCatching { activeManager.goTo(x, y, feed) }.getOrNull()
            if (result !is MachineOperationResult.Completed) {
                logBook.warning("Move to center: $result")
            }
        }
        return null
    }

    suspend fun setWorkZero(includeZ: Boolean = false): CommandResult {
        val activeManager = machineManager ?: return CommandResult.Disconnected
        val result = activeManager.setWorkZero(includeZ)
        if (result == CommandResult.Acknowledged) {
            logBook.info(
                "Work origin set: current position = " +
                        (if (includeZ) "X0 Y0 Z0" else "X0 Y0") + " (G10 L20 P1)."
            )
        } else {
            logBook.warning("Set zero refused: $result")
        }
        return result
    }

    /*
     * ----------------------------------------------------
     * LASERGRBL OVER WI-FI
     * ----------------------------------------------------
     */

    @Volatile
    private var remoteBridgeActive = false

    /* Cached real values returned to LaserGRBL (RECEPTION). */
    private val remoteMachineInfo = object : MachineInfoProvider {

        override fun banner(): String {
            // "[VER:1.1h.20190825:]" -> "1.1h"
            val version = cachedBuildInfo
                .firstOrNull { it.startsWith("[VER:") }
                ?.removePrefix("[VER:")
                ?.split('.')
                ?.take(2)
                ?.joinToString(".")
                ?.takeIf { it.isNotEmpty() }
                ?: "1.1h"
            return "Grbl $version ['\$' for help]"
        }

        override fun settingsLines(): List<String> =
            machineManager?.settings?.value
                ?.toSortedMap()
                ?.map { (number, value) -> "\$$number=$value" }
                .orEmpty()

        override fun buildInfoLines(): List<String> =
            cachedBuildInfo.ifEmpty { listOf("[VER:1.1h.LaserDroid:]", "[OPT:V,15,128]") }

        override fun parserStateLine(): String =
            "[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]"

        override fun parameterLines(): List<String> {
            val offset = workCoordinateOffset
            val zero = "0.000,0.000,0.000"
            return listOf(
                "[G54:${formatTriple(offset)}]",
                "[G55:$zero]", "[G56:$zero]", "[G57:$zero]", "[G58:$zero]", "[G59:$zero]",
                "[G28:$zero]", "[G30:$zero]", "[G92:$zero]", "[TLO:0.000]", "[PRB:$zero:0]"
            )
        }

        override fun statusReport(state: String): String {
            val position = snapshot.value.position
            return "<$state|MPos:${formatTriple(position)}|FS:0,0|WCO:${formatTriple(workCoordinateOffset)}>"
        }
    }

    /* Manual commands from LaserGRBL (RECEPTION, if enabled). */
    private val remoteRelay = object : MachineRelay {

        override suspend fun jog(line: String): String =
            toGrblAnswer(machineManager?.relayJog(line))

        override suspend fun cancelJog() {
            runCatching { machineManager?.cancelJog() }
        }

        override suspend fun unlock(): String =
            toGrblAnswer(machineManager?.unlock())

        override fun liveState(): String? =
            when (machineManager?.snapshot?.value?.machineState) {
                MachineState.Jog -> "Jog"
                else -> null
            }

        private fun toGrblAnswer(result: CommandResult?): String =
            when (result) {
                CommandResult.Acknowledged -> "ok"
                is CommandResult.Rejected -> "error:${result.code ?: 9}"
                else -> "error:9"
            }
    }

    /* Raw serial access (BRIDGE). */
    private val remoteLink = object : RawMachineLink {

        override val incoming: Flow<ByteArray>
            get() = usbTransport?.incomingData ?: emptyFlow()

        override fun acquire(): String? {
            val activeManager = machineManager ?: return "machine not connected"
            if (jobSnapshot.value.state.isActive) return "a job is running on LaserDroid"
            if (!activeManager.beginRemoteControl()) return "machine busy"
            remoteBridgeActive = true
            logBook.warning("BRIDGE: LaserGRBL drives the machine. Phone controls locked.")
            return null
        }

        override suspend fun write(data: ByteArray) {
            usbTransport?.write(data) ?: throw IllegalStateException("USB not connected")
        }

        override suspend fun release(clientLost: Boolean) {
            val activeManager = machineManager
            val state = activeManager?.snapshot?.value?.machineState
            if (
                clientLost &&
                settings.value.remotePauseOnLoss &&
                (state == MachineState.Running || state == MachineState.Jog)
            ) {
                runCatching { usbTransport?.write("!".toByteArray()) }
                logBook.warning("PC lost while moving: feed hold sent (RESET or ~ from the console).")
                soundPlayer.play(SoundPlayer.Sound.WARNING)
            }
            remoteBridgeActive = false
            activeManager?.endJob()
            logBook.info("BRIDGE released: phone controls available.")
        }
    }

    private fun observeRemote() {
        serviceScope.launch {
            var wasConnected = false
            remoteManager.status.collect { status ->
                updateRemoteLocks(status)
                if (status.clientConnected != wasConnected) {
                    wasConnected = status.clientConnected
                    soundPlayer.play(
                        if (status.clientConnected) SoundPlayer.Sound.CONNECT
                        else SoundPlayer.Sound.DISCONNECT
                    )
                }
            }
        }
    }

    /*
     * Wi-Fi kept in high performance mode while the servers run,
     * CPU kept awake while a PC is connected.
     */
    private fun updateRemoteLocks(status: RemoteStatus) {
        if (status.enabled) {
            if (wifiLock?.isHeld != true) {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LaserDroid:remote")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wifiLock = null
        }

        if (status.clientConnected) {
            if (remoteWakeLock?.isHeld != true) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                remoteWakeLock = powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LaserDroid:remote-client")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            runCatching { remoteWakeLock?.takeIf { it.isHeld }?.release() }
            remoteWakeLock = null
        }
    }

    private fun releaseRemoteLocks() {
        updateRemoteLocks(RemoteStatus())
    }

    /*
     * Program received from LaserGRBL: stored in the library and
     * loaded (READY). Never started automatically.
     */
    private suspend fun onRemoteProgram(program: CapturedProgram) {
        val name = "lasergrbl_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".nc"
        val target = File(libraryDirectory, name)
        val moved = withContext(Dispatchers.IO) {
            program.file.renameTo(target) ||
                    runCatching { program.file.copyTo(target, overwrite = true); program.file.delete() }.isSuccess
        }
        if (!moved) {
            logBook.error("Received program could not be saved.")
            return
        }
        soundPlayer.play(SoundPlayer.Sound.RECEIVED)
        logBook.info("LaserGRBL program saved: $name (${program.lines} lines)")

        if (jobSnapshot.value.state.isActive) {
            logBook.warning("A job is running: $name kept in the library.")
            return
        }
        loadLibraryFile(target)
    }

    /** IPv4 addresses of this phone (Wi-Fi, hotspot). */
    fun localIpAddresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network -> network.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }

    fun disconnectRemoteClient() {
        remoteManager.disconnectClient()
    }

    private fun formatTriple(position: MachinePosition): String =
        String.format(Locale.US, "%.3f,%.3f,%.3f", position.x, position.y, position.z)

    /*
     * ----------------------------------------------------
     * G-CODE LIBRARY
     * ----------------------------------------------------
     */

    private val libraryDirectory: File
        get() = File(filesDir, LIBRARY_DIRECTORY).apply { mkdirs() }

    /** Library files, most recent first. */
    fun libraryFiles(): List<File> {
        return libraryDirectory
            .listFiles { file -> file.isFile && !file.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun deleteLibraryFiles(files: List<File>): Int {
        val directory = libraryDirectory.canonicalFile
        var deleted = 0
        files.forEach { file ->
            // Never delete outside the library.
            if (file.canonicalFile.parentFile == directory && file.delete()) {
                deleted++
            }
        }
        logBook.info("$deleted file(s) deleted from the library.")
        return deleted
    }

    /**
     * Copies a document chosen with the Storage Access Framework
     * into the private library, then analyzes it.
     */
    suspend fun importAndLoad(uri: Uri): Result<GCodeAnalysis> {
        val file = runCatching {
            withContext(Dispatchers.IO) { copyToLibrary(uri) }
        }.getOrElse { exception ->
            logBook.error("Import failed: ${exception.message}")
            return Result.failure(exception)
        }
        return loadLibraryFile(file)
    }

    suspend fun loadLibraryFile(file: File): Result<GCodeAnalysis> {
        logBook.info("Loading ${file.name}...")
        val profile = machineManager
            ?.settings
            ?.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { MachineProfile.fromGrblSettings(it) }
        // Touch: most recent first in the library.
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        val result = jobManager.load(newSource = FileGCodeSource(file), profile = profile)
        result.onFailure { logBook.error("Load failed: ${it.message}") }
        return result
    }

    private fun copyToLibrary(uri: Uri): File {
        val directory = libraryDirectory
        val target = File(directory, sanitizeFileName(queryDisplayName(uri) ?: "program.nc"))
        val temporary = File(directory, target.name + ".part")

        contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: throw IllegalStateException("Cannot open the selected file")

        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Cannot replace ${target.name}")
        }
        if (!temporary.renameTo(target)) {
            throw IllegalStateException("Cannot save ${target.name}")
        }
        return target
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /* Only a safe file name: no path separators, no hidden files. */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trimStart('.')
        return cleaned.ifEmpty { "program.nc" }
    }

    /*
     * ----------------------------------------------------
     * NOTIFICATION
     * ----------------------------------------------------
     */

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "LaserDroid CNC connection state"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotificationFromSnapshot(machine: MachineSnapshot) {
        val stateText = when (val state = machine.machineState) {
            MachineState.Disconnected -> "DISCONNECTED"
            MachineState.Connecting -> "CONNECTING"
            MachineState.Idle -> "IDLE"
            MachineState.Running -> "RUNNING"
            MachineState.Jog -> "JOG"
            MachineState.Hold -> "HOLD"
            MachineState.Alarm -> "ALARM"
            is MachineState.Error -> "ERROR ${state.message}"
        }
        val content = "$stateText | X=${formatCoordinate(machine.position.x)} " +
                "Y=${formatCoordinate(machine.position.y)}"

        val job = jobManager.snapshot.value
        val title = if (job.state.isActive) {
            String.format(Locale.US, "LaserDroid job %s %.1f%%", job.state.name, job.percent)
        } else {
            "LaserDroid machine"
        }
        updateNotification(title, content)
    }

    private fun updateNotification(title: String, content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.3f", value)
}
