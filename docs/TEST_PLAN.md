# TEST PLAN — PhotonCamera "Leica Perfect v6.5.0" vs Native Xiaomi 15T Camera

**Goal:** Validate that the 6 VLM-driven upgrades (U-01..U-06) shipped in v6.5.0
fix the 10 problems identified across 5 rounds of comparative testing, without
introducing regressions. The user is going to the **BEACH today** and doing
**NIGHT tests tonight** — this plan is structured around those two scenarios.

**Device:** Xiaomi 15T — OV50E main / S5KJN1 UW / S5K3J1 tele / OV32B selfie
**Build under test:** `LeicaPerfect-v6.5.0-debug.apk`
(commit `782622f`, release tag `latest`)
**Reference:** Stock HyperOS camera, "Photo" mode, auto everything

---

## 0. Quick Start (read this first if in a hurry)

1. Install v6.5.0 APK over v6.4.10 (data preserved).
2. Lock PhotonCamera to **`mode_max`** (gear → Capture Mode → `mode_max`).
3. Keep **RAW ON** for the first 2 beach scenes + first 2 night scenes
   (we want DNGs for quantitative analysis). Turn RAW OFF for the rest
   (faster shooting, larger sample).
4. Use **default AUTO** AWB and **default AUTO** exposure for everything
   except where this plan explicitly says otherwise — U-05 and U-06 only
   trigger in AUTO mode, so manual override would mask their effects.
5. For each scene: **shoot 3 frames on PhotonCamera + 3 frames on Native**,
   same framing, same focal length. Write the shot numbers down (Section D
   checklist).
6. Tonight, download everything to a PC and review at **100% crop**.

---

## A. BEACH SCENARIOS (daytime, bright sun, high contrast)

The beach is the **single most demanding environment** for the upgrades. Bright
sun + sand + water + sky means:
- 4+ stop dynamic range → tests **U-03 (guided filter / no HDR halos)** and
  **U-06 (AE highlight protection)**.
- Water ripples, sand grain, skin → tests **U-02 (adaptive sharpening)** and
  **U-04 (detail preserve / no oil-painting)**.
- Shadow under umbrellas / palm trees → tests **U-01 (shadow denoise)**.
- Mixed sky/sand illuminants → tests **U-05 (AWB clamping)**.

### A.1 — Horizon at noon (sky vs sand, ~4-stop DR)

**Scene:** Wide-angle shot of the horizon with sky taking ~50% of the frame
and wet/dry sand the other ~50%. Sun high, not in frame.

**Look for:**
- Halo along the horizon line? (U-03 — should be **absent**)
- Band of gray/magenta right at the horizon? (tone-mapping transition)
- Sky color: deep blue or washed cyan? (U-05 — should be neutral blue)
- Sand color: warm beige or blue-cast? (U-05 — should be neutral warm)
- Shadow noise in dry sand foreground? (U-01 — should be clean)
- Water ripples: crunchy or natural? (U-02 — should be natural)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×).

---

### A.2 — Wet sand reflections (specular highlights)

**Scene:** Frame a stretch of wet sand near the surf line, where the sun is
reflecting off the water film. The frame will have point-like specular
highlights at 100% brightness surrounded by darker wet sand.

**Look for:**
- Highlight blow-out: do the specular spots clip to pure white, or do they
  roll off gracefully? (U-06 — should roll off)
- Color fringe around highlights (CA / purple fringing) — note as baseline
- Bloom/glow extending past the highlight edge? (U-03 — should be **minimal**)
- Surrounding wet sand: oil-painting smear or natural texture? (U-04)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×).

---

### A.3 — Sunrise / sunset golden hour over water

**Scene:** Sun low on the horizon, partially or fully in frame, water in the
foreground reflecting the sun path. ~6-stop DR.

**Look for:**
- Sun disk: blown to white disk or colored (orange/red) with shape preserved?
  (U-06 — should preserve color)
- Halo around sun disk? (U-03 — should be **absent**; this is the #1 regression
  risk for the guided filter)
- Reflection path on water: continuous or broken by tone-mapping bands?
- Sky gradient: smooth or banded?
- Color saturation of oranges/pinks: natural or "vivid" pop? (U-07 config —
  should be **neutral**, not boosted)
- Foreground (rocks/silhouettes): crushed to black or with shadow detail?
  (U-01 + U-06)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×).
**Critical regression check:** U-05 CCT clamp upper bound is 7000K — sunset
scenes are often 7000-9000K. **Watch for:** sunset oranges pushed too warm
(clamp kicks in late) or pushed too cool (clamp caps too aggressively).

---

### A.4 — Backlit portrait against sea

**Scene:** Person standing between camera and sea/sun, face in shadow.
Sun behind their head (rim light).

