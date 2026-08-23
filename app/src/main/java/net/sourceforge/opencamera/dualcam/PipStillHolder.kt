package net.sourceforge.opencamera.dualcam

import android.graphics.RectF
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A settable, waitable box for the front-camera still that [FrontPipCamera.captureStill]
 * produces asynchronously (on its own camera thread), created by [PipController.onCaptureStarted]
 * at the moment OC's own capture begins and consumed later by ImageSaver's background thread
 * once OC's own JPEG has arrived and is ready to be composited (see PIP_SPEC.md section D).
 *
 * [pipRect] and [viewAspect] are snapshotted at creation time (i.e. at capture-start), so a
 * drag/resize that happens while the shutter is still processing does not affect where this
 * particular photo's PIP gets composited.
 */
class PipStillHolder(
    val pipRect: RectF,
    /** Display rotation (Surface.ROTATION_*) at capture-start, so PhotoProcessor can orient
     *  the front image to match a landscape-held photo (not just natural-portrait). */
    val displayRotation: Int,
) {
    private val latch = CountDownLatch(1)

    @Volatile
    private var result: FrontPipCamera.CapturedJpeg? = null

    /** Called (once) from [FrontPipCamera]'s camera thread when the front still capture
     *  completes (successfully or not - [jpeg] is null on failure). */
    fun complete(jpeg: FrontPipCamera.CapturedJpeg?) {
        result = jpeg
        latch.countDown()
    }

    /**
     * Blocks the calling thread (ImageSaver's background save thread) until the front still
     * arrives or [timeoutMs] elapses, whichever is first - so this NEVER blocks longer than
     * [timeoutMs] regardless of outcome. Interruption-safe: if the waiting thread is
     * interrupted, we re-assert the interrupt flag (so callers further up notice) and return
     * null immediately rather than looping or rethrowing, so the caller can fall back to
     * saving the photo unchanged.
     */
    fun awaitResult(timeoutMs: Long): FrontPipCamera.CapturedJpeg? {
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
