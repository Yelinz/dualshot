# DualCam — Architecture Specification

Simultaneous front+back camera capture app. Primary target device: **Fairphone 5**
(Snapdragon 695, Android 15 / API 35, camera ids: `0`=BACK main, `1`=FRONT, `2`=BACK aux;
all hardware level LEVEL_3).

## Device facts established by on-device probe (do not re-litigate)

- `FEATURE_CAMERA_CONCURRENT` is **false** and `getConcurrentCameraIds()` is **EMPTY**
  on the FP5, but **raw Camera2 concurrent front+back streaming WORKS**: both cameras
  opened via `CameraManager.openCamera`, both sessions streamed 1280x720 YUV
  simultaneously at ~24-30fps for 8s (187/188 frames each).
- Therefore: **do NOT use CameraX and do NOT gate anything on FEATURE_CAMERA_CONCURRENT.**
  Open both cameras directly with Camera2 and handle failure gracefully.
- `ERROR_CAMERA_DISABLED (3)` was observed at probe teardown *after* successful streaming.
  Treat camera `onError`/`onDisconnected` that arrives after we initiated close as benign;
  only surface errors to the user when they occur while we expect the camera to be running.

## Product behaviour

- Portrait-locked, fullscreen. Back camera fills the screen (aspect-fill / center-crop).
  Front camera renders in a small draggable, pinch-resizable PIP window (rounded corners
  optional, thin white border) — mirrored like a normal selfie preview.
- **Photo button**: captures a high-res still from BOTH cameras, composites the front
  image into the back image at the current on-screen PIP position, saves ONE jpeg.
- **Record button**: records mp4 video (with mic audio) of the composited scene —
  the PIP is baked into the video exactly as seen on screen. PIP can be moved/resized
  live during recording. Second tap stops and saves.
- On any fatal camera error: Toast + finish gracefully, never crash.

## Architecture

Single activity, classic Views (no Compose). Package `com.yelinzhang.dualcam`.

```
MainActivity ── owns ──> DualCameraController   (camera2, camera HandlerThread)
      │                        │  two preview SurfaceTextures + two JPEG ImageReaders
      ├── GLSurfaceView + DualPreviewRenderer   (GL thread: composite scene)
      │                        │  draws scene to display; when recording, ALSO draws
      │                        └─ scene to VideoRecorder's encoder EGL surface
      ├── PipGestureView (transparent overlay: drag + pinch)
      ├── VideoRecorder (MediaCodec AVC surface input + AAC mic + MediaMuxer)
      └── PhotoProcessor (Canvas composite of the two JPEGs) ──> MediaSaver (MediaStore)
```

### Threading model

- **GL thread** (GLSurfaceView, RENDERMODE_WHEN_DIRTY): all EGL/GLES calls, including
  all VideoRecorder EGL work (encoder window surface created lazily on first frame via
  `EGL14.eglGetCurrentContext()` sharing). `SurfaceTexture.updateTexImage()` only here.
- **Camera thread**: one `HandlerThread` for all Camera2 callbacks of both cameras.
- **Audio thread**: `AudioRecord` read loop + AAC encoder feed.
- **Muxer**: guarded by a single lock object; started only when BOTH tracks are added;
  `writeSampleData` synchronized. Samples that arrive before muxer start are dropped
  (video) / buffered briefly or dropped (audio) — starting cleanly matters more than
  the first 100ms.
- UI state (pipRect) crosses threads: publish as `@Volatile var pipRect: RectF`
  (replace the object on change, never mutate in place).

## Component contracts

### camera/DualCameraController.kt

```kotlin
class DualCameraController(context: Context, callback: Callback) {
    interface Callback {
        fun onCamerasReady()                          // both sessions configured & streaming
        fun onCameraFatalError(message: String)       // unexpected error while running
    }
    /** Call from GL thread callback once both SurfaceTextures exist.
     *  Sizes: back preview 1920x1080, front preview 1280x720 (set as
     *  setDefaultBufferSize before session creation; these are sensor-landscape sizes). */
    fun open(backPreview: SurfaceTexture, frontPreview: SurfaceTexture)
    fun close()   // idempotent; sets an internal 'closing' flag consulted by error callbacks
    /** Fires STILL_CAPTURE on both cameras; invokes cb on camera thread with JPEG bytes
     *  (null on per-camera failure). Also reports each camera's SENSOR_ORIENTATION and
     *  whether it is front-facing so PhotoProcessor can rotate/mirror correctly. */
    fun takePhoto(cb: (back: CapturedJpeg?, front: CapturedJpeg?) -> Unit)

    data class CapturedJpeg(val bytes: ByteArray, val sensorOrientation: Int, val isFront: Boolean)
}
```