**Look for:**
- Halo around the person's head/silhouette? (U-03 — should be **absent**)
- Face detail: pure black shadow or readable? (U-01 shadow denoise + U-06)
- Skin tone in the shadowed face: gray or natural? (U-05)
- Rim light on hair: clean edge or oversharpened "halo line"?
  (U-02 — should be **natural**, no white outline)
- Color of the sea behind: blue or washed?

**Settings:** `mode_max`, RAW OFF (faster), AWB AUTO, EV 0, main lens (1×).

---

### A.5 — Surfing / wave action shot (motion)

**Scene:** Someone surfing, or breaking waves. Fast motion. Tripod not
possible.

**Look for:**
- Motion blur: appropriate (subject frozen) or excessive?
- Water droplets: defined or smeared? (U-04 detail preserve)
- Spray against sky: crunchy noise or clean? (U-02)
- Color of water: natural teal or boosted vivid? (U-07 config)

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0, main lens (1×).
**Note:** multi-frame stacking may introduce ghosting on fast motion —
this is a known limitation, not a regression. Document if observed.

---

### A.6 — Close-up of shells in shade (macro-ish)

**Scene:** Macro / close-up of shells or pebbles in the shade of a beach
umbrella or under vegetation. Low light, fine texture.

**Look for:**
- Shell texture: natural ridges or oil-painting smear? (U-04 — critical)
- Sand grains around: defined or waxy? (U-04)
- Shadow noise in the deepest parts? (U-01)
- Edge halos around shell rims? (U-02)
- Color fidelity of the shell (white/cream/pink)? (U-05)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×).
Move close (10-15cm). If AF hunts, lock on the shell.

---

### A.7 — Group photo in open sun (people)

**Scene:** 2-4 people standing in direct sun, full body or half body.
Midday or early afternoon.

**Look for:**
- Skin tones: natural or magenta/yellow cast? (U-05 — should be **consistent
  across 3 frames**, no flip-flop)
- Skin pores: visible natural texture or "plastic skin"? (U-04)
- Edge halos around shoulders/hair against sky? (U-02 + U-03)
- Shadow under nose/chin: crushed or readable? (U-01)
- Clothing fabric: weave visible or smeared? (U-04)
- Eye detail: catch-light present or smeared? (U-02)

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0, main lens (1×).

---

### A.8 — Panorama of coastline (wide)

**Scene:** Use PhotonCamera panorama (if available) or widest UW lens
single shot. Coastline stretching left-right, sky/sand/sea.

**Look for:**
- Sky banding (gradient steps) — common in HDR
- Color shift across the frame (left blue, right warm) — AWB drift
- Edge halos along horizon (U-03)
- Sharpness falloff at edges (lens characteristic — note as baseline)
- Water color consistency left-right

**Settings:** UW lens (0.6×), `mode_max`, RAW OFF, AWB AUTO, EV 0.

---

### A.9 — Selfie against bright sky (front camera)

**Scene:** Selfie with sky/sun behind the shooter. Face in shadow.

**Look for:**
- Face exposure: crushed or readable? (U-06 + U-01)
- Sky: blown or with cloud detail? (U-06)
- Skin tone in shadow: gray or natural? (U-05)
- Halo around head silhouette? (U-03)
- Selfie lens is OV32B — note as baseline performance

**Settings:** Front camera, default mode, AWB AUTO, EV 0. Take 3 frames.

---

### A.10 — Food on beach table (midtones)

**Scene:** Plate of food / drinks on a beach table, in open shade (umbrella).
Mostly midtones, low DR. Easy scene — should look great.

**Look for:**
- Color accuracy of food (tomato red, lettuce green, bread tan)? (U-05)
- Detail in food texture (crumbs, grain)? (U-04)
- Background (sand/table) neutral? (U-05)
- Highlights on glass/wet food: clipped? (U-06)

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0, main lens (1×).
**Why this scene:** Easy win — if PhotonCamera can't beat native here,
something is wrong. If it does win, that's confirmation the basic pipeline
is now competitive.

---

## B. NIGHT SCENARIOS (low light, artificial light)

Night is where v6.4.x had the **worst performance** — heavy chroma noise,
smeared people, blown highlights on point lights. v6.5.0 should improve on
all of these (U-01 chroma NR boost, U-04 detail preserve, U-06 highlight
protection). The risk: U-05 CCT clamp at 3500K lower bound may push
candle-lit scenes (which are 2000-2500K) too cool.

### B.1 — Street lamp against dark sky (point-light halos)

**Scene:** Single street lamp, dark sky background. Lamp is point light at
near-100% brightness, sky is 1-5% brightness. ~8-stop DR.

**Look for:**
- Halo/glow around the lamp? (U-03 — should be **minimal**)
- Lamp shape: defined circle or bloomed blob? (U-06)
- Sky noise: chroma specks (red/green/blue)? (U-01)
- Sky gradient: smooth or banded?
- Stars visible (if any) or smeared by denoise? (U-04)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×).
Tripod or brace against a wall.

