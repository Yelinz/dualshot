package net.sourceforge.opencamera.dualcam

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import net.sourceforge.opencamera.MainActivity
import net.sourceforge.opencamera.MyApplicationInterface
import net.sourceforge.opencamera.PreferenceKeys
import net.sourceforge.opencamera.R
import net.sourceforge.opencamera.ToastBoxer
import net.sourceforge.opencamera.cameracontroller.CameraController
import net.sourceforge.opencamera.dualcam.gl.VideoPipCompositor
import net.sourceforge.opencamera.dualcam.photo.PhotoProcessor
import net.sourceforge.opencamera.preview.Preview
import net.sourceforge.opencamera.preview.VideoProfile

/**
 * Feature facade for dual-camera PIP, owned (conceptually) by MainActivity - see
 * PIP_SPEC.md section A. A plain Kotlin singleton rather than an instance held by
 * MainActivity, so the handful of Open Camera Java call sites (Preview, MyApplicationInterface,
 * ImageSaver, OnScreenIcons, MainActivity) can call `PipController.xxx(...)` directly
 * (via @JvmStatic) without MainActivity having to thread a reference through everywhere.
 *
 * Lifecycle entry points (called from the OC hooks listed in PIP_RECON.md section 2):
 * - [onMainCameraOpened]: end of Preview.cameraOpened().
 * - [onMainCameraClosing]: top of Preview.onPause()/mySurfaceDestroyed()/setCamera()'s close.
 * - [onToggled]: the on-screen icon's click handler.
 *
 * Never lets a PIP failure take down Open Camera: every entry point below is defensive (catches
 * are pushed down into FrontPipCamera/PipOverlayView; failures here degrade to hiding the PIP,
 * never to throwing back into OC's own camera lifecycle).
 */
object PipController {
    private const val TAG = "PipController"

    /** Never block ImageSaver's background thread longer than this waiting for the front
     *  still to arrive - see PIP_SPEC.md section D.3. */
    private const val STILL_WAIT_TIMEOUT_MS = 1500L

    private val needsBackCameraToast = ToastBoxer()

    private var container: FrameLayout? = null

    // @Volatile: read cross-thread from the video splice compositor's GL thread every frame
    // during a spliced recording (via currentVideoPipRectNormalized(), Phase P2 / PIP_SPEC.md
    // section E) - only ever reassigned (never mutated in place) on the main thread.
    @Volatile
    private var overlayView: PipOverlayView? = null
    private var frontCamera: FrontPipCamera? = null

    private var containerLayoutListener: View.OnLayoutChangeListener? = null

    @Volatile
    private var pendingHolder: PipStillHolder? = null

    // ---- video splice state (PIP_SPEC.md section E / Phase P2) ---------------------------

    /** Set by [prepareVideoSplice], consumed by [attachVideoCompositor] - both run
     *  synchronously within the same (main-thread) call stack that starts a recording
     *  (Preview.startVideoRecording -> CameraController2.initVideoRecorderPostPrepare), so a
     *  plain (non-volatile) field is safe here; see [attachVideoCompositor]'s kdoc. */
    private var spliceArmed = false
    private var pendingCompositorWidth = 0
    private var pendingCompositorHeight = 0
    private var pendingMapping: VideoSpliceMapping? = null

    @Volatile
    private var videoCompositor: VideoPipCompositor? = null

    /** Consulted by CameraController2 at the video_recorder_surface assignment (PIP_RECON.md
     *  section 4); null whenever no spliced recording is in progress. */
    @Volatile
    private var videoRecorderSurfaceOverride: Surface? = null

    @Volatile
    private var activeMapping: VideoSpliceMapping? = null

    /** Maps a PIP rect from preview-view-normalized space into upright-video-normalized space -
     *  see PIP_SPEC.md section E item 2. Computed once per recording (in [prepareVideoSplice]),
     *  consumed every frame by the compositor's GL thread via [currentVideoPipRectNormalized]. */
    private data class VideoSpliceMapping(
        val rotation90: Boolean,
        val previewViewAspect: Float,
        val videoAspect: Float,
    )

    // ---- lifecycle entry points (called from OC Java) ------------------------------------