Implementation notes:
- Select backId = first `LENS_FACING_BACK` id (expect "0"), frontId = first
  `LENS_FACING_FRONT` id (expect "1").
- Open BACK first, then FRONT from BACK's `onOpened` (matches proven probe sequence).
- Per camera one session with outputs: [preview SurfaceTexture Surface, JPEG ImageReader
  Surface]. JPEG sizes: largest available JPEG size for back; largest for front.
- Repeating request: `TEMPLATE_PREVIEW`, `CONTROL_MODE_AUTO`, video-stable off.
  30fps target FPS range if available (choose range containing 30 with smallest span).
- Still capture: `TEMPLATE_STILL_CAPTURE` targeting the JPEG reader (keep preview in
  the session; do not tear down). `JPEG_ORIENTATION` left at 0 — PhotoProcessor rotates.
- All error paths: if `closing` flag set → log only. Otherwise → `onCameraFatalError`.

### gl/DualPreviewRenderer.kt (+ gl/GlUtil.kt)

- `GLSurfaceView.Renderer`, GLES 2.0. Two OES textures + SurfaceTextures created in
  `onSurfaceCreated`; delivered to MainActivity via callback (which then calls
  `controller.open(...)` on the main thread).
- `onFrameAvailable` (either camera) → per-texture `AtomicBoolean` pending flag +
  `requestRender()`. In `onDrawFrame`: `updateTexImage()` for each pending texture,
  then draw scene to display, then if `videoSink != null` call
  `videoSink.onFrame(sceneDrawer, System.nanoTime())`.
- Scene drawing is factored as `drawScene(viewportW: Int, viewportH: Int)` so the same
  code renders both display and encoder surfaces:
  1. Clear black.
  2. Draw BACK texture as fullscreen quad, aspect-filled.
  3. Draw thin white border quad slightly larger than PIP rect.
  4. Draw FRONT texture inside PIP rect, aspect-filled, mirrored horizontally.
- `interface GlVideoSink { fun onFrame(scene: (w: Int, h: Int) -> Unit, timestampNs: Long) }`
  — implemented by VideoRecorder. Contract: called on GL thread with the display EGL
  context current; the sink makeCurrent()s its own surface, invokes `scene(w, h)`,
  sets presentation time, swapBuffers, then **restores the display surface current**
  (save `eglGetCurrentSurface/Display/Context` before switching).
- `@Volatile var pipRect: RectF` in **normalized viewport coords** (0..1, origin top-left),
  default `RectF(0.62f, 0.04f, 0.96f, 0.30f)`; clamped by gesture layer.

**Rotation/mirroring (CORRECTED after first Fairphone 5 on-device test):**
- The 4x4 matrix from `SurfaceTexture.getTransformMatrix()` is the ONLY orientation
  source. On Camera2, buffers arrive tagged with the HAL transform (rotation to
  upright + horizontal mirror for front-facing cameras) and the ST matrix already
  includes it. The first implementation added an app-side `-SENSOR_ORIENTATION`
  rotation on top, which cancelled the ST matrix's rotation and left both previews
  sideways on device. Do NOT add app-side rotation or front mirroring in GL.
- Use grafika-convention quads: texcoord (0,0) on the bottom-left vertex, so the
  GL-convention y-flip baked into the ST matrix lands upright.
- `SENSOR_ORIENTATION` is still needed for two things: (a) the upright content aspect
  for aspect-fill cropping in GL (a landscape buffer shown via a 90/270 transform is
  portrait content), and (b) rotating the still-capture JPEGs in PhotoProcessor —
  JPEG buffers carry NO transform tag and DO need manual rotation (+ manual mirror
  for the front camera).
- Aspect-fill: scale texcoords about center to crop: if contentAspect > targetAspect
  crop left/right, else crop top/bottom.

### ui/PipGestureView.kt

- Transparent View overlaying the GLSurfaceView. Touch inside current PIP rect: drag
  moves it; two-finger pinch (anywhere) resizes about PIP center. Width range
  20%..60% of viewport width; aspect ratio fixed at 3:4 (portrait). Clamp fully
  on-screen with 2% margin. On every change, write a NEW RectF to renderer.pipRect
  and `requestRender()`.
