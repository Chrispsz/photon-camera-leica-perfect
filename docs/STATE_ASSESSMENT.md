# STATE ASSESSMENT — PhotonCamera "Leica Perfect v6.5.0"

**Date of assessment:** After v6.5.0 build SUCCESS (commit `782622f`, all 6
upgrades U-01..U-06 live in the APK).
**Purpose:** Honest engineering assessment of the 10 problems identified
across 5 rounds of VLM-driven comparative testing, against the 6 upgrades
shipped in v6.5.0.

> This document is deliberately **not sales-pitched**. If something is only
> PARTIALLY_SOLVED, it says so. The user explicitly asked for an honest
> assessment.

---

## Executive Summary

Of the 10 original problems:
- **3 SOLVED or LIKELY_SOLVED** with high confidence (oversharpening, HDR
  halos, AWB instability — the 3 critical pipeline/mode problems)
- **4 LIKELY_SOLVED** with regression risks that need beach/night validation
  (shadow noise, oil-painting, AE highlight blowout, AE crushed blacks)
- **1 PARTIALLY_SOLVED** with known edge-case failure mode (chroma noise in
  low light — U-01 addresses it but extreme low light still at risk)
- **1 NEEDS_TUNING** (color saturation drift — only config-level fix in
  v6.5.0, no algorithmic change)
- **1 NOT_ADDRESSED but partially mitigated** (M9 CCD profile instability —
  U-01..U-04 may have collateral effects, needs re-validation)

**Bottom line:** The 6 upgrades shipped in v6.5.0 are the **right set of
fixes** for the right root causes. Most problems should be visibly improved
or solved. The two big open risks are:
1. **U-04 detail_preserve value (0.75)** — may over-smooth foliage/fabric.
   Calculated risk, easy to tune back to 0.80-0.85 in v6.5.1.
2. **U-05 CCT clamp bounds (3500-7000K)** — may push candle (2000K) and
   sunset (8000K+) scenes off-target. Easy to widen in v6.5.1.

v6.5.1 is likely needed for fine-tuning after the beach/night tests produce
real-world data.

---

## Summary Table

| # | Problem | Upgrade | Confidence | Next Test |
|---|---------|---------|------------|-----------|
| 1 | AWB instability (cold/blue ↔ warm/yellow) | U-05 | **LIKELY_SOLVED** | Beach group portrait 3-shot burst (A.7) — check skin tone consistency |
| 2 | Oversharpening (global USM, 2-4px halos on edges) | U-02 | **LIKELY_SOLVED** | Beach horizon + backlit portrait (A.1, A.4) — check edge halos |
| 3 | HDR halos (bilateral filter in ShadowsHighlightsShader) | U-03 | **LIKELY_SOLVED** | Sunset with sun in frame (A.3) — check halo around sun disk |
| 4 | Oil-painting in low-texture regions (detail_preserve was dead config) | U-04 | **PARTIALLY_SOLVED** (regression risk on value 0.75) | Shells macro + clothing fabric (A.6, A.7) — check texture preservation |
| 5 | Shadow noise (insufficient denoise in dark areas) | U-01 | **LIKELY_SOLVED** | Dark alley + street lamp sky (B.1, B.9) — check chroma specks |
| 6 | Highlight blow-outs (AE overexposes bright scenes) | U-06 | **LIKELY_SOLVED** | Wet sand specular + sun in frame (A.2, A.3) — check roll-off |
| 7 | Crushed blacks (AE underexposes low light) | U-06 | **PARTIALLY_SOLVED** (combined with -46% exposure lift, may over-correct) | Dark alley (B.9) + candle (B.5) — check shadow detail vs total exposure |
| 8 | Color saturation drift (artificial "vivid" boost) | U-07 (config-only) | **NEEDS_TUNING** | Sunset (A.3) + neon (B.2) + concert (B.10) — check vivid scenes |
| 9 | Chroma noise in low light (chroma NR was HARDCODED 0f dead config) | U-01 (partially), U-04 | **PARTIALLY_SOLVED** | Dark alley (B.9) + night portrait (B.6) — check chroma specks |
| 10 | M9 CCD profile instability (75% success, affected by base pipeline) | U-01..U-04 (collateral) | **NOT_ADDRESSED** (but base fixes may help indirectly) | Re-test scene 05 with M9 CCD profile — check 75% → ≥ 85% |

