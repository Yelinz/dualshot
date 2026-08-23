# Dual-camera PIP inside Open Camera's native modes — Implementation Spec

Replaces the separate `DualCameraActivity` approach. The PIP becomes a toggleable
feature of Open Camera's normal photo/video modes, so every OC option (flash,
exposure, zoom, focus, resolutions, HDR, timer, …) keeps working on the main camera.
Read `docs/PIP_RECON.md` first — it has verified file:line splice points; this spec
assigns the architecture. Target device: Fairphone 5 (Android 15); concurrent
front+back streaming is device-validated (see `DUALCAM_SPEC.md` for the engine
history and the load-bearing orientation rule: **SurfaceTexture transform matrix is
the ONLY orientation source in GL; JPEG stills need manual rotation**).

## User-visible behavior

- The on-screen "dual camera" icon **toggles PIP on/off** (no activity launch).
  Preference `preference_dual_camera_pip` (default off) + the existing
  `preference_show_dual_camera_mode` icon-visibility pref stay independent.
- PIP on → a small front-camera window floats over OC's normal preview; drag to
  move, pinch to resize; position/size persist across sessions (SharedPreferences,
  normalized floats).
- Photo capture (any OC single-output mode incl. HDR/DRO/NR): saved photo has the
  front image composited at the PIP's on-screen position. Multi-output/exotic modes
  (RAW-only, Panorama, FastBurst, Expo/FocusBracketing) save unchanged.
- Video (Camera2, not slow-motion): recorded file has the PIP baked in, live
  repositionable during recording. Timelapse works. Video snapshots get the photo
  composite. Slow-motion/high-speed or Camera1: video records WITHOUT PIP
  (preview PIP hides during such recordings, one toast explains why).
- PIP auto-hides while OC itself is using the front camera, and in Panorama mode.
- API < 29 or Camera1-only devices: icon hidden (existing gate) / feature inert.

## Architecture (new package `net.sourceforge.opencamera.dualcam`, replacing most of it)

DELETE: `DualCameraActivity.kt`, `camera/DualCameraController.kt`,
`record/{VideoRecorder,AudioEncoder,MuxerWrapper}.kt`, `storage/MediaSaver.kt`,
`gl/DualPreviewRenderer.kt`, `ui/PipGestureView.kt`, `layout/activity_dual_camera.xml`,
now-unused strings/drawables (keep `ic_dual_camera`, keep/adapt `dualcam_rec_dot` if
used). KEEP: `gl/GlUtil.kt`, `photo/PhotoProcessor.kt` (adapt).

### A. `PipController.kt` — feature façade (owned by MainActivity)
Singleton-per-activity object wiring everything below. Lifecycle entry points
(called from the OC hooks listed in PIP_RECON §2):
- `onMainCameraOpened()` (end of Preview.cameraOpened): if enabled && API29+ &&
  usingCamera2API && OC facing != FRONT && photoMode != Panorama → open front
  camera + show overlay; else hide/close.