- Touches outside PIP fall through (return false) — reserved for future tap-to-focus.

### record/VideoRecorder.kt (+ record/AudioEncoder.kt, record/MuxerWrapper.kt)

- `start(outputFd: FileDescriptor, width=1080, height=1920)`:
  video `MediaCodec` "video/avc", 1080x1920, 30fps, ~14 Mbps, I-frame interval 1s,
  `createInputSurface()`; audio `MediaCodec` AAC-LC 48kHz mono 128kbps fed by
  `AudioRecord` (SOURCE_CAMCORDER) on the audio thread; `MediaMuxer` on the fd.
- Implements `GlVideoSink.onFrame`: lazily create EGL window surface for the encoder
  input surface **sharing the current (display) context**; drain video encoder
  non-blocking each frame; `eglPresentationTimeANDROID` with the passed timestamp.
- `stop()`: signal video EOS via `signalEndOfInputStream`, audio EOS via BUFFER_FLAG_EOS,
  drain both fully, stop/release muxer, release EGL surface **on the GL thread**
  (post a runnable via GLSurfaceView.queueEvent).
- Muxer state machine: track indices added from each encoder's INFO_OUTPUT_FORMAT_CHANGED;
  start muxer when both added; drop samples before start; after stop() everything is
  torn down even if one encoder errored (best-effort, log).

### photo/PhotoProcessor.kt

Runs on `Dispatchers.Default`. Input: two `CapturedJpeg` + current `pipRect` + viewport
aspect (h/w of the GL view). Steps:
1. Decode back JPEG (full res; use `inSampleSize=1`), rotate bitmap by its
   sensorOrientation so it is upright portrait.
2. **Center-crop the upright back bitmap to the viewport aspect** so the saved photo
   frames exactly what the screen showed. (12MP → still plenty after crop.)
3. Decode front JPEG with `inSampleSize` chosen so its width ≳ pipRect width in output
   pixels ×1.5; rotate upright; mirror horizontally (match preview).
4. Canvas-draw front into pipRect mapped to cropped-back pixel coords (aspect-fill
   inside the rect, matching GL), plus the same thin white border.
5. JPEG quality 95 → `MediaSaver`.

### storage/MediaSaver.kt

- MediaStore only (no WRITE_EXTERNAL_STORAGE): images →
  `Pictures/DualCam` `IS_PENDING` pattern; videos → `Movies/DualCam`: insert pending,
  return `ParcelFileDescriptor` for the muxer, finalize (clear IS_PENDING) on stop.
  Filenames `DUAL_yyyyMMdd_HHmmss.jpg/.mp4`.

### MainActivity.kt

- Runtime permissions (CAMERA + RECORD_AUDIO) → then init GL view + renderer.
- Wires: renderer surfaces-ready → controller.open; controller.onCamerasReady →
  enable buttons; photo button → controller.takePhoto → PhotoProcessor → MediaSaver →
  Toast "Saved"; record button → MediaSaver.newVideo → VideoRecorder.start →
  renderer.videoSink = recorder; stop → sink=null first, then recorder.stop, finalize,
  Toast. Recording timer TextView updated every second. `keepScreenOn = true`.
- `onPause`: stop recording if active, controller.close(), GLSurfaceView.onPause().
  `onResume`: reverse. (Cameras must re-open cleanly — SurfaceTextures survive;
  guard against double-open.)
- Layout `res/layout/activity_main.xml`: FrameLayout { GLSurfaceView, PipGestureView,
  bottom bar with two ImageButtons (photo ⚪, record 🔴/⏹ tint swap) + timer TextView }.
  Use simple built-in drawables/shapes; no icon assets required beyond a trivial
  adaptive launcher icon (`res/mipmap-anydpi-v26/ic_launcher.xml` + simple vector).

## Build

- Gradle wrapper 8.13, AGP 8.10.1, Kotlin 2.1.0 (already configured in the scaffold).
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug` must pass —
  run it yourself before finishing and fix all compile errors.
- Only deps: core-ktx, kotlinx-coroutines-android. No CameraX, no appcompat, no Compose.

## Out of scope (MVP)

Tap-to-focus, zoom, flash, camera swap, settings, landscape, saving the two source
photos separately, in-video PIP animation smoothing.
