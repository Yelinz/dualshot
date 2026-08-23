# Handoff — Open Camera Dual-PIP fork

Written for continuation by a future agent harness (or human) taking over this
project, and as a template of requirements for sibling projects with similar
"build in public + eventual publishing" goals. State as of this document: **v6,
confirmed working on the target device**.

## 1. What this project is

An Open Camera (v1.56.2, GPL-3.0) fork adding simultaneous front+back capture as a
PIP toggle inside OC's native photo/video modes. Primary (and so far only) target
device: Fairphone 5, Android 15. The enabling discovery: the FP5's HAL streams both
cameras concurrently even though `FEATURE_CAMERA_CONCURRENT` is false and
`getConcurrentCameraIds()` is empty — verified with a purpose-built probe APK before
any product code was written. CameraX-based approaches are therefore useless here;
everything is raw Camera2.

## 2. Current state (what works, verified on device)

- PIP preview (720p front feed, TextureView overlay inside OC's preview FrameLayout),
  drag/pinch-resize, position persisted in SharedPreferences, correct in portrait and
  landscape, auto-hides when OC itself uses the front camera or Panorama mode.
- Photo composite: front still (~2-4MP stream, chosen as smallest JPEG ≥1440px short
  side for speed) captured at shutter, composited at ImageSaver's post-processing
  choke point. Single-output modes only (incl. HDR/DRO/NR); RAW-only/panorama/
  burst/bracketing save unchanged. Display-rotation-aware.
- Video splice (portrait-held only): GL compositor (own EGL context/thread) between
  the back camera and OC's MediaRecorder; front frames arrive via Camera2 surface
  sharing on the front preview stream. PIP live-repositionable during recording.
  Timelapse compatible; video snapshots keep working.
- Camera2 API auto-selected on capable devices (OC's stock default is Camera1 with a
  manufacturer allow-list that excludes Fairphone — see MainActivity.initCamera2Support
  fenced block).

## 3. Known gaps / roadmap candidates

1. **Landscape-held video**: splice declines (stock recording + toast). Fix requires
   scene rotation in `VideoPipCompositor` + mapping (rect math already exists,
   "best-effort" path in `PipController.mapPreviewRectToVideoSpace`).
2. **Slow motion / high-speed**: constrained high-speed sessions can't take a GL
   consumer; permanently out of scope for the splice (gated, toasted).
3. **Camera1 devices**: PIP requires Camera2 (gated, toasted).
4. **Single-device testing**: every "works" claim above = Fairphone 5 only.
5. **Translations**: new strings (`res/values/strings_dualcam.xml`) are English-only;
   upstream OC is heavily localized.
6. **PIP camera controls**: front camera runs fixed auto everything at 720p preview.

## 4. Hard-won device/platform facts (do not relearn these)

These cost the most iterations; they are also documented next to the code:

- **GL orientation**: the `SurfaceTexture` transform matrix from Camera2 ALREADY
  contains rotation + front-mirroring. Never add app-side rotation in the GL path
  (adding it cancels the correct one → sideways preview). JPEG stills are the
  opposite: no transform tag, always rotate manually by SENSOR_ORIENTATION
  (± capture-time display rotation). See `DUALCAM_SPEC.md` "CORRECTED" section.
- **Encoder timestamps**: camera sensor timestamps are NOT reliably CLOCK_MONOTONIC
  (FP5: arbitrary timebase → 5s clips muxed as 1h+). Stamp encoder frames with
  `System.nanoTime()`.
- **Camera-facing surface sizes must be sensor-landscape** (e.g. 1920x1080); a
  portrait-swapped size is not a supported stream config. Only the encoder output is
  portrait; the ST matrix rotates content.
- **Adding a stream to a running camera at record start is fragile** (session
  recreate with extra stream produced no frames on FP5, silently). Camera2 **surface
  sharing** (`OutputConfiguration.enableSurfaceSharing` +
  `session.updateOutputConfiguration`) on the existing same-size stream is the
  robust route.
- **OC's activity rotates** (configChanges, no recreate) and counter-rotates its own
  preview via `Preview.configureTransform()`; any overlay rendering camera content
  must do its own display-rotation compensation (`PipOverlayView.applyTextureTransform`).
- **Android 15 + targetSdk 35/36 enforces edge-to-edge**: any new UI must apply
  system-bar/cutout insets or controls land under gesture nav.
- **Never degrade silently**: every splice/degrade path now surfaces a toast with the
  reason. The one silent fallback we shipped cost a full debugging round-trip because
  "broken" and "degraded" looked identical from the device.

## 5. Code map

- `app/src/main/java/net/sourceforge/opencamera/dualcam/` (Kotlin, all new):
  - `PipController.kt` — singleton façade; all OC call sites enter here. Lifecycle
    (`onMainCameraOpened/Closing`, `onToggled`), photo holder plumbing, video splice
    arming/attach/teardown, rect mapping, pref/rect persistence.
  - `FrontPipCamera.kt` — front-only Camera2 controller: shared-surface preview
    stream + small JPEG still stream; `attachCompositorOutput()` routes frames to the
    video compositor via surface sharing.
  - `PipOverlayView.kt` — the PIP widget: layout from normalized RectF, drag/pinch,
    rotation-aware center-crop TextureView transform, inset-aware clamping.
  - `PipStillHolder.kt` — latch-based box carrying the front still + rect + display
    rotation from capture-start to ImageSaver's background thread (bounded 1500ms wait).
  - `photo/PhotoProcessor.kt` — composites the front JPEG onto OC's final bitmap.
  - `gl/VideoPipCompositor.kt` — standalone EGL/GLES2 compositor rendering back
    full-frame + front PIP onto `MediaRecorder.getSurface()`.
  - `gl/GlUtil.kt` — shader/texture helpers.
- OC-side patches: `grep -rn "DUALCAM-PIP" app/src/main/java --include=*.java` —
  currently in `Preview.java` (lifecycle hooks, splice arming, orientation-hint
  override, toast), `CameraController2.java` (surface substitution, compositor
  attach, teardown funnel in `reconnect()`), `MyApplicationInterface.java`
  (`onCaptureStarted` hook), `ImageSaver.java` (Request field + composite call),
  `MainActivity.java` (icon handler + Camera2 default), `OnScreenIcons.java` (icon
  registration/state), `PreferenceKeys.java`, plus `activity_main.xml`,
  `preferences_sub_gui.xml`, `strings_dualcam.xml`, manifest.
- Architecture docs: `DUALCAM_SPEC.md` (engine history + orientation rules),
  `docs/PIP_RECON.md` (verified OC pipeline map with file:line splice points),
  `docs/PIP_SPEC.md` (integration architecture + phasing).

## 6. Build & CI

- Local: JDK 17, Android SDK platform 36, `local.properties` with `sdk.dir`,
  `./gradlew assembleDebug`. Kotlin 2.1.0 was added to OC's otherwise-Java build
  (root + app build.gradle) along with kotlinx-coroutines.
- CI: `.github/workflows/build-apk.yml` — debug APK on every push/PR/manual run,
  uploaded as a workflow artifact. **No releases are cut** (deliberate, for now).

## 7. Upstream sync strategy

The fork vendors upstream at commit `0dd4cbe` (v1.56.2, from
`https://git.code.sf.net/p/opencamera/code`). To take a new upstream version:
re-vendor the new tree, then re-apply the fenced regions (find them all via the
`DUALCAM-PIP` grep) plus the resource/manifest additions and the `dualcam/` package.
The fences exist precisely to make this a mechanical exercise; PIP_RECON.md's line
numbers will drift but its method/anchor names should survive.

## 8. Publishing prerequisites (when releases start — not yet)

1. **Change the application ID and app name/icon** — still `net.sourceforge.opencamera`
   / "Open Camera". Mandatory for Play/F-Droid (duplicate ID) and basic courtesy to
   upstream. Keep the manifest's warning in mind: upstream pins certain component
   class names for shortcuts/intents; renaming the appId is safe, moving classes is not.
2. **Release signing key** — everything so far is debug-signed. Generate once, store
   safely, wire a `release` build type (minify config will need testing — OC ships
   proguard rules; the dualcam Kotlin code has none yet).
3. Channel order that fits this project: GitHub releases (immediate) → F-Droid
   (right audience for a GPL camera app; needs distinct appId, fastlane metadata —
   upstream's `fastlane/` dir is already there as a template) → Play only if demand.
4. **GPL-3.0 obligations**: source must stay public alongside binaries; keep
   upstream copyright headers; state fork changes (the fenced markers + git history
   do this).
5. **Upstream contribution**: offer the feature via OC's SourceForge tracker with a
   link here. Expect Kotlin and device-support-burden objections; don't block on it.

## 9. Lessons for the wider harness (multi-project, build-in-public)

What worked in this project's loop and is worth standardizing:

- **Probe before building**: a 200-line diagnostic APK settled the central hardware
  question in one round-trip and dictated the whole architecture. Ship probes early
  when the critical unknown is on-device behavior the environment can't test.
- **The device owner is the test harness**: with no emulator (no KVM) and
  hardware-dependent behavior, every iteration is user-mediated. Optimize for
  information per round-trip: visible diagnostics (toasts with reasons) beat silent
  fallbacks; ask for `adb logcat -s <tags>` only when a toast can't carry enough.
- **Spec files live in the repo**, not in conversation context: SPEC/RECON/HANDOFF
  documents survive context summarization, brief subagents cheaply, and double as
  public documentation when building in public.
- **Fence every change to vendored code** (`// PROJECT-TAG begin/end`): makes review,
  upstream rebases, and "what did we touch" queries mechanical.
- **Orchestrator reviews everything**: the implementer/reviewer split (cheap model
  implements, strong model reviews with full file reads before commit) caught a
  crash-class bug or worse in literally every implementation phase (device leak,
  fullscreen-overlay layout, unsupported stream size, thread-affinity violation).
- **Commit after every reviewed phase** with root-cause commit messages; the git log
  is the project's debugging history and the handoff's changelog.
- **Expect environment auth gaps**: this session never had repo-create/push rights;
  work was never blocked because deliverables (APKs, zips) flowed through files and
  the repo was designed to be exportable (`git archive` of the subtree at any point).

## 10. Immediate next steps (agreed with the owner)

1. Owner creates an empty **public** GitHub repo (no README/license/gitignore).
2. Agent (or owner) pushes this tree as `main` — the CI workflow activates on first
   push; APKs appear as Actions artifacts.
3. No releases yet.