---

### B.2 — Neon sign (saturation + AWB stress)

**Scene:** Neon sign (red/blue/green/mixed) against a dark facade.

**Look for:**
- Color saturation of the neon tubes: natural or radioactive? (U-07 config)
- Glow halo around tubes? (U-03)
- AWB: does the facade stay neutral or shift toward the tube color?
  (U-05 — should stay neutral)
- Detail in the sign backing structure
- Noise in the dark facade? (U-01)

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0, main lens (1×).

---

### B.3 — City skyline at blue hour

**Scene:** Wide shot of city skyline 15-30 min after sunset. Deep blue sky,
warm yellow window lights.

**Look for:**
- Sky color: deep blue (good) or cyan/magenta cast? (U-05)
- Window lights: pinpoint or bloomed? (U-06)
- Sky gradient: smooth or banded? (HDR artifact)
- Halo around building silhouettes? (U-03)
- Noise in the sky? (U-01)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×) or UW.

---

### B.4 — Indoor restaurant warm light (AWB stress)

**Scene:** Interior of a restaurant with tungsten / warm LED lighting
(~2700-3000K). People, food on table.

**Look for:**
- **Critical:** does the scene come out neutral or warm-orange?
  (U-05 — should be neutral-warm, not orange)
- Skin tones of people: natural or jaundiced? (U-05)
- Food color: natural or warm-pushed? (U-05)
- Noise in shadows (under table, dark corners)? (U-01)
- Detail in fabric (tablecloth, clothing)? (U-04)
- Window (if visible to outside): blue night sky correct or smeared?

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0, main lens (1×).
**Critical regression check:** U-05 CCT clamp lower bound is 3500K. Tungsten
is 2700K. **Watch for:** restaurant scenes pushed too cool (blue-gray)
because the clamp refuses to honor the actual 2700K illuminant.

---

### B.5 — Candle-lit dinner (extreme warm + low light)

**Scene:** Dinner table lit only by candles. ~2000K color temp, very low
lux (10-50 lux).

**Look for:**
- **Critical:** scene should look warm-orange (correct) — if it looks neutral
  gray, U-05 CCT clamp is misbehaving at the low end
- Chroma noise in dark background? (U-01 — should be clean)
- Candle flame: blown or with shape/color? (U-06)
- Detail in food / table setting? (U-04)
- Skin tones of people: natural warm or magenta? (U-05)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×).
**Critical regression check:** same as B.4 — the 3500K clamp is the risk.
If scene comes out too cool/gray, log it as a U-05 regression.

---

### B.6 — Night portrait with screen flash

**Scene:** Person in dark environment, use PhotonCamera screen flash
(front camera) or use the phone's flash briefly as a fill.

**Look for:**
- Skin tone: natural or blue-cast (cool flash)? (U-05)
- Background behind subject: visible (good NR) or pure black (over-dark)? (U-01)
- Subject edges: clean or halo'd? (U-03)
- Eye detail: catch-light present? (U-02)
- Noise in the dark background? (U-01)

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0. Use front camera +
screen flash for one version, then rear camera + phone LED flash for
another. 2 sub-scenes.

---

### B.7 — Moon shot (telephoto, point light)

**Scene:** Moon alone or with horizon, using telephoto lens (S5K3J1, ~2×).

**Look for:**
- Moon disk: blown white or with shape/craters? (U-06)
- Halo around moon? (U-03 — common artifact, should be absent)
- Sky noise around moon? (U-01)
- Color of moon: neutral or warm/cool? (U-05)
- Star trails (if long exposure): clean or noisy?

**Settings:** Telephoto lens, `mode_max`, RAW OFF, AWB AUTO, EV 0 (or EV -1
to protect the moon disk). Brace against wall or use tripod.

---

### B.8 — Long exposure light trails (cars)

**Scene:** Road with car headlights/taillights at night. Long exposure
(several seconds) to capture light trails.

**Look for:**
- Trail color: natural red/white or smeared pink? (U-07)
- Trail continuity: smooth or broken?
- Background noise (buildings, sky)? (U-01)
- Halo along the trails? (U-03)
- AE: does the scene come out too dark (under) or too bright (over)? (U-06)

**Settings:** `mode_max` if it supports long exposure, else use PhotonCamera
"Long Exposure" mode if available. RAW OFF, AWB AUTO, EV 0. Tripod required.

---

### B.9 — Dark alley (extreme low light + shadow noise)

**Scene:** A dark alley or narrow street at night, minimal lighting.
Stress test for shadow noise.

**Look for:**
- **Critical:** luminance + chroma noise in dark areas? (U-01 — should be
  much better than v6.4.x)
- Smear / oil-painting in low-texture walls? (U-04)
- Detail in any visible features (door, sign)? (U-02)
- AWB drift? (U-05)
- Total exposure: too dark (under) or usable? (U-06)

