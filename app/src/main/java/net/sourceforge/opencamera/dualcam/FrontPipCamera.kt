package net.sourceforge.opencamera.dualcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Owns the front-facing Camera2 device used for the PIP preview + stills, opened concurrently
 * alongside Open Camera's own (back) camera. Derived from the standalone/ported
 * DualCameraController (see DUALCAM_SPEC.md), trimmed to front-only. Session outputs: the PIP
 * preview [previewSurface] + a JPEG [ImageReader] for stills, plus (Phase P2, PIP_SPEC.md
 * section E) an optional third output added via [attachCompositorOutput] only while a spliced
 * video recording is active - VideoPipCompositor's front-camera input.
 *
 * All Camera2 callbacks run on a single dedicated [cameraThread]; the [captureStill] result
 * callback is invoked on that same thread (callers hop to whatever thread they need).
 *
 * Never throws out of this class into caller code that isn't expecting it: per PIP_SPEC.md's
 * non-negotiables, errors while running are reported via [Callback.onError] so
 * [PipController] can hide the PIP without taking down Open Camera itself.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class FrontPipCamera(
    private val context: Context,
    private val callback: Callback,
) {
    interface Callback {
        /** Unexpected error while we believed the front camera should be running (or failed
         *  to open in the first place). Benign errors that occur after close() was requested
         *  are swallowed (logged only) and do NOT call this. */
        fun onError(message: String)

        /** Session configured and streaming. Called on the camera thread with the camera's
         *  SENSOR_ORIENTATION, so the overlay can set its content-aspect crop transform. */
        fun onOpened(sensorOrientation: Int)

        /** The video splice could not route front frames to the compositor for this recording
         *  (the video records without the PIP; the on-screen PIP keeps working). Called on the
         *  camera thread with a short diagnostic reason. */
        fun onSpliceDegraded(reason: String)
    }

    data class CapturedJpeg(val bytes: ByteArray, val sensorOrientation: Int)

    companion object {
        private const val TAG = "FrontPipCamera"

        // Sensor-landscape default buffer size for the PIP preview SurfaceTexture, per
        // PIP_SPEC.md section B ("720p default buffer"). Also used (not private, per
        // PIP_SPEC.md section E / Phase P2) as the buffer size for VideoPipCompositor's front
        // input SurfaceTexture, so both the on-screen PIP and the spliced-video PIP sample the
        // front camera at the same size and the compositor can reuse this camera's
        // SENSOR_ORIENTATION to compute the same upright content aspect PipOverlayView does.
        val PREVIEW_SIZE = Size(1280, 720)

        /** Minimum short side for the front STILL JPEG stream - see [chooseStillJpegSize]. */
        private const val MIN_STILL_SHORT_SIDE = 1440
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("FrontPipCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { r -> cameraHandler.post(r) }

    /** Consulted by every error path: once close() has been requested, errors are benign. */
    @Volatile private var closing = false

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null

    @Volatile var sensorOrientation: Int = 0
        private set

    private var pendingStillCallback: ((CapturedJpeg?) -> Unit)? = null

    /** The compositor's front-camera input, routed via surface sharing on the preview output
     *  only while a spliced video recording is active (PIP_SPEC.md section E / Phase P2). Null
     *  the rest of the time. Written only on [cameraHandler]. */
    private var compositorSurface: Surface? = null

    /** The preview OutputConfiguration, created with surface sharing enabled so
     *  [attachCompositorOutput] can add the compositor surface to the same stream at record
     *  time without recreating the session. [cameraHandler] only. */
    private var previewOutputConfig: OutputConfiguration? = null

    /** Chosen once per open; reused when the repeating request is re-issued on
     *  attach/detach. [cameraHandler] only. */
    private var fpsRange: Range<Int>? = null

    /** Call once the PIP overlay's TextureView has a SurfaceTexture ready. */
    fun open(previewTexture: SurfaceTexture) {
        closing = false
        cameraHandler.post {
            try {
                val id = findFrontCameraId()
                if (id == null) {
                    callback.onError("No front-facing camera found on this device")
                    return@post
                }
                val chars = cameraManager.getCameraCharacteristics(id)
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                characteristics = chars

                val reader = makeJpegReader(chars)
                imageReader = reader

                previewTexture.setDefaultBufferSize(PREVIEW_SIZE.width, PREVIEW_SIZE.height)
                val surface = Surface(previewTexture)
                previewSurface = surface

                openCameraDevice(id) { opened ->
                    device = opened
                    createSession(opened, surface, reader, chars) { configuredSession ->
                        session = configuredSession
                        callback.onOpened(sensorOrientation)
                    }
                }
            } catch (e: CameraAccessException) {
                callback.onError("Camera access error: ${e.message}")
            } catch (e: SecurityException) {
                callback.onError("Camera permission error: ${e.message}")
            }
        }
    }

    /** Idempotent; sets the 'closing' flag consulted by error paths before tearing everything
     *  down on the camera thread. Safe to call even if open() never completed. */
    fun close() {
        closing = true
        cameraHandler.post {
            try {
                session?.close()
            } catch (e: Exception) {
                Log.w(TAG, "closing session", e)
            }
            session = null

            previewSurface?.release()
            previewSurface = null

            try {
                imageReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "closing image reader", e)
            }
            imageReader = null

            try {
                device?.close()
            } catch (e: Exception) {
                Log.w(TAG, "closing device", e)
            }
            device = null

            pendingStillCallback = null
            // Not our surface to release (owned by VideoPipCompositor); just drop the
            // reference so a later open() starts clean.
            compositorSurface = null
            previewOutputConfig = null
            fpsRange = null
            characteristics = null
        }
    }

    /**
     * Routes the front camera's frames to [surface] (VideoPipCompositor's front-camera input)
     * for the duration of a spliced recording, via Camera2 surface sharing: the surface is
     * added to the ALREADY-CONFIGURED preview OutputConfiguration (created shareable in
     * [createSession]) with `session.updateOutputConfiguration()` -- no session recreate, no
     * additional HAL stream, no new stream-combination risk -- and the repeating request is
     * re-issued targeting both. Both surfaces are 720p SurfaceTexture-backed, satisfying the
     * shared-surface compatibility requirement.
     *
     * On ANY failure the PIP preview keeps running untouched and
     * [Callback.onSpliceDegraded] fires (the recorded video simply won't contain the PIP for
     * this take) -- never [Callback.onError], never a crash.
     */
    fun attachCompositorOutput(surface: Surface) {
        cameraHandler.post {
            if (closing) return@post
            val currentSession = session
            val previewOC = previewOutputConfig
            if (currentSession == null || previewOC == null) {
                callback.onSpliceDegraded("front camera not ready when recording started")
                return@post
            }
            try {
                compositorSurface = surface
                previewOC.addSurface(surface)
                currentSession.updateOutputConfiguration(previewOC)
                startRepeatingRequest(currentSession)
                Log.i(TAG, "compositor surface attached via shared output")
            } catch (e: Exception) {
                Log.w(TAG, "attaching compositor surface failed - video will not contain the PIP", e)
                // Roll back so the preview keeps running without the compositor target.
                compositorSurface = null
                try {
                    previewOC.removeSurface(surface)
                } catch (e2: Exception) {
                    Log.w(TAG, "rollback removeSurface failed", e2)
                }
                try {
                    startRepeatingRequest(currentSession)
                } catch (e2: Exception) {
                    Log.w(TAG, "rollback repeating request failed", e2)
                }
                callback.onSpliceDegraded(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Reverses [attachCompositorOutput] (spliced recording stopped): removes the shared
     *  surface and re-issues the preview-only repeating request. Idempotent; safe to call even
     *  if never attached, or if the camera has since closed. */
    fun detachCompositorOutput() {
        cameraHandler.post {
            val surface = compositorSurface ?: return@post
            compositorSurface = null
            if (closing) return@post
            val currentSession = session ?: return@post
            val previewOC = previewOutputConfig ?: return@post
            try {
                startRepeatingRequest(currentSession)
                previewOC.removeSurface(surface)
                currentSession.updateOutputConfiguration(previewOC)
            } catch (e: Exception) {
                Log.w(TAG, "detaching compositor surface failed (continuing)", e)
            }
        }
    }

    /** Fires a single STILL_CAPTURE; invokes [cb] on the camera thread with the JPEG bytes
     *  (null on failure, e.g. if the session isn't ready). */
    fun captureStill(cb: (CapturedJpeg?) -> Unit) {
        cameraHandler.post {
            val currentSession = session
            val currentDevice = device
            val reader = imageReader
            if (currentSession == null || currentDevice == null || reader == null) {
                cb(null)
                return@post
            }
            pendingStillCallback = cb
            try {
                val builder = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                // Left at 0: manual rotation happens downstream (PhotoProcessor), per the
                // load-bearing orientation rule in DUALCAM_SPEC.md - JPEG buffers carry no
                // transform tag and need it applied by sensorOrientation.
                builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
                currentSession.capture(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            completeStill(null)
                        }
                    },
                    cameraHandler,
                )
            } catch (e: CameraAccessException) {
                Log.w(TAG, "captureStill failed", e)
                completeStill(null)
            }
        }
    }

    // ---- internals ----------------------------------------------------------------

    private fun completeStill(jpeg: CapturedJpeg?) {
        val cb = pendingStillCallback
        pendingStillCallback = null
        cb?.invoke(jpeg)
    }

    private fun findFrontCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return null
    }

    /**
     * The PIP occupies at most ~50% of the saved photo's width (realistically ~1300-2000 output
     * pixels), so a full-resolution front still (12MP+ on the FP5) is wasted work that costs
     * real time per photo: slower STILL_CAPTURE readout, a JPEG 10x larger to decode, more to
     * downscale. Pick the SMALLEST JPEG size whose short side (which becomes the upright width
     * after rotation) is >= [MIN_STILL_SHORT_SIDE] instead; fall back to the largest if none.
     */
    private fun chooseStillJpegSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return Size(1920, 1080)
        val bigEnough = sizes.filter { minOf(it.width, it.height) >= MIN_STILL_SHORT_SIDE }
        return bigEnough.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1920, 1080)
    }

    private fun makeJpegReader(chars: CameraCharacteristics): ImageReader {
        val size = chooseStillJpegSize(chars)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            completeStill(CapturedJpeg(bytes, sensorOrientation))
        }, cameraHandler)
        return reader
    }

    private fun chooseFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges
            .filter { it.lower <= 30 && it.upper >= 30 }
            .minByOrNull { it.upper - it.lower }
    }

    @SuppressLint("MissingPermission") // permission is guaranteed by Open Camera's own CAMERA permission flow before PipController ever opens us
    private fun openCameraDevice(id: String, onOpened: (CameraDevice) -> Unit) {
        cameraManager.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    if (closing) {
                        // close() may have been requested while the open was in flight.
                        opened.close()
                        return
                    }
                    onOpened(opened)
                }

                override fun onDisconnected(opened: CameraDevice) {
                    opened.close()
                    handleDeviceLost("disconnected")
                }

                override fun onError(opened: CameraDevice, error: Int) {
                    opened.close()
                    handleDeviceLost("error code $error")
                }
            },
            cameraHandler,
        )
    }

    private fun handleDeviceLost(reason: String) {
        device = null
        // ERROR_CAMERA_DISABLED (and similar) can arrive at teardown just after a successful
        // close() request - treat as benign then; only surface while we expect it running.
        if (closing) {
            Log.i(TAG, "Front camera $reason after close() requested (benign)")
        } else {
            callback.onError("Front camera $reason")
        }
    }

    private fun createSession(
        cameraDevice: CameraDevice,
        surface: Surface,
        reader: ImageReader,
        chars: CameraCharacteristics,
        onConfigured: (CameraCaptureSession) -> Unit,
    ) {
        val fpsRange = chooseFpsRange(chars)
        this.fpsRange = fpsRange
        // DUALCAM-PIP begin (Phase P2, reworked): the session is ALWAYS the same, known-good
        // stream pair [720p PRIV preview, max JPEG] that the plain PIP preview uses -- but the
        // preview OutputConfiguration is created with surface sharing enabled, so the video
        // compositor's front input (same 720p SurfaceTexture class) can later be added to the
        // SAME stream via CameraCaptureSession.updateOutputConfiguration() WITHOUT recreating
        // the session or adding a HAL stream. The previous approach (recreate the session with
        // the compositor as an additional stream) produced no front frames on the FP5 during
        // recording; sharing sidesteps extra-stream combination/resource limits entirely.
        val previewOC = OutputConfiguration(surface).apply {
            enableSurfaceSharing()
            compositorSurface?.let {
                try {
                    addSurface(it)
                } catch (e: Exception) {
                    Log.w(TAG, "could not pre-add compositor surface to shared output", e)
                }
            }
        }
        previewOutputConfig = previewOC
        val outputs = listOf(previewOC, OutputConfiguration(reader.surface))
        // DUALCAM-PIP end
        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configuredSession: CameraCaptureSession) {
                if (closing) {
                    configuredSession.close()
                    return
                }
                try {
                    startRepeatingRequest(configuredSession)
                    onConfigured(configuredSession)
                } catch (e: CameraAccessException) {
                    if (!closing) callback.onError("Failed to start front preview: ${e.message}")
                }
            }

            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                if (closing) return
                callback.onError("Failed to configure front camera session")
            }
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            sessionCallback,
        )
        cameraDevice.createCaptureSession(sessionConfig)
    }

    /** [cameraHandler] only. (Re)issues the repeating preview request targeting the preview
     *  surface plus, when a spliced recording is active, the compositor's shared surface. */
    @Throws(CameraAccessException::class)
    private fun startRepeatingRequest(currentSession: CameraCaptureSession) {
        val cameraDevice = device ?: currentSession.device
        val surface = previewSurface ?: return
        val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        compositorSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        currentSession.setRepeatingRequest(builder.build(), null, cameraHandler)
    }
}