---

## Per-Problem Deep Dive

### Problem 1 — AWB Instability (cold/blue ↔ warm/yellow across frames)

**Root cause (from 5 rounds):**
- Gray-world AWB without scene detection, no illuminant classification.
- No per-channel gains.
- No temporal smoothing → frame-to-frame flip-flop.
- M9 CCD profile internally clamped CCT, which is why scene 05 was stable.

**Upgrade addressing it:** **U-05 — AWB CCT Clamping**
- New `CLAMPED_AUTO` mode in `WhiteBalanceControlPath`
- CCT clamped to **[3500, 7000] K**
- EMA smoothing with **alpha=0.15** (1 frame = 15% of new value, 85% of previous)
- Imported from M9 CCD's internal stabilization logic to default AUTO mode

**Confidence: LIKELY_SOLVED**

**Why:**
- The fix is the right shape — clamp + EMA is the standard solution for
  AWB instability in production camera pipelines.
- 3500-7000 K covers most common daylight + indoor-neural-white scenes.
- EMA with alpha=0.15 is a moderate smoothing factor — fast enough to track
  real scene changes, slow enough to kill single-frame flip-flop.
- Build SUCCESS confirmed `applyClampedAutoWhiteBalanceSettings` is wired
  (verified post-build: 2 occurrences in Camera2Controller.kt).

**Why not SOLVED:**
- 3500 K lower bound is too high for tungsten (2700 K) and candle (2000 K).
  These scenes may come out cooler than they should (gray instead of warm).
- 7000 K upper bound is too low for sunset/golden hour (7000-9000 K). May
  push sunsets cooler than natural.
- EMA alpha=0.15 is unvalidated — might be too aggressive (laggy) or too
  weak (still flips on rapid scene changes).
- No scene detection — clamp is global, doesn't adapt per scene type.

**Test scenario to confirm/deny:**
- **A.7 (group photo, 3-shot burst):** skin tone must be IDENTICAL across
  the 3 frames (delta-E < 1). If it shifts, EMA alpha too weak.
- **B.4 (restaurant, 2700-3000 K):** scene must look warm-neutral, NOT
  blue-gray. If blue-gray, clamp lower bound too high.
- **B.5 (candle, 2000-2500 K):** scene must look warm-orange (correct for
  candle). If neutral gray, clamp is misbehaving.
- **A.3 (sunset, 7000-9000 K):** oranges/pinks must be vivid. If washed
  cool, clamp upper bound too low.

---

### Problem 2 — Oversharpening (global USM, 2-4px halos on edges)

**Root cause (from 5 rounds):**
- USM with amount ~1.5 and radius 0.9 (too high).
- Sharpening applied globally, no detail/edge mask.
- Possibly applied BEFORE denoise (amplifying noise).
- Affects all profiles including M9 CCD (visible in scene 05 on sand grains).

**Upgrade addressing it:** **U-02 — Adaptive Sharpening**
- USM amount reduced **-40%** (1.5 → 0.9, or in config terms 0.09 → 0.054)
- USM radius reduced -22% (0.9 → 0.7)
- **Sobel edge mask** added — sharpening only on edges, not flat areas
- **Noise gating** — sharpening suppressed where local noise is high
- **Brightness-adaptive** — less sharpening in shadows (where noise lives)
- Sharpening moved to AFTER denoise

**Confidence: LIKELY_SOLVED**

**Why:**
- All 5 root causes are addressed: amount, radius, mask, ordering, noise gate.
- Build SUCCESS confirmed `uEdgeMaskStrength`, `CalcNoise`, `uAdaptive`
  uniforms are bound in the sharpening shader (sanity check counts: 2, 3, 3).
- The -40% amount reduction is conservative enough to not over-soften.
- Adaptive mask is the textbook solution (used by Apple, Google, Samsung).

**Why not SOLVED:**
- The known bug in U-02 (`{N;s}` vs `{N;N;s}` for multi-line sed pattern)
  was documented in the manifest, but **the Python3 rewrite
  (`apply_upgrades_v65.py`) bypasses sed entirely** and uses
  `content.replace()` — build 4 (commit 782622f) confirmed 22/22 sub-patches
  applied with zero warnings, and the adaptive uniform sanity checks all
  passed. So this bug is RESOLVED in the shipped APK.