**Settings:** `mode_max`, RAW ON, AWB AUTO, EV 0, main lens (1×). Brace.
**This is the worst-case scene for v6.4.x — should show the biggest
improvement in v6.5.0.**

---

### B.10 — Concert stage (mixed color temps)

**Scene:** Concert / performance stage with mixed-color spotlights
(red, blue, green, white). Extreme dynamic range, fast-changing lights.

**Look for:**
- Spotlight color: saturated and clean or blown/muddy? (U-06 + U-07)
- Performer skin tone under colored light: readable? (U-05)
- Background audience: black silhouettes or readable? (U-01)
- Halo around spotlit performers? (U-03)
- Noise in the dark audience? (U-01)
- Motion blur on performers (acceptable vs excessive)?

**Settings:** `mode_max`, RAW OFF, AWB AUTO, EV 0, main lens (1×) or tele.

---

## C. STRUCTURED COMPARISON METHODOLOGY

### C.1 — Capture Protocol (per scene)

1. **Both cameras, same framing, same focal length.**
   - PhotonCamera: use the same lens as Native (1× main, 0.6× UW, 2× tele).
   - Same subject distance, same composition (within ±5% framing tolerance).
2. **3-shot burst on each camera.** Best-of-3 to factor out AF/AE lottery.
   - PhotonCamera: shoot 3 frames back-to-back, ~1s apart.
   - Native: same.
3. **Same lighting moment.** Beach/sunset light changes fast — shoot both
   cameras within 60 seconds of each other. If light changes mid-pair, redo.
4. **Lock the same focus point** on both cameras (tap the same subject).
5. **Same EV** (both at 0). If Native chooses its own EV, log it — don't
   try to match it manually (we want to test the AUTO behavior of both).

### C.2 — Side-by-Side 100% Crop Comparisons

For each scene pair, do 6 crops at 100% (1920×1080 region or smaller):

| Crop Region | What to evaluate |
|---|---|
| **Shadows** (darkest 10% of frame) | Luma + chroma noise. Compare SNR. |
| **Midtones** (50% brightness) | Color fidelity, texture, smear. |
| **Highlights** (brightest non-clipped) | Detail retention, blowout. |
| **Edges** (high-contrast boundary) | Halos (1-4px), ringing, oversharpening. |
| **Skin** (if a person is in frame) | Pores, texture, color, "plastic" feel. |
| **Sky** (if sky is in frame) | Gradient smoothness, banding, color. |

### C.3 — Quantitative Metrics (if you have a PC and patience)

For scenes A.6 (shells), A.7 (group), B.4 (restaurant), B.9 (alley):
- **SNR in shadows:** Measure stddev/mean in a 100×100 patch of dark
  uniform area. Higher SNR (dB) = better. Target: ≥ 40 dB after U-01.
- **ΔE2000 vs ColorChecker:** If you bring a small ColorChecker passport
  to the beach, photograph it in scene A.10 (food) lighting. Measure
  ΔE2000 per patch using a tool like Rawtherapee or Imatest. Target: < 5
  average, < 10 worst patch.
- **MTF50 (sharpness):** Slanted-edge target (any straight high-contrast
  edge — a knife edge, or print a slanted-edge chart). Measure MTF50 in
  lp/ph with Imatest or `mtf_generate_edge` from MTFMapper. Target: ≥
  v6.4.10 baseline, ideally no drop > 5% from U-02 reduction.
- **Halo width:** On the horizon / sun disk edges, measure the pixel width
  of the bright "glow" band beyond the true edge. Target: ≤ 2 px after
  U-03 (was 4-8 px in v6.4.x).

These are optional — if you only have time for subjective scoring, that's
fine. But the RAW DNGs from scenes A.1, A.2, A.3, A.6, B.1, B.3, B.5, B.9
are worth keeping for later objective analysis.

### C.4 — Subjective Scorecard (1-10 per axis)

Use this table per scene. Lower = worse, 10 = reference quality.

| Axis | PhotonCamera v6.5.0 | Native | Winner | Notes |
|---|---|---|---|---|
| Sharpness (center) | _/10 | _/10 | | |
| Sharpness (corners) | _/10 | _/10 | | |
| Noise (shadows) | _/10 | _/10 | | |
| Noise (midtones) | _/10 | _/10 | | |
| Color accuracy | _/10 | _/10 | | |
| AWB stability (3 frames) | _/10 | _/10 | | |
| Dynamic range | _/10 | _/10 | | |
| Highlight retention | _/10 | _/10 | | |
| Shadow detail | _/10 | _/10 | | |
| HDR halos (lower=better) | _/10 | _/10 | | |
| Skin tone | _/10 | _/10 | | |
| Texture / oil-painting | _/10 | _/10 | | |
| **OVERALL** | _/10 | _/10 | | |

