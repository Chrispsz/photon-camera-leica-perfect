// ═══════════════════════════════════════════════════════════════════════════════
// PerLensAgxConsumer.patch.kt — P-43 (v6.2) — fecha GAP G1
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-43 injeta no upstream PhotonCamera.
//
// ⭐⭐ PATCH STATUS: REAL — actually effective at runtime (não é no-op). ⭐⭐
//     Fecha GAP G1 (per-lens AgX consumer ativo no runtime).
//
// O QUE FAZ:
//   1. Adiciona import LeicaConfig ao CameraViewModel.kt (grep-guarded).
//   2. Adiciona parâmetro `cameraId: String = ""` à função
//      `resolveCaptureRawToneMappingParameters(userPrefs: UserPreferences?)`.
//   3. Substitui o body line `val base = userPrefs?.rawToneMappingParameters ?:
//      RawToneMappingParameters.DEFAULT` por uma versão que ativa per-lens AgX
//      quando o usuário NÃO customizou explicitamente.
//   4. Thread `currentCameraId` através de todos os 6 call sites da função.
//
// POR QUE:
//   - Task 6-c (definitive audit) achou gap G1: per-lens AgX accessor exists
//     em LeicaConfig + forLens factory em RawToneMappingParameters (via P-41),
//     MAS o consumer (RawDemosaicProcessor.kt 9 DEFAULT references) ainda usa
//     global DEFAULT ao invés de forLens(cameraId).
//   - Task 6-d investigation: os 9 sites em RawDemosaicProcessor.kt são
//     FUNCTION-DEFAULT-ARGUMENT values — só disparam quando caller não passa
//     explicit value. Em produção, todo caller real passa
//     `rawToneMappingParameters = rawToneMappingParameters`. Patching os 9 sites
//     teria ZERO runtime effect.
//   - REAL runtime chokepoint: `resolveCaptureRawToneMappingParameters(userPrefs)`
//     em CameraViewModel.kt L827-832. Esta função retorna o RawToneMappingParameters
//     que é propagado via MediaMetadata → RawDemosaicProcessor.process() →
//     processInternal() → renderCombinedPass() → renderEngineTonePass() →
//     GL shader uniform setters. 6 call sites com `currentCameraId` em scope.
//   - P-43 patcheia ESSE chokepoint com 4 seds — REAL effect no runtime.
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/viewmodel/CameraViewModel.kt
// ÂNCORAS SED:
//   1. `^package com.hinnka.mycamera.viewmodel$` (import injection point)
//   2. `^    private fun resolveCaptureRawToneMappingParameters($` (function signature)
//   3. `        val base = userPrefs?.rawToneMappingParameters ?: RawToneMappingParameters.DEFAULT` (body line)
//   4. `resolveCaptureRawToneMappingParameters(userPrefs)` (6 call sites — sed `g` flag)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.lensKeyFromCameraId(cameraId: String): String
//   - RawToneMappingParameters.forLens(lensKey: String): RawToneMappingParameters (via P-41)
//
// SED DELIMITER GOTCHA:
//   - Replacement da body line contém `||` (Kotlin logical OR).
//   - Se usarmos `|` como sed delimiter, o `||` seria interpretado como fim do
//     replacement + inicio de flags. PREMATURE TERMINATION.
//   - SOLUÇÃO: usar `#` como delimiter (não conflita com `||`).
//   - Mesma lição vale pra P-40 (que tem `||` no replacement mas funciona por
//     coincidência porque o `||` está FORA da regex parte). P-43 seguiu o pattern
//     correto desde o início.
//
// RACE CONDITION NOTE:
//   - `if (userParams == null || userParams == RawToneMappingParameters.DEFAULT)`
//     é race-safe: compara referência (==) — RawToneMappingParameters é data class,
//     mas DEFAULT é singleton reference. Quando user não customizou, userPrefs
//     retorna DEFAULT singleton (mesma reference), comparação == funciona.
//   - Quando user customizou (Settings UI), userPrefs retorna nova instância —
//     userParams != DEFAULT, fallback não ativa, user preference respeitada.
//
// LIMITAÇÕES:
//   - 3 gaps NÃO endereçados (documentados pra v7.0):
//       (a) Gallery reprocessing: galeria "re-edit" usa DEFAULT (não per-lens).
//           Future patch: thread cameraId through GalleryViewModel.
//       (b) UI Settings editor: quando user abre tone curve editor, DEFAULT é
//           mostrado (não per-lens). Future patch: Settings UI mostra per-lens preview.
//       (c) Gap G2 (per-lens DCP ratio): calculateInterpolationWeight é dead code
//           (computeInterpolatedCameraToXyz private sem callers). Wiring pronto
//           mas sem runtime effect. Documentado em P-39.
//   - P-43 não ativa per-lens tone mapping em vídeo (só stills). Video pipeline
//     tem path separado (YUV → encoder). Future patch pra v7.0.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.viewmodel

