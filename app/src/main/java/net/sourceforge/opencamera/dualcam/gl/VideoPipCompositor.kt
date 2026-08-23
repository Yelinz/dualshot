package net.sourceforge.opencamera.dualcam.gl

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.sourceforge.opencamera.dualcam.FrontPipCamera

/**
 * Standalone GL compositor for Phase P2's spliced video recording (PIP_SPEC.md section E /
 * PIP_RECON.md section 4): composites the back camera (full-frame) with the front camera (PIP
 * inset, live-repositionable) directly onto `MediaRecorder.getSurface()`, so the recorded file
 * has the PIP baked in exactly like the on-screen preview.
 *
 * Owns a dedicated [HandlerThread] and its own EGL display/context/config - never shared with
 * OC's own preview rendering (there isn't a shared GL surface to begin with; OC's Camera2
 * preview path doesn't use GLSurfaceView). Two GL_TEXTURE_EXTERNAL_OES textures/SurfaceTextures
 * are created here:
 *  - [cameraInputSurface] (backed by a SurfaceTexture sized to the *upright, possibly swapped*
 *    video profile dimensions - see PipController.prepareVideoSplice): handed to
 *    CameraController2 as a substitute for `video_recorder.getSurface()`, so the back camera
 *    streams into us instead of straight into the encoder.
 *  - [frontInputSurface] (backed by a SurfaceTexture sized like [FrontPipCamera.PREVIEW_SIZE]):
 *    added as an extra output on the front camera's session via
 *    [FrontPipCamera.attachCompositorOutput] for the duration of the recording.
 *
 * The render loop is driven entirely by the back camera's `onFrameAvailable` (PIP_SPEC.md
 * section E): both textures are updated (front only if it has a pending frame - it may lag a
 * frame or two behind, which is invisible for a small PIP inset), the scene is drawn, the
 * presentation time is taken from the back SurfaceTexture's own timestamp, and the frame is
 * swapped onto `MediaRecorder.getSurface()`.
 *
 * Orientation: strictly ST-matrix-only, per DUALCAM_SPEC.md's device-validated rule - no app-side
 * rotation or mirroring is ever applied here. Quad/texcoord conventions (grafika-style, texcoord
 * (0,0) on the bottom-left vertex) and the aspect-fill crop math are ported verbatim from the
 * deleted `gl/DualPreviewRenderer.kt` (see `git show` history / DUALCAM_SPEC.md).
 *
 * Lifecycle: constructed by `PipController.attachVideoCompositor()` once
 * `MediaRecorder.prepare()` has run; [attachRecorderSurface] is called immediately after with
 * the recorder's own surface (only valid post-prepare) to create the real EGL window surface.
 * [release] tears down every EGL/GL/SurfaceTexture resource on the GL thread and is safe to call
 * multiple times (including if construction itself failed partway) - callers must not leak this
 * across record/stop cycles.
 */