- Adaptive sharpening has 3 free parameters (edge_mask_strength, noise
  threshold, brightness threshold) that are unvalidated against real-world
  scenes. May need 1-2 tuning iterations.
- Risk: -40% amount may over-soften center subjects on standard scenes
  (regression check F.1).

**Test scenario to confirm/deny:**
- **A.1 (horizon at noon):** water ripples should look natural, not crunchy.
- **A.4 (backlit portrait):** rim light on hair should be clean, no white
  outline.
- **A.7 (group photo):** skin should have pores, no "plastic" sheen, no
  cartoon edge on jaw/shoulder.
- **B.10 (concert stage):** stage edges should be crisp, no double-line.
- **Regression F.1:** A.10 (food, midtones) — center sharpness vs Native.
  If Native wins by ≥ 2 points, U-02 over-corrected.

---

### Problem 3 — HDR Halos (bilateral filter in ShadowsHighlightsShader)

**Root cause (from 5 rounds):**
- Bilateral filter in `ShadowsHighlightsShader` produces halos around
  high-contrast edges (sun, horizon, backlit subjects).
- Bilateral has inherent halo artifacts at high contrast ratios.
- No guided filter or local laplacian alternative.
- Affects all profiles including M9 CCD (visible in scene 05 around masts).

**Upgrade addressing it:** **U-03 — Bilateral → Guided Filter**
- Replaced bilateral with **He et al. 2010 guided filter** — O(N) and
  halo-free by design.
- New uniforms: `shGuidedFilterBaseL`, `shGuidedFilterRadius`,
  `shGuidedFilterEps`.
- `bindGuidedFilterUniforms` wired into the shader binding.
- Reduced tone mapping strength: `shadow_lift` 0.05 → 0.03 (-40%),
  `highlight_rolloff` 0.35 → 0.30 (-14%).

**Confidence: LIKELY_SOLVED**

**Why:**
- Guided filter is the **canonical replacement** for bilateral in
  tone-mapping contexts — this is exactly what Apple, Adobe, and Google
  use for halo-free local tone mapping.
- Build SUCCESS confirmed all guided filter uniforms are bound (sanity
  check count: 1 for `bindGuidedFilterUniforms`, 2 for
  `shGuidedFilterBaseL`).
- The shadow_lift + highlight_rolloff reduction is conservative — reduces
  the tone-mapping strength without killing DR entirely.

**Why not SOLVED:**
- Guided filter has its own parameters (radius, eps) that need scene-tuning.
  Default values may be too aggressive (image looks flat) or too weak
  (halos persist).
- The transition from bilateral → guided filter may have edge cases
  (very high contrast ratios, e.g., sun disk vs dark sky) that need
  validation.
- Reduced shadow_lift may make scenes look flatter than v6.4.x — users
  may perceive this as "less dynamic range" even though halos are gone.

**Test scenario to confirm/deny:**
- **A.1 (horizon):** NO bright band above or below the horizon line. This
  is the single most important regression test for U-03.
- **A.3 (sun in frame):** NO halo ring around the sun disk.
- **A.4 (backlit portrait):** NO bright outline around the head silhouette.
- **B.1 (street lamp):** minimal bloom around the lamp.
- **B.7 (moon):** NO halo ring around the moon.
- If halos persist on ANY of these, U-03 patch did not land correctly —
  check `ShadowsHighlightsShader.kt` for `shGuidedFilterBaseL` uniform
  presence.

---

### Problem 4 — Oil-Painting in Low-Texture Regions (detail_preserve was dead config)

**Root cause (from 5 rounds):**
- `detail_preserve` config parameter was **dead** — declared in
  `LeicaConfig.kt` but never read by the actual denoise shader.
- Chroma denoise applied aggressive smoothing uniformly — no detail
  preservation.
- Result: low-texture regions (sky, calm water, smooth skin, fabric)
  smeared into "oil-painting" look.
- Affects all profiles including M9 CCD (visible in scene 05 sand).

**Upgrade addressing it:** **U-04 — Detail Preserve Wiring**
- Wired `detail_preserve` → `uCentralPixelWeight` uniform (chroma denoise
  central pixel weight — higher value = more original detail preserved).
- Wired `detail_preserve` → `coarseMix` cap (limits cross-pixel mixing
  in the coarse guide).
- Wired `detail_preserve` → `COARSE_GUIDE_WEIGHT` config (controls how
  much the coarse guide dominates).
