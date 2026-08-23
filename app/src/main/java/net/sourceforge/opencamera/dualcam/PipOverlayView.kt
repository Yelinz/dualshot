package net.sourceforge.opencamera.dualcam

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * The on-screen PIP: a small child of Open Camera's `R.id.preview` FrameLayout, sized and
 * positioned via its own [FrameLayout.LayoutParams] derived from a normalized [RectF] (see
 * PIP_SPEC.md section C). Contains a [textureView] (the front camera's preview surface) and a
 * thin white border background.
 *
 * Because this view's bounds are exactly the PIP rect (nothing more), Android's normal
 * touch-dispatch hit-testing already guarantees "touches outside the PIP fall through to Open
 * Camera" for free: a ViewGroup only offers ACTION_DOWN to a child whose bounds contain the
 * touch point, so a tap-to-focus or pinch-zoom gesture that starts outside this view's bounds
 * is never delivered here at all - it goes straight to OC's own camera surface underneath (see
 * PIP_RECON.md section 6 / PhotoProcessor's caller PipController for how this view gets added
 * as the LAST child of the preview FrameLayout, i.e. drawn - and hit-tested - on top).
 *
 * Gesture handling (drag body / pinch-resize) is ported from the deleted PipGestureView, with
 * one behavioural change per PIP_SPEC.md: pinch is only recognised when it *starts* inside this
 * view (which, given the above, is the only way it can start at all) - "pinch anywhere" is
 * intentionally not supported, so pinch-to-zoom outside the PIP remains OC's own gesture.
 */
class PipOverlayView(context: Context) : FrameLayout(context) {

    companion object {
        const val MIN_WIDTH_FRAC = 0.20f
        const val MAX_WIDTH_FRAC = 0.50f
        const val ASPECT_W = 3f
        const val ASPECT_H = 4f

        /** Default PIP rect (normalized), used the first time there's nothing persisted yet:
         *  top-right corner, ~32% of the preview width wide. */
        val DEFAULT_RECT = normalizedRectForWidth(0.62f, 0.04f, 0.32f, 1f)

        private fun normalizedRectForWidth(left: Float, top: Float, width: Float, containerAspect: Float): RectF {
            // containerAspect == containerHeightPx/containerWidthPx; default assumes a
            // portrait-ish container where this is roughly irrelevant until updateContainerSize()
            // recomputes it properly, see heightForWidth().
            val height = width * ASPECT_H / ASPECT_W * containerAspect
            return RectF(left, top, left + width, top + height)
        }
    }

    interface RectListener {
        /** Called once a drag or pinch gesture ends, with the settled normalized rect, so the
         *  caller can persist it. */
        fun onRectSettled(rect: RectF)
    }

    var rectListener: RectListener? = null

    val textureView = TextureView(context)

    /** Current PIP rect in coordinates normalized to the *container's* size (0..1). Volatile:
     *  read cross-thread by [currentRectNormalized] from the video splice compositor's GL
     *  thread during a spliced recording (Phase P2, PIP_SPEC.md section E) - never mutated in
     *  place, always reassigned wholesale, so a volatile reference is enough for safe
     *  publication (the RectF a reader sees is always a fully-constructed one). */
    @Volatile
    private var normRect = RectF(DEFAULT_RECT)

    private var containerWidthPx = 0
    private var containerHeightPx = 0

    /** Extra margin (normalized, symmetric) kept clear at the container's edges, derived from
     *  MainActivity.getNavigationGap*() - see PIP_RECON.md section 6. Applied uniformly as a
     *  simplification (rather than per-edge-per-orientation like MainUI.layoutUI does) since
     *  this is just a soft "stay clear of system UI / OC's own buttons" clamp, not a hard
     *  layout requirement. */
    private var edgeMarginNormX = 0.02f
    private var edgeMarginNormY = 0.02f

    private enum class Mode { NONE, DRAG, PINCH }
    private var mode = Mode.NONE

    private var dragStartTouchX = 0f
    private var dragStartTouchY = 0f
    private val dragStartRect = RectF()

    private var pinchStartDist = 0f
    private val pinchStartRect = RectF()

    /**
     * Upright content aspect (width/height) of the front camera feed, e.g. 720/1280 for a
     * 1280x720 buffer whose transform rotates it to portrait. When set, the TextureView is
     * center-crop transformed so the (typically 9:16) feed fills this 3:4 window without
     * distortion -- TextureView's default behaviour is to stretch the buffer to the view
     * bounds, which would squash the image. 0 = unknown, no transform applied.
     */
    var contentAspect: Float = 0f
        set(value) {
            field = value
            applyTextureTransform()
        }