    /** Takes a plain [Activity] (rather than [MainActivity]) so that Preview.java - which
     *  deliberately does not import MainActivity, see the commented-out import at the top of
     *  that file - doesn't need to gain that dependency just to call this. */
    @JvmStatic
    fun onMainCameraOpened(activity: Activity) {
        val main = activity as? MainActivity ?: return
        reevaluate(main)
    }

    @JvmStatic
    fun onMainCameraClosing() {
        pendingHolder = null
        // Defensive: normally reconnect()'s hook (onVideoRecordingStopped(), called from
        // CameraController2.reconnect()) already tore down an in-progress video splice as part
        // of Preview's own stopVideo()->reconnectCamera() chain, which runs before this in the
        // onPause() flow (PIP_RECON.md section 2) - but call it again here too (idempotent) as
        // a backstop for any path that closes the main camera without going through reconnect().
        onVideoRecordingStopped()
        stopIfNeeded()
    }

    @JvmStatic
    fun onToggled(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val newValue = !prefs.getBoolean(PreferenceKeys.DualCameraPipPreferenceKey, false)
        prefs.edit().putBoolean(PreferenceKeys.DualCameraPipPreferenceKey, newValue).apply()

        if (newValue && isMainCameraFrontFacing(activity)) {
            activity.preview.showToast(needsBackCameraToast, R.string.dualcam_pip_needs_back_camera, true)
        } else if (newValue && activity.preview?.usingCamera2API() == false) {
            // Never fail silently: the PIP hard-requires the Camera2 API. (The fork also
            // defaults suitable devices to Camera2 in MainActivity.initCamera2Support(), but
            // the user may have explicitly chosen the old API.)
            activity.preview.showToast(needsBackCameraToast, R.string.dualcam_pip_needs_camera2, true)
        }
        reevaluate(activity)
    }