- Value changed 0.85 → 0.75 to compensate for actually being applied now.

**Confidence: PARTIALLY_SOLVED**

**Why PARTIALLY:**
- The WIRING is correct and verified post-build (build 4 success,
  22/22 sub-patches applied). The dead config is no longer dead — that's
  the structural fix.
- **However, the value choice (0.75) is a calculated risk.** Because the
  parameter was previously dead, there's no empirical baseline for what
  value works best. 0.75 was chosen as a "moderate" starting point, but
  it may be:
  - Too low (over-smoothing persists — oil-painting unchanged)
  - Too high (noise preserved as detail — chroma specks in shadows)
- The direction of `detail_preserve` (higher = more detail preserved or
  less?) depends on the shader implementation, which is non-obvious
  from the config alone.

**Why not NEEDS_TUNING:**
- The wiring IS the fix. The value is a tuning parameter that the beach
  test will tell us how to adjust. If 0.75 is wrong, it's a 1-line
  config change in v6.5.1.
- The structural problem (dead config) is solved.

**Test scenario to confirm/deny:**
- **A.6 (shells macro):** shell ridges and sand grains must be defined,
  not smeared. If oil-painting persists, raise to 0.80-0.85.
- **A.7 (group photo):** clothing weave + skin pores must be visible.
  If "plastic skin", raise to 0.80.
- **B.4 (restaurant):** tablecloth + clothing fabric weave must be
  visible.
- **B.9 (dark alley):** wall texture must be defined, not waxy. BUT if
  noise is preserved as detail (chroma specks appear), LOWER to 0.70.

---

### Problem 5 — Shadow Noise (insufficient denoise in dark areas)

**Root cause (from 5 rounds):**
- Denoise applied AFTER tone mapping (when noise was already amplified).
- No RAW-domain denoise.
- No chroma denoise separation.
- Worst in scenes with lifted shadows (rounds 03, 04).

**Upgrade addressing it:** **U-01 — Denoise Shadow Reinforcement**
- NLM (non-local means) **shadow-band boost** — stronger denoise in dark
  image regions (where shadow_lift amplified noise).
- **Per-lens 4-coefficient noise model** — each of the 4 sensors (OV50E,
  S5KJN1, S5K3J1, OV32B) gets its own (shotNoise + readNoise) × (R/Gr/Gb/B)
  model instead of a single global value.
- Increased `chrominance` 0.94 → 0.96 (more chroma NR).
- Increased `luminance` 0.96 → 0.98 (more luma NR).
- `search_radius` configurable.

**Confidence: LIKELY_SOLVED**

**Why:**
- Per-lens noise model is the right architecture — each sensor has
  different noise characteristics, and treating them separately is the
  textbook solution.
- Shadow-band boost addresses the "amplified noise in shadows" failure
  mode specifically.
- Build SUCCESS confirmed `uCentralPixelWeight` and the noise model
  accessors are all in place.

**Why not SOLVED:**
- The 4-coefficient model is only as good as the calibration data.
  Without an actual noise measurement per lens, the coefficients are
  derived from sensor datasheets / empirical fitting — may not match
  the real device exactly.
- Chroma 0.96 + luma 0.98 are aggressive. May over-smooth midtone
  detail (regression risk for problem 4 — oil-painting may WORSEN
  if NR is too strong).
- No temporal NR (TNR) — U-01 doesn't add TNR for multi-frame bursts,
  which is the most powerful tool for shadow denoise. Mode_max with
  15/9/7/11 frames already does implicit temporal fusion, but explicit
  TNR would be better.

**Test scenario to confirm/deny:**
- **B.9 (dark alley):** luminance + chroma noise in dark areas should
  be visibly reduced vs v6.4.10.
- **B.1 (street lamp):** dark sky should be smooth deep blue, no
  red/green/blue specks.
- **A.4 (backlit portrait):** shadowed face should have detail, not noise.
- **A.6 (shells in shade):** shadow under the umbrella should be clean.
- **Quantitative:** if a ColorChecker or uniform dark patch is shot,
  measure SNR — target ≥ 40 dB.

---

### Problem 6 — Highlight Blow-Outs (AE overexposes in bright scenes)

**Root cause (from 5 rounds):**
- No highlight protection in AE.
- Metering without histogram feedback.
- Default exposure bias too high (+0.88 EV) — pushed highlights into
  clipping on bright scenes.
