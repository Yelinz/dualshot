# DualShot Fork — Changes from Open Camera

DualShot is a fork of [Open Camera](https://opencamera.org.uk) v1.56.2 (upstream commit 0dd4cbe) that adds simultaneous front+back camera capture via picture-in-picture.

## Tracking fork changes

All modifications to upstream Open Camera source files are marked with explicit fence comments:

```
// DUALCAM-PIP begin
  ... fork code ...
// DUALCAM-PIP end
```

To see all fork changes in the Java source:

```bash
grep -rn "DUALCAM-PIP" app/src/main/java --include="*.java"
```

## Where new code lives

- **New dual-camera engine:** `app/src/main/java/net/sourceforge/opencamera/dualcam/`
  - `DualCameraController.kt` — Camera2 front+back controller (original standalone app version)
  - `PipController.kt` — Feature controller (splice into Open Camera)
  - `PipOverlayView.kt` — On-screen picture-in-picture widget
  - `FrontPipCamera.kt` — Front camera session manager
  - `PhotoProcessor.kt` — JPEG compositing (adapted from standalone)
  - `VideoRecorder.kt` — Video recording (adapted from standalone)
  - `AudioEncoder.kt` — AAC audio for video
  - `MuxerWrapper.kt` — MediaMuxer wrapper for video
  - `VideoPipCompositor.kt` — GL renderer for video splice
  - `GlUtil.kt` — OpenGL utilities

- **Modified upstream files:** See all `// DUALCAM-PIP` fences in:
  - `app/src/main/java/net/sourceforge/opencamera/Preview.java`
  - `app/src/main/java/net/sourceforge/opencamera/CameraController2.java`
  - `app/src/main/java/net/sourceforge/opencamera/MyApplicationInterface.java`
  - `app/src/main/java/net/sourceforge/opencamera/ImageSaver.java`
  - `app/src/main/java/net/sourceforge/opencamera/MainActivity.java`
  - `app/src/main/AndroidManifest.xml`

## Rebrand (DualShot by zzd)

- `applicationId`: `net.sourceforge.opencamera` → `ch.zzd.dualshot`
- `namespace`: `net.sourceforge.opencamera` → `ch.zzd.dualshot`
- App name (launcher label): `Open Camera` → `DualShot`

**Important:** Java/Kotlin package names remain at `net.sourceforge.opencamera` because the Android manifest pins component class names for shortcuts and intents (see the manifest's warning comments). Only the installation-time `applicationId` and visible app name have changed.

## Git history

This fork was imported as a clean initial commit from an export of the development session. The full development history (architecture decisions, exploration, device probe results) is documented in:

- `DUALCAM_SPEC.md` — Architecture and component contracts
- `docs/PIP_SPEC.md` — Integration spec for the PIP into Open Camera
- `docs/PIP_RECON.md` — Splice-point analysis (upstream file locations)

## License

GPL-3.0-or-later, same as upstream Open Camera.

- **Open Camera:** © 2013–2026 Mark Harman, under GPL-3.0-or-later ([opencamera.org.uk](https://opencamera.org.uk))
- **DualShot fork changes:** © 2024–2026 zzd labs, under GPL-3.0-or-later

All fork code remains under the GPL; source is and will remain public.

---

For build instructions, device requirements, and status, see [README.md](./README.md).