    init {
        isClickable = true
        addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val borderPx = dpToPx(2f)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(borderPx, Color.WHITE)
        }
        setPadding(borderPx, borderPx, borderPx, borderPx)
        clipToPadding = false
        // Re-apply the center-crop transform whenever the TextureView's laid-out size
        // changes (drag-resize, container resize) -- the matrix depends on view dimensions.
        textureView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyTextureTransform()
            }
        }
    }

    /**
     * Rotation-aware center-crop matrix. TextureView renders the camera content upright
     * relative to the device's NATURAL orientation and stretched to the view bounds; this
     * matrix (a) counter-rotates when the display is rotated (Open Camera's activity rotates
     * with the device -- same responsibility its own Preview.configureTransform() handles for
     * the main preview), and (b) restores the content's aspect with a center-crop fill.
     * Without (a), a landscape-held device shows the PIP content 90 degrees off.
     */
    private fun applyTextureTransform() {
        val aspect = contentAspect // natural-upright width/height of the feed
        val vw = textureView.width
        val vh = textureView.height
        if (aspect <= 0f || vw <= 0 || vh <= 0) return
        val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
        val cx = vw / 2f
        val cy = vh / 2f
        val matrix = android.graphics.Matrix()
        if (rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270) {
            // Counter-rotate (sign convention matches OC's configureTransform:
            // 90*(rotation-2) -> -90 for ROTATION_90, +90 for ROTATION_270), then scale the
            // rotated (vh x vw) rect so the content (displayed aspect = 1/aspect in this
            // orientation) covers the box, cropping overflow.
            val displayedAspect = 1f / aspect
            val h = maxOf(vh.toFloat(), vw / displayedAspect)
            val w = h * displayedAspect
            matrix.postRotate(if (rotation == android.view.Surface.ROTATION_90) -90f else 90f, cx, cy)
            matrix.postScale(w / vh, h / vw, cx, cy)
        } else {
            val h = maxOf(vh.toFloat(), vw / aspect)
            val w = h * aspect
            if (rotation == android.view.Surface.ROTATION_180) matrix.postRotate(180f, cx, cy)
            matrix.postScale(w / vw, h / vh, cx, cy)
        }
        textureView.setTransform(matrix)
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    /** Current rect, in a fresh RectF so callers can't mutate our internal state. */
    fun currentRectNormalized(): RectF = RectF(normRect)

    /** Sets the container (i.e. `R.id.preview`) pixel size and an explicit rect to use (e.g.
     *  the persisted one), and applies layout immediately. */
    fun setContainerAndRect(containerW: Int, containerH: Int, rect: RectF) {
        containerWidthPx = containerW
        containerHeightPx = containerH
        normRect = clamp(rect)
        applyLayout()
    }

    /** Call whenever the container's size changes (rotation, inset changes, etc.) so we can
     *  re-clamp and re-margin - see PIP_RECON.md section 6. */
    fun updateContainerSize(containerW: Int, containerH: Int, marginLeftPx: Int, marginRightPx: Int, marginTopPx: Int, marginBottomPx: Int) {
        containerWidthPx = containerW
        containerHeightPx = containerH
        if (containerW > 0) {
            edgeMarginNormX = (maxOf(marginLeftPx, marginRightPx).toFloat() / containerW).coerceIn(0.01f, 0.3f)
        }
        if (containerH > 0) {
            edgeMarginNormY = (maxOf(marginTopPx, marginBottomPx).toFloat() / containerH).coerceIn(0.01f, 0.3f)
        }
        normRect = clamp(normRect)
        applyLayout()
        // Rotation changes arrive as container-size changes (portrait<->landscape); the
        // display-rotation-dependent transform must follow even when the PIP's own laid-out
        // size happens not to change.
        applyTextureTransform()
    }

    private fun heightForWidth(widthNorm: Float): Float {
        val w = containerWidthPx.takeIf { it > 0 } ?: 1
        val h = containerHeightPx.takeIf { it > 0 } ?: 1
        // Fixed 3:4 (w:h) real-pixel aspect ratio, expressed in normalized container
        // coordinates (container pixel aspect == final photo aspect, see PIP_RECON.md section 1).
        return widthNorm * w * ASPECT_H / (h * ASPECT_W)
    }

    private fun widthForHeight(heightNorm: Float): Float {
        val w = containerWidthPx.takeIf { it > 0 } ?: 1
        val h = containerHeightPx.takeIf { it > 0 } ?: 1
        return heightNorm * h * ASPECT_W / (w * ASPECT_H)
    }

    private fun clamp(rect: RectF): RectF {
        var width = rect.width().coerceIn(MIN_WIDTH_FRAC, MAX_WIDTH_FRAC)
        var height = heightForWidth(width)
        // if the aspect-derived height doesn't fit, shrink width to fit height instead
        val maxHeight = 1f - 2 * edgeMarginNormY
        if (height > maxHeight && maxHeight > 0f) {
            height = maxHeight
            width = widthForHeight(height).coerceIn(MIN_WIDTH_FRAC, MAX_WIDTH_FRAC)
            height = heightForWidth(width)
        }
        val maxLeft = (1f - edgeMarginNormX - width).coerceAtLeast(edgeMarginNormX)
        val maxTop = (1f - edgeMarginNormY - height).coerceAtLeast(edgeMarginNormY)
        val left = rect.left.coerceIn(edgeMarginNormX, maxLeft)
        val top = rect.top.coerceIn(edgeMarginNormY, maxTop)
        return RectF(left, top, left + width, top + height)
    }

    private fun applyLayout() {
        if (containerWidthPx <= 0 || containerHeightPx <= 0) return
        // Must work BEFORE this view is attached too: PipController calls
        // setContainerAndRect() before addView(), and ViewGroup.addView(child) adopts the
        // child's existing layoutParams when set. (The original parent-null early-return here
        // meant the overlay was added with FrameLayout's default MATCH_PARENT params -- a
        // fullscreen front camera covering the whole preview.)
        val lp = (layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
        lp.width = (normRect.width() * containerWidthPx).toInt().coerceAtLeast(1)
        lp.height = (normRect.height() * containerHeightPx).toInt().coerceAtLeast(1)
        lp.leftMargin = (normRect.left * containerWidthPx).toInt()
        lp.topMargin = (normRect.top * containerHeightPx).toInt()
        layoutParams = lp
        (parent as? ViewGroup)?.requestLayout()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.DRAG
                dragStartTouchX = event.rawX
                dragStartTouchY = event.rawY
                dragStartRect.set(normRect)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    mode = Mode.PINCH
                    pinchStartDist = distance(event)
                    pinchStartRect.set(normRect)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val w = containerWidthPx.takeIf { it > 0 } ?: return true
                val h = containerHeightPx.takeIf { it > 0 } ?: return true
                when (mode) {
                    Mode.DRAG -> {
                        val dxNorm = (event.rawX - dragStartTouchX) / w
                        val dyNorm = (event.rawY - dragStartTouchY) / h
                        val width = dragStartRect.width()
                        val height = dragStartRect.height()
                        val left = dragStartRect.left + dxNorm
                        val top = dragStartRect.top + dyNorm
                        normRect = clamp(RectF(left, top, left + width, top + height))
                        applyLayout()
                        return true
                    }
                    Mode.PINCH -> {
                        if (event.pointerCount >= 2 && pinchStartDist > 0.001f) {
                            val dist = distance(event)
                            val scale = dist / pinchStartDist
                            val newWidth = (pinchStartRect.width() * scale).coerceIn(MIN_WIDTH_FRAC, MAX_WIDTH_FRAC)
                            val newHeight = heightForWidth(newWidth)
                            val cx = pinchStartRect.centerX()
                            val cy = pinchStartRect.centerY()
                            val left = cx - newWidth / 2f
                            val top = cy - newHeight / 2f
                            normRect = clamp(RectF(left, top, left + newWidth, top + newHeight))
                            applyLayout()
                        }
                        return true
                    }
                    Mode.NONE -> return true
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Simplification: ending one finger of a pinch ends the gesture rather than
                // downgrading to a drag (matches the deleted PipGestureView's behaviour).
                if (mode == Mode.PINCH) {
                    mode = Mode.NONE
                    rectListener?.onRectSettled(currentRectNormalized())
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasActive = mode != Mode.NONE
                mode = Mode.NONE
                if (wasActive) {
                    rectListener?.onRectSettled(currentRectNormalized())
                }
            }
        }
        return true
    }

    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }
}