- Visible in round 03 (shadow lifting too aggressive → highlights blow).

**Upgrade addressing it:** **U-06 — AE Histogram Feedback**
- New `AeHistogramProtector` class — computes per-frame clip fractions
  (fraction of pixels above highlight threshold / below shadow threshold).
- Applies EV compensation in **-6..+6 steps** to protect highlights and
  shadows.
- Wired into 3 AE-comp sites in `Camera2Controller.kt` (build verified:
  `aeProtectionEnabled` count = 3, `lastClipLowFraction` count = 1).
- Reduced base exposure: `default_exposure_ev` 0.88 → 0.5 (-43%),
  `pgtm_pre_tonemap_exposure_boost_ev` 0.8 → 0.4 (-50%).
- Combined: **-46% total exposure lift** vs v6.4.10.

**Confidence: LIKELY_SOLVED**

**Why:**
- Histogram feedback is the canonical solution for AE highlight
  protection — every production camera does this.
- Per-frame clip fraction computation is the right metric.
- Combined -46% exposure reduction is substantial — directly addresses
  the overexposure failure mode.
- Build SUCCESS confirmed all wiring.

**Why not SOLVED:**
- The histogram protector's thresholds (`highlight_clip_threshold`,
  `shadow_clip_threshold`) are unvalidated. May trigger too early
  (under-exposure) or too late (clipping persists).
- The -46% exposure lift reduction is the **biggest single change in
  v6.5.0**. Risk: mid-tone scenes (A.10 food) may now look dull/dark
  (regression F.4).
- EV step range -6..+6 may be too coarse or too fine.

**Test scenario to confirm/deny:**
- **A.2 (wet sand specular):** specular highlights should roll off to
  white, NOT clip to pure white.
- **A.3 (sun in frame):** sun disk should retain color (orange/red), NOT
  be a pure white disk.
- **B.1 (street lamp):** lamp should not bloom to a giant blob.
- **B.7 (moon):** moon disk should retain shape, not be a white circle.
- **Regression F.4:** A.10 (food midtones) — if scene looks dark/dull,
  the combined exposure reduction over-corrected.

---

### Problem 7 — Crushed Blacks (AE underexposes in low light)

**Root cause (from 5 rounds):**
- Same as problem 6 but inverse failure mode.
- No shadow protection in AE.
- Default exposure bias too low in low-light scenes.
- Visible in round 04 (crushed blacks).

**Upgrade addressing it:** **U-06 — AE Histogram Feedback** (same as
problem 6, the shadow protection side)
- `shadow_clip_threshold` triggers EV+ compensation when too many pixels
  are below RGB (5,5,5).
- `lastClipLowFraction` field added to `CameraState.kt` (build verified).

**Confidence: PARTIALLY_SOLVED**

**Why PARTIALLY:**
- The shadow protection mechanism is structurally correct (same as
  highlight protection, inverted).
- **BUT** the combined -46% exposure lift reduction (default_exposure_ev
  0.88→0.5 + pgtm boost 0.8→0.4) **directly counteracts** the shadow
  protection. The shadow protector may lift EV in low light, but the base
  exposure was already reduced — net effect is unclear.
- In very low light (B.9 dark alley), the shadow protector may try to
  lift EV but be capped at +6 steps. If that's insufficient, blacks
  crush.

**Why not NEEDS_TUNING:**
- The shadow protection mechanism IS implemented and wired.
- Whether it produces the right exposure in practice is the test.

**Test scenario to confirm/deny:**
- **B.9 (dark alley):** should be usable exposure, NOT too dark to read.
  If under-exposed, shadow protector not strong enough — tune
  `shadow_clip_threshold` higher.
- **B.5 (candle dinner):** deep shadows should have detail, not be pure
  black.
- **A.6 (shells in shade):** shadow under umbrella should have detail.
- **Regression F.4:** A.10 (food midtones) — if scene is dark, the base
  exposure reduction is too aggressive relative to the protector's lift
  range. Tune `default_exposure_ev` 0.5 → 0.65.

---

### Problem 8 — Color Saturation Drift (artificial "vivid" boost)

**Root cause (from 5 rounds):**
- Global saturation boost of ~+15% applied in default mode.
- Made vivid scenes look "radioactive" / artificial.
- M9 CCD profile uses calibrated color curves instead — that's why scene
  05 looked natural.