Scoring guide:
- **9-10:** Reference / best-in-class
- **7-8:** Good, minor issues
- **5-6:** Acceptable, visible flaws
- **3-4:** Poor, distracting flaws
- **1-2:** Broken

**Per-axis winner:** mark **P** (PhotonCamera), **N** (Native), or **T**
(tie within ±1 point). Tally per scene and overall.

### C.5 — Scene Tally Template

| Scene | P wins | N wins | Ties | Overall winner |
|---|---|---|---|---|
| A.1 Horizon noon | _ | _ | _ | _ |
| A.2 Wet sand | _ | _ | _ | _ |
| A.3 Sunset | _ | _ | _ | _ |
| A.4 Backlit portrait | _ | _ | _ | _ |
| A.5 Surfing action | _ | _ | _ | _ |
| A.6 Shells macro | _ | _ | _ | _ |
| A.7 Group photo | _ | _ | _ | _ |
| A.8 Panorama | _ | _ | _ | _ |
| A.9 Selfie | _ | _ | _ | _ |
| A.10 Food midtones | _ | _ | _ | _ |
| B.1 Street lamp | _ | _ | _ | _ |
| B.2 Neon | _ | _ | _ | _ |
| B.3 Blue hour skyline | _ | _ | _ | _ |
| B.4 Restaurant | _ | _ | _ | _ |
| B.5 Candle dinner | _ | _ | _ | _ |
| B.6 Night portrait | _ | _ | _ | _ |
| B.7 Moon | _ | _ | _ | _ |
| B.8 Light trails | _ | _ | _ | _ |
| B.9 Dark alley | _ | _ | _ | _ |
| B.10 Concert | _ | _ | _ | _ |
| **TOTALS** | _ | _ | _ | **P:N = _:_** |

**v6.5.0 success criteria** (vs v6.4.10 baseline of 0:4):
- **Minimum bar:** P:N ratio ≥ 8:12 (Photon wins at least 8 scenes)
- **Target:** P:N ratio ≥ 12:8 (Photon wins majority)
- **Stretch:** P:N ratio ≥ 14:6 (Photon dominates)

---

## D. USER CHECKLISTS (copy-paste-able to phone notes)

### D.1 Pre-Flight Checklist (do this ONCE before leaving)

```
[ ] APK installed: LeicaPerfect-v6.5.0-debug.apk
[ ] Verified version in app: Settings → About → 6.5.0
[ ] Capture mode: mode_max (gear → Capture Mode → mode_max)
[ ] RAW toggle: ON (gear → RAW toggle)
[ ] AWB: AUTO (do NOT lock — U-05 needs to trigger)
[ ] EV: 0 (do NOT compensate manually — U-06 needs to trigger)
[ ] Lenses available: 0.6× / 1× / 2× / front
[ ] Storage: ≥ 5 GB free (RAW DNGs are ~30 MB each, ~20 RAW scenes = 600 MB)
[ ] Battery: ≥ 70% (Night mode + mode_max is power-heavy)
[ ] Lens cleaned (both cameras — main + front)
[ ] Phone NOT in power-saving mode (kills performance)
[ ] Tripod/monopod if available — useful for B.7, B.8, B.9
[ ] ColorChecker passport if available — useful for A.10
[ ] Pen + paper or notes app ready to log shot numbers
```

### D.2 Per-Scene Checklist (do this for EACH scene A.1..B.10)

```
Scene ID: ___ (e.g., A.3)
Time: ___ : ___
Location: _______________
Lighting: sun / clouded / shade / indoor / night
Native shot #s: IMG___, IMG___, IMG___
Photon shot #s: PhotonCamera____, PhotonCamera____, PhotonCamera____

PhotonCamera settings used:
  - Mode: mode_max / other: ___
  - RAW: ON / OFF
  - Lens: 1× / 0.6× / 2× / front
  - AWB: AUTO / locked: ___
  - EV: 0 / other: ___
  - LUT: default / M9 CCD / other: ___

Quick observations (in the field):
  - Exposure: P under / P ok / P over | N under / N ok / N over
  - Color: P cool / P neutral / P warm | N cool / N neutral / N warm
  - Obvious artifacts seen on Photon? halo / smear / noise / other: ___
  - Obvious artifacts seen on Native? halo / smear / noise / other: ___

Pending: PC analysis tonight (100% crops + scorecard)
```

### D.3 Post-Shoot Analysis Checklist (do this TONIGHT)