- `onMainCameraClosing()` (top of Preview.onPause, mySurfaceDestroyed, before
  setCamera's close): close front camera, hide overlay.
- `onToggled()` (icon click): flip pref, then behave like onMainCameraOpened().
Keep OC-side patch lines MINIMAL: one call per hook, guarded so Camera1/old-API
paths are no-ops.

### B. `FrontPipCamera.kt` — front camera controller (derived from the deleted DualCameraController, front-only)
- Opens the first LENS_FACING_FRONT id on its own HandlerThread.
- Session outputs: [PIP preview SurfaceTexture (from the overlay's TextureView,
  720p default buffer), JPEG ImageReader (max size)] — plus, only while a spliced
  video recording is active, [compositor SurfaceTexture]. Adding/removing the
  compositor output recreates the front session (cheap; mirrors OC's own
  session-recreate-on-record behavior).
- `captureStill(cb)` → single STILL_CAPTURE to the JPEG reader (JPEG_ORIENTATION 0;
  manual rotation downstream), reports `CapturedJpeg(bytes, sensorOrientation, isFront=true)`.
- Benign-error handling after close (same pattern as the old controller).
- Errors while running → log + hide PIP; NEVER take down OC.

### C. `PipOverlayView.kt` — the on-screen PIP (child of `R.id.preview` FrameLayout)
- FrameLayout wrapper: TextureView + thin white border (background drawable).
  Positioned/sized via its own LayoutParams from a normalized RectF.
- TextureView content orientation: apply the same transform approach as OC's
  MyTextureView (`setTransform`) or rely on TextureView default; verify: front
  camera into TextureView normally displays correctly without app transform
  (TextureView consumes buffer transform flags natively). Do NOT rotate the view
  itself with MainUI's icon rotation.
- Gesture handling (port the deleted PipGestureView logic): drag inside rect,
  pinch resize (width 20–50% of preview view, 3:4 aspect), clamp inside the
  preview view minus `MainActivity.getNavigationGap*()` margins; re-clamp on
  layout changes. Persist rect (two/four floats) on gesture end.
- Touches outside the PIP rect MUST fall through to OC (tap-to-focus etc. keep
  working): return false for ACTION_DOWN outside the rect. (Pinch-anywhere is NOT
  needed here — pinch outside the PIP must remain OC's zoom gesture. Pinch only
  when the gesture starts inside the PIP.)

### D. Photo composite path
1. Hook `MyApplicationInterface.onCaptureStarted()` (:2858): if PIP active →
   create `PipStillHolder` (a settable, waitable holder: front CapturedJpeg +
   normalized pipRect snapshot + preview-view aspect), trigger
   `FrontPipCamera.captureStill`, stash holder in PipController.
2. `MyApplicationInterface.saveImage(...)`/`saveImageJpeg` (:3622): pass the
   pending holder (if any, then clear it) into `ImageSaver.Request` (new field
   `pip_still: Object/holder`, nullable).
3. `ImageSaver.saveSingleImageNow` right after the postProcessBitmap line
   (:1910-1911): if `request.pip_still != null && request.process_type == NORMAL
   && jpeg_images.size() == 1 && !isRawOnly()` → wait on the holder (timeout
   1500ms; on timeout log + save unchanged), then composite via adapted
   `PhotoProcessor`: decode front JPEG (subsampled), rotate upright by
   sensorOrientation, mirror horizontally, draw into pipRect mapped onto the
   final bitmap (OC preview view is aspect-locked to the image aspect per recon
   §1, so normalized view coords map directly onto image pixels), white border
   scaled like before. If `bitmap == null` decode the JPEG mutable first (recon §3).
4. Respect OC's own mirroring: OC may mirror front-facing OUTPUT when OC's main
   camera is front — irrelevant here (PIP disabled when OC is front-facing).

### E. Video splice (Camera2, non-high-speed only)
New `gl/VideoPipCompositor.kt` — standalone GL thread (own EGL recordable context;
adapt the EGL-window-surface technique from the deleted VideoRecorder.kt, but the
encoder is OC's MediaRecorder — we do NOT own codecs/muxer/audio/files):
- Inputs: back-camera SurfaceTexture (camera input surface handed to OC's session)
  + front-camera SurfaceTexture (extra output on the front session). Both drawn
  with their ST transform matrices ONLY (orientation rule!). Output: EGL window
  surface on `MediaRecorder.getSurface()`.
- Render on back-frame-available: back full-frame aspect-fill, then front into the
  live pipRect (read volatile from PipController) + border. Use
  `eglPresentationTimeANDROID` with the back SurfaceTexture's `getTimestamp()`.
- **Orientation strategy (upright buffers)**: in `Preview.startVideoRecording`,
  when the splice is active, BEFORE `profile.copyToMediaRecorder` swap
  `videoFrameWidth/Height` so the buffer is upright for the current
  `getImageVideoRotation()` (swap iff rotation % 180 == 90), and force
  `setOrientationHint(0)` (:6163 path). The compositor draws upright content
  (ST matrices produce upright — device-validated), pipRect maps 1:1 from the
  portrait preview. This exactly reproduces the validated standalone pipeline.
  Map pipRect by rotating the normalized rect if the device is held landscape
  (rotation 0/180 → preview-view rect is already in upright space; document the
  mapping in code).
- Splice mechanics (recon §4): in `CameraController2.initVideoRecorderPostPrepare`
  (:7588), if PipController says active → build compositor (sized to the possibly
  swapped profile dims), give it recorder.getSurface(), and substitute the
  assignment at :5392 (`video_recorder_surface = pip_compositor_input_surface`).
  Implement via a nullable static/injected hook on CameraController2 set by
  PipController (keep the patch minimal and obviously reversible; a
  `public static volatile Surface video_recorder_surface_override` is acceptable
  if a cleaner injection needs large refactors — but prefer passing through
  MyApplicationInterface if a path exists).
- Teardown: in `reconnect` (:7621) / recording-stopped path, destroy the
  compositor, remove the front session's compositor output, clear the override.
- Gates (ALL must hold, else record exactly as stock OC and hide the preview PIP
  for the duration with one toast): PIP pref on && overlay currently shown &&
  usingCamera2API && !fpsIsHighSpeed && !extension session. Timelapse allowed.

### F. OC-side patches (keep each as small as possible; mark every block with
`// DUALCAM-PIP begin/end` comments so the fork diff is auditable)
1. `Preview.cameraOpened()` — one call.
2. `Preview.onPause` / `mySurfaceDestroyed` / `setCamera` — one call each.
3. `Preview.startVideoRecording` — profile dim swap + hint override when active.
4. `CameraController2.java:5392` area — surface substitution (+ static override or
   injected supplier).
5. `CameraController2.initVideoRecorderPostPrepare` — compositor construction hook
   (or do construction inside PipController triggered from Preview just before
   :6176's call, whichever yields the smaller CameraController2 diff).
6. `MyApplicationInterface.onCaptureStarted` + `saveImage/saveImageJpeg` — holder.
7. `ImageSaver` — Request field + composite call at the :1911 choke point.
8. `MainActivity.clickedDualCameraMode` — toggle; `OnScreenIcons` —
   `updateDualCameraModeIcon()` (icon alpha/tint reflects on/off) registered in
   `updateOnScreenIcons()`.
9. Manifest — remove DualCameraActivity. Settings XML — keep the icon-visibility
   pref; reword summary strings for the toggle behavior.

## Phasing

- **Phase P1** (first agent): A, B, C, D, F(1,2,6,7,8,9) + deletions. Video is NOT
  spliced yet: recording with PIP on simply records stock OC video (no toast spam —
  a single "PIP not yet in video" toast at record start). Must build green.
- **Phase P2** (second agent): E + F(3,4,5) + the P1 toast removed. Must build green.

## Non-negotiables

- OC behavior with PIP off must be byte-for-byte stock (all patches guarded).
- Never crash OC because of PIP failures — degrade to hiding the PIP.
- GL orientation: ST matrices only. JPEG: manual rotation. (Device-validated.)
- Build: `cd /home/user/Yelinz/opencamera && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug` green before finishing.
