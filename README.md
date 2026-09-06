# DualShot — Simultaneous Front+Back Camera Capture

DualShot is a proof-of-concept app for simultaneous front and back camera capture on devices whose Camera2 HAL supports concurrent streaming. A draggable, pinch-resizable front-camera picture-in-picture floats over the back camera preview, and is composited into saved photos and baked into recorded videos.

**Primary test device:** Fairphone 5 (Snapdragon 695, Android 15 / API 35)

## What it does

- **PIP preview:** Front camera in a movable, resizable window over the back camera view
- **Photo capture:** Composites the front image into the back photo at the PIP's on-screen position
- **Video recording:** Bakes the composited scene into the video; the PIP can be moved/resized during recording
- **Portrait and landscape:** Supports both orientations
- All other Open Camera features remain unchanged: manual controls, multiple save modes, video codecs, and settings

## Status

**Verified working on Fairphone 5 / Android 15.** Known limitations:

- Built and tested on a single device only
- Debug builds via GitHub Actions CI (no production signing yet), so you install the APK yourself
- Requires the Camera2 API and Android 10+
- Video recording in landscape orientation does not yet include the PIP
- Not published on Google Play or F-Droid

See `docs/PIP_SPEC.md` and `DUALCAM_SPEC.md` for the full technical architecture.

## Building

**Requirements:** JDK 17 and Android SDK platform 36.

Create `local.properties` with your SDK location:
```
sdk.dir=/path/to/android-sdk
```

Build the debug APK:
```bash
./gradlew assembleDebug
```

CI builds automatically on every push to `main`. Download the APK from the **Actions → Artifacts** section of this repository.

## Fork information

DualShot is a fork of [Open Camera](https://opencamera.org.uk) v1.56.2 (upstream commit 0dd4cbe). All fork changes are clearly fenced with `// DUALCAM-PIP begin/end` comments in the source code:

```bash
grep -rn "DUALCAM-PIP" app/src/main/java --include="*.java"
```

**New code** lives in `app/src/main/java/net/sourceforge/opencamera/dualcam/` (Kotlin).

## License

GPL-3.0-or-later, same as upstream Open Camera.

- **Open Camera:** © 2013–2026 Mark Harman, under GPL-3.0-or-later
- **DualShot fork changes:** © 2024–2026 zzd labs, under GPL-3.0-or-later

See `gpl-3.0.txt` for the full GPL license text.

## Links

- **zzd Labs:** https://zzd.ch/labs/dualshot
- **Open Camera:** https://opencamera.org.uk
- **This fork:** https://github.com/Yelinz/dualshot

## Technical notes

### Camera2 concurrent streaming without `FEATURE_CAMERA_CONCURRENT`

Fairphone 5 advertises no `FEATURE_CAMERA_CONCURRENT` and `getConcurrentCameraIds()` returns empty, but raw Camera2 concurrent front+back streaming works reliably in practice. This fork does not gate the feature on those Android flags; instead, it attempts concurrent capture and fails gracefully if the device does not support it.

See `DUALCAM_SPEC.md` §1 for device probe results and `docs/PIP_SPEC.md` for the implementation architecture.

### Orientation and transforms

- **GL rendering:** Uses only `SurfaceTexture.getTransformMatrix()` — no app-side rotation applied
- **JPEG stills:** Manually rotated in PhotoProcessor based on `SENSOR_ORIENTATION`
- **Video:** Shot upright; the GL compositor applies the same transform rules as the preview renderer

This approach is device-validated on Fairphone 5 hardware.

## Contributing

For bugs and feature requests, open an issue or submit a pull request.

---

Built with ❤️ for dual-camera enthusiasts and the Fairphone community.
