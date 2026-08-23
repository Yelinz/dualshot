# Open Camera Dual-Cam PIP — Reconnaissance Report (v1.56.2 tree)

(Produced by pipeline reconnaissance; file:line references verified against this repo.)

## 1. Preview / view hierarchy
- Camera2 mode renders preview in a **MyTextureView** (Preview.java:478, using_android_l from :465) + separate CanvasView (:480); Camera1 uses MySurfaceView (:484).
- Root: activity_main.xml RelativeLayout; first child FrameLayout `@+id/preview` (lines 8-13). Preview.java:497-500 adds camera surface + canvasView into it. All buttons are later siblings, so children of `R.id.preview` sit above the camera surface, below controls.
- **PIP insertion**: add PIP view as another child of `R.id.preview` (after Preview.java:500, or from MainActivity after :376).
- OC never rotates the preview view; icons rotate individually via MainUI.setViewRotation (MainUI.java:160-186), driven by layoutUI (:188/:219, ui_rotation :265). PIP container must NOT rotate; its video content self-orients (TextureView transform per MyTextureView.java:78-81).
- Preview sizing: Preview.getMeasureSpec (:950-1029) aspect-locks the main surface; PIP uses its own explicit LayoutParams.

## 2. Camera controller lifecycle
- Open: Preview.openCamera() :1619 (async; openCameraCore :1878; CameraController2 @ :1920); completion → **cameraOpened() :1942** (setupCamera ~:1995-2004). Triggers: mySurfaceCreated :1038, onResume :8015/:8038, reopenCamera :2037, retryOpenCamera :2019, setCamera :4782/:4807.
- Close: closeCamera :1445 (controller null @ :1500). Triggers: mySurfaceDestroyed :1045, onPause :8053/:8075, setCamera :4801.
- MainActivity: onResume :1302→preview.onResume :1403 (guard camera_in_background :1401); onPause :1491→:1524; settings overlay paths :3915/:3969.
- Front detection: `camera_controller.getFacing() == CameraController.Facing.FACING_FRONT` (enum CameraController.java:729-731); unopened id: CameraControllerManager2.getFacing (:43); robust id: MainActivity.getActualCameraId() :702. OC has no selfie mode; front only via user switch (all switches funnel through setCamera → cameraOpened).
- **Recommended hooks**: open PIP front camera at end of cameraOpened() (post setupCamera); close at top of Preview.onPause(boolean) before :8075, in mySurfaceDestroyed (:1041-1046), and before setCamera's closeCamera (:4801); re-evaluate facing in every cameraOpened().

## 3. Photo pipeline
- Trace: MainActivity.clickedTakePhoto :1828 → takePicture :4862 → preview.takePicturePressed :5555 → takePicture :5895 → takePhoto :6441 → takePhotoWhenFocused :6600 → camera_controller.takePicture :6860. CameraController2.takePicture :7427 → takePictureAfterPrecapture :6274 (picture_cb.onStarted :6392; JPEG arrives :616/:697).
- Preview pictureCallback.onPictureTaken :6754 → MyApplicationInterface.onPictureTaken :3660 → saveImage :3380 → imageSaver.saveImageJpeg :3622. ImageSaver.Request (fields :92-208) queued :936, consumed run() :525 → saveImageNow :548/:1288 → **saveSingleImageNow :1860**.
- **Choke point: ImageSaver.java:1910-1911** (postProcessing.postProcessBitmap → bitmap). Insert composite right after :1911, before write block :2036-2061. If bitmap==null decode mutable like ImageUtils.loadBitmapWithRotation(data,true) (~:545/:557). Precedent for canvas-on-output: PostProcessing.stampImage :274 (draw :477-502). Bitmap path re-encodes at request.image_quality (same trade-off as Photo Stamp).
- **Capture-start hook: MyApplicationInterface.onCaptureStarted() :2858** (from Preview.java:6658-6661) — trigger the front still here.
- Photo modes: MyApplicationInterface.PhotoMode enum :70-85; getPhotoMode() :1736; isRawOnly() :1861 (checked ImageSaver:1880, skip :1914-1917). **Composite only when request.process_type == NORMAL && jpeg_images.size()==1 && !isRawOnly()** (skips Panorama/FastBurst/Expo/FocusBracketing multi-file paths :1482-1723, MyApplicationInterface:3679-3711; HDR/DRO/NR still land in saveSingleImageNow and are safe).