import com.hinnka.mycamera.raw.LeicaConfig
import com.hinnka.mycamera.raw.RawToneMappingParameters

// ─── ANTES (upstream original) ───────────────────────────────────────────────
class CameraViewModel {
    // ...

    private fun resolveCaptureRawToneMappingParameters(
        userPrefs: UserPreferences?
    ): RawToneMappingParameters {
        val base = userPrefs?.rawToneMappingParameters ?: RawToneMappingParameters.DEFAULT
        // ... resto da função (modifications based on scene/ISO/etc)
        return base
    }

    // 6 call sites (lines ~2396, ~4869, ~5097, ~5247, ~5433, ~5867):
    fun captureFlow1() {
        // ...
        val params = resolveCaptureRawToneMappingParameters(userPrefs)
        // ... usa params em MediaMetadata
    }
    // ... (5 outros call sites similares)
}

// ─── DEPOIS (após P-43 — 4 seds) ─────────────────────────────────────────────
class CameraViewModel {
    // ...

    // P-43: adiciona parâmetro cameraId (backward-compatible default "")
    private fun resolveCaptureRawToneMappingParameters(
        userPrefs: UserPreferences?,
        cameraId: String = ""  // P-43 — vazio = usar lensKey "main" fallback
    ): RawToneMappingParameters {
        // P-43: ativa per-lens AgX quando user NÃO customizou explicitamente
        val userParams = userPrefs?.rawToneMappingParameters
        val base = if (userParams == null || userParams == RawToneMappingParameters.DEFAULT)
            RawToneMappingParameters.forLens(LeicaConfig.lensKeyFromCameraId(cameraId))
            // forLens() retorna per-lens tuning (main=contrast1.10, UW=1.15, tele=1.18, front=1.08)
        else
            userParams  // user customizou — respeita preference
        // ... resto da função (modifications based on scene/ISO/etc)
        return base
    }

    // 6 call sites agora passam currentCameraId:
    fun captureFlow1() {
        // currentCameraId já está em scope (do camera open state)
        // ...
        val params = resolveCaptureRawToneMappingParameters(userPrefs, currentCameraId)
        // ... usa params em MediaMetadata
    }
    // ... (5 outros call sites similares — todos threaded com currentCameraId)
}

// ─── SED COMMANDS (executados pelo build-archlinux.sh) ───────────────────────
// 1. Import LeicaConfig (grep-guarded):
//    grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$cvm" || \
//      sed -i '/^package com.hinnka.mycamera.viewmodel$/a import com.hinnka.mycamera.raw.LeicaConfig' "$cvm"
//
// 2. Add cameraId parameter (multi-line N+s):
//    sed -i '/^    private fun resolveCaptureRawToneMappingParameters($/{N;s|\(\n        userPrefs: UserPreferences?\)$|\1,\n        cameraId: String = ""|}' "$cvm"
//
// 3. Body replacement (USE # DELIMITER — replacement contains ||):
//    sed -i 's#        val base = userPrefs?.rawToneMappingParameters ?: RawToneMappingParameters.DEFAULT#        val userParams = userPrefs?.rawToneMappingParameters; val base = if (userParams == null || userParams == RawToneMappingParameters.DEFAULT) RawToneMappingParameters.forLens(LeicaConfig.lensKeyFromCameraId(cameraId)) else userParams#' "$cvm"
//
// 4. Thread currentCameraId through 6 call sites (g flag):
//    sed -i 's|resolveCaptureRawToneMappingParameters(userPrefs)|resolveCaptureRawToneMappingParameters(userPrefs, currentCameraId)|g' "$cvm"

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-41 (PerLensAgxToneMapping — provê a factory forLens() que P-43 consome)
//   - P-2 (RawToneMappingParameters AgX defaults — base race-safe)
//   - LeicaConfig.lensKeyFromCameraId() / gammaContrastForLens/gammaShoulderForLens/gammaShadowLiftForLens
//   - CameraViewModel StateFlow seeds (init values pra rawToneMappingParameters)
//   - GalleryViewModel.kt (gap (a) — gallery reprocessing ainda usa DEFAULT)
//   - UserPreferencesRepository.kt (rawToneMappingParameters storage)
//   - RawDemosaicProcessor.kt 9 DEFAULT references (cosmetic only — function-default-args)
//   - DEFINITIVE_AUDIT_v6.2.md gap G1 (closed by P-43)
//   - beat_gcam_rationale.md §gamma (per-lens AgX values derivation)
//
// ⭐  G1 STATUS: CLOSED by P-43. Per-lens AgX tone mapping agora ativo no runtime.
//    UW/tele/front finalmente recebem seus tuned gamma_contrast/shoulder/shadow_lift.
//    Main continua usando DEFAULT quando user não customizou (mas DEFAULT também
//    tem main values via P-2 globals — same result).
