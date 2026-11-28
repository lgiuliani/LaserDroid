package com.laserdroid.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import com.laserdroid.communication.network.FrameProvider
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Phone camera -> JPEG frames for the live stream (Camera2, no
 * preview surface: works with the screen off from the foreground
 * service).
 *
 * YUV_420_888 frames are converted to NV21 then compressed with
 * YuvImage: reliable on old devices at 640x480 / 10 fps.
 */
class CameraStreamer(
    context: Context,
    private val log: (String) -> Unit
) : FrameProvider {

    data class Config(
        val backCamera: Boolean = true,
        val width: Int = 640,
        val height: Int = 480,
        val fps: Int = 10,
        /** Exposure compensation steps (-2..+2): laser glare. */
        val exposure: Int = 0,
        val jpegQuality: Int = 70
    )

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile
    private var frame: ByteArray? = null

    @Volatile
    private var counter = 0L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L

    @Volatile
    var isRunning = false
        private set

    override fun latestFrame(): ByteArray? = frame

    override fun frameNumber(): Long = counter

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(config: Config) {
        if (isRunning) return
        if (!hasPermission()) {
            log("Camera permission not granted.")
            return
        }
        val cameraId = selectCamera(config.backCamera)
        if (cameraId == null) {
            log("No camera found.")
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val size = selectSize(characteristics, config.width, config.height)

        val handlerThread = HandlerThread("laserdroid-camera").also { it.start() }
        val cameraHandler = Handler(handlerThread.looper)
        thread = handlerThread
        handler = cameraHandler

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = imageReader
        val interval = 1_000L / config.fps.coerceIn(1, 30)
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt >= interval) {
                    lastFrameAt = now
                    frame = toJpeg(image, config.jpegQuality)
                    counter++
                }
            } catch (exception: Exception) {
                // Skip a bad frame.
            } finally {
                image.close()
            }
        }, cameraHandler)

        isRunning = true
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, imageReader, characteristics, config, cameraHandler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    log("Camera disconnected.")
                    camera.close()
                    stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("Camera error $error.")
                    camera.close()
                    stop()
                }
            }, cameraHandler)
            log("Camera started: ${size.width}x${size.height}, ${config.fps} fps.")
        } catch (exception: Exception) {
            log("Camera start failed: ${exception.message}")
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(
        camera: CameraDevice,
        imageReader: ImageReader,
        characteristics: CameraCharacteristics,
        config: Config,
        cameraHandler: Handler
    ) {
        try {
            camera.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(imageReader.surface)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                )
                                selectFpsRange(characteristics, config.fps)?.let {
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                                }
                                val range = characteristics.get(
                                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
                                )
                                if (range != null) {
                                    set(
                                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                        exposureSteps(characteristics, config.exposure)
                                            .coerceIn(range.lower, range.upper)
                                    )
                                }
                            }.build()
                            captureSession.setRepeatingRequest(request, null, cameraHandler)
                        } catch (exception: Exception) {
                            log("Camera request failed: ${exception.message}")
                            stop()
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        log("Camera session configuration failed.")
                        stop()
                    }
                },
                cameraHandler
            )
        } catch (exception: Exception) {
            log("Camera session failed: ${exception.message}")
            stop()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning && device == null) return
        isRunning = false
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
        frame = null
        log("Camera stopped.")
    }

    private fun selectCamera(back: Boolean): String? {
        val wanted = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val ids = cameraManager.cameraIdList
        return ids.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: ids.firstOrNull()
    }

    private fun selectSize(characteristics: CameraCharacteristics, width: Int, height: Int): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return Size(width, height)
        return sizes
            .filter { it.width <= width && it.height <= height }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { abs(it.width * it.height - width * height) }!!
    }

    private fun selectFpsRange(characteristics: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges
            .filter { it.upper >= fps }
            .minByOrNull { it.upper - fps + (it.upper - it.lower) }
    }

    /* EV steps -> device compensation units. */
    private fun exposureSteps(characteristics: CameraCharacteristics, ev: Int): Int {
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val stepValue = step?.toDouble()?.takeIf { it > 0.0 } ?: return 0
        return (ev / stepValue).toInt()
    }

    /*
     * YUV_420_888 (any strides) -> NV21 -> JPEG.
     */
    private fun toJpeg(image: Image, quality: Int): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        var offset = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(nv21, offset, width)
            offset += width
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val uIndex = row * uPlane.rowStride + column * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + column * vPlane.pixelStride
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }

        val output = ByteArrayOutputStream(width * height / 4)
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, output)
        return output.toByteArray()
    }
}
