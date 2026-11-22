package com.laserdroid

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.machine.MachineEvent
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.machine.MachineOperationResult
import com.laserdroid.service.MachineService
import com.laserdroid.service.MachineServiceConnection
import com.laserdroid.ui.jog.JogDirection
import com.laserdroid.ui.jog.JogPanelView
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : Activity() {

    companion object {

        private const val ACTION_USB_PERMISSION =
            "com.laserdroid.USB_PERMISSION"

        private const val CONTINUOUS_JOG_INTERVAL_MS =
            150L

        private const val SAVED_LOG =
            "laserdroid.saved.log"

        private const val SAVED_JOG_SPEED =
            "laserdroid.saved.jog.speed"

        private const val SAVED_JOG_STEP =
            "laserdroid.saved.jog.step"

        private const val MAX_LOG_CHARACTERS =
            100_000

        /*
         * Sculpfun S10 CH34x device identified
         * during the physical tests.
         *
         * Vendor ID:
         * 6790 decimal = 0x1A86
         *
         * Product ID:
         * 29987 decimal = 0x7523
         */
        private const val SCULPFUN_VENDOR_ID =
            6790

        private const val SCULPFUN_PRODUCT_ID =
            29987
    }

    private val activityScope =
        MainScope()

    private lateinit var resultText:
            TextView

    private lateinit var logScrollView:
            ScrollView

    private lateinit var jogPanel:
            JogPanelView

    private lateinit var usbManager:
            UsbManager

    private var pendingUsbDevice:
            UsbDevice? = null

    private var machineService:
            MachineService? = null

    private var serviceBound =
        false

    private var serviceObserverJob:
            Job? = null

    private var managerSnapshotJob:
            Job? = null

    private var managerEventsJob:
            Job? = null

    private var controllerEventsJob:
            Job? = null

    private var continuousJogJob:
            Job? = null

    private lateinit var machineServiceConnection:
            MachineServiceConnection

    /*
     * ----------------------------------------------------
     * USB PERMISSION RECEIVER
     * ----------------------------------------------------
     */

    private val usbPermissionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                if (
                    intent.action !=
                    ACTION_USB_PERMISSION
                ) {
                    return
                }

                val granted =
                    intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )

                val device =
                    getUsbDeviceFromIntent(intent)
                        ?: pendingUsbDevice

                pendingUsbDevice =
                    null

                if (
                    !granted ||
                    device == null
                ) {

                    appendLog(
                        "USB permission denied."
                    )

                    return
                }

                appendLog(
                    "USB permission granted."
                )

                /*
                 * The Service owns the real USB
                 * connection. The Activity only
                 * transmits the authorized device.
                 */
                connectAuthorizedDevice(
                    device
                )
            }
        }

    /*
     * ----------------------------------------------------
     * ACTIVITY CREATION
     * ----------------------------------------------------
     */

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        /*
         * Keep the display on only while this
         * Activity is visible in the foreground.
         */
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(
            R.layout.activity_main
        )

        bindViews()

        initializeUsbManager()

        registerUsbPermissionReceiver()

        configureButtons()

        configureJogPanel()

        restoreUiState(
            savedInstanceState
        )

        /*
         * Temporary initial state.
         *
         * This will be replaced by the Service
         * snapshot as soon as binding completes.
         */
        jogPanel.updateMachineState(
            MachineState.Disconnected
        )

        initializeMachineService()
    }

    /*
     * ----------------------------------------------------
     * VIEW INITIALIZATION
     * ----------------------------------------------------
     */

    private fun bindViews() {

        resultText =
            findViewById(
                R.id.textUsbDevices
            )

        logScrollView =
            findViewById(
                R.id.logScrollView
            )

        jogPanel =
            findViewById(
                R.id.jogPanel
            )
    }

    private fun configureButtons() {

        findViewById<Button>(
            R.id.buttonDetectUsb
        ).setOnClickListener {

            detectUsbDevices()
        }

        findViewById<Button>(
            R.id.buttonConnectUsb
        ).setOnClickListener {

            connectUsbSerial()
        }

        findViewById<Button>(
            R.id.buttonStatus
        ).setOnClickListener {

            requestGrblStatus()
        }

        findViewById<Button>(
            R.id.buttonSettings
        ).setOnClickListener {

            requestGrblSettings()
        }

        findViewById<Button>(
            R.id.buttonBuildInfo
        ).setOnClickListener {

            requestGrblBuildInfo()
        }

        findViewById<Button>(
            R.id.buttonClearLog
        ).setOnClickListener {

            clearLog()
        }
    }

    private fun configureJogPanel() {

        jogPanel.onJogClick =
            {
                    direction,
                    step,
                    speed ->

                executeDirectionalJog(
                    direction = direction,
                    step = step,
                    speed = speed
                )
            }

        jogPanel.onContinuousJogStart =
            {
                    direction,
                    step,
                    speed ->

                startContinuousJog(
                    direction = direction,
                    step = step,
                    speed = speed
                )
            }

        jogPanel.onContinuousJogStop = {

            stopContinuousJog()
        }

        /*
         * HOME uses the current speed from
         * the left slider.
         *
         * The step slider is ignored.
         */
        jogPanel.onHomeRequested =
            { speed ->

                executeJogHome(
                    speed = speed
                )
            }

        jogPanel.onCancelRequested = {

            cancelJog()
        }
    }

    /*
     * ----------------------------------------------------
     * MACHINE SERVICE
     * ----------------------------------------------------
     */

    private fun initializeMachineService() {

        machineServiceConnection =
            MachineServiceConnection(

                onConnected =
                    { service ->

                        machineService =
                            service

                        serviceBound =
                            true

                        appendLog(
                            "Machine service connected."
                        )

                        observeMachineService(
                            service
                        )
                    },

                onDisconnected =
                    {

                        serviceBound =
                            false

                        machineService =
                            null

                        cancelBackendObservers()

                        jogPanel.updateMachineState(
                            MachineState.Disconnected
                        )

                        appendLog(
                            "Machine service disconnected."
                        )
                    }
            )

        val serviceIntent =
            Intent(
                this,
                MachineService::class.java
            )

        /*
         * Start the Foreground Service.
         */
        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )

        /*
         * Bind the Activity to the same Service.
         */
        bindService(
            serviceIntent,
            machineServiceConnection,
            BIND_AUTO_CREATE
        )
    }

    private fun observeMachineService(
        service: MachineService
    ) {

        serviceObserverJob?.cancel()

        serviceObserverJob =
            activityScope.launch {

                service.snapshot.collect {
                        snapshot ->

                    jogPanel.updateMachineState(
                        snapshot.machineState
                    )

                    jogPanel.updateMachinePosition(
                        snapshot.position
                    )
                }
            }

        /*
         * If a backend already exists because
         * the Activity was recreated after a
         * rotation, attach to it immediately.
         */
        service.manager?.let {
                manager ->

            observeMachineManager(
                manager
            )
        }
    }

    private fun observeMachineManager(
        manager: MachineManager
    ) {

        managerSnapshotJob?.cancel()
        managerEventsJob?.cancel()
        controllerEventsJob?.cancel()

        managerSnapshotJob =
            activityScope.launch {

                manager.snapshot.collect {
                        snapshot ->

                    jogPanel.updateMachineState(
                        snapshot.machineState
                    )

                    jogPanel.updateMachinePosition(
                        snapshot.position
                    )
                }
            }

        managerEventsJob =
            activityScope.launch {

                manager.events.collect {
                        event ->

                    when (event) {

                        MachineEvent.Connected -> {

                            appendLog(
                                "Machine connected."
                            )
                        }

                        MachineEvent.Disconnected -> {

                            appendLog(
                                "Machine disconnected."
                            )
                        }

                        is MachineEvent.JogStarted -> {

                            appendLog(
                                event.description
                            )
                        }

                        is MachineEvent.PositionUpdated -> {

                            appendLog(
                                "Position: " +
                                        "X=${formatCoordinate(event.position.x)}, " +
                                        "Y=${formatCoordinate(event.position.y)}"
                            )
                        }

                        is MachineEvent.JogCompleted -> {

                            appendLog(
                                "Jog completed: " +
                                        "X=${formatCoordinate(event.position.x)}, " +
                                        "Y=${formatCoordinate(event.position.y)}"
                            )
                        }

                        MachineEvent.JogCancelled -> {

                            appendLog(
                                "Jog cancelled."
                            )
                        }

                        is MachineEvent.Error -> {

                            appendLog(
                                "Machine error: ${event.message}"
                            )
                        }
                    }
                }
            }

        /*
         * Preserve detailed GRBL output such as:
         *
         * $0=10
         * [VER:...]
         * error:...
         * ALARM:...
         */
        controllerEventsJob =
            activityScope.launch {

                manager.controllerEvents.collect {
                        event ->

                    when (event) {

                        is ControllerEvent.SettingReceived -> {

                            appendLog(
                                $$"$$${event.number}=$${event.value}"
                            )
                        }

                        is ControllerEvent.MessageReceived -> {

                            appendLog(
                                event.message
                            )
                        }

                        is ControllerEvent.ErrorReceived -> {

                            appendLog(
                                "GRBL ERROR: " +
                                        "${event.code} " +
                                        event.message
                            )
                        }

                        is ControllerEvent.AlarmReceived -> {

                            appendLog(
                                "GRBL ALARM: " +
                                        "${event.code} " +
                                        event.message
                            )
                        }

                        ControllerEvent.Connected,
                        ControllerEvent.Disconnected -> {

                            /*
                             * Already represented by
                             * MachineEvent.
                             */
                        }
                    }
                }
            }
    }

    private fun cancelBackendObservers() {

        serviceObserverJob?.cancel()
        serviceObserverJob = null

        managerSnapshotJob?.cancel()
        managerSnapshotJob = null

        managerEventsJob?.cancel()
        managerEventsJob = null

        controllerEventsJob?.cancel()
        controllerEventsJob = null
    }

    private fun requireMachineManager():
            MachineManager? {

        val manager =
            machineService?.manager

        if (
            manager == null
        ) {

            appendLog(
                "Machine backend is not connected."
            )
        }

        return manager
    }

    /*
     * ----------------------------------------------------
     * USB DEVICE DETECTION AND PERMISSION
     * ----------------------------------------------------
     */

    private fun initializeUsbManager() {

        usbManager =
            getSystemService(
                USB_SERVICE
            ) as UsbManager
    }

    private fun registerUsbPermissionReceiver() {

        registerReceiver(
            usbPermissionReceiver,
            IntentFilter(
                ACTION_USB_PERMISSION
            ),
            RECEIVER_NOT_EXPORTED
        )
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

    private fun findSupportedUsbDevice():
            UsbDevice? {

        /*
         * Current physical target:
         *
         * CH34x
         * VID 0x1A86 / PID 0x7523.
         */
        return usbManager
            .deviceList
            .values
            .firstOrNull {
                    device ->

                device.vendorId ==
                        SCULPFUN_VENDOR_ID &&
                        device.productId ==
                        SCULPFUN_PRODUCT_ID
            }
            ?: usbManager
                .deviceList
                .values
                .firstOrNull()
    }

    private fun detectUsbDevices() {

        val devices =
            usbManager
                .deviceList
                .values
                .toList()

        if (
            devices.isEmpty()
        ) {

            setLog(
                "No USB device detected."
            )

            return
        }

        setLog(
            buildString {

                appendLine(
                    "${devices.size} USB device(s) detected"
                )

                appendLine()

                devices.forEachIndexed {
                        index,
                        device ->

                    appendLine(
                        "Device ${index + 1}"
                    )

                    appendLine(
                        "Name: ${device.deviceName}"
                    )

                    appendLine(
                        "Vendor ID: ${device.vendorId}"
                    )

                    appendLine(
                        "Product ID: ${device.productId}"
                    )

                    appendLine(
                        "Manufacturer: ${
                            runCatching {
                                device.manufacturerName
                            }.getOrNull()
                                ?: "Unknown"
                        }"
                    )

                    appendLine(
                        "Product: ${
                            runCatching {
                                device.productName
                            }.getOrNull()
                                ?: "Unknown"
                        }"
                    )

                    appendLine(
                        "Permission: ${
                            if (
                                usbManager.hasPermission(device)
                            ) {
                                "Granted"
                            } else {
                                "Not granted"
                            }
                        }"
                    )

                    appendLine()
                }
            }.trimEnd()
        )
    }

    private fun connectUsbSerial() {

        val service =
            machineService

        if (
            service == null
        ) {

            appendLog(
                "Machine service is not ready."
            )

            return
        }

        if (
            service.isMachineConnected
        ) {

            appendLog(
                "Machine is already connected."
            )

            return
        }

        val device =
            findSupportedUsbDevice()

        if (
            device == null
        ) {

            appendLog(
                "No compatible USB device detected."
            )

            return
        }

        if (
            usbManager.hasPermission(device)
        ) {

            connectAuthorizedDevice(
                device
            )

            return
        }

        pendingUsbDevice =
            device

        val permissionIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(
                    ACTION_USB_PERMISSION
                ).setPackage(
                    packageName
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        appendLog(
            "Requesting USB permission..."
        )

        usbManager.requestPermission(
            device,
            permissionIntent
        )
    }

    private fun connectAuthorizedDevice(
        device: UsbDevice
    ) {

        val service =
            machineService

        if (
            service == null
        ) {

            appendLog(
                "Machine service is not available."
            )

            return
        }

        activityScope.launch {

            appendLog(
                "Connecting machine backend..."
            )

            /*
             * Short stabilization delay for CH34x
             * after Android grants permission.
             */
            delay(200.milliseconds)

            val result =
                service.connect(
                    device
                )

            result
                .onSuccess {

                    appendLog(
                        "Machine backend connected."
                    )

                    service.manager?.let {
                            manager ->

                        observeMachineManager(
                            manager
                        )
                    }
                }
                .onFailure {
                        exception ->

                    appendLog(
                        "Machine connection failed: ${
                            exception.message
                                ?: exception.javaClass.simpleName
                        }"
                    )
                }
        }
    }

    /*
     * ----------------------------------------------------
     * GRBL DIAGNOSTIC COMMANDS
     * ----------------------------------------------------
     */

    private fun requestGrblStatus() {

        val manager =
            requireMachineManager()
                ?: return

        activityScope.launch {

            try {

                appendLog(
                    "TX: ?"
                )

                manager.requestStatus()

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "Status error: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    private fun requestGrblSettings() {

        val manager =
            requireMachineManager()
                ?: return

        activityScope.launch {

            try {

                appendLog(
                    "TX: $$"
                )

                val result =
                    manager.requestSettings()

                appendLog(
                    "Settings result: $result"
                )

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "Settings error: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    private fun requestGrblBuildInfo() {

        val manager =
            requireMachineManager()
                ?: return

        activityScope.launch {

            try {

                appendLog(
                    $$"TX: $I"
                )

                val result =
                    manager.requestBuildInfo()

                appendLog(
                    "Identification result: $result"
                )

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "Identification error: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    /*
     * ----------------------------------------------------
     * JOG REQUEST CREATION
     * ----------------------------------------------------
     */

    private fun createJogRequest(
        direction: JogDirection,
        step: Double,
        speed: Double
    ): JogRequest {

        return when (direction) {

            JogDirection.NORTH ->

                JogRequest(
                    y = step,
                    feedRate = speed
                )

            JogDirection.NORTH_EAST ->

                JogRequest(
                    x = step,
                    y = step,
                    feedRate = speed
                )

            JogDirection.EAST ->

                JogRequest(
                    x = step,
                    feedRate = speed
                )

            JogDirection.SOUTH_EAST ->

                JogRequest(
                    x = step,
                    y = -step,
                    feedRate = speed
                )

            JogDirection.SOUTH ->

                JogRequest(
                    y = -step,
                    feedRate = speed
                )

            JogDirection.SOUTH_WEST ->

                JogRequest(
                    x = -step,
                    y = -step,
                    feedRate = speed
                )

            JogDirection.WEST ->

                JogRequest(
                    x = -step,
                    feedRate = speed
                )

            JogDirection.NORTH_WEST ->

                JogRequest(
                    x = -step,
                    y = step,
                    feedRate = speed
                )
        }
    }

    /*
     * ----------------------------------------------------
     * SHORT JOG
     * ----------------------------------------------------
     */

    private fun executeDirectionalJog(
        direction: JogDirection,
        step: Double,
        speed: Double
    ) {

        val manager =
            requireMachineManager()
                ?: return

        val request =
            createJogRequest(
                direction = direction,
                step = step,
                speed = speed
            )

        activityScope.launch {

            try {

                val result =
                    manager.executeJog(
                        request
                    )

                logOperationResult(
                    operationName =
                        "Jog $direction",

                    result =
                        result
                )

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "Jog failed: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    /*
     * ----------------------------------------------------
     * CONTINUOUS JOG
     * ----------------------------------------------------
     */

    private fun startContinuousJog(
        direction: JogDirection,
        step: Double,
        speed: Double
    ) {

        val manager =
            requireMachineManager()
                ?: return

        continuousJogJob?.cancel()

        appendLog(
            "Continuous Jog started: " +
                    "$direction | " +
                    "step=${formatNumber(step)} mm | " +
                    "speed=${formatNumber(speed)} mm/min"
        )

        continuousJogJob =
            activityScope.launch {

                while (isActive) {

                    val request =
                        createJogRequest(
                            direction = direction,
                            step = step,
                            speed = speed
                        )

                    val result =
                        manager.startContinuousJog(
                            request
                        )

                    if (
                        result !=
                        CommandResult.Acknowledged
                    ) {

                        appendLog(
                            "Continuous Jog stopped: $result"
                        )

                        break
                    }

                    delay(
                        CONTINUOUS_JOG_INTERVAL_MS.milliseconds
                    )
                }
            }
    }

    private fun stopContinuousJog() {

        continuousJogJob?.cancel()
        continuousJogJob = null

        val manager =
            requireMachineManager()
                ?: return

        activityScope.launch {

            try {

                manager.cancelJog()

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "Continuous Jog stop error: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    /*
     * ----------------------------------------------------
     * HOME
     * ----------------------------------------------------
     */

    private fun executeJogHome(
        speed: Double
    ) {

        val manager =
            requireMachineManager()
                ?: return

        activityScope.launch {

            try {

                val result =
                    manager.goToZero(
                        feedRate = speed
                    )

                logOperationResult(
                    operationName =
                        "HOME X0 Y0",

                    result =
                        result
                )

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "HOME failed: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    /*
     * ----------------------------------------------------
     * CANCEL JOG
     * ----------------------------------------------------
     */

    private fun cancelJog() {

        continuousJogJob?.cancel()
        continuousJogJob = null

        val manager =
            requireMachineManager()
                ?: return

        activityScope.launch {

            try {

                manager.cancelJog()

            } catch (
                exception: Exception
            ) {

                appendLog(
                    "Jog Cancel error: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    /*
     * ----------------------------------------------------
     * MACHINE OPERATION RESULTS
     * ----------------------------------------------------
     */

    private fun logOperationResult(
        operationName: String,
        result: MachineOperationResult
    ) {

        when (result) {

            is MachineOperationResult.Completed -> {

                appendLog(
                    "$operationName completed: " +
                            "X=${formatCoordinate(result.finalPosition.x)}, " +
                            "Y=${formatCoordinate(result.finalPosition.y)}"
                )
            }

            is MachineOperationResult.CommandRejected -> {

                appendLog(
                    "$operationName rejected: ${result.result}"
                )
            }

            is MachineOperationResult.Interrupted -> {

                appendLog(
                    "$operationName interrupted: " +
                            formatMachineState(
                                result.machineState
                            )
                )
            }

            is MachineOperationResult.Timeout -> {

                appendLog(
                    "$operationName timeout after " +
                            "${result.timeoutMillis / 1_000L}s. " +
                            "Last position: " +
                            "X=${formatCoordinate(result.lastPosition.x)}, " +
                            "Y=${formatCoordinate(result.lastPosition.y)}"
                )
            }

            is MachineOperationResult.Failed -> {

                appendLog(
                    "$operationName failed: ${result.message}"
                )
            }
        }
    }

    /*
     * ----------------------------------------------------
     * LOG AND ROTATION
     * ----------------------------------------------------
     */

    private fun restoreUiState(
        savedInstanceState: Bundle?
    ) {

        if (
            savedInstanceState == null
        ) {

            setLog(
                "LaserDroid ready."
            )

            return
        }

        val savedLog =
            savedInstanceState.getString(
                SAVED_LOG
            )

        setLog(
            if (
                savedLog.isNullOrBlank()
            ) {
                "LaserDroid ready."
            } else {
                savedLog
            }
        )

        jogPanel.setSelectedSpeed(
            savedInstanceState.getDouble(
                SAVED_JOG_SPEED,
                500.0
            )
        )

        jogPanel.setSelectedStep(
            savedInstanceState.getDouble(
                SAVED_JOG_STEP,
                1.0
            )
        )
    }

    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        outState.putString(
            SAVED_LOG,
            resultText.text
                ?.toString()
                .orEmpty()
        )

        outState.putDouble(
            SAVED_JOG_SPEED,
            jogPanel.getSelectedSpeed()
        )

        outState.putDouble(
            SAVED_JOG_STEP,
            jogPanel.getSelectedStep()
        )

        super.onSaveInstanceState(
            outState
        )
    }

    private fun setLog(
        message: String
    ) {

        runOnUiThread {

            resultText.text =
                limitLogSize(
                    message
                )

            scrollLogToBottom()
        }
    }

    private fun appendLog(
        message: String
    ) {

        runOnUiThread {

            val current =
                resultText.text
                    ?.toString()
                    .orEmpty()

            val updated =
                if (
                    current.isBlank()
                ) {
                    message
                } else {
                    "$current\n$message"
                }

            resultText.text =
                limitLogSize(
                    updated
                )

            scrollLogToBottom()
        }
    }

    private fun clearLog() {

        setLog(
            "Log cleared."
        )
    }

    private fun limitLogSize(
        value: String
    ): String {

        return if (
            value.length <=
            MAX_LOG_CHARACTERS
        ) {
            value
        } else {
            value.takeLast(
                MAX_LOG_CHARACTERS
            )
        }
    }

    private fun scrollLogToBottom() {

        logScrollView.post {

            logScrollView.fullScroll(
                ScrollView.FOCUS_DOWN
            )
        }
    }

    /*
     * ----------------------------------------------------
     * FORMATTING
     * ----------------------------------------------------
     */

    private fun formatMachineState(
        state: MachineState
    ): String {

        return when (state) {

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
                "ERROR: ${state.message}"
        }
    }

    private fun formatCoordinate(
        value: Double
    ): String {

        return String.format(
            Locale.US,
            "%.3f",
            value
        )
    }

    private fun formatNumber(
        value: Double
    ): String {

        return String.format(
            Locale.US,
            "%.3f",
            value
        )
            .trimEnd('0')
            .trimEnd('.')
    }

    /*
     * ----------------------------------------------------
     * ACTIVITY DESTRUCTION
     * ----------------------------------------------------
     */

    override fun onDestroy() {

        continuousJogJob?.cancel()
        continuousJogJob = null

        cancelBackendObservers()

        activityScope.cancel()

        unregisterReceiver(
            usbPermissionReceiver
        )

        if (
            serviceBound
        ) {

            unbindService(
                machineServiceConnection
            )

            serviceBound =
                false
        }

        machineService =
            null

        /*
         * Do not close USB.
         * Do not disconnect MachineManager.
         *
         * MachineService owns the machine backend.
         */

        super.onDestroy()
    }
}