```
[ ] Download all photos to PC (USB-C cable, "File Transfer" mode)
[ ] Separate into folders: /beach/A.1_horizon/, /beach/A.2_wet_sand/, ...
[ ] For each scene folder:
    [ ] Open Photon + Native side-by-side in viewer (IrfanView / Preview / GIMP)
    [ ] Zoom to 100% (1:1 pixel)
    [ ] Compare 6 crop regions: shadows / midtones / highlights / edges / skin / sky
    [ ] Fill subjective scorecard (Section C.4)
    [ ] Note specific artifacts observed
    [ ] Pick winner per axis + overall
[ ] Tally scene-level winners (Section C.5)
[ ] Compare with v6.4.10 baseline (Native 4 × 0 Photon)
[ ] Identify regressions (any scene Photon lost that it would have tied on v6.4.x)
[ ] Identify biggest wins (scenes where v6.5.0 dramatically improved)
[ ] Write summary: "v6.5.0 P:N = ___:___, regressions: ___, top wins: ___"
```

---

## E. SPECIFIC THINGS TO LOOK FOR POST U-01..U-06

### E.1 — U-01 (Denoise shadow reinforcement)
**What changed:** NLM shadow-band boost + per-lens 4-coef noise model
(OV50E / S5KJN1 / S5K3J1 / OV32B each get their own noise model).
**Expected visible effect:** Shadow noise visibly reduced.

**Test scenes:** A.1 (dry sand foreground), A.4 (face in shadow),
A.6 (under umbrella), B.1 (dark sky), B.9 (dark alley), B.10 (audience).

**Look at:**
- Dark rock crevices / wet sand shadows — should be clean, no chroma specks
- Shadow under beach umbrella — should have texture, not noise
- Tree shade line on the sand — smooth gradient, not speckled
- Night sky in B.1 / B.3 — should be smooth deep blue, no red/green/blue pixels

**Failure modes:**
- Still noisy → U-01 not aggressive enough → tune `noise_reduction.chrominance`
  higher (currently 0.96 → try 0.97)
- Over-smoothed (looks "plastic") → U-01 too aggressive → tune
  `noise_reduction.luminance` lower (currently 0.98 → try 0.96)

### E.2 — U-02 (Adaptive sharpening)
**What changed:** USM amount -40%, added Sobel edge mask, noise gating,
brightness-adaptive (less sharpening in shadows where noise lives).
**Expected visible effect:** Water ripples + sand grain natural, no crunchy
look; skin has no "cartoon edge" halos.

**Test scenes:** A.1 (water ripples), A.2 (wet sand texture), A.5 (spray),
A.7 (skin), B.10 (stage edges).

**Look at:**
- Water ripples: should look like water, not like CG
- Sand grain: should be fine natural grain, not "crunch"
- Skin: pores visible but no white outline on jaw/shoulder
- Building edges: crisp, no double-line / halo
- Flat areas (sky, calm water): should be SMOOTH (sharpening suppressed)

**Failure modes:**
- Whole image soft → U-02 over-corrected → tune `sharpening.amount` higher
  (currently 0.054 → try 0.07)
- Halos persist on edges → edge mask not strong enough → tune
  `sharpening.edge_mask_strength` higher (currently 3.5 → try 4.5)
- Noise reappears in shadows → noise gating threshold too high

### E.3 — U-03 (Bilateral → Guided filter)
**What changed:** Replaced bilateral filter in `ShadowsHighlightsShader`
with He et al. 2010 guided filter. O(N) and halo-free by design.
**Expected visible effect:** NO halos around sun, horizon, or backlit subjects.

**Test scenes:** A.1 (horizon), A.3 (sun in frame), A.4 (backlit portrait),
B.1 (street lamp), B.7 (moon).

**Look at:**
- Horizon line: NO bright band above or below the transition
- Sun disk: clean edge, no glow ring (or only natural atmospheric glow)
- Backlit portrait head: NO bright outline around silhouette
- Street lamp / moon: minimal bloom, clean point source

**This is the BIG one for beach.** If U-03 fails on sun/horizon, the beach
test is a regression regardless of other improvements.

**Failure modes:**
- Halos still present → U-03 patch may not have been applied correctly.
  Check `ShadowsHighlightsShader.kt` for `shGuidedFilterBaseL` uniform.
- Image looks flat / low contrast → guided filter too strong → tune
  `tone_mapping.shadow_lift` (currently 0.03 → try 0.04) and
  `tone_mapping.highlight_rolloff` (currently 0.30 → try 0.32)

### E.4 — U-04 (Detail preserve wiring)
**What changed:** `detail_preserve` was dead config in v6.4.x — now wired
to `uCentralPixelWeight` + `coarseMix` cap + `COARSE_GUIDE_WEIGHT`.
Value reduced 0.85 → 0.75 to compensate for actually being applied.
**Expected visible effect:** Foliage, fabric, skin pores NOT look like
oil-painting.

**Test scenes:** A.6 (shells + sand grain), A.7 (clothing fabric + skin),
B.4 (tablecloth + clothing), B.9 (wall texture).

**Look at:**
- Palm tree foliage: leaves defined, not "painted"
- Towel / clothing fabric: weave visible
- Skin pores: visible texture (not "wax")
- Sand grain: each grain readable, not smeared

