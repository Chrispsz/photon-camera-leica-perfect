# Photon Camera — Leica Perfect v6.4.8

Fork of [Photon Camera](https://github.com/bjzhou/PhotonCamera) (upstream tag `1.26.1`)
with **77 surgical patches** (Cron 17: night precision — Leica M9 LUT de volta + AgX softer + NR up),
optimized for the **Xiaomi 15T** (dizi — OV50E + S5KJN1 + S5K3J1 + OV32B).

**Single capture mode: `mode_max`** — max quality (15/9/7/11 frames, super-res 2.0x,
NLM radius 7, JPEG/HEIC/UltraHDR Q100). **JPEG by default; RAW available on-demand**
via gear icon → RAW toggle (v6.4.5: `force_no_raw=false`).

**v6.4.8: Night precision** — revert P-75 LUT `leica_m9` de volta (flat+noisy fix).
Diagnosed via EXIF comparison (Stock ISO 1000 1/30s clean vs Photon ISO 1506 1/59s flat+noisy,
mesma sala 5 min de diferença) + VLM analysis. 3 root causes:
1. P-75 (v6.4.7) `lut_id="none"` removeu color science Leica M9 (cor + contraste + mascaramento de ruído)
2. AgX `pgtm_pre_tonemap_exposure_boost_ev=1.3` + `shadow_lift=0.10` → +2.3EV lift amplificou ruído ISO 1500-3000 ~5x
3. `noise_reduction.luminance=0.92` insuficiente pra ISO alto do mode_max

Fix P-76 (5 sub-patches): `lut_id="leica_m9"` + `force_baseline_lut_id="leica_m9"` (case-sensitive correct ID,
bug latente do v6.4.6 era STAYS FIXED) + `shadow_lift 0.10→0.05` + `pgtm_pre_tonemap_exposure_boost_ev 1.3→0.8`
+ `noise_reduction.luminance 0.92→0.96`.

**v6.4.7-fix2: DEBUG build** — release build (v6.4.6) had "parse error" on Xiaomi 15T.
Reverted to debug build (guaranteed installable, same as v6.4.1-v6.4.5). APK ~138 MB.

---

## ⬇ Download the APK (fast CDN)

Direct download from the latest GitHub Release:

```
https://github.com/Chrispsz/photon-camera-leica-perfect/releases/download/latest/LeicaPerfect-v6.4.8-debug.apk
```

Install on device (enable *Install unknown apps* for your browser / file manager):

```bash
adb install -r LeicaPerfect-v6.4.8-debug.apk
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
| `patch` | 77 surgical `sed` patches (tiers P-1 … P-76) over the upstream Kotlin/C++ source |
| `build` | `./gradlew assembleDefaultDebug` (single flavor — DEBUG build, guaranteed installable) |

Output: `apk/LeicaPerfect-v6.4.8-debug.apk` (~138 MB — debug build, same as v6.4.1-v6.4.5).

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

## What's in v6.4.8 (vs upstream)

- **Cron 17: Night precision** — 77 patches / 180+ substeps, all verified
- **P-76 night precision (5 sub-patches)**:
  - **P-76.1: revert LUT `leica_m9`** — P-75 (v6.4.7) set `lut_id="none"` which removed
    Leica M9 CCD color science (rich colors + contrast + noise masking). Night photos at
    ISO 1500-3275 came out FLAT and NOISY. Revert restores Leica M9 look + masks sensor grain.
  - **P-76.2: `force_baseline_lut_id="leica_m9"`** — case-sensitive correct ID. The latent bug
    from v6.4.6 era (`"Leica_M9_STD"` filename that never resolved in `LutManager.getLutInfo`)
    STAYS FIXED with the correct ID `"leica_m9"`.
  - **P-76.3: AgX `shadow_lift 0.10→0.05`** — -50% shadow lift. Less sensor noise reveal in shadows.
  - **P-76.4: `pgtm_pre_tonemap_exposure_boost_ev 1.3→0.8`** — -38% pre-tonemap boost. ISO 1500-3000
    noise no longer amplified ~5x by AgX lift.
  - **P-76.5: `noise_reduction.luminance 0.92→0.96`** — +4% luma NR. Compensates mode_max night ISO
    without plasticizing (`detail_preserve 0.96` retained).
  - **Diagnosis**: EXIF comparison (Stock ISO 1000 1/30s clean vs Photon ISO 1506 1/59s flat+noisy,
    same room 5 min apart) + VLM analysis (Photon underexposed/flat/noisy, Stock bright/vivid/clean).
- **Cron 16: Natural baseline (v6.4.7 — P-75, REVERTED by P-76.1)** — historical
  - P-75 set `lut_id="none"` for "natural" look → flat+noisy at night → reverted in v6.4.8.
  - P-75 latent bug fix retained (now with correct case-sensitive ID `"leica_m9"` in v6.4.8).
- **Cron 15: mode_fast removed + release APK attempt** — 75 patches / 175+ substeps
  - **P-73 mode_fast removed**: user reported "não esquenta tirando fotos, mode_fast só atrapalha".
    Now single capture mode (`mode_max` only). `cycleCaptureMode()` is now no-op (always `mode_max`).
    `shouldDegradeCapture()` returns false (thermal throttle is warning-only, no auto-degrade).
  - **P-74 Release APK + R8 + arm64-v8a**: attempted release build (R8 + arm64-v8a, ~88 MB).
    **REVERTED to DEBUG in v6.4.7-fix2** — release APK had "parse error" on Xiaomi 15T.
    Debug build (~138 MB) is guaranteed installable (same as v6.4.1-v6.4.5).
- **DCP baseline**: `builtin_dcp_Leica M8 Camera Standard` (professional rangefinder APS-H CCD
  2006 — gives authentic Leica look). LUTs are the creative layer on top.
- **P-72 RAW on-demand** (v6.4.5): flipped `force_no_raw`/`force_no_dng` true→false in JSON config.
  **JPEG by default (mode_max shoots YUV); RAW available on-demand via gear icon → RAW toggle**.
  When RAW is on, mode_max does 15-frame RAW burst stacking + saves DNG alongside JPEG (P-71).
- **P-71 composition aids ON** (3 settings): rule-of-thirds **grid** ON by default,
  **horizon level** indicator ON (gravity sensor, turns green <3°), **DNG-with-RAW export**
  ON (preserves full DNG alongside JPEG when shooting RAW). **Clear Data after install to apply.**
- **P-69 photo quality defaults** (8 settings): NR Off (software NLM handles it),
  Sharpening HQ, JPEG Q100, JPEG 4:4:4 + Ultra HDR, tone mapping preview+capture fix,
  P010 10-bit YUV, JPGmax HDR composition.
- **P-70 video 4K stability fix**: bitrate 250→120 Mbps (P4 — visually lossless,
  encoder can keep up), muxer.stop() failure no longer silent (propagates to UI +
  keeps partial file for recovery), writeSampleData frame drops upgraded to PLog.e
- **v6.4.2 P-68**: live preview LUT picker fix (P-52a respects runtimeLutOverride)
- Already ON by default (no patch needed): live histogram, focus peaking,
  RAW lens shading correction, UltraHDR gain map, HDR screen preview, RAWmax HDR composition
- Per-lens AgX tone mapping, NLM, frame counts, DCP ratios
- 26 creative profiles (Leica Authentic, Leica Monochrome, Hasselblad HNCS, Fuji, …)
- doNotStrip for all `.so` (fixes JVM crash on `libBugly_Native.so` with NDK r29)
- Video: HEVC 120 Mbps (was 250 — stability), HDR10, AAC 256k (mode_max)

⚠️ Use at your own risk. Modifies upstream open-source code (Apache 2.0 / GPL).
