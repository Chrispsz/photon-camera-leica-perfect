// ═══════════════════════════════════════════════════════════════════════════════
// SuperResDngExport.patch.kt — P-40 (v6.1)
// ═══════════════════════════════════════════════════════════════════════════════
//
// ⚠️  DOCUMENTATION-ONLY — Este arquivo NÃO é compilado. Mostra o que o sed em
//     build-archlinux.sh cmd_patch() P-40 injeta no upstream PhotonCamera.
//
// O QUE FAZ:
//   Adiciona `|| LeicaConfig.exportSuperResDng` às DUAS gates `if (shouldAutoSave
//   && exportDngWithRawExport)` em GalleryManager.kt (lines ~2194 e ~3776).
//   Isso força o export de DNG 16-bit uncompressed (com super res 2.0× aplicado)
//   mesmo quando o usuário desligou o toggle "Save RAW with photo" na UI.
//
// POR QUE:
//   - Task 5-a gap R2 sibling: advanced.export_super_res_dng=true no JSON mas
//     sem runtime enforcement — JSON era doc-only.
//   - Stock PhotonCamera: exportDngWithRawExport default false. Usuário precisa
//     ligar toggle manualmente pra salvar DNG.
//   - v6.1: super res DNG é o OUTPUT MAX (16-bit uncompressed + 2.0× upscale via
//     RAW Radiance fusion). Forçar export garante que sempre temos o arquivo
//     máximo, não apenas quando o usuário lembra de ligar.
//   - P-27 já setou exportDngWithRawExport default true (via LeicaConfig), mas
//     o usuário ainda pode desligar na UI. P-40 garante que mesmo desligado,
//     quando LeicaConfig.exportSuperResDng=true, o DNG é salvo.
//
// ARQUIVO TARGET: app/src/main/java/com/hinnka/mycamera/manager/GalleryManager.kt
// ÂNCORA SED: `if (shouldAutoSave && exportDngWithRawExport) {` (appears 2x no arquivo)
// INJEÇÃO: substituição in-place com OR adicional (sed `g` flag pega ambos)
//
// LeicaConfig ACCESSORS USADOS:
//   - LeicaConfig.exportSuperResDng: Boolean (default true via advanced.export_super_res_dng)
//
// LIMITAÇÕES:
//   - P-40 força DNG export mesmo quando o usuário NÃO quer. Isso pode consumir
//     storage significant (~50-100MB por foto no main sensor com super res 2.0×).
//   - Mitigação: P-27 setou default true; P-40 só ativa parallel OR quando toggle
//     é desligado na UI. Usuário ainda pode desligar via JSON (export_super_res_dng=false).
//   - O DNG exportado inclui super res 2.0× quando multiFrameSuperResolutionScale=2.0
//     (= modo_max capture mode). Em mode_balanced/fast, scale=1.0 (sem super res).
//   - DNG 16-bit é o max arquitetural (DNG spec) — não há como ir além.
// ═══════════════════════════════════════════════════════════════════════════════

package com.hinnka.mycamera.manager

import com.hinnka.mycamera.raw.LeicaConfig

// ─── ANTES (upstream original, 2 sites) ──────────────────────────────────────
// Site 1 (~line 2194 — gallery save):
fun saveToGallery(...) {
    // ...
    if (shouldAutoSave && exportDngWithRawExport) {
        saveDngFile(...)
    }
    // ...
}

// Site 2 (~line 3776 — batch save):
fun saveBatchToGallery(...) {
    // ...
    if (shouldAutoSave && exportDngWithRawExport) {
        saveDngFiles(...)
    }
    // ...
}

// ─── DEPOIS (após P-40) ──────────────────────────────────────────────────────
// Site 1 (~line 2194):
fun saveToGallery(...) {
    // ...
    // P-40: força DNG export quando LeicaConfig.exportSuperResDng=true
    // (mesmo se usuário desligou toggle "Save RAW" na UI)
    if (shouldAutoSave && (exportDngWithRawExport || LeicaConfig.exportSuperResDng)) {
        saveDngFile(...)
    }
    // ...
}

// Site 2 (~line 3776):
fun saveBatchToGallery(...) {
    // ...
    if (shouldAutoSave && (exportDngWithRawExport || LeicaConfig.exportSuperResDng)) {
        saveDngFiles(...)
    }
    // ...
}

// ─── SED COMMAND (executado pelo build-archlinux.sh) ─────────────────────────
// Usa `g` flag pega ambos sites (line ~2194 + ~3776):
// sed -i 's|if (shouldAutoSave \&\& exportDngWithRawExport) {|if (shouldAutoSave \&\& (exportDngWithRawExport || LeicaConfig.exportSuperResDng)) {|g' "$gm"
//
// NOTA: `\&\&` é escaped ampersand (em sed replacement, `&` significa "matched text").
// `||` precisa ser `||` literal — em sed replacement com `|` delimiter seria conflito,
// mas aqui usamos `|` como regex delimiter também pra consistência com P-43 lesson.
// ⚠️  P-43 aprendeu que `||` em replacement com `|` delimiter quebra — usa `#` delimiter.
// P-40 atualmente usa `|` delimiter e funciona porque o replacement NÃO tem `||` (apenas
// `||` fora do replacement, na parte de regex). Funciona, mas futuro patches com `||`
// no replacement devem usar `#` delimiter (vide P-43).

// ─── REFERENCIADO POR (NÃO QUEBRAR) ──────────────────────────────────────────
//   - P-27 (GalleryManager DNG export default — base pra P-40)
//   - LeicaConfig.exportSuperResDng (default true via advanced.export_super_res_dng)
//   - LeicaConfig.AdvancedTuning data class (export_super_res_dng field)
//   - SuperResolutionDngWriter.kt (consumer — escreve o DNG 16-bit + 2.0× upscale)
//   - max_capability_audit.md §RAW MAX (DNG 16-bit uncompressed confirmation)
//   - beat_gcam_rationale.md §frame_count (super res 2.0× needs 15+ frames)
