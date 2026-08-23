package net.sourceforge.opencamera.dualcam.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import net.sourceforge.opencamera.dualcam.FrontPipCamera.CapturedJpeg

/**
 * Composites the front-camera PIP still onto Open Camera's own already-processed photo
 * bitmap, matching what the on-screen PIP overlay showed: the front camera image drawn into
 * the current PIP rect (aspect-filled, mirrored like a normal selfie preview), with a thin
 * white border. See PIP_SPEC.md section D.
 *
 * Unlike the original standalone/DualCam-engine version of this file (which composited two
 * full-frame captures together, see DUALCAM_SPEC.md), Open Camera already owns producing the
 * base photo (including its own rotation/mirroring/stamps/etc via PostProcessing) - this class
 * only has to draw the small front-camera inset on top of that already-finished bitmap. Runs
 * synchronously on the caller's thread (ImageSaver's background save thread - already off the
 * UI thread, so no coroutine dispatch is needed here for Phase P1).
 */
object PhotoProcessor {

    /** Screen-pixel border width used by the on-screen PipOverlayView; scaled below to the
     *  output photo's resolution since photos are typically much larger than the preview view,
     *  so the same *proportional* border reads as "the same look" rather than an imperceptible
     *  few pixels on a multi-thousand-pixel photo. */
    private const val REFERENCE_BORDER_PX = 4f
    private const val REFERENCE_WIDTH_PX = 1080f

    /**
     * Draws [front] into [bitmap] at [pipRect] (normalized 0..1 coordinates, matching the
     * on-screen PIP position/size - see PipStillHolder). [bitmap] must be mutable; it is
     * mutated in place and also returned for convenience. The base image is Open Camera's own
     * final photo, already upright - per PIP_RECON.md section 1, OC's preview view is
     * aspect-locked to the image aspect, so the PIP rect (captured in preview-view-normalized
     * coordinates) maps directly onto image pixels with no separate crop/aspect step needed
     * (unlike the original two-camera composite, which had to center-crop the back image to
     * the viewport aspect first).
     */
    fun compositeOntoBitmap(bitmap: Bitmap, front: CapturedJpeg, pipRect: RectF, displayRotation: Int): Bitmap {
        // Total rotation for the front image within THIS photo's frame: to natural-upright
        // first (sensorOrientation), then compensated by how the device was held at capture
        // (Surface.ROTATION_90 -> -90 etc., matching the preview's counter-rotation).
        val displayDegrees = when (displayRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val totalRotation = ((front.sensorOrientation - displayDegrees) % 360 + 360) % 360
        val target = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val outW = target.width
        val outH = target.height

        // Choose inSampleSize so the front JPEG's post-rotation width is comfortably >= the
        // PIP's width in output pixels (1.5x oversample for a sharp downscale when drawn).
        val pipWidthPx = (pipRect.width() * outW).toInt().coerceAtLeast(1)
        val targetFrontWidth = (pipWidthPx * 1.5f).toInt().coerceAtLeast(1)
        val sampleSize = computeInSampleSize(front.bytes, totalRotation, targetFrontWidth)
        val frontRaw = decode(front.bytes, sampleSize)
        val frontUpright = rotateAndMirror(frontRaw, totalRotation)
        if (frontUpright !== frontRaw) frontRaw.recycle()

        val canvas = Canvas(target)
        val destRect = RectF(
            pipRect.left * outW,
            pipRect.top * outH,
            pipRect.right * outW,
            pipRect.bottom * outH,
        )
        val borderPx = REFERENCE_BORDER_PX * (outW / REFERENCE_WIDTH_PX)
        val borderRect = RectF(
            destRect.left - borderPx,
            destRect.top - borderPx,
            destRect.right + borderPx,
            destRect.bottom + borderPx,
        )
        canvas.drawRect(borderRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        drawAspectFilled(canvas, frontUpright, destRect)
        frontUpright.recycle()

        return target
    }

    private fun decode(bytes: ByteArray, sampleSize: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("Failed to decode front JPEG (sampleSize=$sampleSize)")
    }

    /** Rotates content by [totalRotation] degrees to orient it for the photo's frame, then
     *  mirrors horizontally (front camera always mirrors, matching a normal selfie preview -
     *  rotate first, then mirror). [totalRotation] = sensorOrientation adjusted by the
     *  capture-time display rotation, so a landscape-held photo gets a landscape-oriented
     *  PIP rather than one rotated to natural-portrait. */
    private fun rotateAndMirror(src: Bitmap, totalRotation: Int): Bitmap {
        val matrix = Matrix()
        if (totalRotation != 0) matrix.postRotate(totalRotation.toFloat())
        matrix.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** Draws [bitmap] into [destRect], aspect-filled (center-cropped to destRect's aspect),
     *  matching the on-screen PIP's rendering. */
    private fun drawAspectFilled(canvas: Canvas, bitmap: Bitmap, destRect: RectF) {
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destAspect = destRect.width() / destRect.height()
        val srcRect = if (srcAspect > destAspect) {
            val cropW = (bitmap.height * destAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - cropW) / 2
            Rect(left, 0, left + cropW, bitmap.height)
        } else {
            val cropH = (bitmap.width / destAspect).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - cropH) / 2
            Rect(0, top, bitmap.width, top + cropH)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, srcRect, destRect, paint)
    }

    /** Chooses a power-of-two inSampleSize so the *post-rotation* width is still >=
     *  [targetWidth] - for a 90/270 sensorOrientation the raw JPEG's height becomes the
     *  upright width after rotation, so we sample against outHeight in that case. */
    private fun computeInSampleSize(bytes: ByteArray, sensorOrientation: Int, targetWidth: Int): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val rawWidthAfterRotation = if (sensorOrientation % 180 != 0) opts.outHeight else opts.outWidth
        var sampleSize = 1
        while (rawWidthAfterRotation / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }
}