class VideoPipCompositor(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val frontSensorOrientation: Int,
    /** Called on the GL thread once per rendered frame; must be cheap and thread-safe (see
     *  PipController.currentVideoPipRectNormalized()). Coordinates are normalized (0..1, origin
     *  top-left) in the *output frame's* space (i.e. already mapped from preview-view space by
     *  the caller - PipController owns that mapping, see PIP_SPEC.md section E item 2). */
    private val pipRectProvider: () -> RectF,
) {
    companion object {
        private const val TAG = "VideoPipCompositor"

        // Android EGL extension attribute for a surface usable as a MediaCodec/MediaRecorder
        // input ("recordable"); not exposed as an EGL14 constant.
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val BORDER_PX = 4f

        private val UNIT_QUAD_TEXCOORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        private val FULLSCREEN = RectF(0f, 0f, 1f, 1f)

        // Shaders ported verbatim from the deleted gl/DualPreviewRenderer.kt.
        private const val OES_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val COLOR_VERTEX_SHADER = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """

        private const val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }

    private val thread = HandlerThread("VideoPipCompositor").apply { start() }
    private val handler = Handler(thread.looper)

    // ---- GL-thread-only state (everything below is only ever touched from [handler]'s
    // looper thread, except where marked @Volatile for cross-thread reads). ----------------

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var attachedToRecorder = false
    private var released = false

    private var oesProgram = 0
    private var oesPositionLoc = 0
    private var oesTexCoordLoc = 0
    private var oesTexMatrixLoc = 0
    private var oesTextureLoc = 0

    private var colorProgram = 0
    private var colorPositionLoc = 0
    private var colorUniformLoc = 0

    private var backTexId = 0
    private var frontTexId = 0
    private var backSurfaceTexture: SurfaceTexture? = null
    private var frontSurfaceTexture: SurfaceTexture? = null
    private val frontFramePending = AtomicBoolean(false)
    private var frontContentAspect = 1f

    // Reused across draws (GL thread only) to avoid per-frame direct-buffer allocation.
    private val posBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(UNIT_QUAD_TEXCOORDS); position(0) }

    /** The back camera's input surface (substitute for MediaRecorder's own surface at the
     *  CameraController2 splice point). Null until GL setup completes; stays null if setup
     *  failed, in which case the caller must fall back to stock (unspliced) recording. */
    @Volatile var cameraInputSurface: Surface? = null
        private set

    /** The front camera's extra session output for the duration of the recording. */
    @Volatile var frontInputSurface: Surface? = null
        private set

    init {
        // Blocking (bounded) init: this constructor runs on the caller's thread
        // (CameraController2.initVideoRecorderPostPrepare, i.e. within Preview's main-thread
        // call chain for starting a recording) because the *result* - cameraInputSurface - is
        // needed synchronously a few lines later to substitute into the capture session being
        // built in the same call stack (PIP_RECON.md section 4: the video_recorder_surface
        // assignment is a synchronous, non-callback code path). GL/EGL setup only takes a few
        // milliseconds, an acceptable one-time cost matching the blocking cost Open Camera
        // itself already pays for MediaRecorder.prepare() a few lines earlier in the same flow.
        val latch = CountDownLatch(1)
        handler.post {
            try {
                setUpEgl()
                setUpGl()
            } catch (e: Exception) {
                Log.w(TAG, "VideoPipCompositor init failed - splice will fall back to stock recording", e)
                releaseInternal()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            Log.w(TAG, "VideoPipCompositor init timed out - splice will fall back to stock recording")
        }
    }

    /** Called once (from PipController.attachVideoCompositor, immediately after construction)
     *  with `MediaRecorder.getSurface()` - only valid post-`prepare()`. Creates the real EGL
     *  window surface and switches the render loop over to it. Safe to call from any thread;
     *  hops internally. Until this completes, [renderFrame] still drains the back texture (so
     *  the camera doesn't stall) but has nowhere to draw to. */
    fun attachRecorderSurface(surface: Surface) {
        handler.post {
            if (released) return@post
            val config = eglConfig ?: return@post
            try {
                val newWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (newWindowSurface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                    return@post
                }
                if (!EGL14.eglMakeCurrent(eglDisplay, newWindowSurface, newWindowSurface, eglContext)) {
                    Log.w(TAG, "eglMakeCurrent(recorder window) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                    EGL14.eglDestroySurface(eglDisplay, newWindowSurface)
                    return@post
                }
                windowSurface = newWindowSurface
                attachedToRecorder = true
            } catch (e: Exception) {
                Log.w(TAG, "attachRecorderSurface failed", e)
            }
        }
    }

    /** Stops the render loop and releases every EGL/GL/SurfaceTexture resource. Safe to call
     *  multiple times (idempotent) and safe even if construction failed partway - callers must
     *  call this exactly once per compositor on every record/stop cycle to avoid leaking GL
     *  contexts/SurfaceTextures across repeated recordings. Does not block the caller. */
    fun release() {
        handler.post { releaseInternal() }
        thread.quitSafely()
    }

    // ---- GL thread only ---------------------------------------------------------------------

    private fun setUpEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // We need both: a 1x1 pbuffer up front (to have a current surface while creating
            // textures/programs, before MediaRecorder's surface exists) and, later, the real
            // recordable window surface from the same config.
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "no matching (recordable) EGLConfig for VideoPipCompositor"
        }
        val config = configs[0]!!
        eglConfig = config

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // Texture/program objects belong to the context, not the surface, so they remain valid
        // once attachRecorderSurface() later swaps the current surface to the real window
        // surface and this pbuffer is abandoned (destroyed on release()).
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) { "eglMakeCurrent(pbuffer) failed" }
    }

    private fun setUpGl() {
        oesProgram = GlUtil.createProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
        oesPositionLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesTexCoordLoc = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesTexMatrixLoc = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        oesTextureLoc = GLES20.glGetUniformLocation(oesProgram, "sTexture")

        colorProgram = GlUtil.createProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        colorPositionLoc = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        colorUniformLoc = GLES20.glGetUniformLocation(colorProgram, "uColor")

        backTexId = GlUtil.createExternalTexture()
        frontTexId = GlUtil.createExternalTexture()

        val back = SurfaceTexture(backTexId)
        // IMPORTANT: the camera-facing surface must use the SENSOR-LANDSCAPE stream size --
        // Camera2 stream configurations are landscape (e.g. 1920x1080) and a portrait-swapped
        // surface size is NOT in getOutputSizes(), which would fail session creation. Only the
        // encoder output (outputWidth x outputHeight, possibly upright-swapped) is portrait;
        // the SurfaceTexture transform matrix rotates the landscape buffer upright when drawn
        // (device-validated, see DUALCAM_SPEC.md). The upright content aspect then equals the
        // output aspect by construction (same swap), so no extra aspect crop is needed for the
        // back camera in renderFrame().
        val cameraW = maxOf(outputWidth, outputHeight)
        val cameraH = minOf(outputWidth, outputHeight)
        back.setDefaultBufferSize(cameraW, cameraH)
        backSurfaceTexture = back

        val front = SurfaceTexture(frontTexId)
        front.setDefaultBufferSize(FrontPipCamera.PREVIEW_SIZE.width, FrontPipCamera.PREVIEW_SIZE.height)
        frontSurfaceTexture = front

        // Same "upright content aspect from SENSOR_ORIENTATION" calc PipOverlayView/
        // FrontPipCamera use for the on-screen PIP - see DUALCAM_SPEC.md.
        frontContentAspect = if (frontSensorOrientation % 180 != 0) {
            FrontPipCamera.PREVIEW_SIZE.height.toFloat() / FrontPipCamera.PREVIEW_SIZE.width
        } else {
            FrontPipCamera.PREVIEW_SIZE.width.toFloat() / FrontPipCamera.PREVIEW_SIZE.height
        }

        cameraInputSurface = Surface(back)
        frontInputSurface = Surface(front)

        // Render loop driven entirely by the back camera's frames (PIP_SPEC.md section E).
        back.setOnFrameAvailableListener({ renderFrame() }, handler)
        front.setOnFrameAvailableListener({ frontFramePending.set(true) }, handler)
    }

    private fun renderFrame() {
        if (released) return
        val back = backSurfaceTexture ?: return
        try {
            back.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "back updateTexImage failed", e)
            return
        }
        if (!attachedToRecorder || windowSurface == EGL14.EGL_NO_SURFACE) {
            // MediaRecorder's surface isn't wired up yet (a narrow start-up race) - we've
            // already drained the back texture above so the camera doesn't stall, but there's
            // nowhere to draw to yet; this frame is simply dropped.
            return
        }
        if (frontFramePending.getAndSet(false)) {
            try {
                frontSurfaceTexture?.updateTexImage()
            } catch (e: Exception) {
                Log.w(TAG, "front updateTexImage failed", e)
            }
        }

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Back camera: fills the whole output frame - see setUpGl()'s comment on why no
        // aspect-fill crop is needed (ST matrix is still applied; contentAspect<=0 just skips
        // the extra crop-scale composition).
        drawCameraTexture(backTexId, back, contentAspect = 0f, destRect = FULLSCREEN)

        val pip = clampedPipRect()

        val borderMarginX = BORDER_PX / outputWidth
        val borderMarginY = BORDER_PX / outputHeight
        val borderRect = RectF(
            pip.left - borderMarginX, pip.top - borderMarginY,
            pip.right + borderMarginX, pip.bottom + borderMarginY,
        )
        drawColorQuad(borderRect, WHITE)

        frontSurfaceTexture?.let { front ->
            drawCameraTexture(frontTexId, front, contentAspect = frontContentAspect, destRect = pip)
        }

        // Use CLOCK_MONOTONIC (System.nanoTime) for the presentation time, NOT the
        // SurfaceTexture's own timestamp: camera sensor timestamps are only in the monotonic
        // timebase when SENSOR_INFO_TIMESTAMP_SOURCE is REALTIME - on devices where it is
        // UNKNOWN (an arbitrary timebase) they can be wildly offset from MediaRecorder's audio
        // clock (which IS monotonic), which the muxer then interprets as an enormous video
        // duration (observed on the Fairphone 5: a 5s clip muxed as 1h+). The few-ms latency
        // between sensor capture and this render call is irrelevant at 30fps.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, System.nanoTime())
        if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    /** Defends the draw call against a pathological/racy rect from [pipRectProvider] (e.g. read
     *  mid-mutation elsewhere) turning into an inverted or wildly out-of-bounds quad. */
    private fun clampedPipRect(): RectF {
        val rect = pipRectProvider()
        val left = rect.left.coerceIn(0f, 1f)
        val top = rect.top.coerceIn(0f, 1f)
        val right = rect.right.coerceIn(left, 1f)
        val bottom = rect.bottom.coerceIn(top, 1f)
        return RectF(left, top, right, bottom)
    }

    /** Ported from the deleted gl/DualPreviewRenderer.kt: composes the SurfaceTexture's own
     *  transform matrix (sole orientation source, per DUALCAM_SPEC.md) with an optional
     *  aspect-fill crop scale about center, applied to texcoords. [contentAspect] <= 0 skips the
     *  crop (ST matrix only) - used for the back camera. */
    private fun drawCameraTexture(texId: Int, surfaceTexture: SurfaceTexture, contentAspect: Float, destRect: RectF) {
        val stMatrix = FloatArray(16)
        surfaceTexture.getTransformMatrix(stMatrix)

        val combined: FloatArray
        if (contentAspect <= 0f) {
            combined = stMatrix
        } else {
            val destPxW = destRect.width() * outputWidth
            val destPxH = destRect.height() * outputHeight
            val targetAspect = if (destPxH == 0f) contentAspect else destPxW / destPxH
            var sx = 1f
            var sy = 1f
            if (contentAspect > targetAspect) sx = targetAspect / contentAspect else sy = contentAspect / targetAspect
            val crop = FloatArray(16)
            Matrix.setIdentityM(crop, 0)
            Matrix.translateM(crop, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(crop, 0, sx, sy, 1f)
            Matrix.translateM(crop, 0, -0.5f, -0.5f, 0f)
            combined = FloatArray(16)
            Matrix.multiplyMM(combined, 0, stMatrix, 0, crop, 0)
        }

        GLES20.glUseProgram(oesProgram)

        val posBuf = fillPosBuffer(destRect)
        texCoordBuffer.position(0)

        GLES20.glEnableVertexAttribArray(oesPositionLoc)
        GLES20.glVertexAttribPointer(oesPositionLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(oesTexCoordLoc)
        GLES20.glVertexAttribPointer(oesTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(oesTexMatrixLoc, 1, false, combined, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(oesTextureLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(oesPositionLoc)
        GLES20.glDisableVertexAttribArray(oesTexCoordLoc)
    }

    private fun drawColorQuad(rect: RectF, color: FloatArray) {
        GLES20.glUseProgram(colorProgram)
        val posBuf = fillPosBuffer(rect)
        GLES20.glEnableVertexAttribArray(colorPositionLoc)
        GLES20.glVertexAttribPointer(colorPositionLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glUniform4fv(colorUniformLoc, 1, color, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(colorPositionLoc)
    }

    /** Writes a normalized (0..1, origin top-left) rect into [posBuffer] as NDC (-1..1, origin
     *  center, y-up) triangle-strip vertex positions in grafika order -
     *  (left,bottom),(right,bottom),(left,top),(right,top) - so texcoord (0,0) lands on the
     *  BOTTOM-left vertex, matching the GL-convention y-flip already baked into the
     *  SurfaceTexture transform matrix (DUALCAM_SPEC.md). GL thread only. */
    private fun fillPosBuffer(rect: RectF): FloatBuffer {
        val left = rect.left * 2f - 1f
        val right = rect.right * 2f - 1f
        val top = 1f - rect.top * 2f
        val bottom = 1f - rect.bottom * 2f
        posBuffer.clear()
        posBuffer.put(left).put(bottom)
        posBuffer.put(right).put(bottom)
        posBuffer.put(left).put(top)
        posBuffer.put(right).put(top)
        posBuffer.position(0)
        return posBuffer
    }

    private fun releaseInternal() {
        if (released) return
        released = true
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Exception) {
            Log.w(TAG, "EGL teardown failed", e)
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        pbufferSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY

        try {
            backSurfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "back SurfaceTexture release failed", e)
        }
        try {
            frontSurfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "front SurfaceTexture release failed", e)
        }
        backSurfaceTexture = null
        frontSurfaceTexture = null

        cameraInputSurface?.release()
        frontInputSurface?.release()
        cameraInputSurface = null
        frontInputSurface = null
    }
}
