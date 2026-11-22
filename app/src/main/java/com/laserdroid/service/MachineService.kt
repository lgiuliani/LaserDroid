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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.laserdroid.MainActivity
import com.laserdroid.R
import com.laserdroid.communication.usb.UsbSerialConnection
import com.laserdroid.communication.usb.UsbSerialTransport
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.controllers.grbl.GrblController
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.machine.MachineSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MachineService : Service() {

    companion object {

        private const val NOTIFICATION_CHANNEL_ID =
            "laserdroid_machine"

        private const val NOTIFICATION_CHANNEL_NAME =
            "LaserDroid machine connection"

        private const val NOTIFICATION_ID =
            1001
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
        closeMachineBackend()

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

    private fun closeMachineBackend() {

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

        updateNotification(
            title =
                "LaserDroid machine",

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

        closeMachineBackend()

        _snapshot.value =
            MachineSnapshot.initial()

        serviceScope.cancel()

        super.onDestroy()
    }
}