**Failure modes:**
- Oil-painting persists → U-04 wiring may have failed, OR value 0.75 is
  too low. Tune `noise_reduction.detail_preserve` higher (0.75 → 0.80 → 0.85)
- Noise preserved as detail → too high. Tune lower (0.75 → 0.70).
- **Note:** the value was REDUCED 0.85→0.75 specifically because the
  parameter is now actually wired and has an effect. This is a calculated
  risk — if over-smoothing appears, raising it back to 0.80-0.85 is the fix.

### E.5 — U-05 (AWB CCT clamping)
**What changed:** M9 CCD stabilization logic imported to default AUTO mode.
CCT clamped to [3500, 7000] K, EMA smoothing (alpha=0.15).
**Expected visible effect:** Skin tones consistent across 3+ frames, no
blue↔yellow flip-flop. Sand neutral, no blue cast.

**Test scenes:** A.7 (3-shot burst consistency), B.4 (tungsten stress),
B.5 (candle stress), B.10 (mixed color stage).

**Look at:**
- 3 frames of A.7: skin tone should be IDENTICAL across frames (no shift)
- Sand in A.1: neutral warm beige, not blue-gray
- Sky in A.1: deep blue, not cyan
- Restaurant B.4: neutral-warm, NOT orange (good) but NOT blue-gray (clamp
  failure)
- Candle B.5: should look WARM-ORANGE (correct for 2000K), not neutral gray

**Failure modes:**
- Scene pushed too cool (gray) in low-CCT light → clamp lower bound (3500K)
  too high. Tune `awb_clamp.min_cct` lower (3500 → 3000 → 2700).
- Scene pushed too warm (yellow) in daylight → clamp upper bound (7000K)
  too high. Tune `awb_clamp.max_cct` lower (7000 → 6500).
- Frame-to-frame flip-flop persists → EMA smoothing too weak. Tune
  `awb_clamp.smoothing_alpha` lower (0.15 → 0.10 → 0.05).
- AWB feels sluggish / lagging behind scene changes → EMA too strong.
  Tune `smoothing_alpha` higher (0.15 → 0.20).

**Critical regression check:** sunset (A.3) is naturally 7000-9000K. The
clamp caps at 7000K, which may push sunset oranges TOO warm or TOO cool.
Document carefully.

### E.6 — U-06 (AE histogram feedback)
**What changed:** `AeHistogramProtector` class added, runs per-frame,
computes clip fractions, applies EV compensation in -6..+6 steps to
protect highlights and shadows. Also default_exposure_ev reduced
0.88→0.5, pgtm boost 0.8→0.4 (-46% total exposure lift).
**Expected visible effect:** Highlights on wet sand / sun in frame should
NOT blow out completely. Shadows should NOT be crushed to pure black.

**Test scenes:** A.2 (wet sand specular), A.3 (sun in frame), A.6 (shade
deep shadows), B.1 (street lamp), B.7 (moon), B.9 (alley).

**Look at:**
- Wet sand specular highlights: roll off to white, not clip to pure white
- Sun disk: retains some color (orange/red), not pure white disk
- Shade shadows in A.6: have detail, not pure black
- Night alley B.9: usable exposure, not too dark
- Street lamp / moon: not bloomed to blob

**Failure modes:**
- Scenes systematically underexposed → AE protector over-reacting. Tune
  `ae_protection.highlight_clip_threshold` higher (e.g., 0.01 → 0.02)
- Highlights still blow out → AE protector not aggressive enough. Tune
  threshold lower (0.01 → 0.005) or increase EV step range
- Shadows crushed → shadow protection not engaged. Verify
  `ae_protection.shadow_clip_threshold` is set (target: RGB < 5,5,5
  should trigger shadow lift)
- Mid-scenes (A.10 food) look dull/dark → combined -46% exposure lift
  from default_exposure_ev + pgtm boost may be too much. This is the
  biggest regression risk.

---

## F. REGRESSION CHECKS — Things That Worked Before That U-01..U-06 Might Break

The 6 upgrades are aggressive. Here's what to actively verify ISN'T broken.