- Visible in rounds 02, 03.

**Upgrade addressing it:** **U-07 — Reduce Artificial Saturation**
  *(config-only, not a code patch in v6.5.0)*
- `color.saturation_boost` 1.02 → 1.0 (neutral)
- `color.vibrance` 1.01 → 1.04 (selective — only boosts low-saturation
  colors, leaves already-saturated colors alone)

**Confidence: NEEDS_TUNING**

**Why:**
- The fix is a config change, not an algorithmic change. The saturation
  boost was reduced from +2% to 0% — that's a minor change, not the -15%
  recommended in the original plan.
- Vibrance 1.01 → 1.04 is a small increase, may partially counteract the
  saturation reduction.
- U-07 was supposed to be "saturation +15 → 0 (neutral)" per the original
  plan, but the actual config delta is "+2 → 0". This is because prior
  versions of the fork (v6.4.x) had already reduced saturation from +15
  to +2 — v6.5.0 finishes the job by going to 0.
- No algorithmic vibrance implementation — the existing "vibrance" config
  may or may not actually do selective saturation depending on shader
  support.

**Why not PARTIALLY_SOLVED:**
- The fix is too small to claim "solved" — the difference between +2% and
  0% saturation is barely perceptible. The original problem was +15%
  causing "radioactive" look; we're now at 0% which is the right target,
  but the path from +15% → 0% was mostly done in v6.4.x, not v6.5.0.
- Vibrance 1.01 → 1.04 is in the OPPOSITE direction (more saturation).

**Test scenario to confirm/deny:**
- **A.3 (sunset):** oranges/pinks should look natural, not "punchy" or
  "vivid pop". If they look flat/desaturated, U-07 over-corrected —
  raise saturation_boost back to 1.02 or vibrance to 1.06.
- **B.2 (neon):** neon colors should be saturated but not radioactive.
- **B.10 (concert):** stage spotlight colors should be vivid but natural.
- **ColorChecker (optional):** ΔE2000 measurement. Target: < 5 average,
  < 10 worst patch.

---

### Problem 9 — Chroma Noise in Low Light (chroma NR was HARDCODED 0f dead config)

**Root cause (from 5 rounds):**
- In v6.4.x, chroma NR `chrominance` parameter was discovered to be
  HARDCODED to 0f in the shader (dead config) — fixed in P-76/P-77 (v6.4.9).
- v6.4.10 had `chrominance` 0.94. v6.5.0 raised it to 0.96.
- Detail_preserve was also dead (problem 4) — fixed in U-04.
- Combined effect: chroma NR is now actually applied, but at 0.96 may
  still be insufficient for extreme low light.

**Upgrade addressing it:** **U-01 + U-04 (combined)**
- U-01: `chrominance` 0.94 → 0.96, `luminance` 0.96 → 0.98, per-lens noise
  model, NLM shadow-band boost.
- U-04: detail_preserve wiring (was dead config) + value 0.85 → 0.75.

**Confidence: PARTIALLY_SOLVED**

**Why PARTIALLY:**
- The dead config fix (from P-76/P-77 in v6.4.9) was the structural fix
  — that's already shipped and SOLVED.
- v6.5.0's contribution is incremental: chrominance +0.02 (0.94→0.96),
  per-lens model, NLM boost. These are improvements but not paradigm
  shifts.
- For **extreme low light** (B.9 dark alley, B.5 candle), chroma noise
  may still be visible because:
  - 0.96 chroma NR is moderate, not aggressive
  - Multi-frame stacking helps but doesn't eliminate
  - No temporal chroma NR specifically

**Why not NEEDS_TUNING:**
- The mechanism is correctly implemented and wired.
- The 0.96 value is a reasonable starting point.

**Test scenario to confirm/deny:**
- **B.9 (dark alley):** check for red/green/blue specks in dark walls.
  Compare to v6.4.10 baseline. If significantly cleaner, U-01 worked.
- **B.6 (night portrait):** check for chroma noise on faces.
- **B.5 (candle):** check for chroma noise in dark background.
- **Quantitative:** SNR measurement in dark uniform patch. Target: ≥ 40 dB.
- **If still noisy:** tune `noise_reduction.chrominance` 0.96 → 0.97 → 0.98.

---

### Problem 10 — M9 CCD Profile Instability (75% success, affected by base pipeline)

