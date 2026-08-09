# Photon Camera — Leica Perfect v6.4.4

Fork of [Photon Camera](https://github.com/bjzhou/PhotonCamera) (upstream tag `1.26.1`)
with **71 surgical patches** (Cron 13 composition aids + photo quality + video 4K stability),
optimized for the **Xiaomi 15T** (dizi — OV50E + S5KJN1 + S5K3J1 + OV32B).

**Default capture mode: `mode_max`** — max quality (15/9/7/11 frames, super-res 2.0x,
NLM radius 7, JPEG/HEIC/UltraHDR Q100). RAW/DNG export disabled (JPEG one-click).

---

## ⬇ Download the APK (fast CDN)

Direct download from the latest GitHub Release:

```
https://github.com/Chrispsz/photon-camera-leica-perfect/releases/download/latest/LeicaPerfect-v6.4.4-debug.apk
```

Install on device (enable *Install unknown apps* for your browser / file manager):

```bash
adb install -r LeicaPerfect-v6.4.4-debug.apk
```

Package: `com.hinnka.mycamera.debug`

---

## How it builds

This repo contains the **patch bundle**, not the full source. The build script
clones the upstream open-source Photon Camera, applies the patches, and compiles
with Gradle:

| Step | What happens |
|---|---|
| `clone` | `git clone https://github.com/bjzhou/PhotonCamera.git` @ tag `1.26.1` |
| `patch` | 71 surgical `sed` patches (tiers P-1 … P-71) over the upstream Kotlin/C++ source |
| `build` | `./gradlew assembleDefaultDebug` (single flavor — fast, no OOM) |

Output: `apk/LeicaPerfect-v6.4.4-debug.apk` (~138 MB).

### Build locally (Arch Linux / CachyOS)

```bash
sudo pacman -S jdk17-openjdk android-sdk android-sdk-platform-tools \
  android-sdk-build-tools git sed grep
export ANDROID_HOME=/opt/android-sdk
./build-archlinux.sh all
```

### Build locally (other distros)

Install JDK 17, Android SDK (platform 35, build-tools 35, NDK, CMake 3.22),
then:

```bash
export ANDROID_HOME=/path/to/android-sdk
./build-archlinux.sh all
```

---

## GitHub Actions

Every push to `main` builds the APK and updates a **rolling Release** tagged
`latest`, so the download URL above always points to the newest green build.

- Workflow: [`.github/workflows/build.yml`](.github/workflows/build.yml)
- Runner: `ubuntu-22.04`, JDK 17 (Temurin), Android SDK + NDK 27 + CMake 3.22
- Build time: ~15-30 min (clone + patch + Gradle + native)

---

## Repo layout

```
.
├── .github/workflows/build.yml   # CI: build + rolling release
├── build-archlinux.sh            # 4700-line build script (clone + patch + gradle)
├── config/
│   └── leica_perfect.json        # master config — active_capture_mode = "mode_max"
└── patches/                      # 16 .kt patch files (LeicaConfig, LeicaSettingsScreen, …)
```

---

## What's in v6.4.4 (vs upstream)

- **Cron 13 composition aids + photo quality + video 4K stability** — 71 patches / 168 substeps, all verified
- **P-71 composition aids ON** (3 settings): rule-of-thirds **grid** ON by default,
  **horizon level** indicator ON (gravity sensor, turns green <3°), **DNG-with-RAW export**
  ON (preserves full DNG alongside JPEG when shooting RAW). **Clear Data after install to apply.**
- **P-69 photo quality defaults** (8 settings): NR Off (software NLM handles it),
  Sharpening HQ, JPEG Q100, JPEG 4:4:4 + Ultra HDR, tone mapping preview+capture fix,
  P010 10-bit YUV, JPGmax HDR composition.
- **P-70 video 4K stability fix**: bitrate 250→120 Mbps (P4 — visually lossless,
  encoder can keep up), muxer.stop() failure no longer silent (propagates to UI +
  keeps partial file for recovery), writeSampleData frame drops upgraded to PLog.e
- Already ON by default (no patch needed): live histogram, focus peaking,
  RAW lens shading correction, UltraHDR gain map, HDR screen preview, RAWmax HDR composition
- **v6.4.2 P-68**: live preview LUT picker fix (P-52a respects runtimeLutOverride)
- `mode_max` as the active default (was `mode_fast`)
- `mode_balanced` removed; RAW/DNG export disabled (one-click JPEG)
- Per-lens AgX tone mapping, NLM, frame counts, DCP ratios
- 26 creative profiles (Leica Authentic, Leica Monochrome, Hasselblad HNCS, Fuji, …)
- doNotStrip for all `.so` (fixes JVM crash on `libBugly_Native.so` with NDK r29)
- Video: HEVC 120 Mbps (was 250 — stability), HDR10, AAC 256k (mode_max)

⚠️ Use at your own risk. Modifies upstream open-source code (Apache 2.0 / GPL).
