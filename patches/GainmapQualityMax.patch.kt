// ═══════════════════════════════════════════════════════════════════════════════
// GainmapQualityMax.patch.kt — P-42 (v6.1)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-42 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   1. Verifica + reforça P-23 (que setou GAINMAP_JPEG_QUALITY = 100 via LeicaConfig).
//   2. CRITICAL FIX: converte `const val X = LeicaConfig.Y` (que NÃO compila em
//      Kotlin — const val requires constant initializer) pra `val X: Int get() =
//      LeicaConfig.Y` (race-safe form que compila).
//   3. Mesma correção aplicada pra JPEG_QUALITY (defensive).
//   4. Defensive grep-guarded LeicaConfig import.
//
// POR QUE:
//   - Task 5-a gap L1: gainmap JPEG quality hardcoded 95 no stock. P-23 setou
//     pra 100 mas manteve `const val` keyword (bug).
//   - Kotlin spec: `const val X = LeicaConfig.Y` é COMPILE ERROR porque
//     `const val` initializer must be a constant expression (LeicaConfig.Y é
//     runtime lookup, não constante).
//   - Sintoma: build falha com "Const 'val' initializer should be a constant value".
//   - Fix: converter `const val X = LeicaConfig.Y` → `val X: Int get() = LeicaConfig.Y`
//     (race-safe form, compila, mantém valor dinâmico).
//   - Mesma bug pattern existe em Patches 8/9/11/12/13/14 (const val = LeicaConfig.X)
//     — flagged pra future fix. P-42 fixa P-23 e a si mesmo.
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/processor/Jpeg444ExportEncoder.kt
// ÂNCORA SED: `private const val GAINMAP_JPEG_QUALITY = 95` (line ~335)
// INJEÇÃO: 3 seds — step 1 (defensive), step 2 (corrige P-23 bug), step 3 (JPEG_QUALITY)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.gainmapJpegQuality: Int (default 100 via advanced.gainmap_jpeg_quality)
//   - LeicaConfig.outputQuality: Int (default 100 via output.quality)
//
// RACE CONDITION NOTE:
//   - `val X: Int get() = LeicaConfig.Y` é RACE-SAFE: cada read consulta
//     LeicaConfig.currentConfig, que é @Volatile. Mesmo se config reload em
//     background, reads são consistentes.
//   - `const val X = LeicaConfig.Y` seria race-UNSAFE (e não compila): captura
//     valor em init-time, congela, ignora future reloads.
//   - P-42 fix garante que gainmap Q100 funciona mesmo após reload de config.
//
// LIMITAÇÕES:
//   - P-42 só corrige P-23 + a si mesmo. Outros patches (8/9/11/12/13/14) com
//     mesmo bug `const val = LeicaConfig.X` ainda precisam ser corrigidos.
//   - P-42 step 1 (defensive): se P-23 já aplicou race-safe form, step 1 é no-op.
//   - P-42 step 2 (corrige P-23 bug): se P-23 aplicou `const val = LeicaConfig.X`
//     (errado), step 2 converte pra race-safe form.
//   - Gainmap Q100 aumenta arquivo UltraHDR JPEG em ~5-15% (vs Q95). Aceitável
//     porque gainmap é small (1/4 resolution SDR reference).
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.processor

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (upstream original) ───────────────────────────────────────────────
class Jpeg444ExportEncoder {
    companion object {
        private const val JPEG_QUALITY = 95
        private const val GAINMAP_JPEG_QUALITY = 95
        // ...
    }
    // ...
}

// ─── ESTADO INTERMEDIÁRIO (após P-23 — com bug) ──────────────────────────────
class Jpeg444ExportEncoder {
    companion object {
        // P-23 trocou 95 por LeicaConfig.X mas MANTIVE const val — COMPILE ERROR!
        private val JPEG_QUALITY: Int get() = LeicaConfig.outputQuality  // P-23 race-safe (OK)
        private const val GAINMAP_JPEG_QUALITY = LeicaConfig.gainmapJpegQuality  // P-23 BUG: const val com runtime init
        // ...
    }
    // ...
}

// ─── DEPOIS (após P-42 — bug corrigido) ──────────────────────────────────────
class Jpeg444ExportEncoder {
    companion object {
        // P-23 race-safe (preservado por P-42)
        private val JPEG_QUALITY: Int get() = LeicaConfig.outputQuality  // 100
        // P-42 corrige: const val → val get() (race-safe, compila)
        private val GAINMAP_JPEG_QUALITY: Int get() = LeicaConfig.gainmapJpegQuality  // 100
        // ...
    }
    // ...
}

// ─── SED COMMANDS (executados pelo build-archlinux.sh) ───────────────────────
// Step 1 (defensive — garante que const val 95 virou val get()):
//   sed -i 's|private const val GAINMAP_JPEG_QUALITY = 95|private val GAINMAP_JPEG_QUALITY: Int get() = LeicaConfig.gainmapJpegQuality|' "$j444"
//
// Step 2 (corrige P-23 bug se produzido const val = LeicaConfig.X):
//   sed -i 's|private const val GAINMAP_JPEG_QUALITY = LeicaConfig.gainmapJpegQuality|private val GAINMAP_JPEG_QUALITY: Int get() = LeicaConfig.gainmapJpegQuality|' "$j444"
//
// Step 3 (mesmo fix pra JPEG_QUALITY — defensive):
//   sed -i 's|private const val JPEG_QUALITY = LeicaConfig.outputQuality|private val JPEG_QUALITY: Int get() = LeicaConfig.outputQuality|' "$j444"
//
// Step 4 (grep-guarded import — defensive):
//   grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$j444" || \
//     sed -i '/^package com.hinnka.mycamera.processor$/a import com.hinnka.mycamera.raw.LeicaConfig' "$j444"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-23 (Jpeg444ExportEncoder JPEG+gainmap Q100 — P-42 corrige o const-val bug)
//   - P-24 (HeicExportEncoder HEIC Q100 — mesmo pattern, não tem o bug)
//   - P-25 (UltraHdrWriter UltraHDR Q100 — mesmo pattern, não tem o bug)
//   - LeicaConfig.gainmapJpegQuality / outputQuality
//   - LeicaConfig.AdvancedTuning data class (gainmap_jpeg_quality field)
//   - max_capability_audit.md §LUT MAX (gainmap Q100 = max arquitetural)
//
// ⚠️  CONST-VAL RACE BUG PATTERN: existe em Patches 8/9/11/12/13/14 também.
//     P-42 só corrige P-23 + a si mesmo. Future fix: varrer todos os patches
//     e converter `const val X = LeicaConfig.Y` → `val X: T get() = LeicaConfig.Y`.
//     Padrão já está correto em P-2/P-8/P-9/P-11/etc (race-safe from the start).