### F.1 — Center sharpness on standard scenes (U-02 risk)
**Risk:** U-02 reduced sharpening amount -40%. Center subject may now look
soft vs v6.4.10.
**Test:** Scene A.7 (group photo), A.10 (food). Compare center sharpness
of v6.5.0 vs Native. If Native wins by ≥ 2 points on sharpness axis, U-02
over-corrected.
**Tune:** `sharpening.amount` 0.054 → 0.065 (split the difference with
v6.4.10's 0.09).

### F.2 — Color saturation on vivid scenes (U-05 + U-07 risk)
**Risk:** U-05 CCT clamp + U-07 saturation_boost 1.02→1.0 may make vivid
scenes (sunset A.3, neon B.2, concert B.10) look flat/desaturated.
**Test:** A.3 sunset — oranges/pinks should still pop. B.2 neon — colors
should still be vivid.
**Tune:** `color.saturation_boost` 1.0 → 1.02, OR `color.vibrance`
1.04 → 1.07.

### F.3 — Detail in foliage / fabric (U-04 risk)
**Risk:** U-04 reduced `detail_preserve` 0.85→0.75. Combined with U-01
shadow NR boost, foliage and fabric may be over-smoothed.
**Test:** A.6 shells/sand, A.7 clothing, B.4 tablecloth.
**Tune:** `noise_reduction.detail_preserve` 0.75 → 0.80.

### F.4 — Exposure on standard scenes (U-06 risk)
**Risk:** U-06 added per-frame EV compensation + reduced base exposure
(-46% lift). Standard mid-tone scenes may now look underexposed.
**Test:** A.10 food (midtones) is the critical one. Also any plain
daylight landscape.
**Tune:** `processing.default_exposure_ev` 0.5 → 0.65,
`processing.pgtm_pre_tonemap_exposure_boost_ev` 0.4 → 0.55.

### F.5 — AWB on extreme warm light (U-05 risk)
**Risk:** 3500K lower CCT clamp may push candle/tungsten scenes too cool.
**Test:** B.4 (restaurant, 2700-3000K), B.5 (candle, 2000-2500K).
**Tune:** `awb_clamp.min_cct` 3500 → 3000 → 2700.

### F.6 — AWB on sunset/golden hour (U-05 risk)
**Risk:** 7000K upper CCT clamp may push sunset scenes (7000-9000K) too
cool, killing the orange/gold.
**Test:** A.3 sunset.
**Tune:** `awb_clamp.max_cct` 7000 → 7500 → 8000.

### F.7 — Capture latency (any U-01..U-06 risk)
**Risk:** U-01 added NLM shadow-band boost (more compute), U-03 swapped
bilateral for guided filter (slightly faster but new code path), U-06
added per-frame histogram computation.
**Test:** Subjective feel — does the shutter feel slower than v6.4.10?
Time the gap between tap and capture-complete notification across 5
shots.
**Acceptable:** < 1.5× v6.4.10 latency.
**Tune:** Disable `ae_protection.histogram_feedback_enabled` to test
whether U-06 is the culprit; disable `noise_reduction.shadow_band_boost`
to test whether U-01 is the culprit.

### F.8 — Multi-frame stacking (mode_max)
**Risk:** mode_max uses 15/9/7/11 frames + super-res. U-01..U-06 changes
might destabilize the fusion pipeline.
**Test:** A.5 (surfing action — motion stress), A.7 (group — people
moving slightly), B.9 (alley — low light stacking stress).
**Look for:** ghosting, misalignment, weird halos around moving subjects.

### F.9 — M9 CCD profile (regression on the 75% win)
**Risk:** U-01..U-04 are pipeline-base changes. They might break the
palettes/microcontrast/grain that made M9 CCD score 75%.
**Test:** Repeat scene 05 from the original 5 rounds (beach with boats)
using M9 CCD profile. Score should stay ≥ 75%.
**Look for:** Kodak palette intact, microcontrast intact, grain intact.
If any of these regress, the pipeline-base changes need to be scoped to
exclude M9 CCD path.

### F.10 — RAW DNG export quality
**Risk:** U-01..U-04 touch the raw pipeline. The DNG output may have
unexpected processing baked in.
**Test:** Open the RAW DNGs from A.1, A.2, A.3, A.6, B.1, B.3, B.5, B.9
in RawTherapee or Lightroom. Check that they look like raw files (flat,
linear, no sharpening halos, no NR smear baked in).
**Look for:** Any processing artifacts that suggest the raw pipeline is
no longer truly "raw".

---

## G. Summary — What Success Looks Like

**v6.5.0 is a SUCCESS if:**
1. P:N ratio ≥ 12:8 across the 20 scenes (Section C.5)
2. No regressions in F.1-F.10 that drop Photon below v6.4.10 baseline
3. M9 CCD profile still scores ≥ 75% on a re-test of scene 05
4. U-03 (no halos) visibly works on at least 3 of: A.1, A.3, A.4, B.1, B.7
5. U-05 (AWB stability) shows consistent skin tone across 3 frames in A.7

**v6.5.0 is a PARTIAL SUCCESS if:**
1. P:N ratio ≥ 8:12 (Photon wins at least 8 scenes)
2. Halos visibly reduced but not eliminated
3. AWB more stable but with documented edge cases (sunset/candle)

**v6.5.0 needs a v6.5.1 fine-tune if:**
1. Any of F.1-F.6 regression modes triggers visibly
2. P:N ratio < 8:12
3. M9 CCD drops below 70%
4. Latency > 2× v6.4.10

---

**Document end.** Print Sections D.1, D.2, D.3 to phone notes before leaving.
Take photos, enjoy the beach, do good science tonight.