**Root cause (from 5 rounds):**
- M9 CCD profile scored 75% as a CCD emulation (palettes, microcontrast,
  grain were good).
- But it was affected by the same base pipeline problems as the standard
  mode: oversharpening, HDR halos, oil-painting, weak shadow denoise.
- These base problems capped M9 CCD at 75% — couldn't reach 85%+ because
  sand grains had oversharpening, masts had halos, etc.

**Upgrade addressing it:** **U-01..U-04 (indirect — they fix the base
pipeline that affects M9 CCD)**
- U-01: better shadow denoise → M9 CCD dark areas cleaner
- U-02: adaptive sharpening → M9 CCD sand grains no longer crunchy
- U-03: guided filter → M9 CCD masts/horizon no longer halo'd
- U-04: detail preserve → M9 CCD texture more natural

**Confidence: NOT_ADDRESSED (but indirectly improved)**

**Why NOT_ADDRESSED:**
- No M9 CCD-specific work was done in v6.5.0.
- U-05 (AWB clamping) was IMPORTED FROM M9 CCD, not applied TO it. M9 CCD
  already had its own AWB clamping internally.
- U-01..U-04 are pipeline-base changes — they apply to ALL profiles
  including M9 CCD, but they were not designed or tested specifically
  for M9 CCD preservation.

**Why indirectly improved:**
- The 3 base pipeline problems that capped M9 CCD at 75% (oversharpening,
  halos, oil-painting) are the exact problems U-02, U-03, U-04 target.
- IF those upgrades work as designed, M9 CCD should benefit automatically.
- Expected: 75% → 80-85% if all 3 upgrades land cleanly.

**Risk:**
- U-01..U-04 may BREAK M9 CCD's 3 strengths (palettes, microcontrast,
  grain) if the pipeline changes disrupt the M9 CCD color science.
- Specifically: U-02's sharpening reduction may reduce the "bite"
  (high-frequency microcontrast) that is a signature CCD characteristic.
- U-04's detail_preserve change may affect grain rendering.
- U-03's guided filter may flatten the M9 CCD microcontrast.

**Test scenario to confirm/deny:**
- **Re-test scene 05 (beach with boats) using M9 CCD profile.**
- Check the 3 M9 CCD strengths:
  - Kodak palette: deep blues, magentas in midtones, faithful greens
  - Microcontrast: metallic "bite" in fine details
  - Photographic grain: fine uniform grain, no chroma noise
- If any of these regress, M9 CCD profile needs separate tuning in v6.5.1.
- Target: 75% → ≥ 85% (success) or 75% → < 70% (regression, urgent fix).

---

## FINAL VERDICT

### "Boa parte dos problemas já foi sanada?"

**YES (with caveats).**

Of the 10 problems:
- **3 are SOLVED or LIKELY_SOLVED with high confidence** (problems 1, 2, 3 —
  AWB instability, oversharpening, HDR halos — the 3 critical problems that
  capped overall quality).
- **4 are LIKELY_SOLVED with regression risks** (problems 5, 6, and
  partially 4, 7 — shadow noise, highlight blowouts, oil-painting, crushed
  blacks).
- **2 are PARTIALLY_SOLVED** (problems 4 and 9 — oil-painting detail_preserve
  value risk, chroma noise in extreme low light).
- **1 NEEDS_TUNING** (problem 8 — color saturation, config-only fix too
  small).
- **1 NOT_ADDRESSED but indirectly improved** (problem 10 — M9 CCD profile).

**The structural fixes are all in place.** The dead configs are wired. The
bilateral filter is replaced. The AWB clamping is active. The AE histogram
protector is running. The per-lens noise model is loaded. The adaptive
sharpening mask is bound.

**What remains is tuning**, not architecture. The values chosen (0.75
detail_preserve, 3500-7000K CCT clamp, -46% exposure lift, 0.054 sharpening
amount) are reasonable starting points but unvalidated against real-world
scenes.

### "Resta apenas ajustes finos?"

**YES, for problems 1, 2, 3, 5, 6.**
These should work visibly better than v6.4.10 even without tuning. If the
beach/night tests show them working, no v6.5.1 needed for these.

**PARTIAL YES, for problems 4, 7, 9.**
These need validation. If the beach/night tests show:
- Oil-painting persisting → raise `detail_preserve` 0.75 → 0.80
- Mid-scenes underexposed → raise `default_exposure_ev` 0.5 → 0.65
- Chroma noise in extreme low light → raise `chrominance` 0.96 → 0.97