    /** Whether the PIP pref is currently on - used for the icon's alpha (see
     *  OnScreenIcons.updateDualCameraModeIcon()). Independent of whether the PIP is actually
     *  showing right now (e.g. it's off-screen while OC uses its front camera). */
    @JvmStatic
    fun isPipEnabledPref(activity: MainActivity): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        return prefs.getBoolean(PreferenceKeys.DualCameraPipPreferenceKey, false)
    }

    /** Whether the PIP overlay + front camera are actually active right now. */
    @JvmStatic
    fun isActive(): Boolean = overlayView != null && frontCamera != null

    // ---- photo composite path (PIP_SPEC.md section D) -------------------------------------

    /** Called from MyApplicationInterface.onCaptureStarted(): if the PIP is active, snapshot
     *  its current rect, create a new pending holder, and trigger the front still capture. */
    @JvmStatic
    fun onCaptureStarted() {
        val camera = frontCamera
        val overlay = overlayView
        if (camera == null || overlay == null) {
            pendingHolder = null
            return
        }
        val displayRotation = overlay.display?.rotation ?: android.view.Surface.ROTATION_0
        val holder = PipStillHolder(overlay.currentRectNormalized(), displayRotation)
        pendingHolder = holder
        camera.captureStill { jpeg -> holder.complete(jpeg) }
    }

    /** Called from ImageSaver's private saveImage() right after building the outgoing
     *  Request, for the single-JPEG/NORMAL-process-type case only: consumes (clears) the
     *  pending holder so it isn't reused by any later, unrelated Request. Returns null if
     *  there's no PIP capture in flight for this photo. */
    @JvmStatic
    fun takePendingStillHolder(): PipStillHolder? {
        val holder = pendingHolder
        pendingHolder = null
        return holder
    }

    /** Called from ImageSaver.saveSingleImageNow() at the composite choke point. Waits (up to
     *  [STILL_WAIT_TIMEOUT_MS]) for the front still, then composites it onto [bitmap]. On
     *  timeout, capture failure, or any compositing exception, logs why and returns the
     *  original [bitmap] unchanged - this must never fail the photo save. */
    @JvmStatic
    fun compositeStill(holder: PipStillHolder, bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) {
            Log.w(TAG, "compositeStill: no base bitmap to composite onto, saving unchanged")
            return null
        }
        val jpeg = holder.awaitResult(STILL_WAIT_TIMEOUT_MS)
        if (jpeg == null) {
            Log.w(TAG, "PIP still not ready within ${STILL_WAIT_TIMEOUT_MS}ms (or capture failed) - saving photo unchanged")
            return bitmap
        }
        return try {
            PhotoProcessor.compositeOntoBitmap(bitmap, jpeg, holder.pipRect, holder.displayRotation)
        } catch (e: Exception) {
            Log.w(TAG, "PIP composite failed - saving photo unchanged", e)
            bitmap
        }
    }

    // ---- video splice path (PIP_SPEC.md section E, PIP_RECON.md section 4) ----------------

    /** Pure gate check (no side effects) - ALL must hold for a recording to get the spliced
     *  (PIP-baked-in) video path; otherwise it records exactly as stock OC and the on-screen PIP
     *  is left showing with a one-off toast explaining why it won't appear in this file (see
     *  Preview.startVideoRecording). Timelapse (capture rate != 1) is deliberately NOT gated
     *  here - it's compatible (PIP_RECON.md section 4). Called from both the toast decision and
     *  [prepareVideoSplice] itself. */
    @JvmStatic
    fun isVideoSpliceGatesOpen(preview: Preview): Boolean {
        if (!isActive()) return false
        if (!preview.usingCamera2API()) return false
        if (preview.isVideoHighSpeed()) return false
        val controller = preview.cameraController ?: return false
        if (controller.isCameraExtension) return false
        return true
    }

    /**
     * Called from Preview.startVideoRecording, before `profile.copyToMediaRecorder(...)` (see
     * PIP_SPEC.md section E). Re-checks [isVideoSpliceGatesOpen]; if open, mutates [profile] in
     * place - swapping `videoFrameWidth`/`videoFrameHeight` iff [rotation90] - so the encoder's
     * buffer is upright-shaped for the compositor to draw straight into (the "upright buffers"
     * strategy: the compositor draws with the SurfaceTexture transform matrix only, which
     * already produces upright content - device-validated, see DUALCAM_SPEC.md - so once the
     * *buffer itself* is upright-shaped, no further rotation metadata is needed; the caller
     * must also force `setOrientationHint(0)` when this returns true).
     *
     * Also snapshots the preview-view/video aspect ratios needed to map the live PIP rect from
     * preview-view space into this (possibly swapped) video space - see [VideoSpliceMapping] and
     * [currentVideoPipRectNormalized]. Does NOT build the compositor itself yet (that needs
     * MediaRecorder's surface, only valid after `prepare()` - see [attachVideoCompositor]).
     *
     * Returns whether the splice will be used for this recording.
     */
    @JvmStatic
    fun prepareVideoSplice(preview: Preview, profile: VideoProfile, rotation90: Boolean): Boolean {
        spliceArmed = false
        pendingMapping = null
        if (!isVideoSpliceGatesOpen(preview)) return false
        if (!rotation90) {
            // Landscape-held recording: the compositor's ST-matrix-only drawing produces
            // natural-portrait-upright content, which would come out sideways in a landscape
            // buffer. Until landscape compositing is implemented, record stock (no PIP) --
            // the caller shows the "PIP not recorded in this video" toast.
            Log.i(TAG, "video splice skipped: landscape-held recording not yet supported")
            return false
        }
        val previewContainer = container
        if (previewContainer == null || previewContainer.width <= 0 || previewContainer.height <= 0) return false

        if (rotation90) {
            val w = profile.videoFrameWidth
            profile.videoFrameWidth = profile.videoFrameHeight
            profile.videoFrameHeight = w
        }
        pendingCompositorWidth = profile.videoFrameWidth
        pendingCompositorHeight = profile.videoFrameHeight
        pendingMapping = VideoSpliceMapping(
            rotation90 = rotation90,
            previewViewAspect = previewContainer.width.toFloat() / previewContainer.height.toFloat(),
            videoAspect = profile.videoFrameWidth.toFloat() / profile.videoFrameHeight.toFloat(),
        )
        spliceArmed = true
        return true
    }

    /**
     * Called from CameraController2.initVideoRecorderPostPrepare, immediately before the
     * capture session that will use [recorderSurface] is built (PIP_RECON.md section 4). If
     * [prepareVideoSplice] armed a pending splice, builds the [VideoPipCompositor] (sized to the
     * dimensions [prepareVideoSplice] settled on), hands it [recorderSurface], attaches its
     * front-input surface to the front camera's session, and publishes
     * [videoRecorderSurfaceOverride] for the CameraController2 splice point to pick up. No-op if
     * nothing is armed (the overwhelmingly common case - PIP off, or gated out).
     *
     * Runs synchronously on the same (main) thread/call-stack as [prepareVideoSplice] - Preview
     * never yields the main thread between the two calls (MediaRecorder.prepare() is a blocking
     * native call, not a Looper-pumping one) - so `frontCamera`/`overlayView` cannot have changed
     * in between, which is what makes the plain (non-volatile) `pending*` fields above safe.
     *
     * Never throws: any failure here (compositor init failure, front camera gone) falls back to
     * stock (unspliced) recording for this take - logged, not surfaced to the user, matching the
     * "never crash OC" non-negotiable. In that rare case the caller (Preview) may already have
     * swapped the profile's dimensions/orientation hint for a splice that didn't actually
     * engage, which would produce a wrongly-shaped recording rather than a crash; this is an
     * accepted degradation for what should be a very rare failure (EGL/GL setup on the target
     * hardware), not the common path.
     */
    @JvmStatic
    fun attachVideoCompositor(recorderSurface: Surface) {
        if (!spliceArmed) return
        spliceArmed = false
        val mapping = pendingMapping
        pendingMapping = null
        val camera = frontCamera
        if (camera == null || mapping == null) return
        try {
            val compositor = VideoPipCompositor(
                outputWidth = pendingCompositorWidth,
                outputHeight = pendingCompositorHeight,
                frontSensorOrientation = camera.sensorOrientation,
                pipRectProvider = { currentVideoPipRectNormalized() },
            )
            val cameraInput = compositor.cameraInputSurface
            val frontInput = compositor.frontInputSurface
            if (cameraInput == null || frontInput == null) {
                Log.w(TAG, "VideoPipCompositor failed to initialize - recording without the PIP splice")
                compositor.release()
                return
            }
            compositor.attachRecorderSurface(recorderSurface)
            camera.attachCompositorOutput(frontInput)
            videoCompositor = compositor
            videoRecorderSurfaceOverride = cameraInput
            activeMapping = mapping
        } catch (e: Exception) {
            Log.w(TAG, "attachVideoCompositor failed - recording without the PIP splice", e)
            videoCompositor?.release()
            videoCompositor = null
            videoRecorderSurfaceOverride = null
            activeMapping = null
        }
    }

    /** Consulted by CameraController2 at the video_recorder_surface assignment (PIP_RECON.md
     *  section 4, ~CameraController2.java:5392). Null whenever no spliced recording is active,
     *  in which case the caller must use MediaRecorder's own surface as normal. */
    @JvmStatic
    fun getVideoRecorderSurfaceOverride(): Surface? = videoRecorderSurfaceOverride

    /** Torn down from CameraController2.reconnect() (PIP_RECON.md section 4's stop path) and
     *  defensively from [onMainCameraClosing] (covers recording stopped via onPause - both call
     *  sites funnel through Preview.stopVideo()/reconnectCamera() either way, see
     *  PIP_RECON.md section 2). Idempotent and safe to call when nothing is active. */
    @JvmStatic
    fun onVideoRecordingStopped() {
        spliceArmed = false
        pendingMapping = null
        activeMapping = null
        videoRecorderSurfaceOverride = null
        val compositor = videoCompositor
        videoCompositor = null
        compositor?.release()
        frontCamera?.detachCompositorOutput()
    }

    /**
     * Thread-safe (see [PipOverlayView]'s `@Volatile normRect`), called every frame from the
     * compositor's own GL thread. Reads the *live* on-screen PIP rect (so drag/resize during
     * recording is reflected, per PIP_SPEC.md section E) and maps it from preview-view-normalized
     * space into upright-video-normalized space via [mapPreviewRectToVideoSpace]. Falls back to
     * the raw (unmapped) rect if no mapping is available (shouldn't normally happen while a
     * compositor is actually running, but never worth crashing the render thread over).
     */
    @JvmStatic
    fun currentVideoPipRectNormalized(): RectF {
        val overlay = overlayView ?: return PipOverlayView.DEFAULT_RECT
        val rect = overlay.currentRectNormalized()
        val mapping = activeMapping ?: return rect
        return mapPreviewRectToVideoSpace(rect, mapping)
    }

    /**
     * Maps a normalized PIP rect from preview-view space into upright-video space - see
     * PIP_SPEC.md section E item 2.
     *
     * - `rotation90 == true` (device held portrait - the primary, validated case): the video
     *   buffer is upright-portrait shaped (post [prepareVideoSplice]'s dimension swap) and the
     *   preview view is also portrait, so both already agree on *orientation* - but not
     *   necessarily *aspect ratio* (e.g. a 9:16 video profile vs. some other portrait-shaped
     *   preview view). Per Camera2's default behaviour of independently center-cropping each
     *   output stream from the same sensor field of view, two portrait-ish streams (both
     *   narrower than the sensor's native ~4:3) typically share the FULL vertical field of view
     *   and differ only in how much they show horizontally - so X is rescaled about center by
     *   (previewAspect / videoAspect) and Y is left unchanged.
     * - `rotation90 == false` (device held landscape): best-effort, NOT device-validated (the
     *   primary target/tested path is portrait use per PIP_SPEC.md). The normalized rect is
     *   rotated 90 degrees clockwise first (OC's preview view container doesn't itself rotate
     *   for landscape holds - PIP_RECON.md section 1 - so it no longer shares orientation with
     *   the un-swapped, landscape-shaped video buffer), then the same aspect correction is
     *   applied with the axes' roles swapped to match.
     *
     * Result is clamped back into 0..1 defensively (a mismatched/unexpected aspect pair should
     * never be able to push the PIP off the encoded frame entirely).
     */
    private fun mapPreviewRectToVideoSpace(rect: RectF, mapping: VideoSpliceMapping): RectF {
        val oriented = if (mapping.rotation90) {
            rect
        } else {
            RectF(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
        }
        val previewAspect = if (mapping.rotation90) mapping.previewViewAspect else 1f / mapping.previewViewAspect
        val videoAspect = if (mapping.rotation90) mapping.videoAspect else 1f / mapping.videoAspect
        if (previewAspect <= 0f || videoAspect <= 0f) return oriented
        val scaleX = previewAspect / videoAspect
        val cx = 0.5f
        val left = cx + (oriented.left - cx) * scaleX
        val right = cx + (oriented.right - cx) * scaleX
        return RectF(
            left.coerceIn(0f, 1f),
            oriented.top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            oriented.bottom.coerceIn(0f, 1f),
        )
    }

    // ---- internals -------------------------------------------------------------------------

    private fun reevaluate(activity: MainActivity) {
        if (computeShouldBeActive(activity)) {
            startIfNeeded(activity)
        } else {
            stopIfNeeded()
        }
    }

    private fun computeShouldBeActive(activity: MainActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!isPipEnabledPref(activity)) return false
        val preview = activity.preview ?: return false
        if (!preview.usingCamera2API()) return false
        if (isMainCameraFrontFacing(activity)) return false
        if (activity.applicationInterface.photoMode == MyApplicationInterface.PhotoMode.Panorama) return false
        return true
    }

    private fun isMainCameraFrontFacing(activity: MainActivity): Boolean {
        val controller = activity.preview?.cameraController ?: return false
        return controller.facing == CameraController.Facing.FACING_FRONT
    }

    private fun startIfNeeded(activity: MainActivity) {
        if (overlayView != null) {
            // Already active (e.g. re-evaluated after a no-op camera reopen); nothing to do.
            return
        }
        val previewContainer = activity.findViewById<FrameLayout>(R.id.preview) ?: return

        val overlay = PipOverlayView(activity)
        val rect = loadPersistedRect(activity)
        overlay.setContainerAndRect(previewContainer.width, previewContainer.height, rect)
        overlay.rectListener = object : PipOverlayView.RectListener {
            override fun onRectSettled(settled: RectF) {
                persistRect(activity, settled)
            }
        }
        overlay.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                frontCamera?.open(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // We own teardown via stopIfNeeded()/FrontPipCamera.close(); tell the
                // framework it's safe to release the SurfaceTexture once that's done.
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                // Clamp margin: the largest of OC's three orientation-dependent navigation
                // gaps (see PIP_RECON.md section 6 / MainActivity.getNavigationGap*()),
                // applied uniformly on every edge as a simple "stay clear of system UI / OC's
                // own buttons" soft margin (PipOverlayView.updateContainerSize() takes the max
                // of what's passed for each axis anyway).
                val gap = maxOf(activity.navigationGap, activity.navigationGapLandscape, activity.navigationGapReverseLandscape)
                overlay.updateContainerSize(right - left, bottom - top, gap, gap, gap, gap)
            }
        }
        previewContainer.addOnLayoutChangeListener(layoutListener)
        containerLayoutListener = layoutListener

        previewContainer.addView(overlay)
        this.container = previewContainer
        this.overlayView = overlay

        val camera = FrontPipCamera(activity, object : FrontPipCamera.Callback {
            override fun onError(message: String) {
                Log.w(TAG, "FrontPipCamera error, hiding PIP: $message")
                activity.runOnUiThread { stopIfNeeded() }
            }

            override fun onOpened(sensorOrientation: Int) {
                // 1280x720 sensor-landscape buffer; if the transform rotates it 90/270 the
                // upright content is portrait 720x1280. Feed the overlay so it can
                // center-crop instead of letting TextureView stretch (see
                // PipOverlayView.contentAspect).
                val aspect = if (sensorOrientation % 180 != 0) 720f / 1280f else 1280f / 720f
                activity.runOnUiThread { overlayView?.contentAspect = aspect }
            }

            override fun onSpliceDegraded(reason: String) {
                // Diagnosable, never silent: the video being recorded right now won't contain
                // the PIP. Show the reason so on-device failures can actually be reported.
                Log.w(TAG, "video splice degraded: $reason")
                activity.runOnUiThread {
                    val msg = activity.getString(R.string.dualcam_pip_video_degraded, reason)
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })
        this.frontCamera = camera

        // If the TextureView's SurfaceTexture is already available (e.g. it was attached and
        // measured before this listener was set), open() won't otherwise be triggered.
        if (overlay.textureView.isAvailable) {
            overlay.textureView.surfaceTexture?.let { camera.open(it) }
        }
    }

    private fun stopIfNeeded() {
        frontCamera?.close()
        frontCamera = null

        val overlay = overlayView
        if (overlay != null) {
            (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
        overlayView = null

        val listener = containerLayoutListener
        if (listener != null) {
            container?.removeOnLayoutChangeListener(listener)
        }
        containerLayoutListener = null
        container = null
    }

    private fun loadPersistedRect(activity: MainActivity): RectF {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.contains(PreferenceKeys.DualCameraPipRectLeftKey)) {
            return PipOverlayView.DEFAULT_RECT
        }
        val left = prefs.getFloat(PreferenceKeys.DualCameraPipRectLeftKey, PipOverlayView.DEFAULT_RECT.left)
        val top = prefs.getFloat(PreferenceKeys.DualCameraPipRectTopKey, PipOverlayView.DEFAULT_RECT.top)
        val width = prefs.getFloat(PreferenceKeys.DualCameraPipRectWidthKey, PipOverlayView.DEFAULT_RECT.width())
        // height is derived from width + the fixed 3:4 aspect by PipOverlayView.clamp(), so we
        // only need to persist left/top/width.
        return RectF(left, top, left + width, top + width) // bottom is a placeholder; clamp() recomputes it from width
    }

    private fun persistRect(activity: MainActivity, rect: RectF) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit()
            .putFloat(PreferenceKeys.DualCameraPipRectLeftKey, rect.left)
            .putFloat(PreferenceKeys.DualCameraPipRectTopKey, rect.top)
            .putFloat(PreferenceKeys.DualCameraPipRectWidthKey, rect.width())
            .apply()
    }
}
