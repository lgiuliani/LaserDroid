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
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.controllers.grbl.GrblController
import com.laserdroid.core.gcode.FileGCodeSource
import com.laserdroid.core.gcode.GCodeAnalysis
import com.laserdroid.core.gcode.MachineProfile
import com.laserdroid.core.jobs.JobManager
import com.laserdroid.core.jobs.JobSnapshot
import com.laserdroid.core.jobs.JobState
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.machine.MachineSnapshot
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MachineService : Service() {

    companion object {

        private const val NOTIFICATION_CHANNEL_ID =
            "laserdroid_machine"

        private const val NOTIFICATION_CHANNEL_NAME =
            "LaserDroid machine connection"

        private const val NOTIFICATION_ID =
            1001

        private const val LIBRARY_DIRECTORY =
            "library"

        private const val WAKE_LOCK_TAG =
            "LaserDroid:job"
    }

    /*
     * ----------------------------------------------------
     * LOCAL BINDER
     * ----------------------------------------------------
     */

    inner class LocalBinder : Binder() {

        fun getService(): MachineService {
            return this@MachineService
        }
    }

    private val binder =
        LocalBinder()

    /*
     * ----------------------------------------------------
     * SERVICE SCOPE
     * ----------------------------------------------------
     */

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    /*
     * ----------------------------------------------------
     * USB BACKEND
     * ----------------------------------------------------
     */

    private lateinit var usbConnection:
            UsbSerialConnection

    private var usbTransport:
            UsbSerialTransport? = null

    /*
     * ----------------------------------------------------
     * GRBL / MACHINE BACKEND
     * ----------------------------------------------------
     */

    private var grblController:
            GrblController? = null

    private var machineManager:
            MachineManager? = null

    /*
     * Physical USB device currently associated
     * with the backend.
     */
    private var connectedDeviceId:
            Int? = null

    /*
     * Snapshot observer associated with the
     * current MachineManager.
     */
    private var snapshotObserverJob:
            Job? = null

    /*
     * ----------------------------------------------------
     * SERVICE SNAPSHOT
     * ----------------------------------------------------
     */

    private val _snapshot =
        MutableStateFlow(
            MachineSnapshot.initial()
        )

    val snapshot:
            StateFlow<MachineSnapshot> =
        _snapshot.asStateFlow()

    val manager: MachineManager?
        get() = machineManager

    val isMachineBackendCreated: Boolean
        get() = machineManager != null

    val isMachineConnected: Boolean
        get() = snapshot.value.isConnected

    /**
     * Current work coordinate offset (WCO).
     * Work position = machine position - offset.
     */
    val workCoordinateOffset: MachinePosition
        get() =
            grblController
                ?.workCoordinateOffset
                ?.value
                ?: MachinePosition.ZERO

    /*
     * ----------------------------------------------------
     * JOB MANAGER
     * ----------------------------------------------------
     *
     * Lives as long as the Service, independently of the
     * USB session. A lost connection marks the job
     * DISCONNECTED: it is never resumed automatically.
     */

    val jobManager =
        JobManager()

    val jobSnapshot:
            StateFlow<JobSnapshot>
        get() = jobManager.snapshot

    /*
     * Keeps the CPU running while a job is active,
     * screen off included.
     */
    private var jobWakeLock:
            PowerManager.WakeLock? = null

    private var jobObserverJob:
            Job? = null

    /*
     * ----------------------------------------------------
     * USB DETACH RECEIVER
     * ----------------------------------------------------
     */

    private val usbDetachReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                if (
                    intent.action !=
                    UsbManager.ACTION_USB_DEVICE_DETACHED
                ) {
                    return
                }

                val detachedDevice =
                    getUsbDeviceFromIntent(
                        intent
                    )
                        ?: return

                val activeDeviceId =
                    connectedDeviceId

                /*
                 * Ignore unrelated USB devices.
                 */
                if (
                    activeDeviceId == null ||
                    detachedDevice.deviceId !=
                    activeDeviceId
                ) {
                    return
                }

                serviceScope.launch {

                    handlePhysicalDisconnect(
                        detachedDevice
                    )
                }
            }
        }

    /*
     * ----------------------------------------------------
     * SERVICE CREATION
     * ----------------------------------------------------
     */

    override fun onCreate() {

        super.onCreate()

        usbConnection =
            UsbSerialConnection(
                applicationContext
            )

        registerUsbDetachReceiver()

        createNotificationChannel()

        startJobObserver()

        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title =
                    "LaserDroid",

                content =
                    "Machine service ready"
            )
        )
    }

    override fun onBind(
        intent: Intent?
    ): IBinder {

        return binder
    }

    /*
     * ----------------------------------------------------
     * USB DETACH MANAGEMENT
     * ----------------------------------------------------
     */

    private fun registerUsbDetachReceiver() {

        val filter =
            IntentFilter(
                UsbManager.ACTION_USB_DEVICE_DETACHED
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                usbDetachReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")

            registerReceiver(
                usbDetachReceiver,
                filter
            )
        }
    }

    private fun getUsbDeviceFromIntent(
        intent: Intent
    ): UsbDevice? {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            intent.getParcelableExtra(
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )

        } else {

            @Suppress("DEPRECATION")

            intent.getParcelableExtra(
                UsbManager.EXTRA_DEVICE
            )
        }
    }

    /*
     * ----------------------------------------------------
     * CONNECTION
     * ----------------------------------------------------
     */

    suspend fun connect(
        device: UsbDevice
    ): Result<Unit> {

        /*
         * Already connected.
         */
        if (
            machineManager != null &&
            snapshot.value.isConnected
        ) {

            return Result.success(
                Unit
            )
        }

        /*
         * Remove any stale backend from a previous
         * failed or physically interrupted session.
         */
        if (
            machineManager != null ||
            usbTransport != null ||
            grblController != null
        ) {

            closeMachineBackend()

            _snapshot.value =
                MachineSnapshot.initial()
        }

        /*
         * Publish CONNECTING before creating
         * MachineManager.
         *
         * This avoids exposing an old state while
         * the new physical connection is opening.
         */
        _snapshot.value =
            MachineSnapshot.initial()
                .copy(
                    machineState =
                        MachineState.Connecting
                )

        return runCatching {

            val transport =
                UsbSerialTransport(
                    connection =
                        usbConnection,

                    device =
                        device
                )

            val controller =
                GrblController(
                    transport
                )

            val manager =
                MachineManager(
                    controller =
                        controller,

                    statusPollIntervalMillis =
                        200L
                )

            usbTransport =
                transport

            grblController =
                controller

            machineManager =
                manager

            /*
             * Keep the device ID before connection
             * so a physical detach occurring during
             * connection can still be detected.
             */
            connectedDeviceId =
                device.deviceId

            startSnapshotObserver(
                manager
            )

            updateNotification(
                title =
                    "LaserDroid",

                content =
                    "Connecting to CNC..."
            )

            /*
             * This opens the USB transport,
             * connects GRBL and requests status.
             */
            manager.connect()

            jobManager.attachMachine(
                controller = controller,
                machine = manager
            )

            /*
             * Read the GRBL settings ($$) once: travel and
             * max rates are used by the G-code analysis.
             * A failure here does not fail the connection.
             */
            runCatching {
                manager.requestSettings()
            }

            Unit

        }.onSuccess {

            updateNotificationFromSnapshot(
                snapshot.value
            )

        }.onFailure {
                exception ->

            closeMachineBackend()

            _snapshot.value =
                MachineSnapshot.initial()

            updateNotification(
                title =
                    "LaserDroid",

                content =
                    buildString {

                        append(
                            "Machine connection failed"
                        )

                        exception.message
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                    message ->

                                append(
                                    ": "
                                )

                                append(
                                    message
                                )
                            }
                    }
            )
        }
    }

    /*
     * ----------------------------------------------------
     * EXPLICIT DISCONNECT
     * ----------------------------------------------------
     */

    suspend fun disconnect() {

        val manager =
            machineManager

        if (
            manager != null
        ) {

            runCatching {

                manager.disconnect()
            }
        }

        closeMachineBackend()

        _snapshot.value =
            MachineSnapshot.initial()

        updateNotification(
            title =
                "LaserDroid",

            content =
                "Machine disconnected"
        )
    }

    /*
     * ----------------------------------------------------
     * PHYSICAL USB DISCONNECT
     * ----------------------------------------------------
     */

    private suspend fun handlePhysicalDisconnect(
        detachedDevice: UsbDevice
    ) {

        /*
         * Immediately tell all UI clients that
         * the CNC is unavailable.
         */
        _snapshot.value =
            MachineSnapshot.initial()

        /*
         * Destroy the complete physical session.
         *
         * Reconnection will always create:
         *
         * new UsbSerialTransport
         * new GrblController
         * new MachineManager
         */
        closeMachineBackend(
            reason =
                "USB disconnected"
        )

        updateNotification(
            title =
                "LaserDroid",

            content =
                "USB disconnected " +
                        "(${detachedDevice.vendorId}:" +
                        "${detachedDevice.productId})"
        )

        /*
         * IMPORTANT:
         *
         * Do NOT reconnect automatically.
         * Do NOT resume a previous job.
         */
    }

    /*
     * ----------------------------------------------------
     * MACHINE SNAPSHOT
     * ----------------------------------------------------
     */

    private fun startSnapshotObserver(
        manager: MachineManager
    ) {

        snapshotObserverJob?.cancel()

        snapshotObserverJob =
            serviceScope.launch {

                manager.snapshot.collect {
                        value ->

                    /*
                     * IMPORTANT:
                     *
                     * MachineManager initially publishes
                     * DISCONNECTED before connect() has
                     * completed.
                     *
                     * Therefore a DISCONNECTED snapshot
                     * MUST NOT automatically destroy
                     * the backend here.
                     *
                     * Physical USB removal is handled
                     * separately by usbDetachReceiver.
                     */
                    _snapshot.value =
                        value

                    updateNotificationFromSnapshot(
                        value
                    )
                }
            }
    }

    /*
     * ----------------------------------------------------
     * BACKEND CLEANUP
     * ----------------------------------------------------
     */

    private fun closeMachineBackend(
        reason: String = "Machine disconnected"
    ) {

        /*
         * An active job becomes DISCONNECTED.
         * Never resumed automatically.
         */
        jobManager.detachMachine(
            reason
        )

        /*
         * Stop observing the old MachineManager.
         */
        snapshotObserverJob?.cancel()

        snapshotObserverJob =
            null

        /*
         * Stop manager-owned coroutines.
         */
        machineManager?.close()

        machineManager =
            null

        /*
         * Drop GRBL/transport references.
         */
        grblController =
            null

        usbTransport =
            null

        connectedDeviceId =
            null

        /*
         * Close the physical serial connection.
         *
         * This must tolerate the USB device already
         * having disappeared physically.
         */
        runCatching {

            usbConnection.close()
        }
    }

    /*
     * ----------------------------------------------------
     * G-CODE LIBRARY AND JOB LOADING
     * ----------------------------------------------------
     */

    /**
     * Copies the document selected with the Storage Access
     * Framework into the private library, then analyzes it.
     *
     * The copy keeps the program available even if the
     * temporary SAF permission is revoked.
     */
    suspend fun importAndLoad(
        uri: Uri
    ): Result<GCodeAnalysis> {

        val file =
            runCatching {
                withContext(Dispatchers.IO) {
                    copyToLibrary(uri)
                }
            }.getOrElse { exception ->
                return Result.failure(exception)
            }

        return loadLibraryFile(file)
    }

    suspend fun loadLibraryFile(
        file: File
    ): Result<GCodeAnalysis> {

        val profile =
            machineManager
                ?.settings
                ?.value
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    MachineProfile.fromGrblSettings(it)
                }

        return jobManager.load(
            newSource =
                FileGCodeSource(file),

            profile =
                profile
        )
    }

    private fun copyToLibrary(
        uri: Uri
    ): File {

        val directory =
            File(
                filesDir,
                LIBRARY_DIRECTORY
            ).apply {
                mkdirs()
            }

        val target =
            File(
                directory,
                sanitizeFileName(
                    queryDisplayName(uri)
                        ?: "program.nc"
                )
            )

        val temporary =
            File(
                directory,
                target.name + ".part"
            )

        contentResolver
            .openInputStream(uri)
            ?.use { input ->

                temporary
                    .outputStream()
                    .use { output ->
                        input.copyTo(
                            output,
                            bufferSize = 64 * 1024
                        )
                    }
            }
            ?: throw IllegalStateException(
                "Cannot open the selected file"
            )

        if (
            target.exists() &&
            !target.delete()
        ) {
            throw IllegalStateException(
                "Cannot replace ${target.name}"
            )
        }

        if (!temporary.renameTo(target)) {
            throw IllegalStateException(
                "Cannot save ${target.name}"
            )
        }

        return target
    }

    private fun queryDisplayName(
        uri: Uri
    ): String? {

        return runCatching {

            contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->

                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /*
     * Keeps only a safe file name: no path separators,
     * no hidden files.
     */
    private fun sanitizeFileName(
        name: String
    ): String {

        val cleaned =
            name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(
                    Regex("[^A-Za-z0-9._ -]"),
                    "_"
                )
                .trim()
                .trimStart('.')

        return cleaned.ifEmpty {
            "program.nc"
        }
    }

    /*
     * ----------------------------------------------------
     * JOB OBSERVATION: WAKE LOCK + NOTIFICATION
     * ----------------------------------------------------
     */

    private fun startJobObserver() {

        jobObserverJob?.cancel()

        jobObserverJob =
            serviceScope.launch {

                var lastState: JobState? = null
                var lastPercent = -1

                jobManager.snapshot.collect { job ->

                    updateJobWakeLock(
                        job.state.isActive
                    )

                    val percent =
                        job.percent.toInt()

                    /*
                     * Refresh the notification on state
                     * changes and every 1%, not on every
                     * acknowledged line.
                     */
                    if (
                        job.state != lastState ||
                        percent != lastPercent
                    ) {
                        lastState = job.state
                        lastPercent = percent

                        updateNotificationFromSnapshot(
                            snapshot.value
                        )
                    }
                }
            }
    }

    private fun updateJobWakeLock(
        active: Boolean
    ) {

        if (active) {

            if (jobWakeLock?.isHeld == true) {
                return
            }

            val powerManager =
                getSystemService(
                    POWER_SERVICE
                ) as PowerManager

            jobWakeLock =
                powerManager
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG
                    )
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }

        } else {

            releaseJobWakeLock()
        }
    }

    private fun releaseJobWakeLock() {

        runCatching {

            jobWakeLock
                ?.takeIf { it.isHeld }
                ?.release()
        }

        jobWakeLock =
            null
    }

    /*
     * ----------------------------------------------------
     * NOTIFICATION
     * ----------------------------------------------------
     */

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {

                description =
                    "LaserDroid CNC connection state"

                setShowBadge(
                    false
                )
            }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            channel
        )
    }

    private fun createNotification(
        title: String,
        content: String
    ): Notification {

        val openApplicationIntent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openApplicationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat
            .Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
            .setSmallIcon(
                R.drawable.ic_launcher_foreground
            )
            .setContentTitle(
                title
            )
            .setContentText(
                content
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(
                true
            )
            .setOnlyAlertOnce(
                true
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    private fun updateNotificationFromSnapshot(
        snapshot: MachineSnapshot
    ) {

        val machineStateText =
            when (
                snapshot.machineState
            ) {

                MachineState.Disconnected ->
                    "DISCONNECTED"

                MachineState.Connecting ->
                    "CONNECTING"

                MachineState.Idle ->
                    "IDLE"

                MachineState.Running ->
                    "RUNNING"

                MachineState.Jog ->
                    "JOG"

                MachineState.Hold ->
                    "HOLD"

                MachineState.Alarm ->
                    "ALARM"

                is MachineState.Error ->
                    "ERROR"
            }

        val content =
            buildString {

                append(
                    machineStateText
                )

                append(
                    " | X="
                )

                append(
                    formatCoordinate(
                        snapshot.position.x
                    )
                )

                append(
                    " Y="
                )

                append(
                    formatCoordinate(
                        snapshot.position.y
                    )
                )
            }

        val job =
            jobManager.snapshot.value

        val title =
            if (job.state.isActive) {
                String.format(
                    Locale.US,
                    "LaserDroid job %s %.1f%%",
                    job.state.name,
                    job.percent
                )
            } else {
                "LaserDroid machine"
            }

        updateNotification(
            title =
                title,

            content =
                content
        )
    }

    private fun updateNotification(
        title: String,
        content: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            createNotification(
                title =
                    title,

                content =
                    content
            )
        )
    }

    private fun formatCoordinate(
        value: Double
    ): String {

        return String.format(
            java.util.Locale.US,
            "%.3f",
            value
        )
    }

    /*
     * ----------------------------------------------------
     * SERVICE DESTRUCTION
     * ----------------------------------------------------
     */

    override fun onDestroy() {

        runCatching {

            unregisterReceiver(
                usbDetachReceiver
            )
        }

        closeMachineBackend(
            reason =
                "Service stopped"
        )

        jobObserverJob?.cancel()

        releaseJobWakeLock()

        jobManager.close()

        _snapshot.value =
            MachineSnapshot.initial()

        serviceScope.cancel()

        super.onDestroy()
    }
}