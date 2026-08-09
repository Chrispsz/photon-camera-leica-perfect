# Photon Camera — Leica Perfect v6.4.7

Fork of [Photon Camera](https://github.com/bjzhou/PhotonCamera) (upstream tag `1.26.1`)
with **76 surgical patches** (Cron 16: natural baseline — LUT "none" no perfil ativo),
optimized for the **Xiaomi 15T** (dizi — OV50E + S5KJN1 + S5K3J1 + OV32B).

**Single capture mode: `mode_max`** — max quality (15/9/7/11 frames, super-res 2.0x,
NLM radius 7, JPEG/HEIC/UltraHDR Q100). **JPEG by default; RAW available on-demand**
via gear icon → RAW toggle (v6.4.5: `force_no_raw=false`).

**v6.4.7: Natural baseline** — LUT `"none"` no perfil ativo (`leica_perfect_signature`).
Saída padrão = **DCP Leica M8 + AgX + sharpening** (sem LUT criativa forçada).
Usuário aplica LUT criativa (Leica M9, Hasselblad, Fuji, ...) via mod menu on-demand.
Também corrige bug latente em `force_baseline_lut_id` (era `"Leica_M9_STD"` que nunca
resolvia no `LutManager.getLutInfo` case-sensitive).

**v6.4.6: Release build** — R8 minified + arm64-v8a only (~50% smaller APK, ~70 MB vs 138 MB).

---

## ⬇ Download the APK (fast CDN)

Direct download from the latest GitHub Release:

```
https://github.com/Chrispsz/photon-camera-leica-perfect/releases/download/latest/LeicaPerfect-v6.4.7-release.apk
```

Install on device (enable *Install unknown apps* for your browser / file manager):

```bash
adb install -r LeicaPerfect-v6.4.7-release.apk
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
| `patch` | 76 surgical `sed` patches (tiers P-1 … P-75) over the upstream Kotlin/C++ source |
| `build` | `./gradlew assembleDefaultRelease` (single flavor — R8 + arm64-v8a only) |

Output: `apk/LeicaPerfect-v6.4.7-release.apk` (~70 MB — release build with R8 + arm64-v8a).

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

## What's in v6.4.7 (vs upstream)

- **Cron 16: Natural baseline** — 76 patches / 175+ substeps, all verified
- **P-75 natural baseline (LUT none)**: investigation (`9-a` no worklog) confirmou que o
  perfil ativo `leica_perfect_signature` forçava LUT `leica_m9` (Kodak M9 CCD magenta/blue).
  Agora `lut_id="none"` → sentinel que pula LUT no `LutManager.loadLut()` (path="" → retorna
  null → `uLutEnabled=0` → GLSL LUT block skipped). Resultado: imagem = **DCP Leica M8 + AgX
  + sharpening** (caminho natural, sem desvio criativo forçado). Usuário aplica LUT criativa
  via mod menu on-demand (`runtimeLutOverride`).
- **P-75 latent bug fix**: `force_baseline_lut_id` era `"Leica_M9_STD"` (filename do .plut)
  mas `LutManager.getLutInfo` usa match case-sensitive por ID (`"leica_m9"`). Ou seja, se o
  usuário ativasse o perfil `leica_authentic` (baseline), a LUT seria silenciosamente pulada.
  Agora `force_baseline_lut_id="none"` → sentinel explícito, sem bug latente.
- **Cron 15: mode_fast removed + release APK** — 75 patches / 175+ substeps, all verified
- **P-73 mode_fast removed**: user reported "não esquenta tirando fotos, mode_fast só atrapalha".
  Now single capture mode (`mode_max` only). `cycleCaptureMode()` is now no-op (always `mode_max`).
  `shouldDegradeCapture()` returns false (thermal throttle is warning-only, no auto-degrade).
  Removed from: JSON `capture_modes.modes`, `LeicaConfig.kt` defaults, `LeicaThermalMonitor.kt`,
  `LeicaRuntimeState.kt`, `LeicaSettingsScreen.kt`.
- **P-74 Release APK + R8 + arm64-v8a**: switched from debug to release build.
  R8 minification with conservative keep rules (`-keep com.hinnka.mycamera.**` — safe, no
  reflection crashes). `shrinkResources=true` removes unused resources. `ndk.abiFilters += "arm64-v8a"`
  removes x86/armeabi .so (Xiaomi 15T is arm64-v8a only). APK is now signed + ~50% smaller
  (138 MB → ~70 MB).
- **DCP baseline**: `builtin_dcp_Leica M8 Camera Standard` (professional rangefinder APS-H CCD
  2006 — gives authentic Leica look). LUTs are the creative layer on top.
- **P-72 RAW on-demand** (v6.4.5): flipped `force_no_raw`/`force_no_dng` true→false in JSON config.
  Investigation found these were essentially no-ops (only affected 2 LeicaConfig accessors
  which are bypassed by the actual export path), but the config was misleading. Now consistent:
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
- Already ON by default (no patch needed): live histogram, focus peaking,
  RAW lens shading correction, UltraHDR gain map, HDR screen preview, RAWmax HDR composition
- **v6.4.2 P-68**: live preview LUT picker fix (P-52a respects runtimeLutOverride)
- `mode_max` as the active default (was `mode_fast`)
- `mode_balanced` removed; JPEG one-click by default, RAW opt-in via toggle
- Per-lens AgX tone mapping, NLM, frame counts, DCP ratios
- 26 creative profiles (Leica Authentic, Leica Monochrome, Hasselblad HNCS, Fuji, …)
- doNotStrip for all `.so` (fixes JVM crash on `libBugly_Native.so` with NDK r29)
- Video: HEVC 120 Mbps (was 250 — stability), HDR10, AAC 256k (mode_max)

⚠️ Use at your own risk. Modifies upstream open-source code (Apache 2.0 / GPL).