## 4. Video pipeline (Camera2)
- Entry: Preview.startVideoRecording :6029 → getVideoProfile :6036/:3607 → new MediaRecorder :6058 → initVideoRecorderPrePrepare :6093 → profile.copyToMediaRecorder :6108 (VideoProfile.java:81; setVideoFrameRate :95, setCaptureRate :100 timelapse-only, setVideoSize :102, bitrate :103; Camera2 videoSource=SURFACE @ Preview.java:3759) → output file :6151/:6154 → setOrientationHint(getImageVideoRotation()) :6163 → prepare :6167 → **initVideoRecorderPostPrepare :6176** → start :6187 → videoRecordingStarted :6194.
- Session IS recreated at record start: CameraController2.initVideoRecorderPostPrepare :7588 → TEMPLATE_RECORD :7599 → createCaptureSession :7608 → closeCaptureSession :5300 first. Key lines: **video_recorder_surface = video_recorder.getSurface() @ CameraController2.java:5392** (field :276); surfaces list :5560-5566; addTarget :5457. Stop: reconnect :7621 → preview-only session :7630.
- **Splice point: substitute the assignment at :5392** with our GL compositor's camera-input Surface; pass the real MediaRecorder (or its surface) to the compositor in initVideoRecorderPostPrepare (:7588) before :7608; GL input ST must be sized to profile.videoFrameWidth/Height before session creation.
- Gates: NOT high-speed (branch :5666-5717; detect Preview.fpsIsHighSpeed :7952 / MyApplicationInterface.fpsIsHighSpeed :691 / setVideoHighSpeed Preview:2213); Camera2 only (preview.usingCamera2API() :9196); timelapse (setCaptureRate) is COMPATIBLE; extension sessions block video anyway (:7583/:7595).
- Video snapshot survives: separate imageReader surface (:5562, want_photo_video_recording; TEMPLATE_VIDEO_SNAPSHOT :6323/:7027) — and its stills flow through the same ImageSaver choke point, so photo compositing applies during recording too.

## 5. Preferences & on-screen icon
- Current icon: activity_main.xml:449-462; OnScreenIcons.java :71, :249/:277-278, :359-363, gate showDualCameraModeIcon :475-482; click MainActivity.clickedDualCameraMode :2336-2347 (currently startActivity :2342) — rewrite body to toggle pref.
- Toggle template: Auto Level — OnScreenIcons.clickedAutoLevel :643-671 (putBoolean :651), updateAutoLevelIcon :149-154, aggregate updateOnScreenIcons :74-89; MainActivity.clickedAutoLevel :1928-1932.
- PreferenceKeys pattern: constants (e.g. :128 AutoStabilisePreferenceKey). Add DualCameraPipPreferenceKey = "preference_dual_camera_pip". Read via PreferenceManager.getDefaultSharedPreferences.
- PIP rect persistence: putFloat precedent PreferenceSubCameraControlsMore.java:176 / read MyApplicationInterface.java:1369.

## 6. Insets / fullscreen
- MainActivity.setupSystemUiVisibilityListener :3442-3681 (insets listener :3450-3649; navigationBars|displayCutout insets :3468-3477; API30+ edge-to-edge root padding :3492-3499); setImmersiveMode :3743+; immersiveModeChanged :3408-3437.
- Button positioning uses MainActivity.getNavigationGap()/getNavigationGapLandscape()/getNavigationGapReverseLandscape() :3396-3406, consumed in MainUI.layoutUI :384-386 via setMarginsForSystemUI (MainUI.java:936-944).
- **PIP overlay must clamp its draggable bounds with the same getNavigationGap*() values** and refresh on layoutUI()/rotation.

## Cross-cutting
1. Video splice = single-assignment change at CameraController2.java:5392 + compositor wiring in initVideoRecorderPostPrepare; no live-session surgery.
2. Photo composite at ImageSaver:1911 runs on ImageSaver background thread; front still triggered at onCaptureStarted; pass via a new Request field (settable holder — front JPEG may arrive after Request construction; wait with timeout at composite time).
3. Existing dualcam VideoRecorder.kt's EGL-window-surface technique ports to MediaRecorder.getSurface() directly (videoSource==SURFACE).
4. Front-facing detection / PIP disable funnels entirely through Preview.cameraOpened().