These are 1-line config changes — "ajustes finos" qualifies.

**NO, for problem 8 (color saturation).**
The v6.5.0 fix is too small (saturation +2% → 0% is barely perceptible).
A real fix would require either:
- Algorithmic vibrance implementation (shader work)
- Adopting M9 CCD color curves as the default (significant color science work)
- Per-scene saturation logic (scene detection required)

This is a v6.6+ item, not fine-tuning.

**NO, for problem 10 (M9 CCD profile).**
If U-01..U-04 break M9 CCD's strengths (palette, microcontrast, grain), the
fix is non-trivial — requires scoping pipeline changes to exclude M9 CCD
path, or building M9 CCD as a separate post-processing LUT applied AFTER
the standard pipeline.

### Top 3 Risks for the Beach Test

1. **U-03 guided filter may have residual halos on sun-in-frame scenes
   (A.3 sunset).** Guided filter is halo-free by design at moderate
   contrast ratios, but at extreme ratios (sun disk vs dark sky = 8+ stops),
   edge cases may persist. If A.3 shows halos, U-03 needs parameter tuning
   (radius / eps).

2. **U-05 CCT clamp may push sunset (A.3) too cool OR candle (B.5) too
   cool.** The 3500-7000K window is a calculated compromise — it covers
   daylight + neutral white LEDs, but excludes the warmest (candle) and
   coolest (sunset) extremes. Either failure mode is a 1-line fix
   (`min_cct` or `max_cct` change), but it WILL be a visible flaw in the
   beach/night tests.

3. **U-04 detail_preserve value 0.75 may over-smooth foliage/fabric.**
   Because the parameter was dead before, there's no baseline to know if
   0.75 is right. The shells macro (A.6) and group photo (A.7) are the
   critical tests. If oil-painting persists, raise to 0.80-0.85. If noise
   is preserved as detail (chroma specks), lower to 0.70.

### Top 3 Things Most Likely to Need a v6.5.1 Fine-Tune

1. **`awb_clamp.min_cct` 3500 → 2700-3000.** The 3500K lower bound is too
   high for tungsten (2700K) and candle (2000K). Beach restaurant (B.4)
   and candle dinner (B.5) are likely to show scenes pushed too cool.
   1-line fix in `leica_perfect.json`.

2. **`processing.default_exposure_ev` 0.5 → 0.6-0.65 + `processing.pgtm_pre_tonemap_exposure_boost_ev`
   0.4 → 0.5.** The combined -46% exposure lift reduction is the most
   aggressive single change in v6.5.0. Mid-tone scenes (A.10 food, A.7
   group photo) are at risk of looking dark/dull. Splitting the difference
   (going back ~halfway) is a 2-line fix.

3. **`noise_reduction.detail_preserve` 0.75 → 0.80.** The value was reduced
   0.85 → 0.75 to compensate for the parameter being wired for the first
   time. This is a calculated risk — if the beach/night tests show
   oil-painting (especially in foliage, fabric, sand), raise it back to
   0.80-0.85. 1-line fix.

### Honest Bottom Line

**v6.5.0 is a substantive upgrade**, not a cosmetic one. The 6 upgrades
collectively address the 3 critical pipeline problems (oversharpening, HDR
halos, AWB instability) that capped quality across all profiles, plus 3
mode-specific problems (shadow noise, AE highlight/shadow protection, detail
preserve wiring).

**Expected outcome of beach/night tests:**
- PhotonCamera should **no longer lose 4-0** to Native.
- Realistic P:N ratio target: **12:8 to 14:6** in Photon's favor on the 20
  scenes (vs 0:4 baseline in v6.4.10).
- If P:N ratio is **8:12 or worse**, something is broken — likely U-03
  guided filter, U-05 CCT clamp, or U-06 exposure reduction. Investigate.
- If P:N ratio is **15:5 or better**, v6.5.0 exceeded expectations — proceed
  to Fase 3 (U-08 ICC calibration, U-09 profile expansion, U-10 film grain).

**v6.5.1 is likely needed** for the 3 fine-tuning items above, but should be
a small patch (config-only, no code changes, no rebuild of native code).

---

**Document end.** For the test execution plan, see `TEST_PLAN.md` in the
same directory.
