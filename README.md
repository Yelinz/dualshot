# Open Camera — Dual-Camera PIP fork

A fork of [Open Camera](https://opencamera.org.uk) (v1.56.2) that adds **simultaneous
front + back camera capture**: a draggable, pinch-resizable front-camera
picture-in-picture over the normal camera, composited into saved photos and baked
into recorded videos. Built for — and developed on — the **Fairphone 5**, which (like
many devices) supports concurrent front+back streaming even though it doesn't
advertise `FEATURE_CAMERA_CONCURRENT`, so flag-checking apps refuse to try. This
fork uses raw Camera2 and just tries.

Everything else is stock Open Camera: all of its photo/video modes, manual controls,
and settings work unchanged. The PIP is a toggle (on-screen icon), not a separate mode.

## Status

Working on Fairphone 5 / Android 15 (developed and tested there exclusively so far):

- PIP preview over the normal camera: drag to move, pinch to resize, position persisted
- Photos: front image composited into the saved photo at the PIP position
  (single-output modes; panorama/burst/RAW-only save unchanged)
- Video: PIP baked into the recorded file via a GL compositor spliced between the
  camera and Open Camera's recorder — live-repositionable while recording
- Portrait and landscape preview/photos; **landscape video records without the PIP**
  (not yet implemented — a toast says so)
- Requires the Camera2 API (the fork auto-selects it on capable devices) and Android 10+

Not yet done: releases/signing, F-Droid/Play packaging (the app still uses the upstream
application ID — must change before any store publishing), translations for the new
strings, testing beyond one device. See `docs/HANDOFF.md` for the full state of the
project and `DUALCAM_SPEC.md` / `docs/PIP_SPEC.md` / `docs/PIP_RECON.md` for the
architecture.

## Building

JDK 17 and an Android SDK with platform 36. Create `local.properties` with
`sdk.dir=/path/to/sdk`, then:

```
./gradlew assembleDebug
```

CI builds a debug APK for every push (see Actions → artifacts).

## Where the fork's code lives

- New code: `app/src/main/java/net/sourceforge/opencamera/dualcam/` (Kotlin)
- Every change to upstream files is fenced with `// DUALCAM-PIP begin` / `end`
  comments: `grep -rn "DUALCAM-PIP" app/src/main/java --include=*.java`

## License

GPL-3.0-or-later, same as upstream Open Camera (© Mark Harman and contributors).
Fork changes © the fork's contributors, same license.
