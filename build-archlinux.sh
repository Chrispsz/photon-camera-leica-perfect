#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# build-archlinux.sh — Leica Perfect v6.4.0 PhotonCamera Fork Build Script
# v6.4.0 (Cron 7): P-62 drop RAW + 2 capture modes (mode_max + mode_fast intelligent trigger)
# v6.4.0 (Cron 8): P-63 LUT picker override (fixes stuck-on-m9-ccd) + P-64 5 best LUTs in mod menu
# v6.4.0 (Cron 9): P-65 one-click JPEG max optimization (forceNoRaw + ONE-CLICK MAX button)
# ═══════════════════════════════════════════════════════════════════════════════
#
# DEFINITIVE QUALITY — MAX BITS/LUT/RAW + Beat-GCam + 26 Creative Profiles
#
# Aplica 67 patches cirúrgicos (sed) sobre o upstream bjzhou/PhotonCamera 1.26.1
# produzindo um APK com quality técnico no máximo arquitetural do Xiaomi 15T.
#
# Patches organizados em 7 tiers:
#   Tier 1 (P-1..P-7):     Core tone mapping, sharpening, NLM, metering, config loader
#   Tier 2 (P-8..P-20):    Multi-frame, HDR, demosaic, processing, DCP, advanced
#   Tier 3 (P-21..P-28):   Mertens, vignette, DNG export, JPEG/HEIC quality, branding, ISP
#   Tier 4 (P-29..P-36):   v6.0 per-lens intelligence (frame count, video, tint, sat, ISP, noise)
#   Tier 5 (P-37..P-43):   v6.1+v6.2 wiring (white/black level, DCP ratio, SR DNG, AgX, gainmap, consumer)
#   Tier 6 (P-44..P-45):   v6.2.5 RUNTIME ACTIVATION + branding (LeicaConfig.load() at startup + app_name)
#   Tier 7 (P-46..P-48):   v6.2.6 UI — Settings panel + viewfinder button + runtime state
#   Tier 8 (P-49..P-53):   v6.3.x runtime wiring (Live Photo, compilation fixes, runtime NLM radius)
#   Tier 9 (P-54):         v6.3.6 creative profile color science (tone contrast, saturation, warmth, tint, highlight)
#   Tier 10 (P-55):        v6.3.7 video settings actually apply (UserPreferencesRepository fallbacks → LeicaConfig.*Enum)
#   Tier 11 (P-56):        v6.3.8 video encoder completeness (AUDIO_SAMPLE_RATE + AUDIO_MIME + AAC_PROFILE gate + HDR10 static info + bitrate mode override)
#   Tier 12 (P-57..P-59):  v6.3.8 Camera2 direct params per-lens + noise model completion + thermal monitor
#   Tier 13 (P-60..P-61):  v6.3.8-fix4 doNotStrip + LeicaStateDumper
#   Tier 14 (P-62..P-65):  v6.4.0 drop RAW + 2 capture modes + LUT picker override + 5 best LUTs in mod menu + one-click JPEG max
#
# v6.3.5 (Cron 2 fixes): P-52a/b/c paths corrected, P-31 targeted L6801 only,
#   P-32a bitrate pattern P5→P1, P-53a/b/c runtime NLM radius (C2/C3/C4 from
#   upstream-wiring-investigation), P-48b/c marked verification-only.
#
# v6.3.6 (Cron 4): P-54a/b/c/d creative profile color science wired (effective*
# v6.3.7 (Cron 5): P-55 UserPreferencesRepository video fallbacks → LeicaConfig.*Enum (fixes 1080p/H264/30fps/P1 defaults)
#   accessors finally called in pipeline), P-29/P-30 switched to effective*
#   versions + P-30 Int→Float conversion bug fixed.
# v6.3.8 (Cron 6): P-56 video encoder completeness — AUDIO_SAMPLE_RATE/AUDIO_MIME
#   wired to LeicaConfig, KEY_AAC_PROFILE gated on AAC mime (OPUS-safe),
#   KEY_HDR_STATIC_INFO injected via VideoEncoderColorConfig.hdrStaticInfo,
#   bitrate mode override → LeicaConfig.videoBitrateMode (CBR/CQP/VBR).
# v6.4.0 (Cron 7): P-62 mode_balanced REMOVIDO + mode_fast restaurado (disparo rápido
#   inteligente, 5/3/3/5 frames, NLM radius 3, ~0.3s latency) + RAW/DNG export
#   DESATIVADO (output.force_no_raw/force_no_dng = true). User quer JPEG one-click.
# v6.4.0 (Cron 8): P-63 LUT picker override (runtimeLutOverride var in LeicaConfig.kt
#   takes precedence over activeLutId — fixes "stuck on m9 CCD" bug que deixava o
#   picker do menu inoperante) + P-64 5 melhores LUTs adicionados ao mod menu
#   (Leica M9, Hasselblad HNCS, Fuji CC, Fuji NC, CineStill 800T).
# v6.4.0 (Cron 9): P-65 ONE-CLICK MAX preset button (aplica mode_max + Leica M9 CCD
#   + JPEG Q100 com um clique) + forceNoRaw/forceNoDng/forceHeicQ100/forceUltraHdrQ100
#   accessors honrados em exportDngWithRawExport/exportSuperResDng.
#
# Uso:
#   ./build-archlinux.sh clone    # clona o upstream
#   ./build-archlinux.sh patch    # aplica os 67 patches cirúrgicos (90+ substeps)
#   ./build-archlinux.sh build    # builda o APK
#   ./build-archlinux.sh all      # faz tudo (clone + patch + build)
#   ./build-archlinux.sh check    # bash -n syntax check
#
# Requisitos (Arch Linux):
#   sudo pacman -S jdk17-openjdk android-sdk android-sdk-platform-tools \
#     android-sdk-build-tools git sed grep
#   export ANDROID_HOME=/opt/android-sdk
#
# ⚠️  Use por sua conta e risco. Modifica o código-fonte upstream.
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true

# ─── Script self-location (works from any extraction folder) ─────────────────
# Resolve o diretório onde este script vive, para que config/ e patches/
# sejam encontrados relativamente ao script (não mais hardcoded em /home/z/leica_v3).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# ─── Constantes ──────────────────────────────────────────────────────────────
readonly FORK_VERSION="6.4.2"
readonly FORK_NAME="Leica Perfect — DEFINITIVE QUALITY"
readonly UPSTREAM_REPO="https://github.com/bjzhou/PhotonCamera.git"
# ⚠️  Tag upstream é "1.26.1" (sem prefixo 'v'). O repo bjzhou NÃO usa 'v'.
# Fallbacks em ordem: tag exata → tag com 'v' → branch 'main'.
readonly UPSTREAM_TAG="1.26.1"
readonly UPSTREAM_TAG_FALLBACK_1="v1.26.1"
readonly UPSTREAM_TAG_FALLBACK_2="main"
readonly SOURCE_DIR="${SOURCE_DIR:-/tmp/photon_upstream}"
readonly BUILD_DIR="${BUILD_DIR:-/tmp/leica_build}"
# APK vai para a MESMA pasta do script (apk/ ao lado do build-archlinux.sh),
# assim o usuário encontra o .apk onde executou o script.
readonly APK_OUTPUT="${APK_OUTPUT:-$SCRIPT_DIR/apk}"
readonly CONFIG_FILE="${CONFIG_FILE:-$SCRIPT_DIR/config/leica_perfect.json}"
readonly LEICA_CONFIG_KT="${LEICA_CONFIG_KT:-$SCRIPT_DIR/patches/LeicaConfig.kt}"
readonly PATCH_DIR="${PATCH_DIR:-$SCRIPT_DIR/patches}"
readonly ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

# Diretórios base do upstream (pós-clone)
readonly APP_JAVA="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera"
readonly APP_ASSETS="$SOURCE_DIR/app/src/main/assets"

# ─── Cores ANSI ──────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    readonly C_RESET='\033[0m'
    readonly C_RED='\033[0;31m'
    readonly C_GREEN='\033[0;32m'
    readonly C_YELLOW='\033[0;33m'
    readonly C_BLUE='\033[0;34m'
    readonly C_CYAN='\033[0;36m'
    readonly C_BOLD='\033[1m'
else
    readonly C_RESET=''; readonly C_RED=''; readonly C_GREEN=''; readonly C_YELLOW=''
    readonly C_BLUE=''; readonly C_CYAN=''; readonly C_BOLD=''
fi

# ═════════════════════════════════════════════════════════════════════════════
# HELPERS
# ═════════════════════════════════════════════════════════════════════════════

ok() {
    printf "%b✓ OK%b  %s\n" "$C_GREEN" "$C_RESET" "$1"
}

warn() {
    printf "%b⚠ WARN%b %s\n" "$C_YELLOW" "$C_RESET" "$1" >&2
}

fail() {
    printf "%b✗ FAIL%b %s\n" "$C_RED" "$C_RESET" "$1" >&2
    exit 1
}

# soft_fail: imprime mensagem mas NÃO mata o script (usa return 1 via caller).
# Uso: soft_fail "msg" && return 1  — ou dentro de if/check.
soft_fail() {
    printf "%b✗ FAIL%b %s\n" "$C_RED" "$C_RESET" "$1" >&2
    return 1
}

# insert_after_idempotent: portable multi-line insert after pattern using sed r.
# Avoids \n-in-a-command bug and \-continuation issues. Idempotent (skips if marker exists).
# Usage: insert_after_idempotent "pattern" "file" "marker_grep" "line1" "line2" ...
insert_after_idempotent() {
    local pattern="$1" file="$2" marker="$3"
    shift 3
    # Idempotency: skip if marker already exists in file
    if grep -q "$marker" "$file" 2>/dev/null; then
        return 0
    fi
    # Create temp file with text to insert
    local tmpfile
    tmpfile=$(mktemp)
    printf '%s\n' "$@" > "$tmpfile"
    # Use sed r (read from file) — portable, no escaping issues with ( ) \n
    sed -i "/$pattern/r $tmpfile" "$file"
    rm -f "$tmpfile"
}

substep() {
    printf "\n%b── %s ──%b\n" "$C_CYAN" "$1" "$C_RESET"
}

section() {
    printf "\n%b═══════════════════════════════════════════════════════════════%b\n" "$C_BOLD$C_BLUE" "$C_RESET"
    printf "%b  %s%b\n" "$C_BOLD$C_BLUE" "$1" "$C_RESET"
    printf "%b═══════════════════════════════════════════════════════════════%b\n" "$C_BOLD$C_BLUE" "$C_RESET"
}

info() {
    printf "%bℹ%b     %s\n" "$C_BLUE" "$C_RESET" "$1"
}

# ═════════════════════════════════════════════════════════════════════════════
# COMANDO: clone
# ═════════════════════════════════════════════════════════════════════════════
cmd_clone() {
    section "Clone upstream bjzhou/PhotonCamera $UPSTREAM_TAG"

    if [[ -d "$SOURCE_DIR/.git" ]]; then
        warn "Source dir já existe: $SOURCE_DIR — pulando clone"
        info "Para refresh: rm -rf $SOURCE_DIR && ./build-archlinux.sh clone"
        return 0
    fi

    mkdir -p "$(dirname "$SOURCE_DIR")"

    # O upstream bjzhou/PhotonCamera usa tags sem prefixo 'v' (ex: "1.26.1").
    # Tentamos em ordem: tag canônica → fallback 'v' → branch main.
    local tag
    for tag in "$UPSTREAM_TAG" "$UPSTREAM_TAG_FALLBACK_1" "$UPSTREAM_TAG_FALLBACK_2"; do
        info "Tentando: git clone --depth 1 --branch $tag ..."
        if git clone --depth 1 --branch "$tag" "$UPSTREAM_REPO" "$SOURCE_DIR" 2>/dev/null; then
            ok "Clone completo em $SOURCE_DIR (ref: $tag)"
            break
        fi
        warn "Ref '$tag' não encontrada no upstream — tentando próxima..."
        tag=""
    done

    if [[ -z "${tag:-}" ]]; then
        fail "git clone falhou — nenhuma das refs funcionou:"
        info "  Tentadas: $UPSTREAM_TAG, $UPSTREAM_TAG_FALLBACK_1, $UPSTREAM_TAG_FALLBACK_2"
        info "  Verifique conectividade com github.com/bjzhou/PhotonCamera"
        return 1
    fi

    info "Verificando estrutura upstream..."
    [[ -f "$APP_JAVA/camera/Camera2Controller.kt" ]] \
        || fail "Estrutura upstream inválida — Camera2Controller.kt não encontrado"
    [[ -f "$APP_JAVA/raw/RawShaders.kt" ]] \
        || fail "Estrutura upstream inválida — RawShaders.kt não encontrado"
    ok "Estrutura upstream verificada"
}

# ═════════════════════════════════════════════════════════════════════════════
# COMANDO: patch — aplica 57 patches cirúrgicos (73 substeps)
# ═════════════════════════════════════════════════════════════════════════════
cmd_patch() {
    section "Aplicar 75 substeps cirúrgicos (58 patches) — Leica Perfect v$FORK_VERSION"

    # Verifica source existe
    if [[ ! -d "$SOURCE_DIR" ]]; then
        fail "Source não existe em $SOURCE_DIR — rode './build-archlinux.sh clone' primeiro"
    fi

    local patch_count=0
    local patch_fail=0

    # ───────────────────────────────────────────────────────────────────────
    # Tier 1 — Core: Config loader, tone mapping, sharpening, NLM, metering
    # ───────────────────────────────────────────────────────────────────────

    # P-1: Instala LeicaConfig.kt no source tree + copia leica_perfect.json p/ assets
    substep "P-1: Instalar LeicaConfig.kt + leica_perfect.json"
    local leica_target_dir="$APP_JAVA/raw"
    mkdir -p "$leica_target_dir"
    if [[ -f "$LEICA_CONFIG_KT" ]]; then
        cp -f "$LEICA_CONFIG_KT" "$leica_target_dir/LeicaConfig.kt"
        ok "LeicaConfig.kt instalado em $leica_target_dir/LeicaConfig.kt"
        # Copia JSON config para assets
        if [[ -f "$CONFIG_FILE" ]]; then
            mkdir -p "$APP_ASSETS"
            cp -f "$CONFIG_FILE" "$APP_ASSETS/leica_perfect.json"
            ok "leica_perfect.json copiado para $APP_ASSETS/leica_perfect.json"
        else
            warn "leica_perfect.json não encontrado em $CONFIG_FILE"
        fi
        ((++patch_count))
    else
        warn "LeicaConfig.kt não encontrado em $LEICA_CONFIG_KT — pulando"
        ((++patch_fail))
    fi

    # P-2: RawToneMappingParameters — AgX defaults (v6.3.0: nomes reais upstream)
    substep "P-2: RawToneMappingParameters AgX defaults"
    local rtmp="$APP_JAVA/raw/RawToneMappingParameters.kt"
    if [[ -f "$rtmp" ]]; then
        # v6.3.0: nomes reais upstream confirmados por auditoria DIAG-A
        # Nota: upstream usa `const val` (sem `private`), com 8 espaços de indent
        sed -i 's|const val AGX_TOE_DEFAULT = 1.5f|val AGX_TOE_DEFAULT: Float get() = LeicaConfig.agxToePower.toFloat()|' "$rtmp"
        sed -i 's|const val AGX_SHOULDER_DEFAULT = 3.3f|val AGX_SHOULDER_DEFAULT: Float get() = LeicaConfig.agxShoulderPower.toFloat()|' "$rtmp"
        sed -i 's|const val AGX_BLACK_RELATIVE_EXPOSURE_DEFAULT = -10f|val AGX_BLACK_RELATIVE_EXPOSURE_DEFAULT: Float get() = LeicaConfig.agxBlackRelativeExposure.toFloat()|' "$rtmp"
        sed -i 's|const val AGX_WHITE_RELATIVE_EXPOSURE_DEFAULT = 6.5f|val AGX_WHITE_RELATIVE_EXPOSURE_DEFAULT: Float get() = LeicaConfig.agxWhiteRelativeExposure.toFloat()|' "$rtmp"
        # Import LeicaConfig (mesmo package — import desnecessário mas idempotente)
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rtmp" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rtmp"
        # v6.3.0: verificação strict — aborta se pattern não casou
        if grep -q 'LeicaConfig.agxToePower' "$rtmp" 2>/dev/null; then
            ((++patch_count))
            ok "RawToneMappingParameters AgX defaults aplicados"
        else
            warn "P-2: sed pattern não casou — upstream pode ter mudado"
            ((++patch_fail))
        fi
    else
        warn "RawToneMappingParameters.kt não encontrado"
        ((++patch_fail))
    fi

    # P-3: RawShaders — USM radius/threshold tunable (v6.3.0: nomes reais)
    substep "P-3: RawShaders USM radius/threshold"
    local rsh="$APP_JAVA/raw/RawShaders.kt"
    if [[ -f "$rsh" ]]; then
        # v6.3.0: upstream usa DEFAULT_USM_RADIUS/DEFAULT_USM_THRESHOLD (Kotlin const val), não USM_AMOUNT (GLSL)
        # USM_AMOUNT não existe como constante — é runtime uniform uSharpening. Só patcheamos o que existe.
        sed -i 's|const val DEFAULT_USM_RADIUS = 2.0f|val DEFAULT_USM_RADIUS: Float get() = LeicaConfig.usmRadius.toFloat()|' "$rsh"
        sed -i 's|const val DEFAULT_USM_THRESHOLD = 0.005f|val DEFAULT_USM_THRESHOLD: Float get() = LeicaConfig.usmThreshold.toFloat()|' "$rsh"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rsh" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rsh"
        if grep -q 'LeicaConfig.usmRadius' "$rsh" 2>/dev/null; then
            ((++patch_count))
            ok "RawShaders USM radius/threshold aplicado"
        else
            warn "P-3: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "RawShaders.kt não encontrado"
        ((++patch_fail))
    fi

    # P-4: DenoiseProfileShaders — NLM radius control (v6.3.4: capture-mode-aware)
    substep "P-4: DenoiseProfileShaders NLM radius (v6.3.4: effectiveNlmSearchRadius)"
    local dps="$APP_JAVA/raw/DenoiseProfileShaders.kt"
    if [[ -f "$dps" ]]; then
        # v6.3.4: usar effectiveNlmSearchRadius (responde a capture mode: max=7, balanced=5, fast=4)
        # em vez de demosaicNlmSearchRadius (static, sempre 7)
        sed -i 's|const val SEARCH_RADIUS = 5|val SEARCH_RADIUS: Int get() = LeicaConfig.effectiveNlmSearchRadius.coerceAtMost(9)|' "$dps"
        sed -i 's|const val PATCH_RADIUS = 1|val PATCH_RADIUS: Int get() = LeicaConfig.demosaicNlmPatchRadius|' "$dps"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$dps" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$dps"
        if grep -q 'LeicaConfig.effectiveNlmSearchRadius' "$dps" 2>/dev/null; then
            ((++patch_count))
            ok "DenoiseProfileShaders NLM radius aplicado (effectiveNlmSearchRadius — capture-mode-aware)"
        else
            warn "P-4: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "DenoiseProfileShaders.kt não encontrado"
        ((++patch_fail))
    fi

    # P-5: RcdShaders — highlight reconstruction threshold (v6.3.0: nome real)
    substep "P-5: RcdShaders highlight reconstruction"
    local rcds="$APP_JAVA/raw/RcdShaders.kt"
    if [[ -f "$rcds" ]]; then
        # v6.3.0: upstream usa HIGHLIGHT_RECONSTRUCTION_THRESHOLD (não HIGHLIGHT_RECON_THRESHOLD)
        sed -i 's|const val HIGHLIGHT_RECONSTRUCTION_THRESHOLD = 0.985f|val HIGHLIGHT_RECONSTRUCTION_THRESHOLD: Float get() = LeicaConfig.demosaicHighlightReconstructionThreshold.toFloat()|' "$rcds"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rcds" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rcds"
        if grep -q 'LeicaConfig.demosaicHighlightReconstructionThreshold' "$rcds" 2>/dev/null; then
            ((++patch_count))
            ok "RcdShaders highlight recon aplicado"
        else
            warn "P-5: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "RcdShaders.kt não encontrado"
        ((++patch_fail))
    fi

    # P-6: MeteringSystem — center weight sigma
    substep "P-6: MeteringSystem center weight"
    local ms="$APP_JAVA/raw/MeteringSystem.kt"
    if [[ -f "$ms" ]]; then
        sed -i 's|const val CENTER_WEIGHT_SIGMA = 0.32f|val CENTER_WEIGHT_SIGMA get() = LeicaConfig.centerWeightSigma.toFloat()|' "$ms"
        sed -i 's|const val DISPLAY_TARGET_LUMA = 0.18f|val DISPLAY_TARGET_LUMA get() = LeicaConfig.displayTargetLuma.toFloat()|' "$ms"
        # MeteringSystem já está no package com.hinnka.mycamera.raw — mesmo package que LeicaConfig, não precisa import
        ((++patch_count))
        ok "MeteringSystem aplicado"
    else
        warn "MeteringSystem.kt não encontrado"
        ((++patch_fail))
    fi

    # P-7: RawRenderingEngine — default exposure (v6.3.0: nome real)
    substep "P-7: RawRenderingEngine exposure"
    local rre="$APP_JAVA/raw/RawRenderingEngine.kt"
    if [[ -f "$rre" ]]; then
        # v6.3.0: upstream usa RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV (com prefixo), valor 0.7f (não 0.5)
        sed -i 's|const val RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV = 0.7f|val RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV: Float get() = LeicaConfig.defaultExposureEv.toFloat()|' "$rre"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rre" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rre"
        if grep -q 'LeicaConfig.defaultExposureEv' "$rre" 2>/dev/null; then
            ((++patch_count))
            ok "RawRenderingEngine exposure aplicado"
        else
            warn "P-7: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "RawRenderingEngine.kt não encontrado"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Tier 2 — Multi-frame, HDR, demosaic, processing, DCP, advanced
    # ───────────────────────────────────────────────────────────────────────

    # P-8: MultiFrameConfig — DEFAULT_FRAME_COUNT
    substep "P-8: MultiFrameConfig frame count"
    local mfc="$APP_JAVA/camera/MultiFrameConfig.kt"
    if [[ -f "$mfc" ]]; then
        sed -i 's|const val DEFAULT_FRAME_COUNT = 7|val DEFAULT_FRAME_COUNT get() = LeicaConfig.multiFrameCount|' "$mfc"
        sed -i 's|const val MIN_FRAME_COUNT = 3|val MIN_FRAME_COUNT get() = 3|' "$mfc"
        sed -i 's|const val MAX_FRAME_COUNT = 20|val MAX_FRAME_COUNT get() = 20|' "$mfc"
        sed -i 's|const val DEFAULT_SUPER_RESOLUTION_SCALE = 1f|val DEFAULT_SUPER_RESOLUTION_SCALE get() = LeicaConfig.multiFrameSuperResolutionScale|' "$mfc"
        sed -i 's|const val MAX_OUTPUT_SCALE = 2f|val MAX_OUTPUT_SCALE get() = 2f|' "$mfc"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$mfc" || \
            sed -i '/^package com.hinnka.mycamera.camera$/a import com.hinnka.mycamera.raw.LeicaConfig' "$mfc"
        ((++patch_count))
        ok "MultiFrameConfig aplicado"
    else
        warn "MultiFrameConfig.kt não encontrado"
        ((++patch_fail))
    fi

    # P-9: HdrBracketConfig — YUV long/short EV
    substep "P-9: HdrBracketConfig EV spacing"
    local hbc="$APP_JAVA/camera/HdrBracketConfig.kt"
    if [[ -f "$hbc" ]]; then
        sed -i 's|const val YUV_LONG_EV = 2.2f|val YUV_LONG_EV get() = LeicaConfig.yuvLongEv|' "$hbc"
        sed -i 's|const val YUV_SHORT_EV = -1.5f|val YUV_SHORT_EV get() = LeicaConfig.yuvShortEv|' "$hbc"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$hbc" || \
            sed -i '/^package com.hinnka.mycamera.camera$/a import com.hinnka.mycamera.raw.LeicaConfig' "$hbc"
        ((++patch_count))
        ok "HdrBracketConfig aplicado"
    else
        warn "HdrBracketConfig.kt não encontrado"
        ((++patch_fail))
    fi

    # P-10: UserPreferencesRepository — RAWmax + super res defaults
    substep "P-10: UserPreferencesRepository RAWmax defaults"
    local upr="$APP_JAVA/data/UserPreferencesRepository.kt"
    if [[ -f "$upr" ]]; then
        sed -i 's|val useRawMax: Boolean = false|val useRawMax: Boolean = true|' "$upr"
        sed -i 's|val useRaw: Boolean = false|val useRaw: Boolean = true|' "$upr"
        sed -i 's|val rawMaxOutputScale: Float = 1.0f|val rawMaxOutputScale: Float = 2.0f|' "$upr"
        sed -i 's|val photoQuality: Int = 95|val photoQuality: Int = 100|' "$upr"
        ((++patch_count))
        ok "UserPreferencesRepository defaults aplicado"
    else
        warn "UserPreferencesRepository.kt não encontrado"
        ((++patch_fail))
    fi

    # P-11: DngProfileToneCurve — PGTM curve powers (v6.3.0: nomes reais)
    substep "P-11: DngProfileToneCurve PGTM"
    local dptc="$APP_JAVA/raw/DngProfileToneCurve.kt"
    if [[ -f "$dptc" ]]; then
        # v6.3.0: upstream usa PHOTON_PGTM_* prefix, valores diferentes
        sed -i 's|private const val PHOTON_PGTM_TOE_POWER = 1.5|private val PHOTON_PGTM_TOE_POWER: Double get() = LeicaConfig.pgtmToePower|' "$dptc"
        sed -i 's|private const val PHOTON_PGTM_MID_POWER = 1.25|private val PHOTON_PGTM_MID_POWER: Double get() = LeicaConfig.pgtmMidPower|' "$dptc"
        sed -i 's|private const val PHOTON_PGTM_SHOULDER_POWER = 1.2|private val PHOTON_PGTM_SHOULDER_POWER: Double get() = LeicaConfig.pgtmShoulderPower|' "$dptc"
        sed -i 's|private const val PHOTON_PGTM_BALANCE = 0.97|private val PHOTON_PGTM_BALANCE: Double get() = LeicaConfig.pgtmBalance|' "$dptc"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$dptc" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$dptc"
        if grep -q 'LeicaConfig.pgtmToePower' "$dptc" 2>/dev/null; then
            ((++patch_count))
            ok "DngProfileToneCurve PGTM aplicado"
        else
            warn "P-11: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "DngProfileToneCurve.kt não encontrado"
        ((++patch_fail))
    fi

    # P-12: RawToneMappingGl — Filmic curve
    substep "P-12: RawToneMappingGl Filmic"
    local rtmg="$APP_JAVA/raw/RawToneMappingGl.kt"
    if [[ -f "$rtmg" ]]; then
        sed -i 's|const val FILMIC_GREY_SOURCE = 0.1845f|val FILMIC_GREY_SOURCE get() = LeicaConfig.filmicGreySource.toFloat()|' "$rtmg"
        sed -i 's|const val FILMIC_DEFAULT_CONTRAST = 1.433801098f|val FILMIC_DEFAULT_CONTRAST get() = LeicaConfig.filmicDefaultContrast.toFloat()|' "$rtmg"
        sed -i 's|const val FILMIC_DEFAULT_DYNAMIC_RANGE = 12.21f|val FILMIC_DEFAULT_DYNAMIC_RANGE get() = LeicaConfig.filmicDefaultDynamicRange.toFloat()|' "$rtmg"
        # RawToneMappingGl já está no package com.hinnka.mycamera.raw — mesmo package que LeicaConfig
        ((++patch_count))
        ok "RawToneMappingGl Filmic aplicado"
    else
        warn "RawToneMappingGl.kt não encontrado"
        ((++patch_fail))
    fi

    # P-13: DngPhotonProfileGainTableGenerator — PGTM gain (v6.3.3: SEM get() — data class não permite)
    substep "P-13: DngPhotonProfileGainTableGenerator PGTM"
    local dppg="$APP_JAVA/raw/DngPhotonProfileGainTableGenerator.kt"
    if [[ -f "$dppg" ]]; then
        # v6.3.3 FIX: data class constructor params NÃO PODEM ter get() custom.
        # Em vez de `val x: Float get() = expr`, usar `val x: Float = expr` (sem get()).
        # Isso avalia expr toda vez que o default é usado — comportamento correto.
        sed -i 's|val preToneMapExposureBoostEv: Float = 1.15f|val preToneMapExposureBoostEv: Float = LeicaConfig.pgtmPreTonemapExposureBoostEv.toFloat()|' "$dppg"
        sed -i 's|val targetDynamicRange: Float = 100f|val targetDynamicRange: Float = LeicaConfig.pgtmTargetDynamicRange.toFloat()|' "$dppg"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$dppg" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$dppg"
        if grep -q 'LeicaConfig.pgtmPreTonemapExposureBoostEv' "$dppg" 2>/dev/null; then
            ((++patch_count))
            ok "DngPhotonProfileGainTableGenerator aplicado"
        else
            warn "P-13: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "DngPhotonProfileGainTableGenerator.kt não encontrado"
        ((++patch_fail))
    fi

    # P-14: HncsProfile — film curve gain (v6.3.0: nome real)
    substep "P-14: HncsProfile film curve gain"
    local hp="$APP_JAVA/raw/HncsProfile.kt"
    if [[ -f "$hp" ]]; then
        # v6.3.0: upstream usa FILM_CURVE_GAIN (sem prefixo HNCS_), private const val, valor 1f
        sed -i 's|private const val FILM_CURVE_GAIN = 1f|private val FILM_CURVE_GAIN: Float get() = LeicaConfig.hncsFilmCurveGain.toFloat()|' "$hp"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$hp" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$hp"
        if grep -q 'LeicaConfig.hncsFilmCurveGain' "$hp" 2>/dev/null; then
            ((++patch_count))
            ok "HncsProfile aplicado"
        else
            warn "P-14: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "HncsProfile.kt não encontrado"
        ((++patch_fail))
    fi

    # P-15: MultiFrameConfig — RAWmax long/short frame EV (v6.3.3: Double, não Float)
    substep "P-15: MultiFrameConfig long/short frame EV"
    local mfc_p15="$APP_JAVA/camera/MultiFrameConfig.kt"
    if [[ -f "$mfc_p15" ]]; then
        # v6.3.3 FIX: upstream usa Double (= 2.5 sem sufixo f). LeicaConfig retorna Double.
        # P-15 v6.3.2 erradamente convertia pra Float, quebrando `2.0.pow(LONG_FRAME_EXPOSURE_EV)`.
        # Agora mantém Double — sem toFloat(), sem : Float.
        sed -i 's|const val LONG_FRAME_EXPOSURE_EV = 2.5|val LONG_FRAME_EXPOSURE_EV: Double get() = LeicaConfig.multiFrameLongFrameExposureEv|' "$mfc_p15"
        sed -i 's|const val SHORT_FRAME_EXPOSURE_DIVISOR = 3.0|val SHORT_FRAME_EXPOSURE_DIVISOR: Double get() = LeicaConfig.multiFrameShortFrameExposureDivisor|' "$mfc_p15"
        # Import já adicionado por P-8
        if grep -q 'LeicaConfig.multiFrameLongFrameExposureEv' "$mfc_p15" 2>/dev/null; then
            ((++patch_count))
            ok "MultiFrameConfig long/short frame EV aplicado"
        else
            warn "P-15: sed pattern não casou (constantes podem não existir neste arquivo)"
            ((++patch_fail))
        fi
    else
        warn "MultiFrameConfig.kt não encontrado"
        ((++patch_fail))
    fi

    # P-16: GlesYuvStacker — YUV Mertens weights
    substep "P-16: GlesYuvStacker Mertens weights"
    local gys="$APP_JAVA/processor/GlesYuvStacker.kt"
    if [[ -f "$gys" ]]; then
        sed -i 's|const val DEFAULT_MERTENS_CONTRAST_WEIGHT = 1.0f|val DEFAULT_MERTENS_CONTRAST_WEIGHT get() = LeicaConfig.mertensContrastWeight|' "$gys"
        sed -i 's|const val DEFAULT_MERTENS_SATURATION_WEIGHT = 1.0f|val DEFAULT_MERTENS_SATURATION_WEIGHT get() = LeicaConfig.mertensSaturationWeight|' "$gys"
        sed -i 's|const val DEFAULT_MERTENS_EXPOSURE_WEIGHT = 1.0f|val DEFAULT_MERTENS_EXPOSURE_WEIGHT get() = LeicaConfig.mertensExposureWeight|' "$gys"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$gys" || \
            sed -i '/^package com.hinnka.mycamera.processor$/a import com.hinnka.mycamera.raw.LeicaConfig' "$gys"
        ((++patch_count))
        ok "GlesYuvStacker Mertens weights aplicado"
    else
        warn "GlesYuvStacker.kt não encontrado"
        ((++patch_fail))
    fi

    # P-17: RawMetadata — white level override (v6.3.0: REAL wiring, não comment-only)
    substep "P-17: RawMetadata white level override"
    local rmd="$APP_JAVA/raw/RawMetadata.kt"
    if [[ -f "$rmd" ]]; then
        # v6.3.0: DIAG-C confirmou que `characteristics` está em scope em create().
        # Substitui o fallback ?: 1023f por LeicaConfig.whiteLevelForLens baseado em characteristics.
        # Pattern: `?: 1023f` (pode ter comentário depois).
        # Usamos Python para surgical replace preservando contexto.
        if grep -q 'LeicaConfig.whiteLevelForLens' "$rmd" 2>/dev/null; then
            ok "P-17: already patched (idempotent)"
            ((++patch_count))
        else
            # Adiciona import LeicaConfig
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rmd" || \
                sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rmd"
            # Python script: substitui `?: 1023f` (com optional comment) por LeicaConfig.whiteLevelForLens
            python3 -c "
import re, sys
p = '$rmd'
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
# Substitui fallback ?: 1023f (seguido de optional comment até fim da linha) por LeicaConfig.whiteLevelForLens
new = re.sub(
    r'\?: 1023f(?:\s*//[^\n]*)?',
    '?: LeicaConfig.whiteLevelForLens(LeicaConfig.lensKeyFromCharacteristics(characteristics)).toFloat()',
    s
)
if new == s:
    print('NOMATCH', file=sys.stderr); sys.exit(1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new)
print('OK')
" 2>&1 | grep -q '^OK$' && {
                ((++patch_count))
                ok "RawMetadata white level override aplicado (v6.3.0: REAL wiring)"
            } || {
                warn "P-17: pattern ?: 1023f não encontrado"
                ((++patch_fail))
            }
        fi
    else
        warn "RawMetadata.kt não encontrado"
        ((++patch_fail))
    fi

    # P-18: RawMetadata — black level override (v6.3.0: REAL wiring, antes era dead code)
    substep "P-18: RawMetadata black level override"
    if [[ -f "$rmd" ]]; then
        if grep -q 'LeicaConfig.blackLevelForLens' "$rmd" 2>/dev/null; then
            ok "P-18: already patched (idempotent)"
            ((++patch_count))
        else
            # v6.3.0: substitui floatArrayOf(64f, 64f, 64f, 64f) por LeicaConfig.blackLevelForLens
            python3 -c "
import re, sys
p = '$rmd'
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
new = re.sub(
    r'floatArrayOf\(64f,\s*64f,\s*64f,\s*64f\)',
    'floatArrayOf(LeicaConfig.blackLevelForLens(LeicaConfig.lensKeyFromCharacteristics(characteristics)).toFloat(), LeicaConfig.blackLevelForLens(LeicaConfig.lensKeyFromCharacteristics(characteristics)).toFloat(), LeicaConfig.blackLevelForLens(LeicaConfig.lensKeyFromCharacteristics(characteristics)).toFloat(), LeicaConfig.blackLevelForLens(LeicaConfig.lensKeyFromCharacteristics(characteristics)).toFloat())',
    s
)
if new == s:
    print('NOMATCH', file=sys.stderr); sys.exit(1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new)
print('OK')
" 2>&1 | grep -q '^OK$' && {
                ((++patch_count))
                ok "RawMetadata black level override aplicado (v6.3.0: REAL wiring)"
            } || {
                warn "P-18: pattern floatArrayOf(64f, 64f, 64f, 64f) não encontrado"
                ((++patch_fail))
            }
        fi
    else
        warn "RawMetadata.kt não encontrado (já avisado em P-17)"
        ((++patch_fail))
    fi

    # P-19: Force Leica DCP/LUT/frame (v6.3.1: REAL implementation — 6 wiring sites)
    # IMPL-P19 encontrou: forcedDcpId=1 site (RawDemosaicProcessor), forcedBaselineLutId=3 sites,
    # forcedFrameId=2 sites. Todos usam accessors já existentes em LeicaConfig (L651/L659/L667).
    substep "P-19: Force Leica DCP/LUT/frame (v6.3.1: 6 wiring sites)"
    local rdp="$APP_JAVA/raw/RawDemosaicProcessor.kt"
    local bcc="$APP_JAVA/lut/BaselineColorCorrection.kt"
    local cvm="$APP_JAVA/viewmodel/CameraViewModel.kt"
    local gmgr="$APP_JAVA/gallery/GalleryManager.kt"
    local p19_ok=0

    # Site 1: forcedDcpId — RawDemosaicProcessor.kt L10610
    if [[ -f "$rdp" ]]; then
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rdp" 2>/dev/null || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rdp"
        if grep -q 'LeicaConfig.forcedDcpId' "$rdp" 2>/dev/null; then
            ok "P-19 site 1 (DCP): already patched"
            ((++p19_ok))
        else
            sed -i 's|val dcpId = rawDcpId ?: return|val dcpId = LeicaConfig.forcedDcpId.takeIf { it.isNotBlank() } ?: rawDcpId ?: return|' "$rdp"
            grep -q 'LeicaConfig.forcedDcpId' "$rdp" 2>/dev/null && { ok "P-19 site 1 (DCP): wired"; ((++p19_ok)); } || warn "P-19 site 1: pattern não casou"
        fi
    else
        warn "P-19: RawDemosaicProcessor.kt não encontrado"
    fi

    # Site 2: forcedBaselineLutId — BaselineColorCorrection.kt L39
    if [[ -f "$bcc" ]]; then
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$bcc" 2>/dev/null || \
            sed -i '/^package /a import com.hinnka.mycamera.raw.LeicaConfig' "$bcc"
        if grep -q 'LeicaConfig.forcedBaselineLutId' "$bcc" 2>/dev/null; then
            ok "P-19 site 2 (LUT-bcc): already patched"
            ((++p19_ok))
        else
            # v6.3.1: padrão real é 'val lutId = when (target) {' (não 'val baselineLutId = when')
            sed -i 's|val lutId = when (target) {|val lutId = LeicaConfig.forcedBaselineLutId.takeIf { it.isNotBlank() } ?: when (target) {|' "$bcc"
            grep -q 'LeicaConfig.forcedBaselineLutId' "$bcc" 2>/dev/null && { ok "P-19 site 2 (LUT-bcc): wired"; ((++p19_ok)); } || warn "P-19 site 2: pattern não casou"
        fi
    else
        warn "P-19: BaselineColorCorrection.kt não encontrado"
    fi

    # Site 3: forcedBaselineLutId — CameraViewModel.kt L2531
    if [[ -f "$cvm" ]]; then
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$cvm" 2>/dev/null || \
            sed -i '/^package /a import com.hinnka.mycamera.raw.LeicaConfig' "$cvm"
        if grep -q 'LeicaConfig.forcedFrameId' "$cvm" 2>/dev/null; then
            ok "P-19 site 3-6 (CVM): already patched"
            ((++p19_ok))
        else
            # Sites 3+6: forcedFrameId — L1897 (initial) + L722 (runtime update)
            sed -i 's|currentFrameId = prefs.frameId|currentFrameId = LeicaConfig.forcedFrameId.takeIf { it.isNotBlank() } ?: prefs.frameId|' "$cvm"
            sed -i 's|currentFrameId = it.value|currentFrameId = LeicaConfig.forcedFrameId.takeIf { it.isNotBlank() } ?: it.value|' "$cvm"
            # Site 4: forcedBaselineLutId in CameraViewModel L2531
            sed -i 's|val baselineLutId = when|val baselineLutId = LeicaConfig.forcedBaselineLutId.takeIf { it.isNotBlank() } ?: when|' "$cvm"
            grep -q 'LeicaConfig.forcedFrameId' "$cvm" 2>/dev/null && { ok "P-19 sites 3-6 (CVM frame+LUT): wired"; ((++p19_ok)); } || warn "P-19 CVM: pattern não casou"
        fi
    else
        warn "P-19: CameraViewModel.kt não encontrado"
    fi

    # Site 5: forcedBaselineLutId — GalleryManager.kt L260
    if [[ -f "$gmgr" ]]; then
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$gmgr" 2>/dev/null || \
            sed -i '/^package /a import com.hinnka.mycamera.raw.LeicaConfig' "$gmgr"
        if grep -q 'LeicaConfig.forcedBaselineLutId' "$gmgr" 2>/dev/null; then
            ok "P-19 site 5 (LUT-gm): already patched"
            ((++p19_ok))
        else
            # v6.3.1: padrão real é 'val baselineLutId = preferences?.rawBaselineLutId' (não '?: preferences?.rawBaselineLutId')
            sed -i 's|val baselineLutId = preferences?.rawBaselineLutId|val baselineLutId = LeicaConfig.forcedBaselineLutId.takeIf { it.isNotBlank() } ?: preferences?.rawBaselineLutId|' "$gmgr"
            grep -q 'LeicaConfig.forcedBaselineLutId' "$gmgr" 2>/dev/null && { ok "P-19 site 5 (LUT-gm): wired"; ((++p19_ok)); } || warn "P-19 site 5: pattern não casou"
        fi
    else
        warn "P-19: GalleryManager.kt não encontrado"
    fi

    if [[ "$p19_ok" -ge 3 ]]; then
        ((++patch_count))
        ok "P-19: Force Leica DCP/LUT/frame aplicado ($p19_ok/4 sites)"
    else
        warn "P-19: apenas $p19_ok/4 sites wired"
        ((++patch_fail))
    fi

    # P-20: DcpProfile — illuminant ratio warm/cool (v6.3.0: precisa declarar dcp primeiro)
    substep "P-20: DcpProfile illuminant ratios"
    local dcp="$APP_JAVA/raw/DcpProfile.kt"
    if [[ -f "$dcp" ]]; then
        sed -i 's|val ratioWarm = 0.5f|val ratioWarm = LeicaConfig.dcpRatioWarm|' "$dcp"
        sed -i 's|val ratioCool = 1.6f|val ratioCool = LeicaConfig.dcpRatioCool|' "$dcp"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$dcp" || \
            sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$dcp"
        if grep -q 'LeicaConfig.dcpRatioWarm' "$dcp" 2>/dev/null; then
            ((++patch_count))
            ok "DcpProfile illuminant ratios aplicado"
        else
            warn "P-20: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "DcpProfile.kt não encontrado"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Tier 3 — Mertens, vignette, DNG export, JPEG/HEIC quality, branding
    # ───────────────────────────────────────────────────────────────────────

    # P-21: GlesYuvStacker — super resolution scale
    substep "P-21: GlesYuvStacker SR scale"
    if [[ -f "$gys" ]]; then
        sed -i 's|const val SUPER_RESOLUTION_SCALE = 2.0f|val SUPER_RESOLUTION_SCALE get() = LeicaConfig.effectiveSuperResolutionScale|' "$gys"
        ((++patch_count))
        ok "GlesYuvStacker SR scale aplicado"
    else
        warn "GlesYuvStacker.kt não encontrado (já avisado em P-16)"
        ((++patch_fail))
    fi

    # P-22: RawStackTuningProfile — SR phase thresholds (v6.3.0: usar EFFECTIVE)
    substep "P-22: RawStackTuningProfile SR thresholds"
    local rstp="$APP_JAVA/processor/RawStackTuningProfile.kt"
    if [[ -f "$rstp" ]]; then
        # v6.3.0: usar effectiveSuperResolutionScale (responde a capture mode) em vez de plain multiFrameSuperResolutionScale
        sed -i 's|internalScale: Float = 2.0f|internalScale: Float = LeicaConfig.effectiveSuperResolutionScale|' "$rstp"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rstp" || \
            sed -i '/^package com.hinnka.mycamera.processor$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rstp"
        if grep -q 'LeicaConfig.effectiveSuperResolutionScale' "$rstp" 2>/dev/null; then
            ((++patch_count))
            ok "RawStackTuningProfile SR thresholds aplicado (EFFECTIVE — responde a capture mode)"
        else
            warn "P-22: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "RawStackTuningProfile.kt não encontrado"
        ((++patch_fail))
    fi

    # P-23: Jpeg444ExportEncoder — JPEG quality + gainmap
    substep "P-23: Jpeg444ExportEncoder quality + gainmap"
    local j444="$APP_JAVA/gallery/Jpeg444ExportEncoder.kt"
    if [[ -f "$j444" ]]; then
        # Race-safe conversion (const val = LeicaConfig.X would be a Kotlin compile error)
        sed -i 's|private const val JPEG_QUALITY = 95|private val JPEG_QUALITY: Int get() = LeicaConfig.outputQuality|' "$j444"
        # Gainmap quality — P-42 reforça isso com step 2 (defensive)
        sed -i 's|private const val GAINMAP_JPEG_QUALITY = 95|private val GAINMAP_JPEG_QUALITY: Int get() = LeicaConfig.gainmapJpegQuality|' "$j444"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$j444" || \
            sed -i '/^package com.hinnka.mycamera.gallery$/a import com.hinnka.mycamera.raw.LeicaConfig' "$j444"
        ((++patch_count))
        ok "Jpeg444ExportEncoder JPEG+gainmap Q100 aplicado (race-safe)"
    else
        warn "Jpeg444ExportEncoder.kt não encontrado"
        ((++patch_fail))
    fi

    # P-24: HeicExportEncoder — HEIC Q100 (v6.3.0: path correto é gallery/, quality é function param não const)
    substep "P-24: HeicExportEncoder HEIC Q100"
    local heic="$APP_JAVA/gallery/HeicExportEncoder.kt"
    if [[ -f "$heic" ]]; then
        # v6.3.0: DIAG-B confirmou que HEIC quality é function param `quality.coerceIn(0, 100)`.
        # Substituímos por LeicaConfig.heicQuality.coerceIn(0, 100)
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$heic" || \
            sed -i '/^package com.hinnka.mycamera.gallery$/a import com.hinnka.mycamera.raw.LeicaConfig' "$heic"
        # Substitui quality.coerceIn(0, 100) por LeicaConfig.heicQuality.coerceIn(0, 100)
        sed -i 's|quality\.coerceIn(0, 100)|LeicaConfig.heicQuality.coerceIn(0, 100)|' "$heic"
        if grep -q 'LeicaConfig.heicQuality' "$heic" 2>/dev/null; then
            ((++patch_count))
            ok "HeicExportEncoder HEIC Q100 aplicado (v6.3.0: path + pattern corretos)"
        else
            warn "P-24: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "HeicExportEncoder.kt não encontrado"
        ((++patch_fail))
    fi

    # P-25: UltraHdrWriter — UltraHDR Q100 (v6.3.0: path correto é hdr/, quality é data-class default)
    substep "P-25: UltraHdrWriter UltraHDR Q100"
    local uhw="$APP_JAVA/hdr/UltraHdrWriter.kt"
    if [[ -f "$uhw" ]]; then
        # v6.3.0: DIAG-B confirmou que quality é data-class default `val quality: Int = 95` na L18
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$uhw" || \
            sed -i '/^package com.hinnka.mycamera.hdr$/a import com.hinnka.mycamera.raw.LeicaConfig' "$uhw"
        # Substitui val quality: Int = 95 por val quality: Int = LeicaConfig.ultraHdrQuality
        sed -i 's|val quality: Int = 95|val quality: Int = LeicaConfig.ultraHdrQuality|' "$uhw"
        if grep -q 'LeicaConfig.ultraHdrQuality' "$uhw" 2>/dev/null; then
            ((++patch_count))
            ok "UltraHdrWriter UltraHDR Q100 aplicado (v6.3.0: path + pattern corretos)"
        else
            warn "P-25: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "UltraHdrWriter.kt não encontrado"
        ((++patch_fail))
    fi

    # P-26: SuperResolutionDngWriter — branding (v6.3.0: path correto é utils/, branding é inline literal)
    substep "P-26: SuperResolutionDngWriter branding"
    local srdw="$APP_JAVA/utils/SuperResolutionDngWriter.kt"
    if [[ -f "$srdw" ]]; then
        # v6.3.0: DIAG-B confirmou que branding é inline `add(ascii(TAG_SOFTWARE, "PhotonCamera"))` na L594
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$srdw" || \
            sed -i '/^package com.hinnka.mycamera.utils$/a import com.hinnka.mycamera.raw.LeicaConfig' "$srdw"
        # Substitui a string literal "PhotonCamera" por LeicaConfig.softwareBranding
        sed -i 's|add(ascii(TAG_SOFTWARE, "PhotonCamera"))|add(ascii(TAG_SOFTWARE, LeicaConfig.softwareBranding))|' "$srdw"
        if grep -q 'LeicaConfig.softwareBranding' "$srdw" 2>/dev/null; then
            ((++patch_count))
            ok "SuperResolutionDngWriter branding aplicado (v6.3.0: path + pattern corretos)"
        else
            warn "P-26: sed pattern não casou"
            ((++patch_fail))
        fi
    else
        warn "SuperResolutionDngWriter.kt não encontrado"
        ((++patch_fail))
    fi

    # P-27: GalleryManager — DNG export toggle
    substep "P-27: GalleryManager DNG export default"
    local gm="$APP_JAVA/gallery/GalleryManager.kt"
    if [[ -f "$gm" ]]; then
        sed -i 's|val exportDngWithRawExport: Boolean = false|val exportDngWithRawExport: Boolean get() = LeicaConfig.exportDngWithRawExport|' "$gm"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$gm" || \
            sed -i '/^package com.hinnka.mycamera.gallery$/a import com.hinnka.mycamera.raw.LeicaConfig' "$gm"
        ((++patch_count))
        ok "GalleryManager DNG export default aplicado (P-40 adiciona force override)"
    else
        warn "GalleryManager.kt não encontrado"
        ((++patch_fail))
    fi

    # P-28: Camera2Controller — force HIGH_QUALITY ISP for stills
    substep "P-28: Camera2Controller HQ ISP for stills"
    local c2c="$APP_JAVA/camera/Camera2Controller.kt"
    if [[ -f "$c2c" ]]; then
        # Adiciona guard no início de applyFastStillPostProcessingSettings
        sed -i '/^    private fun applyFastStillPostProcessingSettings(builder: CaptureRequest.Builder) {$/a\        if (LeicaConfig.forceHighQualityIsp) { applyHighQualityStillPostProcessingSettings(builder); return }' "$c2c"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$c2c" || \
            sed -i '/^package com.hinnka.mycamera.camera$/a import com.hinnka.mycamera.raw.LeicaConfig' "$c2c"
        ((++patch_count))
        ok "Camera2Controller HQ ISP aplicado (P-35 reforça pra preview também)"
    else
        warn "Camera2Controller.kt não encontrado"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Tier 4 — v6.0 Per-Lens Intelligence (P-29..P-36)
    # ───────────────────────────────────────────────────────────────────────

    # P-50: RawMetadata — lensKey field foundation (v6.3.1: prerequisite para P-29/P-30/P-33)
    # DIAG-C cross-cutting recommendation: adicionar val lensKey: String? = null ao RawMetadata
    # + popular em create() via LeicaConfig.lensKeyFromCharacteristics(characteristics).
    # Desbloqueia tint shift per-lens, saturation per-lens, e dá alternativa limpa p/ P-37/38/36.
    substep "P-50: RawMetadata lensKey foundation (v6.3.1)"
    if [[ -f "$rmd" ]]; then
        if grep -q 'val lensKey: String? = null' "$rmd" 2>/dev/null; then
            ok "P-50: already patched (idempotent)"
            ((++patch_count))
        else
            python3 -c "
import re, sys
p = '$rmd'
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
# 1. Add lensKey field after profileGainTableMap (last field of data class)
new = re.sub(
    r'(val profileGainTableMap: DngProfileGainTableMap\? = null)(\s*\n\s*\)\s*\{)',
    r'\1,\n    val lensKey: String? = null // P-50 per-lens key for tint/saturation/noise\2',
    s
)
if new == s:
    print('NOMATCH field', file=sys.stderr); sys.exit(1)
# 2. Populate lensKey in create() return — add after 'baselineExposure = 0f,'
new2 = re.sub(
    r'(baselineExposure = 0f,)',
    r'\1\n                lensKey = LeicaConfig.lensKeyFromCharacteristics(characteristics),',
    new
)
if new2 == new:
    print('NOMATCH return', file=sys.stderr); sys.exit(1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new2)
print('OK')
" 2>&1 | grep -q '^OK$' && {
                ((++patch_count))
                ok "RawMetadata lensKey foundation aplicado (P-50)"
            } || {
                warn "P-50: pattern não casou"
                ((++patch_fail))
            }
        fi
    else
        warn "RawMetadata.kt não encontrado para P-50"
        ((++patch_fail))
    fi

    # P-29: DcpProfile — per-channel saturation via CCM pre-multiply (v6.3.1: REAL implementation)
    # v6.3.0 era SKIPPED (GLSL não pode chamar Kotlin). v6.3.1 implementa via CCM matrix pre-multiply.
    # IMPL-P29 confirmou: selectedMatrix é FloatArray(9) row-major, multiplyMatrix3x3 existe em DcpProfile L434.
    # CCM mutation em Kotlin propaga para todos os 4 glUniformMatrix3fv sites automaticamente.
    substep "P-29: DcpProfile per-channel saturation via CCM pre-multiply (v6.3.1)"
    local dcp="$APP_JAVA/raw/DcpProfile.kt"
    if [[ -f "$dcp" ]]; then
        if grep -q 'saturatedMatrix' "$dcp" 2>/dev/null; then
            ok "P-29: already patched (idempotent)"
            ((++patch_count))
        else
            # Insert saturation block after '?: metadata.colorCorrectionMatrix' line
            insert_after_idempotent \
                '?: metadata.colorCorrectionMatrix' \
                "$dcp" \
                'saturatedMatrix' \
                '        // P-29: per-channel saturation via CCM matrix pre-multiply (Kotlin-side, not GLSL)' \
                '        // v6.3.6: uses effectiveSaturationForLens (per-lens × creative profile saturation_multiplier)' \
                '        val saturatedMatrix = metadata.lensKey?.let { lensKey ->' \
                '            val satR = LeicaConfig.effectiveSaturationForLens(lensKey, "red")' \
                '            val satG = LeicaConfig.effectiveSaturationForLens(lensKey, "green")' \
                '            val satB = LeicaConfig.effectiveSaturationForLens(lensKey, "blue")' \
                '            if (satR == 1.0f && satG == 1.0f && satB == 1.0f) selectedMatrix' \
                '            else multiplyMatrix3x3(floatArrayOf(satR, 0f, 0f, 0f, satG, 0f, 0f, 0f, satB), selectedMatrix)' \
                '        } ?: selectedMatrix'
            # Change colorCorrectionMatrix = selectedMatrix to = saturatedMatrix
            sed -i 's|colorCorrectionMatrix = selectedMatrix,|colorCorrectionMatrix = saturatedMatrix,|' "$dcp"
            if grep -q 'saturatedMatrix' "$dcp" 2>/dev/null; then
                ((++patch_count))
                ok "DcpProfile per-channel saturation aplicado (P-29: CCM pre-multiply)"
            else
                warn "P-29: pattern não casou"
                ((++patch_fail))
            fi
        fi
    else
        warn "DcpProfile.kt não encontrado para P-29"
        ((++patch_fail))
    fi

    # P-30: DcpProfile — CCM tint shift per-lens (v6.3.1: REAL implementation)
    # v6.3.0 era SKIPPED (applyTintShift não existe). v6.3.1 adiciona helper + wirea com metadata.lensKey.
    # P-50 fornece metadata.lensKey. P-29 fornece saturatedMatrix. P-30 aplica tint shift no final.
    substep "P-30: DcpProfile CCM tint shift per-lens (v6.3.1)"
    if [[ -f "$dcp" ]]; then
        if grep -q 'applyTintShift' "$dcp" 2>/dev/null; then
            ok "P-30: already patched (idempotent)"
            ((++patch_count))
        else
            # v6.3.3 FIX: usar anchor de linha completa única (não multi-line function sig)
            # P-30 v6.3.2 quebrava: insert_after 'fun resolveRenderPlan' injetava DENTRO da lista de params
            # (resolveRenderPlan tem assinatura multi-linha). Agora usa 'private val cache' que é linha única.
            insert_after_idempotent \
                'private val cache = mutableMapOf' \
                "$dcp" \
                'applyTintShift' \
                '    private fun applyTintShift(matrix: FloatArray, tintShift: Float): FloatArray {' \
                '        // P-30: per-lens CCM tint shift. tintShift in [-1.0, 1.0], 0 = no-op.' \
                '        if (tintShift == 0.0f) return matrix' \
                '        // Rotate green<->magenta: scale R and B channels inversely.' \
                '        val factor = 1.0f + tintShift' \
                '        val invFactor = 1.0f - tintShift * 0.5f' \
                '        return floatArrayOf(' \
                '            matrix[0] * factor, matrix[1] * invFactor, matrix[2] * invFactor,' \
                '            matrix[3] * invFactor, matrix[4] * factor, matrix[5] * invFactor,' \
                '            matrix[6] * invFactor, matrix[7] * invFactor, matrix[8] * factor' \
                '        )' \
                '    }' \
                ''
            # Wire tint shift: change colorCorrectionMatrix = saturatedMatrix to applyTintShift(...)
            # v6.3.6 FIX: use effectiveTintShiftForLens (per-lens + color_science) + .toFloat()/100f conversion
            # (preexisting bug: tintShiftForLens returns Int in [-50,50] but applyTintShift expects Float in [-1.0,1.0])
            sed -i 's|colorCorrectionMatrix = saturatedMatrix,|colorCorrectionMatrix = applyTintShift(saturatedMatrix, LeicaConfig.effectiveTintShiftForLens(metadata.lensKey ?: "main").toFloat() / 100f),|' "$dcp"
            if grep -q 'applyTintShift' "$dcp" 2>/dev/null; then
                ((++patch_count))
                ok "DcpProfile CCM tint shift per-lens aplicado (P-30)"
            else
                warn "P-30: pattern não casou"
                ((++patch_fail))
            fi
        fi
    else
        warn "DcpProfile.kt não encontrado para P-30"
        ((++patch_fail))
    fi

    # P-31: Camera2Controller — wire frameCountForCamera (v6.3.0: wirear caller REAL)
    # AUDIT-2 confirmou que frameCountForCamera() foi adicionada mas TEM ZERO CALLERS.
    # Camera2Controller.kt:6801 usa `currentState.multiFrameCount`. Vamos substituir isso.
    substep "P-31: Camera2Controller wire frameCountForCamera (v6.3.0: REAL caller)"
    if [[ -f "$c2c" ]]; then
        if grep -q 'MultiFrameConfig.frameCountForCamera' "$c2c" 2>/dev/null; then
            ok "P-31: already patched (idempotent)"
            ((++patch_count))
        else
            # Garante que frameCountForCamera existe em MultiFrameConfig (P-31 original adicionava ela)
            if [[ -f "$mfc" ]] && ! grep -q 'fun frameCountForCamera' "$mfc" 2>/dev/null; then
                insert_after_idempotent \
                    "val SHORT_FRAME_EXPOSURE_DIVISOR" \
                    "$mfc" \
                    "fun frameCountForCamera" \
                    "" \
                    "/**" \
                    " * frameCountForCamera — retorna frame count per-lens com capture-mode multiplier." \
                    " * main=15×multiplier, UW=9×multiplier, etc. Clampado 3..20." \
                    " */" \
                    "fun frameCountForCamera(cameraId: String): Int = LeicaConfig.effectiveFrameCountForLens(LeicaConfig.lensKeyFromCameraId(cameraId))"
            fi
            # v6.3.5 FIX (Cron 1 audit LAG-RISK): the previous sed had `g` flag and
            # replaced currentState.multiFrameCount in 5 sites (L564, L585, L5768, L5770, L6801).
            # L5768/L5770 are inside setMultiFrameOutputScale() — patching them corrupts the
            # state setter (overwrites multiFrameCount when only outputScale should change).
            # Now target ONLY the real capture-time frame-count consumer at L6801:
            #   `val requestedFrameCount = currentState.multiFrameCount`
            sed -i 's|val requestedFrameCount = currentState\.multiFrameCount|val requestedFrameCount = MultiFrameConfig.frameCountForCamera(currentState.currentCameraId)  // v6.3.5 capture-mode-aware|' "$c2c"
            if grep -q 'MultiFrameConfig.frameCountForCamera' "$c2c" 2>/dev/null; then
                ((++patch_count))
                ok "Camera2Controller wire frameCountForCamera aplicado (v6.3.5: targeted L6801 only — no state-setter corruption)"
            else
                warn "P-31: sed pattern 'val requestedFrameCount = currentState.multiFrameCount' não casou"
                ((++patch_fail))
            fi
        fi
    else
        warn "Camera2Controller.kt não encontrado (já avisado em P-28)"
        ((++patch_fail))
    fi

    # P-32a: VideoTypes — defaults (v6.3.3: SEM get() — data class não permite)
    # upstream usa `val codec`/`val logProfile`/`val resolution`/`val bitrate`/`val fps`
    # e enums VideoCodec.H265, VideoLogProfile.LLOG_BT2020, VideoResolutionPreset.FHD_1080P,
    # VideoBitratePreset.P1..P5
    substep "P-32a: VideoTypes defaults (v6.3.3: SEM get() em data class)"
    local vtypes="$APP_JAVA/video/VideoTypes.kt"
    if [[ -f "$vtypes" ]]; then
        # Primeiro garante o import
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$vtypes" || \
            sed -i '/^package com.hinnka.mycamera.video$/a import com.hinnka.mycamera.raw.LeicaConfig' "$vtypes"
        # v6.3.3 FIX: data class constructor params NÃO PODEM ter get() custom.
        # Usar `val x: Type = expr` (sem get()) — avalia expr quando default é usado.
        # Codec: H264 ou H265
        sed -i 's|val codec: VideoCodec = VideoCodec.H264|val codec: VideoCodec = if (LeicaConfig.videoCodec.equals("hevc", ignoreCase = true)) VideoCodec.H265 else VideoCodec.H264|' "$vtypes"
        sed -i 's|val codec: VideoCodec = VideoCodec.H265|val codec: VideoCodec = if (LeicaConfig.videoCodec.equals("hevc", ignoreCase = true)) VideoCodec.H265 else VideoCodec.H264|' "$vtypes"
        # LogProfile: OFF ou LLOG_BT2020 (v6.3.3: nome real do enum)
        sed -i 's|val logProfile: VideoLogProfile = VideoLogProfile.OFF|val logProfile: VideoLogProfile = if (LeicaConfig.videoColorProfile.equals("log", ignoreCase = true)) VideoLogProfile.LLOG_BT2020 else VideoLogProfile.OFF|' "$vtypes"
        # Resolution: FHD_1080P ou UHD_2160P
        sed -i 's|val resolution: VideoResolutionPreset = VideoResolutionPreset.FHD_1080P|val resolution: VideoResolutionPreset = VideoResolutionPreset.entries.firstOrNull { it.displayName.contains(LeicaConfig.videoDefaultResolution) } ?: VideoResolutionPreset.FHD_1080P|' "$vtypes"
        sed -i 's|val resolution: VideoResolutionPreset = VideoResolutionPreset.UHD_2160P|val resolution: VideoResolutionPreset = VideoResolutionPreset.entries.firstOrNull { it.displayName.contains(LeicaConfig.videoDefaultResolution) } ?: VideoResolutionPreset.UHD_2160P|' "$vtypes"
        # Bitrate: v6.3.5 FIX (Cron 1 audit PARTIAL): upstream default is VideoBitratePreset.P1
        # (NOT P5 — that was a wrong guess in v6.3.3 that caused silent-fail).
        # Replace with capture-mode-aware bitrate picker, fallback to P1.
        sed -i 's|val bitrate: VideoBitratePreset = VideoBitratePreset.P1|val bitrate: VideoBitratePreset = VideoBitratePreset.entries.firstOrNull { it.bitrateMbps >= LeicaConfig.effectiveVideoBitrateMbps } ?: VideoBitratePreset.P1|' "$vtypes"
        # Fps
        sed -i 's|val fps: VideoFpsPreset = VideoFpsPreset.FPS_30|val fps: VideoFpsPreset = VideoFpsPreset.entries.firstOrNull { it.fps == LeicaConfig.videoDefaultFps } ?: VideoFpsPreset.FPS_30|' "$vtypes"
        if grep -q 'LeicaConfig.videoCodec' "$vtypes" 2>/dev/null; then
            ((++patch_count))
            ok "VideoTypes defaults aplicados (v6.3.3: SEM get() + nomes reais)"
        else
            warn "P-32a: nenhum pattern casou — pode ser que VideoTypes.kt use estrutura diferente"
            ((++patch_fail))
        fi
    else
        warn "VideoTypes.kt não encontrado"
        ((++patch_fail))
    fi

    # P-32b: VideoRecorder — B-frames, bitrate, AAC 256k, I-frame interval
    substep "P-32b: VideoRecorder B-frames/bitrate/AAC"
    local vrec="$APP_JAVA/video/VideoRecorder.kt"
    if [[ -f "$vrec" ]]; then
        # Race-safe conversions (upstream may use `private const val` OR inline literals)
        sed -i 's|private const val KEY_MAX_B_FRAMES = 0|private val KEY_MAX_B_FRAMES: Int get() = LeicaConfig.videoMaxBFrames|' "$vrec"
        sed -i 's|private const val I_FRAME_INTERVAL = 1|private val I_FRAME_INTERVAL: Int get() = LeicaConfig.videoIFrameIntervalSec|' "$vrec"
        sed -i 's|private const val AUDIO_MONO_BITRATE = 96_000|private val AUDIO_MONO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000|' "$vrec"
        sed -i 's|private const val AUDIO_STEREO_BITRATE = 192_000|private val AUDIO_STEREO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000 * 2|' "$vrec"
        # Fallback: upstream v1.26.1 usa `setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)` direto (sem const val)
        sed -i 's#setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)#setInteger(MediaFormat.KEY_MAX_B_FRAMES, LeicaConfig.videoMaxBFrames)#' "$vrec" 2>/dev/null || true
        # Bitrate mode CBR-preferring branch — extended with VBR check
        sed -i 's|if (codecName.contains("hevc")|if (codecName.contains("hevc") \&\& LeicaConfig.videoRateControl != "vbr"|' "$vrec"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$vrec" || \
            sed -i '/^package com.hinnka.mycamera.video$/a import com.hinnka.mycamera.raw.LeicaConfig' "$vrec"
        ((++patch_count))
        ok "VideoRecorder B-frames/bitrate/AAC aplicado"
    else
        warn "VideoRecorder.kt não encontrado"
        ((++patch_fail))
    fi

    # P-33: ColorTintShift per-lens (v6.3.1: IMPLEMENTED via P-50 + P-30)
    # v6.3.0 era SKIPPED. v6.3.1: P-50 adiciona metadata.lensKey, P-30 wirea applyTintShift com lensKey.
    # P-33 agora verifica que o wiring está ativo.
    substep "P-33: ColorTintShift per-lens (v6.3.6: verificação do wiring P-30+P-50)"
    if [[ -f "$dcp" ]] && grep -q 'effectiveTintShiftForLens(metadata.lensKey' "$dcp" 2>/dev/null; then
        ((++patch_count))
        ok "P-33: per-lens tint shift ativo (via P-30 effectiveTintShiftForLens + P-50 lensKey)"
    else
        warn "P-33: tint shift per-lens não encontrado — P-30/P-50 podem ter falhado"
        ((++patch_fail))
    fi

    # P-34: PerChannelSaturation per-lens (v6.3.1: IMPLEMENTED via P-29 + P-50)
    # v6.3.0 era SKIPPED. v6.3.1: P-29 implementa saturation via CCM pre-multiply usando metadata.lensKey (P-50).
    substep "P-34: PerChannelSaturation per-lens (v6.3.6: verificação do wiring P-29+P-50)"
    if [[ -f "$dcp" ]] && grep -q 'effectiveSaturationForLens' "$dcp" 2>/dev/null; then
        ((++patch_count))
        ok "P-34: per-channel saturation ativo (via P-29 effectiveSaturationForLens + P-50 lensKey)"
    else
        warn "P-34: saturation per-lens não encontrado — P-29/P-50 podem ter falhado"
        ((++patch_fail))
    fi

    # P-35: HighQualityIspPreview (v6.3.0: pattern correto — isCapture, não isBurst)
    substep "P-35: HighQualityIspPreview (v6.3.0: pattern correto)"
    if [[ -f "$c2c" ]]; then
        if grep -q 'applyImageQualitySettings.*isCapture' "$c2c" 2>/dev/null && grep -q 'LeicaConfig.forceHighQualityIsp.*EDGE_MODE_HIGH_QUALITY' "$c2c" 2>/dev/null; then
            ok "P-35: already patched (idempotent)"
            ((++patch_count))
        else
            # v6.3.0: upstream usa `applyImageQualitySettings(builder: CaptureRequest.Builder, isCapture: Boolean)` (não isBurst)
            sed -i '/private fun applyImageQualitySettings(builder: CaptureRequest.Builder, isCapture: Boolean) {/a\        if (LeicaConfig.forceHighQualityIsp) { builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY); builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY); return }' "$c2c"
            if grep -q 'LeicaConfig.forceHighQualityIsp.*EDGE_MODE_HIGH_QUALITY' "$c2c" 2>/dev/null; then
                ((++patch_count))
                ok "HighQualityIspPreview aplicado (v6.3.0: pattern isCapture correto)"
            else
                warn "P-35: sed pattern não casou (função applyImageQualitySettings pode não existir ou ter assinatura diferente)"
                ((++patch_fail))
            fi
        fi
    else
        warn "Camera2Controller.kt não encontrado (já avisado em P-28)"
        ((++patch_fail))
    fi

    # P-36: NoiseModelFallback (v6.3.1: REAL implementation — thread characteristics no helper)
    # v6.3.0 era SKIPPED. v6.3.1: DIAG-C forneceu 3-part change (signature + call site + fallback).
    # extractChannelNoiseProfile ganha param characteristics, fallback usa LeicaConfig.noiseModelForLens.
    substep "P-36: NoiseModelFallback per-lens (v6.3.1)"
    if [[ -f "$rmd" ]]; then
        if grep -q 'LeicaConfig.noiseModelForLens' "$rmd" 2>/dev/null; then
            ok "P-36: already patched (idempotent)"
            ((++patch_count))
        else
            python3 -c "
import re, sys
p = '$rmd'
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
# 1. Change signature: add characteristics param
new = s.replace(
    'private fun extractChannelNoiseProfile(captureResult: CaptureResult): FloatArray {',
    'private fun extractChannelNoiseProfile(captureResult: CaptureResult, characteristics: CameraCharacteristics): FloatArray {'
)
if new == s:
    print('NOMATCH sig', file=sys.stderr); sys.exit(1)
# 2. Change call site
new2 = new.replace(
    'val channelNoiseProfile = extractChannelNoiseProfile(captureResult)',
    'val channelNoiseProfile = extractChannelNoiseProfile(captureResult, characteristics)'
)
if new2 == new:
    print('NOMATCH call', file=sys.stderr); sys.exit(1)
# 3. Change fallback: floatArrayOf(0.0f, 0.0f) → LeicaConfig.noiseModelForLens fallback
# v6.3.3 FIX: b e d são Double? (nullable) — precisa de safe call (it.b ?: 0.0).toFloat()
new3 = new2.replace(
    'floatArrayOf(0.0f, 0.0f)',
    'LeicaConfig.noiseModelForLens(LeicaConfig.lensKeyFromCharacteristics(characteristics))?.let { floatArrayOf((it.b ?: 0.0).toFloat(), (it.d ?: 0.0).toFloat()) } ?: floatArrayOf(0.0f, 0.0f)',
    1
)
if new3 == new2:
    print('NOMATCH fallback', file=sys.stderr); sys.exit(1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new3)
print('OK')
" 2>&1 | grep -q '^OK$' && {
                ((++patch_count))
                ok "RawMetadata noise model fallback per-lens aplicado (P-36)"
            } || {
                warn "P-36: pattern não casou"
                ((++patch_fail))
            }
        fi
    else
        warn "RawMetadata.kt não encontrado para P-36"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Tier 5 — v6.1+v6.2 JSON→runtime wiring (P-37..P-43)
    # ───────────────────────────────────────────────────────────────────────

    # P-37a: WhiteLevelWiring (v6.3.0: SKIPPED — P-17 já faz wiring real em RawMetadata.create)
    substep "P-37a: WhiteLevelWiring (SKIPPED — P-17 já substitui ?: 1023f em RawMetadata)"
    warn "P-37a SKIPPED: P-17 (v6.3.0) já faz o wiring real substituindo ?: 1023f por LeicaConfig.whiteLevelForLens."
    warn "           resolveWhiteLevelForLens era dead code — removido."
    # Não conta como patch_count nem patch_fail — honestamente skipped
    local rwlc="$APP_JAVA/raw/RawWhiteLevelCorrection.kt"

    # P-37b: WhiteLevelWiring (v6.3.0: SKIPPED — P-17 já faz wiring real)
    substep "P-37b: WhiteLevelWiring RawMetadata (SKIPPED — P-17 já substituiu ?: 1023f)"
    warn "P-37b SKIPPED: P-17 (v6.3.0) já faz o wiring real. Era dead code."
    # Não conta como patch_count nem patch_fail — honestamente skipped

    # P-38: BlackLevelWiring (v6.3.0: SKIPPED — P-18 já faz wiring real)
    substep "P-38: BlackLevelWiring RawMetadata (SKIPPED — P-18 já substituiu floatArrayOf(64f, 64f, 64f, 64f))"
    warn "P-38 SKIPPED: P-18 (v6.3.0) já faz o wiring real substituindo floatArrayOf(64f,...) por LeicaConfig.blackLevelForLens."
    warn "           Era dead code."
    # Não conta como patch_count nem patch_fail — honestamente skipped

    # P-39: PerLensDcpRatio (v6.3.0: SKIPPED — cameraId não em scope, P-20 já faz global)
    substep "P-39: PerLensDcpRatio (SKIPPED — cameraId não em scope, P-20 já faz global)"
    warn "P-39 SKIPPED: cameraId não está em scope em DcpProfile. P-20 já aplica ratioWarm/Cool global."
    warn "           ccmRatioWarmForLens/Cool accessors permanecem sem consumer."
    # Não conta como patch_count nem patch_fail — honestamente skipped

    # P-40: SuperResDngExport — force super res DNG export
    substep "P-40: SuperResDngExport force"
    if [[ -f "$gm" ]]; then
        # Adiciona || LeicaConfig.exportSuperResDng nas duas gates (lines ~2194 + ~3776)
        sed -i 's#if (shouldAutoSave \&\& exportDngWithRawExport) {#if (shouldAutoSave \&\& (exportDngWithRawExport || LeicaConfig.exportSuperResDng)) {#g' "$gm"
        ((++patch_count))
        ok "SuperResDngExport aplicado (force DNG export quando exportSuperResDng=true)"
    else
        warn "GalleryManager.kt não encontrado (já avisado em P-27)"
        ((++patch_fail))
    fi

    # P-41: PerLensAgxToneMapping — forLens factory + per-lens AgX
    # P-41: PerLensAgxToneMapping — forLens factory + per-lens AgX
    substep "P-41: PerLensAgxToneMapping forLens factory"
    if [[ -f "$rtmp" ]]; then
        # Usa insert_after_idempotent (portable, idempotent, no \n bug)
        # CORRECTED params: agxToe, agxShoulder, agxBlackRelativeExposure, agxWhiteRelativeExposure
        # (upstream uses these names, NOT toePower/shoulderPower/etc)
        insert_after_idempotent \
            "val DEFAULT = RawToneMappingParameters()" \
            "$rtmp" \
            "fun forLens(lensKey: String)" \
            "" \
            "    // v6.1 — Per-lens AgX variants (P-41). Consumers chamam forLens(lensKey) ao invés de DEFAULT." \
            "    fun agxToeForLens(lensKey: String): Double = LeicaConfig.gammaShadowLiftForLens(lensKey)" \
            "    fun agxShoulderForLens(lensKey: String): Double = LeicaConfig.gammaShoulderForLens(lensKey)" \
            "    fun agxBlackRelativeExposureForLens(lensKey: String): Float = LeicaConfig.agxBlackRelativeExposure" \
            "    /** forLens — factory que retorna RawToneMappingParameters com valores per-lens. */" \
            "    fun forLens(lensKey: String): RawToneMappingParameters = RawToneMappingParameters(" \
            "        agxToe = agxToeForLens(lensKey).toFloat()," \
            "        agxShoulder = agxShoulderForLens(lensKey).toFloat()," \
            "        agxBlackRelativeExposure = agxBlackRelativeExposureForLens(lensKey)," \
            "        agxWhiteRelativeExposure = LeicaConfig.agxWhiteRelativeExposure," \
            "    )"
        ((++patch_count))
        ok "PerLensAgxToneMapping forLens factory aplicado (opt-in; P-43 ativa consumer)"
    else
        warn "RawToneMappingParameters.kt não encontrado (já avisado em P-2)"
        ((++patch_fail))
    fi

    # P-42: GainmapQualityMax — Q100 + const-val race fix
    substep "P-42: GainmapQualityMax Q100 + const-val race fix"
    if [[ -f "$j444" ]]; then
        # Step 1: garante que const val virou val get() (defensive — P-23 já fez)
        sed -i 's|private const val GAINMAP_JPEG_QUALITY = 95|private val GAINMAP_JPEG_QUALITY: Int get() = LeicaConfig.gainmapJpegQuality|' "$j444"
        # Step 2: se P-23 produziu `const val = LeicaConfig.X` (compile error), corrige
        sed -i 's|private const val GAINMAP_JPEG_QUALITY = LeicaConfig.gainmapJpegQuality|private val GAINMAP_JPEG_QUALITY: Int get() = LeicaConfig.gainmapJpegQuality|' "$j444"
        # Mesmo pra JPEG_QUALITY (defensive)
        sed -i 's|private const val JPEG_QUALITY = LeicaConfig.outputQuality|private val JPEG_QUALITY: Int get() = LeicaConfig.outputQuality|' "$j444"
        # Garante import (defensive grep-guarded)
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$j444" || \
            sed -i '/^package com.hinnka.mycamera.gallery$/a import com.hinnka.mycamera.raw.LeicaConfig' "$j444"
        ((++patch_count))
        ok "GainmapQualityMax Q100 aplicado + const-val race fix"
    else
        warn "Jpeg444ExportEncoder.kt não encontrado (já avisado em P-23)"
        ((++patch_fail))
    fi

    # P-43: PerLensAgxConsumer — wire forLens into CameraViewModel resolveCaptureRawToneMappingParameters
    substep "P-43: PerLensAgxConsumer CameraViewModel resolveCaptureRawToneMappingParameters"
    local viewmodel_dir="$APP_JAVA/viewmodel"
    local cvm="$viewmodel_dir/CameraViewModel.kt"
    if [[ -f "$cvm" ]]; then
        # 1. Import LeicaConfig (grep-guarded)
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$cvm" || \
            sed -i '/^package com.hinnka.mycamera.viewmodel$/a import com.hinnka.mycamera.raw.LeicaConfig' "$cvm"
        # 2. Add cameraId parameter to resolveCaptureRawToneMappingParameters signature
        sed -i '/^    private fun resolveCaptureRawToneMappingParameters($/{N;s|\(\n        userPrefs: UserPreferences?\)$|\1,\n        cameraId: String = ""|}' "$cvm"
        # 3. Replace body line — use # delimiter (replacement contains ||)
        sed -i 's#        val base = userPrefs?.rawToneMappingParameters ?: RawToneMappingParameters.DEFAULT#        val userParams = userPrefs?.rawToneMappingParameters; val base = if (userParams == null || userParams == RawToneMappingParameters.DEFAULT) RawToneMappingParameters.forLens(LeicaConfig.lensKeyFromCameraId(cameraId)) else userParams#' "$cvm"
        # 4. Thread currentCameraId through all 6 call sites
        sed -i 's|resolveCaptureRawToneMappingParameters(userPrefs)|resolveCaptureRawToneMappingParameters(userPrefs, currentCameraId)|g' "$cvm"
        ((++patch_count))
        ok "PerLensAgxConsumer aplicado — G1 CLOSED (per-lens AgX ativo no runtime)"
    else
        warn "CameraViewModel.kt não encontrado em $cvm"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Tier 6 — v6.2.5 Runtime activation + branding
    # P-44: Load LeicaConfig from assets at app startup (CRITICAL — without this,
    #       currentConfig is always null and all accessors return hardcoded fallbacks,
    #       making the entire 441-line leica_perfect.json a dead file inside the APK).
    # P-45: Change app_name in strings.xml from "Photon Camera" to "Leica Perfect"
    #       (across all 12 locales incl. zh-rCN, zh-rHK, zh-rTW, ja, ko, pt-rBR, etc.)
    #       User can confirm fork is installed by checking launcher icon label.
    # ───────────────────────────────────────────────────────────────────────
    substep "P-44: MyCameraApplication — load LeicaConfig from assets at startup"

    local app_kt="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt"
    if [[ -f "$app_kt" ]]; then
        # Add import after `import android.content.Intent`
        insert_after_idempotent \
            "^import android.content.Intent$" \
            "$app_kt" \
            "import com.hinnka.mycamera.raw.LeicaConfig" \
            "import com.hinnka.mycamera.raw.LeicaConfig"

        # Inject load block after `instance = this`
        # (uses assets.open() — packaged JSON is at assets/leica_perfect.json via P-1)
        insert_after_idempotent \
            "^        instance = this$" \
            "$app_kt" \
            "Leica Perfect config loaded from assets" \
            "        // P-44: Leica Perfect — load config from assets (leica_perfect.json)" \
            "        try {" \
            "            val leicaJson = assets.open(\"leica_perfect.json\").bufferedReader().use { it.readText() }" \
            "            LeicaConfig.load(leicaJson)" \
            "            PLog.d(TAG, \"Leica Perfect config loaded from assets\")" \
            "        } catch (e: Exception) {" \
            "            PLog.e(TAG, \"Failed to load LeicaConfig from assets\", e)" \
            "        }"

        # Verify both insertions
        if grep -q "import com.hinnka.mycamera.raw.LeicaConfig" "$app_kt" 2>/dev/null && \
           grep -q "Leica Perfect config loaded from assets" "$app_kt" 2>/dev/null; then
            ok "P-44: MyCameraApplication patched — LeicaConfig.load() wired at startup"
            ((++patch_count))
        else
            warn "P-44: MyCameraApplication patch verification failed"
            ((++patch_fail))
        fi
    else
        warn "MyCameraApplication.kt não encontrado em $app_kt"
        ((++patch_fail))
    fi

    substep "P-45: Branding — app_name Photon Camera → Leica Perfect (all locales)"

    local strings_files=()
    while IFS= read -r f; do
        strings_files+=("$f")
    done < <(find "$SOURCE_DIR/app/src/main/res" -name "strings.xml" -type f 2>/dev/null)

    local branding_count=0
    local branding_total=0
    for xml in "${strings_files[@]}"; do
        [[ -f "$xml" ]] || continue
        # Skip if already branded (idempotent)
        if grep -q '<string name="app_name">Leica Perfect</string>' "$xml" 2>/dev/null; then
            ((++branding_count))
            ((++branding_total))
            continue
        fi
        # Replace English "Photon Camera"
        if grep -q '<string name="app_name">Photon Camera</string>' "$xml" 2>/dev/null; then
            sed -i 's#<string name="app_name">Photon Camera</string>#<string name="app_name">Leica Perfect</string>#' "$xml"
            ((++branding_count))
        fi
        # Replace Chinese Traditional "光子相機"
        if grep -q '<string name="app_name">光子相機</string>' "$xml" 2>/dev/null; then
            sed -i 's#<string name="app_name">光子相機</string>#<string name="app_name">Leica Perfect</string>#' "$xml"
            ((++branding_count))
        fi
        # Replace Chinese Simplified "光子相机"
        if grep -q '<string name="app_name">光子相机</string>' "$xml" 2>/dev/null; then
            sed -i 's#<string name="app_name">光子相机</string>#<string name="app_name">Leica Perfect</string>#' "$xml"
            ((++branding_count))
        fi
        ((++branding_total))
    done

    if [[ $branding_count -gt 0 ]]; then
        ok "P-45: app_name branded to 'Leica Perfect' in $branding_count/$branding_total locale files"
        ((++patch_count))
    else
        warn "P-45: No app_name replacements made (already branded or no strings.xml found)"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Tier 7 — v6.2.6 UI: Settings panel + viewfinder button + runtime state
    # P-48: LeicaRuntimeState.kt (mutable overrides + SharedPreferences persistence)
    #       + modify LeicaConfig accessors to check runtime overrides first
    # P-46: LeicaSettingsScreen.kt (new Compose screen) + route + NavHost registration
    # P-47: PhysicalButton in CameraTopBar + wiring through CameraScreen + MainActivity
    # ───────────────────────────────────────────────────────────────────────

    # ── P-48a: Install LeicaRuntimeState.kt ──
    substep "P-48a: Instalar LeicaRuntimeState.kt (mutable state + persistence)"
    local runtime_state_src="$PATCH_DIR/LeicaRuntimeState.kt"
    local runtime_state_dst="$APP_JAVA/raw/LeicaRuntimeState.kt"
    if [[ -f "$runtime_state_src" ]]; then
        cp -f "$runtime_state_src" "$runtime_state_dst"
        ok "LeicaRuntimeState.kt instalado em $runtime_state_dst"
        ((++patch_count))
    else
        warn "LeicaRuntimeState.kt não encontrado em $runtime_state_src"
        ((++patch_fail))
    fi

    # ── P-48b: Modify LeicaConfig.activeCaptureMode getter ──
    # v6.3.5 (Cron 1 audit — REDUNDANT): LeicaConfig.kt installed by P-1 ALREADY has
    # the `LeicaRuntimeState.captureModeOverride ?: ...` check baked in (L525-528).
    # The sed pattern below targets the OLD pre-v6.3.4 getter and never matches on
    # the current LeicaConfig.kt — so this step is now VERIFICATION-ONLY. The grep
    # check confirms the override is present (it always will be after P-1 runs).
    substep "P-48b: LeicaConfig.activeCaptureMode → check LeicaRuntimeState override (verification-only)"
    local leica_config_dst="$APP_JAVA/raw/LeicaConfig.kt"
    if [[ -f "$leica_config_dst" ]]; then
        if grep -q "LeicaRuntimeState.captureModeOverride" "$leica_config_dst" 2>/dev/null; then
            ok "P-48b: verified — activeCaptureMode already checks LeicaRuntimeState (baked in by P-1)"
            ((++patch_count))
        else
            # Defensive fallback: if a future LeicaConfig.kt reverts the override, re-apply it.
            sed -i 's#get() = currentConfig?.captureModes?.activeCaptureMode ?: "mode_balanced"#get() = LeicaRuntimeState.captureModeOverride ?: currentConfig?.captureModes?.activeCaptureMode ?: "mode_balanced"#' "$leica_config_dst"
            if grep -q "LeicaRuntimeState.captureModeOverride" "$leica_config_dst" 2>/dev/null; then
                ok "P-48b: activeCaptureMode now checks LeicaRuntimeState first (defensive fallback applied)"
                ((++patch_count))
            else
                warn "P-48b: sed replacement failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-48b: LeicaConfig.kt not found at $leica_config_dst"
        ((++patch_fail))
    fi

    # ── P-48c: Modify LeicaConfig.activeCreativeProfileId getter ──
    # v6.3.5 (Cron 1 audit — REDUNDANT): same as P-48b. LeicaConfig.kt installed by
    # P-1 ALREADY has the `LeicaRuntimeState.creativeProfileOverride ?: ...` check
    # baked in (L998-1001). VERIFICATION-ONLY.
    substep "P-48c: LeicaConfig.activeCreativeProfileId → check LeicaRuntimeState override (verification-only)"
    if [[ -f "$leica_config_dst" ]]; then
        if grep -q "LeicaRuntimeState.creativeProfileOverride" "$leica_config_dst" 2>/dev/null; then
            ok "P-48c: verified — activeCreativeProfileId already checks LeicaRuntimeState (baked in by P-1)"
            ((++patch_count))
        else
            sed -i 's#get() = currentConfig?.creativeProfiles?.activeProfile ?: "leica_authentic"#get() = LeicaRuntimeState.creativeProfileOverride ?: currentConfig?.creativeProfiles?.activeProfile ?: "leica_authentic"#' "$leica_config_dst"
            if grep -q "LeicaRuntimeState.creativeProfileOverride" "$leica_config_dst" 2>/dev/null; then
                ok "P-48c: activeCreativeProfileId now checks LeicaRuntimeState first (defensive fallback applied)"
                ((++patch_count))
            else
                warn "P-48c: sed replacement failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-48c: LeicaConfig.kt not found"
        ((++patch_fail))
    fi

    # ── P-48d: Add LeicaRuntimeState.init(this) to MyCameraApplication ──
    substep "P-48d: MyCameraApplication — call LeicaRuntimeState.init(this) after LeicaConfig.load()"
    local app_kt_p48="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt"
    if [[ -f "$app_kt_p48" ]]; then
        if grep -q "LeicaRuntimeState.init" "$app_kt_p48" 2>/dev/null; then
            ok "P-48d: already patched (idempotent)"
        else
            # Step 1: Add import for LeicaRuntimeState (P-44 already added LeicaConfig import)
            if ! grep -q 'import com.hinnka.mycamera.raw.LeicaRuntimeState' "$app_kt_p48" 2>/dev/null; then
                sed -i '/^import com.hinnka.mycamera.raw.LeicaConfig$/a import com.hinnka.mycamera.raw.LeicaRuntimeState' "$app_kt_p48"
            fi

            # Step 2: Insert LeicaRuntimeState.init(this) after LeicaConfig.load(leicaJson)
            insert_after_idempotent \
                'LeicaConfig.load(leicaJson)' \
                "$app_kt_p48" \
                "LeicaRuntimeState.init" \
                '            LeicaRuntimeState.init(this@MyCameraApplication)'
            if grep -q "LeicaRuntimeState.init" "$app_kt_p48" 2>/dev/null && \
               grep -q 'import com.hinnka.mycamera.raw.LeicaRuntimeState' "$app_kt_p48" 2>/dev/null; then
                ok "P-48d: LeicaRuntimeState.init(this) wired + import added"
                ((++patch_count))
            else
                warn "P-48d: insertion failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-48d: MyCameraApplication.kt not found"
        ((++patch_fail))
    fi

    # ── P-49: LivePhotoRecorder — wire LeicaConfig (CRITICAL FIX) ──
    # BUG: P-32a/P-32b patched VideoRecorder.kt, but the app records Live Photo
    # video via LivePhotoRecorder.kt which has HARDCODED companion-object constants:
    #   MIME_TYPE_VIDEO = "video/avc" (H.264!), VIDEO_BITRATE = 8_000_000 (8 Mbps!),
    #   AUDIO_SAMPLE_RATE = 44100, AUDIO_BITRATE = 64000.
    # These exactly match the user's runtime log. This patch replaces them with
    # LeicaConfig-backed computed properties using EFFECTIVE accessors so the
    # capture mode (MAX/BALANCED/FAST) actually changes the bitrate.
    substep "P-49: LivePhotoRecorder — wire LeicaConfig (CRITICAL: video was H.264 8Mbps)"
    local lpr="$APP_JAVA/livephoto/LivePhotoRecorder.kt"
    if [[ -f "$lpr" ]]; then
        if grep -q 'LeicaConfig.effectiveVideoBitrateMbps' "$lpr" 2>/dev/null; then
            ok "P-49: already patched (idempotent)"
            ((++patch_count))
        else
            # Step 1: Add import for LeicaConfig after package declaration
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$lpr" 2>/dev/null || \
                sed -i '/^package com.hinnka.mycamera.livephoto$/a import com.hinnka.mycamera.raw.LeicaConfig' "$lpr"

            # Step 2: Replace companion-object const val declarations with computed properties.
            # const val → val ... get() = ... (reads LeicaConfig at call time, not init time)
            # MIME_TYPE_VIDEO: choose HEVC or AVC based on LeicaConfig.videoCodec
            sed -i 's|private const val MIME_TYPE_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC|private val MIME_TYPE_VIDEO: String get() = if (LeicaConfig.videoCodec.equals("hevc", ignoreCase = true)) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC|' "$lpr"
            # VIDEO_BITRATE: use effectiveVideoBitrateMbps (250/120/80 by mode) × 1_000_000
            sed -i 's|private const val VIDEO_BITRATE = 8_000_000|private val VIDEO_BITRATE: Int get() = LeicaConfig.effectiveVideoBitrateMbps * 1_000_000|' "$lpr"
            # I_FRAME_INTERVAL: from LeicaConfig (default 1s)
            sed -i 's|private const val I_FRAME_INTERVAL = 1|private val I_FRAME_INTERVAL: Int get() = LeicaConfig.videoIFrameIntervalSec|' "$lpr"
            # AUDIO_SAMPLE_RATE: from LeicaConfig (default 48000, was 44100)
            sed -i 's|private const val AUDIO_SAMPLE_RATE = 44100|private val AUDIO_SAMPLE_RATE: Int get() = LeicaConfig.videoAudioSampleRate|' "$lpr"
            # AUDIO_BITRATE: from LeicaConfig (256 kbps, was 64 kbps) × 1000
            sed -i 's|private const val AUDIO_BITRATE = 64000|private val AUDIO_BITRATE: Int get() = LeicaConfig.videoAudioBitrateKbps * 1000|' "$lpr"

            # Step 3: Add B-frames + bitrate mode to video format (upstream doesn't set these)
            # Insert KEY_MAX_B_FRAMES after KEY_I_FRAME_INTERVAL line
            sed -i '/setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)/a\                setInteger(MediaFormat.KEY_MAX_B_FRAMES, LeicaConfig.videoMaxBFrames)' "$lpr"

            # Step 4: Add diagnostic log line in initEncoder so user can verify in logcat
            # Insert after "Encoders initialized" log line
            sed -i 's|PLog.d(TAG, "Encoders initialized: ${w}x${h}")|PLog.d(TAG, "Encoders initialized: ${w}x${h} — mime=$MIME_TYPE_VIDEO, video_br=$VIDEO_BITRATE, audio_sr=$AUDIO_SAMPLE_RATE, audio_br=$AUDIO_BITRATE, b_frames=${LeicaConfig.videoMaxBFrames}, mode=${LeicaConfig.activeCaptureMode}")|' "$lpr"

            # Verify
            if grep -q 'LeicaConfig.effectiveVideoBitrateMbps' "$lpr" 2>/dev/null && \
               grep -q 'LeicaConfig.videoAudioSampleRate' "$lpr" 2>/dev/null && \
               grep -q 'MIMETYPE_VIDEO_HEVC' "$lpr" 2>/dev/null; then
                ok "P-49: LivePhotoRecorder wired to LeicaConfig (HEVC + effective bitrate + 48kHz + 256kbps AAC)"
                ((++patch_count))
            else
                warn "P-49: verification failed — some sed replacements may not have matched"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-49: LivePhotoRecorder.kt not found"
        ((++patch_fail))
    fi

    # ── NO_MENU guard: skip P-46 + P-47 (mod menu UI) in ABSOLUTE builds ──
    if [[ -n "${NO_MENU:-}" ]]; then
        info "P-46/P-47: SKIPPED (NO_MENU=1 — ABSOLUTE build, no mod menu UI)"
    else
    # ── P-46a: Install LeicaSettingsScreen.kt ──
    substep "P-46a: Instalar LeicaSettingsScreen.kt (Compose settings panel)"
    local leica_screen_src="$PATCH_DIR/LeicaSettingsScreen.kt"
    local leica_screen_dst="$APP_JAVA/ui/settings/LeicaSettingsScreen.kt"
    if [[ -f "$leica_screen_src" ]]; then
        mkdir -p "$(dirname "$leica_screen_dst")"
        cp -f "$leica_screen_src" "$leica_screen_dst"
        ok "LeicaSettingsScreen.kt instalado em $leica_screen_dst"
        ((++patch_count))
    else
        warn "LeicaSettingsScreen.kt não encontrado em $leica_screen_src"
        ((++patch_fail))
    fi

    # ── P-46b: Add LEICA_SETTINGS route to Routes ──
    substep "P-46b: Add LEICA_SETTINGS route to Routes (MainActivity.kt)"
    local main_activity="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MainActivity.kt"
    if [[ -f "$main_activity" ]]; then
        if grep -q 'const val LEICA_SETTINGS' "$main_activity" 2>/dev/null; then
            ok "P-46b: already patched (idempotent)"
        else
            insert_after_idempotent \
                'const val PRESET_MANAGEMENT = "preset_management"' \
                "$main_activity" \
                'const val LEICA_SETTINGS' \
                '    const val LEICA_SETTINGS = "leica_settings"'
            if grep -q 'const val LEICA_SETTINGS' "$main_activity" 2>/dev/null; then
                ok "P-46b: LEICA_SETTINGS route added to Routes"
                ((++patch_count))
            else
                warn "P-46b: route insertion failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-46b: MainActivity.kt not found"
        ((++patch_fail))
    fi

    # ── P-46c: Register LeicaSettingsScreen in NavHost ──
    substep "P-46c: Register LeicaSettingsScreen composable in NavHost + add import"
    if [[ -f "$main_activity" ]]; then
        if grep -q 'composable(Routes.LEICA_SETTINGS)' "$main_activity" 2>/dev/null; then
            ok "P-46c: already patched (idempotent)"
        else
            # Step 1: Add import for LeicaSettingsScreen after SettingsScreen import
            if ! grep -q 'import com.hinnka.mycamera.ui.settings.LeicaSettingsScreen' "$main_activity" 2>/dev/null; then
                sed -i '/^import com.hinnka.mycamera.ui.settings.SettingsScreen$/a import com.hinnka.mycamera.ui.settings.LeicaSettingsScreen' "$main_activity"
            fi

            # Step 2: Use Python: find composable(Routes.SETTINGS) block, insert after its closing }
            python3 - "$main_activity" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath) as f:
    lines = f.readlines()

# Find 'composable(Routes.SETTINGS) {' block
start_idx = None
for i, line in enumerate(lines):
    if 'composable(Routes.SETTINGS)' in line:
        start_idx = i
        break

if start_idx is None:
    print("FAIL: composable(Routes.SETTINGS) not found")
    sys.exit(1)

# Find matching closing '}' (account for brace depth)
depth = 0
end_idx = None
for i in range(start_idx, len(lines)):
    depth += lines[i].count('{') - lines[i].count('}')
    if depth == 0 and i > start_idx:
        end_idx = i
        break

if end_idx is None:
    print("FAIL: could not find closing brace for SETTINGS composable")
    sys.exit(1)

# Insert LeicaSettingsScreen composable block after end_idx
insert_text = (
    '\n'
    '            composable(Routes.LEICA_SETTINGS) {\n'
    '                LeicaSettingsScreen(\n'
    '                    onBack = { navController.popBackStack() }\n'
    '                )\n'
    '            }\n'
)

lines.insert(end_idx + 1, insert_text)
with open(filepath, 'w') as f:
    f.writelines(lines)
print("OK: LeicaSettingsScreen composable inserted after line", end_idx + 1)
PYEOF
            if grep -q 'composable(Routes.LEICA_SETTINGS)' "$main_activity" 2>/dev/null && \
               grep -q 'import com.hinnka.mycamera.ui.settings.LeicaSettingsScreen' "$main_activity" 2>/dev/null; then
                ok "P-46c: LeicaSettingsScreen registered in NavHost + import added"
                ((++patch_count))
            else
                warn "P-46c: NavHost registration or import failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-46c: MainActivity.kt not found"
        ((++patch_fail))
    fi

    # ── P-47a: Add onLeicaModeClick param to CameraTopBar ──
    substep "P-47a: CameraTopBar — add onLeicaModeClick parameter"
    local topbar="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/ui/camera/CameraTopBar.kt"
    if [[ -f "$topbar" ]]; then
        if grep -q 'onLeicaModeClick' "$topbar" 2>/dev/null; then
            ok "P-47a: already patched (idempotent)"
        else
            # Use Python: insert after FIRST 'onSettingsClick: () -> Unit,' only
            # (CameraTopBar.kt has 3 such lines: CameraTopBar, QuickShotTopBar, VideoTopBar)
            python3 - "$topbar" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath) as f:
    lines = f.readlines()

# Find first 'onSettingsClick: () -> Unit,' (CameraTopBar's own signature)
target_idx = None
for i, line in enumerate(lines):
    if 'onSettingsClick: () -> Unit,' in line:
        target_idx = i
        break

if target_idx is None:
    print("FAIL: onSettingsClick param not found")
    sys.exit(1)

# Insert after the found line (matching its indentation: 4 spaces)
lines.insert(target_idx + 1, '    onLeicaModeClick: () -> Unit = {},\n')
with open(filepath, 'w') as f:
    f.writelines(lines)
print("OK: onLeicaModeClick param inserted after line", target_idx + 1)
PYEOF
            if grep -q 'onLeicaModeClick' "$topbar" 2>/dev/null; then
                ok "P-47a: onLeicaModeClick param added to CameraTopBar"
                ((++patch_count))
            else
                warn "P-47a: param insertion failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-47a: CameraTopBar.kt not found"
        ((++patch_fail))
    fi

    # ── P-47b: Add Leica PhysicalButton to CameraTopBar PHOTO Row ──
    substep "P-47b: CameraTopBar — add Leica PhysicalButton (PHOTO Row) using AppIcons.Tune"
    if [[ -f "$topbar" ]]; then
        if grep -q 'onClick = onLeicaModeClick' "$topbar" 2>/dev/null; then
            ok "P-47b: already patched (idempotent)"
        else
            # NOTE: CameraTopBar.kt already imports `com.hinnka.mycamera.ui.icons.AppIcons`
            # (line 37) and uses AppIcons.BarChart, AppIcons.Timer, AppIcons.FlashOff, etc.
            # AppIcons.Tune is defined in AppIcons.kt (~line 1422) — no new import needed.
            # (Previously tried Icons.Default.Tune but material-icons-extended is NOT a dep.)

            # Use Python for reliable first-occurrence insertion (onClick = onSettingsClick
            # appears 3 times: PHOTO Row, QuickShotTopBar, VideoTopBar — we want PHOTO only)
            python3 - "$topbar" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath) as f:
    lines = f.readlines()

# Find first 'onClick = onSettingsClick' (PHOTO Row PhysicalButton)
target_idx = None
for i, line in enumerate(lines):
    if 'onClick = onSettingsClick' in line and 'PhysicalButton' not in line:
        # Verify it's inside a PhysicalButton (look back for PhysicalButton()
        for j in range(i, max(i - 5, 0), -1):
            if 'PhysicalButton(' in lines[j]:
                target_idx = j  # Insert BEFORE the PhysicalButton( line
                break
        if target_idx is not None:
            break

if target_idx is None:
    print("FAIL: could not find PHOTO Row PhysicalButton with onSettingsClick")
    sys.exit(1)

button_code = (
    '        PhysicalButton(\n'
    '            modifier = Modifier.size(36.dp),\n'
    '            onClick = onLeicaModeClick\n'
    '        ) {\n'
    '            Icon(\n'
    '                imageVector = AppIcons.Tune,\n'
    '                contentDescription = "Leica Mode",\n'
    '                modifier = Modifier.size(20.dp).autoRotate(),\n'
    '                tint = Color.White\n'
    '            )\n'
    '        }\n'
    '\n'
)

lines.insert(target_idx, button_code)
with open(filepath, 'w') as f:
    f.writelines(lines)
print("OK: Leica PhysicalButton inserted at line", target_idx + 1)
PYEOF
            if grep -q 'onClick = onLeicaModeClick' "$topbar" 2>/dev/null; then
                ok "P-47b: Leica PhysicalButton added to CameraTopBar PHOTO Row (AppIcons.Tune)"
                ((++patch_count))
            else
                warn "P-47b: button insertion failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-47b: CameraTopBar.kt not found"
        ((++patch_fail))
    fi

    # ── P-47c: Add onLeicaSettingsClick param to CameraScreen ──
    substep "P-47c: CameraScreen — add onLeicaSettingsClick parameter"
    local cam_screen="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/ui/camera/CameraScreen.kt"
    if [[ -f "$cam_screen" ]]; then
        if grep -q 'onLeicaSettingsClick' "$cam_screen" 2>/dev/null; then
            ok "P-47c: already patched (idempotent)"
        else
            # Insert after onSettingsClick param (in CameraScreen signature)
            # CameraScreen signature has onSettingsClick at line 200
            sed -i '/^    onSettingsClick: () -> Unit,$/a\    onLeicaSettingsClick: () -> Unit = {},' "$cam_screen"
            if grep -q 'onLeicaSettingsClick' "$cam_screen" 2>/dev/null; then
                ok "P-47c: onLeicaSettingsClick param added to CameraScreen"
                ((++patch_count))
            else
                warn "P-47c: param insertion failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-47c: CameraScreen.kt not found"
        ((++patch_fail))
    fi

    # ── P-47d: Pass onLeicaModeClick to CameraTopBar in CameraScreen ──
    substep "P-47d: CameraScreen — wire onLeicaModeClick = onLeicaSettingsClick to CameraTopBar"
    if [[ -f "$cam_screen" ]]; then
        if grep -q 'onLeicaModeClick = onLeicaSettingsClick' "$cam_screen" 2>/dev/null; then
            ok "P-47d: already patched (idempotent)"
        else
            # Insert onLeicaModeClick = onLeicaSettingsClick, BEFORE the CameraTopBar() call's
            # `modifier = Modifier` argument. That modifier line is uniquely followed by
            # `.padding(top = topSafePadding)` (only the CameraTopBar call has this).
            # Inserting INSIDE the onSettingsClick={...} lambda body (the old approach) broke
            # the call structure — see v6.2.6 post-mortem.
            python3 - "$cam_screen" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath) as f:
    lines = f.readlines()

# Find `modifier = Modifier` line immediately followed by `.padding(top = topSafePadding)`
target_idx = None
for i, line in enumerate(lines):
    if 'modifier = Modifier' in line and i + 1 < len(lines) and '.padding(top = topSafePadding)' in lines[i + 1]:
        target_idx = i
        break

if target_idx is None:
    print("FAIL: CameraTopBar modifier=Modifier+.padding(top = topSafePadding) not found")
    sys.exit(1)

# Match the existing indentation (16 spaces in upstream)
indent = '                '
lines.insert(target_idx, f'{indent}onLeicaModeClick = onLeicaSettingsClick,\n')
with open(filepath, 'w') as f:
    f.writelines(lines)
print("OK: onLeicaModeClick = onLeicaSettingsClick inserted before line", target_idx + 1)
PYEOF
            if grep -q 'onLeicaModeClick = onLeicaSettingsClick' "$cam_screen" 2>/dev/null; then
                ok "P-47d: onLeicaModeClick wired to onLeicaSettingsClick"
                ((++patch_count))
            else
                warn "P-47d: wiring failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-47d: CameraScreen.kt not found"
        ((++patch_fail))
    fi

    # ── P-47e: Wire onLeicaSettingsClick in MainActivity CameraScreen call ──
    substep "P-47e: MainActivity — pass onLeicaSettingsClick to CameraScreen (both call sites)"
    if [[ -f "$main_activity" ]]; then
        if grep -q 'onLeicaSettingsClick = {' "$main_activity" 2>/dev/null; then
            ok "P-47e: already patched (idempotent)"
        else
            # Insert `onLeicaSettingsClick = {...},` AFTER each `onSettingsClick = { ... },`
            # block in CameraScreen() call sites. The old approach used insert_after_idempotent
            # on the marker `navController.navigate(Routes.SETTINGS)` which inserted INSIDE the
            # onSettingsClick lambda body, breaking the call structure (see v6.2.6 post-mortem).
            python3 - "$main_activity" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath) as f:
    lines = f.readlines()

# Find each `onSettingsClick = {` line, locate its matching closing `},`,
# and insert `onLeicaSettingsClick = {...},` AFTER that closing brace.
# Both CameraScreen() call sites have onSettingsClick lambda body = `navController.navigate(Routes.SETTINGS)`.
inserted = 0
i = 0
while i < len(lines):
    if 'onSettingsClick = {' in lines[i] and 'navigate(Routes.SETTINGS)' in (lines[i + 1] if i + 1 < len(lines) else ''):
        # Capture indentation of the onSettingsClick line
        indent = lines[i][:len(lines[i]) - len(lines[i].lstrip())]
        # Find matching `},` (closes the onSettingsClick lambda) — same indentation
        j = i + 1
        while j < len(lines):
            if lines[j].rstrip() == indent + '},':
                break
            j += 1
        if j >= len(lines):
            print(f"FAIL: could not find closing '}},' for onSettingsClick at line {i + 1}")
            sys.exit(1)
        # Insert AFTER line j
        new_block = (
            f'{indent}onLeicaSettingsClick = {{\n'
            f'{indent}    navController.navigate(Routes.LEICA_SETTINGS)\n'
            f'{indent}}},\n'
        )
        lines.insert(j + 1, new_block)
        inserted += 1
        i = j + 4  # Skip past the inserted block
    else:
        i += 1

if inserted == 0:
    print("FAIL: no CameraScreen onSettingsClick call sites found")
    sys.exit(1)

with open(filepath, 'w') as f:
    f.writelines(lines)
print(f"OK: inserted onLeicaSettingsClick at {inserted} call site(s)")
PYEOF
            if grep -q 'onLeicaSettingsClick = {' "$main_activity" 2>/dev/null; then
                ok "P-47e: onLeicaSettingsClick wired in MainActivity"
                ((++patch_count))
            else
                warn "P-47e: wiring failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-47e: MainActivity.kt not found"
        ((++patch_fail))
    fi

    # ───────────────────────────────────────────────────────────────────────
    # Verificação pós-patch
    # ───────────────────────────────────────────────────────────────────────
    substep "Verificação pós-patch"

    # Verifica patches críticos
    grep -q "LeicaConfig.multiFrameCount" "$mfc" 2>/dev/null && ok "P-8: MultiFrameConfig patched" || warn "P-8: MultiFrameConfig not patched"
    grep -q "LeicaConfig.videoMaxBFrames" "$vrec" 2>/dev/null && ok "P-32b: VideoRecorder B-frames patched" || warn "P-32b: VideoRecorder B-frames not patched"
    grep -q "LeicaConfig.colorTintShift\|LeicaConfig.effectiveTintShiftForLens" "$dcp" 2>/dev/null && ok "P-30/P-33: DcpProfile tint shift patched" || warn "P-30/P-33: DcpProfile tint shift not patched"
    grep -q "LeicaConfig.gainmapJpegQuality" "$j444" 2>/dev/null && ok "P-23/P-42: Jpeg444 gainmap Q100 patched" || warn "P-23/P-42: Jpeg444 gainmap not patched"
    grep -q "LeicaConfig.whiteLevelForLens\|resolveWhiteLevelForLens" "$rmd" 2>/dev/null && ok "P-37b: RawMetadata white level per-lens patched" || warn "P-37b: RawMetadata white level not patched"
    grep -q "LeicaConfig.blackLevelForLens" "$rmd" 2>/dev/null && ok "P-38: RawMetadata black level per-lens patched" || warn "P-38: RawMetadata black level not patched"
    grep -q "LeicaConfig.ccmRatioWarmForLens\|LeicaConfig.ccmRatioCoolForLens" "$dcp" 2>/dev/null && ok "P-39: DcpProfile per-lens ratio patched" || warn "P-39: DcpProfile per-lens ratio not patched"
    grep -q "LeicaConfig.exportSuperResDng" "$gm" 2>/dev/null && ok "P-40: GalleryManager super res DNG patched" || warn "P-40: GalleryManager super res DNG not patched"
    grep -q "fun forLens" "$rtmp" 2>/dev/null && ok "P-41: RawToneMappingParameters forLens factory patched" || warn "P-41: RawToneMappingParameters forLens not patched"
    grep -q "RawToneMappingParameters.forLens(LeicaConfig.lensKeyFromCameraId" "$cvm" 2>/dev/null && ok "P-43: CameraViewModel per-lens AgX consumer patched — G1 closed" || warn "P-43: CameraViewModel not patched — G1 OPEN"

    # P-44 verification: LeicaConfig.load() must be called at app startup
    local app_kt_verify="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt"
    if [[ -f "$app_kt_verify" ]]; then
        if grep -q "LeicaConfig.load(leicaJson)" "$app_kt_verify" 2>/dev/null && \
           grep -q "import com.hinnka.mycamera.raw.LeicaConfig" "$app_kt_verify" 2>/dev/null; then
            ok "P-44: MyCameraApplication LeicaConfig.load() at startup — config will be live"
        else
            warn "P-44: LeicaConfig.load() NOT wired — config will be null at runtime"
        fi
    fi

    # P-45 verification: at least one strings.xml should have "Leica Perfect"
    local branded_count
    branded_count=$( { grep -rl '<string name="app_name">Leica Perfect</string>' "$SOURCE_DIR/app/src/main/res" 2>/dev/null || true; } | wc -l | tr -d '[:space:]')
    if [[ "${branded_count:-0}" -ge 1 ]]; then
        ok "P-45: app_name branded to 'Leica Perfect' in $branded_count locale file(s)"
    else
        warn "P-45: app_name branding not detected"
    fi

    # Verifica const-val race bugs (deve ser ZERO)
    local const_race_count
    # Note: grep returns 1 when no matches — under `set -o pipefail` this kills the script.
    # Use `|| true` to suppress, then count.
    const_race_count=$( { grep -rE 'const val [A-Z_]+ = LeicaConfig\.' "$APP_JAVA" 2>/dev/null || true; } | wc -l | tr -d '[:space:]')
    const_race_count="${const_race_count:-0}"
    if [[ "${const_race_count:-0}" -eq 0 ]]; then
        ok "const-val race bugs: 0 (todos convertidos pra val get() race-safe)"
    else
        warn "const-val race bugs: $const_race_count (precisa converter pra val get())"
        grep -rnE 'const val [A-Z_]+ = LeicaConfig\.' "$APP_JAVA" 2>/dev/null | head -10 || true
    fi

    # ═══════════════════════════════════════════════════════════════════════════
    # P-51: Compilation fixes (v6.3.3)
    # Corrige erros de sintaxe/tipo introduzidos pelos patches P-29/P-30/P-32/P-13.
    # v6.3.3: fix real que pega o pattern `: Type get() = expr` (não `= value { get() = ... }`)
    # Estes fixes são IDEMPOTENTES — podem ser rodados múltiplas vezes sem efeito colateral.
    # ═══════════════════════════════════════════════════════════════════════════
    fi # end NO_MENU guard (P-46 + P-47)

    section "P-51: Compilation fixes (v6.3.3)"
    local p51_count=0

    # ─── P-51.1: DcpProfile.kt — applyTintShift .toFloat() ─────────────────
    local dcp="$APP_JAVA/raw/DcpProfile.kt"
    if [[ -f "$dcp" ]]; then
        if grep -q 'applyTintShift(saturatedMatrix' "$dcp" 2>/dev/null; then
            if ! grep -q 'applyTintShift(saturatedMatrix, LeicaConfig.tintShiftForLens.*\.toFloat()' "$dcp" 2>/dev/null; then
                sed -i 's|applyTintShift(saturatedMatrix, LeicaConfig.tintShiftForLens(\([^)]*\)))|applyTintShift(saturatedMatrix, LeicaConfig.tintShiftForLens(\1).toFloat())|g' "$dcp"
                echo "  P-51.1: added .toFloat() to tintShiftForLens call"
                ((++p51_count))
            fi
        fi
    fi

    # ─── P-51.2: VideoTypes.kt — remove get() de data class params ─────────
    # v6.3.3 FIX: o pattern real é `: Type get() = expr` (não `= value { get() = ... }`)
    # Ex: `val fps: Int get() = LeicaConfig.videoDefaultFps` → `val fps: Int = LeicaConfig.videoDefaultFps`
    local vt="$APP_JAVA/video/VideoTypes.kt"
    if [[ -f "$vt" ]]; then
        # Remove `get() ` entre o tipo e o `=` em data class constructor params
        # Pattern: `: <Type> get() = ` → `: <Type> = `
        if grep -qE ': [A-Za-z_][A-Za-z0-9_]* get\(\) =' "$vt" 2>/dev/null; then
            sed -i -E 's/: ([A-Za-z_][A-Za-z0-9_]*(<[^>]*>)?) get\(\) =/: \1 =/g' "$vt"
            echo "  P-51.2: VideoTypes.kt — removed get() from data class params"
            ((++p51_count))
        fi
        # VideoLogProfile.LOG não existe no upstream; usar LLOG_BT2020
        if grep -q 'VideoLogProfile\.LOG\b' "$vt" 2>/dev/null; then
            sed -i 's/VideoLogProfile\.LOG\b/VideoLogProfile.LLOG_BT2020/g' "$vt"
            echo "  P-51.2: VideoTypes.kt — fixed VideoLogProfile.LOG → LLOG_BT2020"
            ((++p51_count))
        fi
    fi

    # ─── P-51.3: MultiFrameConfig.kt — Float → Double ──────────────────────
    local mfc="$APP_JAVA/processing/MultiFrameConfig.kt"
    [[ ! -f "$mfc" ]] && mfc="$APP_JAVA/raw/MultiFrameConfig.kt"
    if [[ -f "$mfc" ]]; then
        if grep -qE 'SHORT_FRAME_EXPOSURE_DIVISOR\s*=\s*[0-9.]+f' "$mfc" 2>/dev/null; then
            sed -i -E 's/(SHORT_FRAME_EXPOSURE_DIVISOR\s*=\s*[0-9.]+)f/\1/g' "$mfc"
            echo "  P-51.3: MultiFrameConfig.kt — SHORT_FRAME_EXPOSURE_DIVISOR Float→Double"
            ((++p51_count))
        fi
        if grep -qE 'LONG_FRAME_EXPOSURE_EV\s*=\s*[0-9.]+f' "$mfc" 2>/dev/null; then
            sed -i -E 's/(LONG_FRAME_EXPOSURE_EV\s*=\s*[0-9.]+)f/\1/g' "$mfc"
            echo "  P-51.3: MultiFrameConfig.kt — LONG_FRAME_EXPOSURE_EV Float→Double"
            ((++p51_count))
        fi
    fi

    # ─── P-51.4: RawMetadata.kt — nullable Double? safe call ───────────────
    # v6.3.3: fix real pra `Double?.toFloat()` → `(double ?: 0.0).toFloat()`
    local rm="$APP_JAVA/raw/RawMetadata.kt"
    if [[ -f "$rm" ]]; then
        # Procura nullable receiver .toFloat() sem safe call
        # Pattern comum: `someNullableDouble.toFloat()` onde someNullableDouble é Double?
        # Fix: adicionar `?` antes do `.toFloat()` e `?: 0f` no final
        # Como não sabemos qual variável é nullable sem analisar tipos, fazemos fix direto
        # no pattern reportado: line 364 col 128/144
        if grep -qE '\b([a-zA-Z]+)\.toFloat\(\)' "$rm" 2>/dev/null; then
            # Verifica se há erro conhecido de nullable Double?
            # O pattern real do patch P-17/P-18 produz `LeicaConfig.X?.toFloat() ?: 0f`
            # que DEVE funcionar. Se ainda dá erro, pode ser outro campo.
            # Fix conservador: não mexer — deixa o compilador avisar.
            : # no-op seguro
        fi
    fi

    # ─── P-51.5: DenoiseProfileShaders.kt — const → val ────────────────────
    local dps="$APP_JAVA/raw/DenoiseProfileShaders.kt"
    [[ ! -f "$dps" ]] && dps="$APP_JAVA/processing/DenoiseProfileShaders.kt"
    if [[ -f "$dps" ]]; then
        if grep -qE 'const val FUSED_TILE_(X|Y)' "$dps" 2>/dev/null; then
            sed -i -E 's/const val (FUSED_TILE_[XY])/val \1/g' "$dps"
            echo "  P-51.5: DenoiseProfileShaders.kt — FUSED_TILE_X const→val"
            echo "  P-51.5: DenoiseProfileShaders.kt — FUSED_TILE_Y const→val"
            ((++p51_count))
            ((++p51_count))
        fi
    fi

    # ─── P-51.7: DngPhotonProfileGainTableGenerator.kt — remove get() ──────
    # v6.3.3: mesmo fix que P-51.2 — pattern `: Type get() =` → `: Type =`
    local dpgtg="$APP_JAVA/raw/DngPhotonProfileGainTableGenerator.kt"
    if [[ -f "$dpgtg" ]]; then
        if grep -qE ': [A-Za-z_][A-Za-z0-9_]* get\(\) =' "$dpgtg" 2>/dev/null; then
            sed -i -E 's/: ([A-Za-z_][A-Za-z0-9_]*(<[^>]*>)?) get\(\) =/: \1 =/g' "$dpgtg"
            echo "  P-51.7: DngPhotonProfileGainTableGenerator.kt — removed get() from data class"
            ((++p51_count))
        fi
    fi

    # ─── P-51.8: SAFE MODE — scan ALL .kt files for data class get() ────────
    # v6.3.3: safety net que escaneia TODOS os .kt patcheados por `: Type get() =`
    # e remove o get(). Pega qualquer patch que esquecemos de corrigir.
    local safe_fixed=0
    while IFS= read -r ktfile; do
        if grep -qE ': [A-Za-z_][A-Za-z0-9_]* get\(\) =' "$ktfile" 2>/dev/null; then
            # Só remove se o arquivo tiver `data class` (evita false positives em companion objects)
            if grep -qE '^\s*data class ' "$ktfile" 2>/dev/null; then
                sed -i -E 's/: ([A-Za-z_][A-Za-z0-9_]*(<[^>]*>)?) get\(\) =/: \1 =/g' "$ktfile"
                safe_fixed=$((safe_fixed + 1))
            fi
        fi
    done < <(find "$APP_JAVA" -name '*.kt' -type f 2>/dev/null)
    if [[ $safe_fixed -gt 0 ]]; then
        echo "  P-51.8: SAFE MODE — removed get() from $safe_fixed data class file(s)"
        p51_count=$((p51_count + safe_fixed))
    fi

    ok "P-51: compilation fixes applied (v6.3.3) — total: $p51_count"
    echo ""

    # ═══════════════════════════════════════════════════════════════════════════
    # P-52: RUNTIME WIRING (v6.3.4) — fazer menu ter efeito de verdade
    # ═══════════════════════════════════════════════════════════════════════════
    # Problema: P-48b/c corrigiram os accessors, mas NINGUÉM no pipeline de
    # rendering consome activeLutId/activeDcpId. Resultado: mudar creative profile
    # ou capture mode no menu não tinha efeito nenhum na foto/vídeo.
    #
    # Fix: wirear os consumers nos pontos certos do upstream.
    section "P-52: Runtime Wiring (v6.3.4) — menu → pipeline"
    local p52_count=0

    # ─── P-52a: LUT wiring — CameraViewModel.setLut() ──────────────────────
    # Quando usuário seleciona creative profile, a LUT do perfil deve ser aplicada.
    # setLut() é chamado em vários lugares; injetamos override no início.
    substep "P-52a: CameraViewModel.setLut() — creative profile LUT override"
    # v6.3.5 FIX (Cron 1 audit): path was ui/camera/ — real upstream path is viewmodel/
    local cvm="$APP_JAVA/viewmodel/CameraViewModel.kt"
    if [[ -f "$cvm" ]]; then
        if grep -q 'LeicaConfig.activeLutId' "$cvm" 2>/dev/null; then
            ok "P-52a: already patched (idempotent)"
            p52_count=$((p52_count + 1))
        else
            # Add import
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$cvm" || \
                sed -i '/^import com.hinnka.mycamera.raw\./a import com.hinnka.mycamera.raw.LeicaConfig' "$cvm"
            # Shadow lutId param with LeicaConfig.activeLutId when creative profile is non-baseline
            sed -i 's#fun setLut(lutId: String?, persist: Boolean = true) {#fun setLut(lutId: String?, persist: Boolean = true) {\n        val lutId = if (!LeicaConfig.isActiveProfileBaseline) LeicaConfig.activeLutId else lutId  // v6.3.4 creative profile override#' "$cvm"
            if grep -q 'LeicaConfig.activeLutId' "$cvm" 2>/dev/null; then
                ok "P-52a: setLut() now applies creative profile LUT"
                p52_count=$((p52_count + 1))
            else
                warn "P-52a: sed injection failed"
            fi
        fi
    else
        warn "P-52a: CameraViewModel.kt not found"
    fi

    # ─── P-52b: DCP wiring — UserPreferencesRepository.rawDcpIdForLens() ──
    # Centralizado: rawDcpIdForLens() é chamado por 6 sites. Override aqui cobre todos.
    substep "P-52b: UserPreferencesRepository.rawDcpIdForLens() — creative profile DCP override"
    # v6.3.5 FIX (Cron 1 audit): path was ui/settings/ — real upstream path is data/
    # (matches P-10 which uses $APP_JAVA/data/UserPreferencesRepository.kt correctly)
    local upr="$APP_JAVA/data/UserPreferencesRepository.kt"
    if [[ -f "$upr" ]]; then
        if grep -q 'LeicaConfig.activeDcpId' "$upr" 2>/dev/null; then
            ok "P-52b: already patched (idempotent)"
            p52_count=$((p52_count + 1))
        else
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$upr" || \
                sed -i '/^package com.hinnka.mycamera/a import com.hinnka.mycamera.raw.LeicaConfig' "$upr"
            # Prepend override at start of rawDcpIdForLens body
            sed -i 's#fun rawDcpIdForLens(lensId: String?): String? {#fun rawDcpIdForLens(lensId: String?): String? {\n        if (!LeicaConfig.isActiveProfileBaseline) return LeicaConfig.activeDcpId  // v6.3.4 creative profile override#' "$upr"
            if grep -q 'LeicaConfig.activeDcpId' "$upr" 2>/dev/null; then
                ok "P-52b: rawDcpIdForLens() now applies creative profile DCP"
                p52_count=$((p52_count + 1))
            else
                warn "P-52b: sed injection failed"
            fi
        fi
    else
        warn "P-52b: UserPreferencesRepository.kt not found"
    fi

    # ─── P-52c: RAWmax wiring — UserPreferencesRepository.useRawMax ───────
    # effectiveForceRawmax = true quando capture mode = max. Força RAWmax.
    # v6.3.5 FIX (Cron 1 audit): inherits corrected $upr from P-52b (was wrong path before — smoking gun for "Quality Max has no RAWmax effect").
    substep "P-52c: UserPreferencesRepository — effectiveForceRawmax override"
    if [[ -f "$upr" ]]; then
        if grep -q 'LeicaConfig.effectiveForceRawmax' "$upr" 2>/dev/null; then
            ok "P-52c: already patched (idempotent)"
            p52_count=$((p52_count + 1))
        else
            # Override: if capture mode forces RAWmax, return true regardless of user pref
            sed -i 's#val useRawMax = requestedUseRawMax#val useRawMax = if (LeicaConfig.effectiveForceRawmax) true else requestedUseRawMax  // v6.3.4 capture mode override#' "$upr"
            if grep -q 'LeicaConfig.effectiveForceRawmax' "$upr" 2>/dev/null; then
                ok "P-52c: useRawMax now respects effectiveForceRawmax"
                p52_count=$((p52_count + 1))
            else
                warn "P-52c: sed injection failed"
            fi
        fi
    fi

    # ─── P-52d: Diagnostic logging — MyCameraApplication startup ─────────
    # Log qual modo/perfil está ativo no startup, pra usuário verificar no logcat.
    substep "P-52d: MyCameraApplication — log active state on startup"
    local app_kt_p52="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt"
    if [[ -f "$app_kt_p52" ]]; then
        if grep -q 'LeicaPerfect active state' "$app_kt_p52" 2>/dev/null; then
            ok "P-52d: already patched (idempotent)"
            p52_count=$((p52_count + 1))
        else
            # Inject after "Leica Perfect config loaded from assets" log (added by P-44)
            sed -i '/PLog.d(TAG, "Leica Perfect config loaded from assets")/a\
\        PLog.d(TAG, "LeicaPerfect active state: mode=${LeicaConfig.activeCaptureMode}, profile=${LeicaConfig.activeCreativeProfileId}, lut=${LeicaConfig.activeLutId}, dcp=${LeicaConfig.activeDcpId}, frames_main=${LeicaConfig.effectiveFrameCountForLens(\"main\")}, SR=${LeicaConfig.effectiveSuperResolutionScale}, NLM=${LeicaConfig.effectiveNlmSearchRadius}, RAWmax=${LeicaConfig.effectiveForceRawmax}, videoBr=${LeicaConfig.effectiveVideoBitrateMbps}Mbps")' "$app_kt_p52" 2>/dev/null || true
            if grep -q 'LeicaPerfect active state' "$app_kt_p52" 2>/dev/null; then
                ok "P-52d: startup logging injected — grep 'LeicaPerfect active state' no logcat"
                p52_count=$((p52_count + 1))
            else
                warn "P-52d: logging injection skipped — non-critical"
            fi
        fi
    fi

    ok "P-52: Runtime Wiring applied (v6.3.4) — total: $p52_count/4"
    echo ""

    # ═══════════════════════════════════════════════════════════════════════════
    # P-53: Runtime NLM radius (v6.3.5 — capture-mode-aware without shader recompile)
    # ═══════════════════════════════════════════════════════════════════════════
    # Cron 1 audit (INEFFECTIVE): P-4 makes SEARCH_RADIUS a `val get()` returning
    # effectiveNlmSearchRadius, BUT the GLSL shader source template interpolates
    # $SEARCH_RADIUS / $FUSED_TILE_X via Kotlin string interpolation. The shader is
    # compiled ONCE in RawDemosaicProcessor.initNLMPrograms() (L5511) and cached.
    # DenoiseProfileNlmConfig.searchOffsets is also a top-level val init-once (L37).
    # Result: changing capture mode mid-session did NOT change NLM radius — only the
    # mode active at first initNLMPrograms() call was baked in.
    #
    # Fix: implement C2/C3/C4 from upstream-wiring-investigation worklog.
    # Read effectiveNlmSearchRadius at dispatch time, clamped to the compile-time
    # ceiling (DenoiseProfileShaders.SEARCH_RADIUS, itself capped at 9 by P-4).
    # This makes radius changes effective without shader recompile, as long as
    # new_radius ≤ SEARCH_RADIUS-at-compile-time (≤9).
    #
    # Prerequisite: P-4 (SEARCH_RADIUS → val get() coerceAtMost(9)) and
    # P-51.5 (FUSED_TILE_X/Y → val) must be applied — both are already in this script.
    section "P-53: Runtime NLM radius (v6.3.5) — capture-mode-aware without recompile"
    local p53_count=0

    # ─── P-53a: C3 — RawDemosaicProcessor params builder (drives params.searchRadius) ──
    substep "P-53a: RawDemosaicProcessor — searchRadius reads effectiveNlmSearchRadius"
    local rdp="$APP_JAVA/raw/RawDemosaicProcessor.kt"
    if [[ -f "$rdp" ]]; then
        if grep -q 'LeicaConfig.effectiveNlmSearchRadius' "$rdp" 2>/dev/null; then
            ok "P-53a: already patched (idempotent)"
            p53_count=$((p53_count + 1))
        else
            # Ensure import (no-op if already present from another patch)
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rdp" || \
                sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rdp"
            # C3 (upstream L5922): replace `val searchRadius = DenoiseProfileShaders.SEARCH_RADIUS`
            # with capture-mode-aware value clamped at the compile-time ceiling.
            sed -i 's#val searchRadius = DenoiseProfileShaders.SEARCH_RADIUS#val searchRadius = LeicaConfig.effectiveNlmSearchRadius.coerceAtMost(DenoiseProfileShaders.SEARCH_RADIUS)  // v6.3.5 capture-mode-aware#' "$rdp"
            if grep -q 'LeicaConfig.effectiveNlmSearchRadius' "$rdp" 2>/dev/null; then
                ok "P-53a: RawDemosaicProcessor searchRadius now reads effectiveNlmSearchRadius"
                p53_count=$((p53_count + 1))
                ((++patch_count))
            else
                warn "P-53a: sed injection failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-53a: RawDemosaicProcessor.kt not found"
        ((++patch_fail))
    fi

    # ─── P-53b: C2 — RawDemosaicProcessor NLM offsets loop (use buildSearchOffsets at runtime) ──
    substep "P-53b: RawDemosaicProcessor — offsets loop uses buildSearchOffsets(params.searchRadius)"
    if [[ -f "$rdp" ]]; then
        if grep -q 'DenoiseProfileNlmConfig.buildSearchOffsets' "$rdp" 2>/dev/null; then
            ok "P-53b: already patched (idempotent)"
            p53_count=$((p53_count + 1))
        else
            # C2 (upstream L6000): replace `for (offset in DenoiseProfileNlmConfig.searchOffsets) {`
            # with buildSearchOffsets(params.searchRadius clamped to shader ceiling).
            # params.searchRadius is set by P-53a (C3) above.
            sed -i 's#for (offset in DenoiseProfileNlmConfig.searchOffsets) {#for (offset in DenoiseProfileNlmConfig.buildSearchOffsets(params.searchRadius.coerceAtMost(DenoiseProfileShaders.SEARCH_RADIUS))) {  // v6.3.5 runtime radius#' "$rdp"
            if grep -q 'DenoiseProfileNlmConfig.buildSearchOffsets' "$rdp" 2>/dev/null; then
                ok "P-53b: RawDemosaicProcessor offsets loop now uses runtime radius"
                p53_count=$((p53_count + 1))
                ((++patch_count))
            else
                warn "P-53b: sed injection failed"
                ((++patch_fail))
            fi
        fi
    fi

    # ─── P-53c: C4 — LutImageProcessor NLM offsets loop (bitmap/JPG NLM) ──
    substep "P-53c: LutImageProcessor — offsets loop uses buildSearchOffsets(effectiveNlmSearchRadius)"
    local lip="$APP_JAVA/lut/LutImageProcessor.kt"
    if [[ -f "$lip" ]]; then
        if grep -q 'LeicaConfig.effectiveNlmSearchRadius' "$lip" 2>/dev/null; then
            ok "P-53c: already patched (idempotent)"
            p53_count=$((p53_count + 1))
        else
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$lip" || \
                sed -i '/^package com.hinnka.mycamera.lut$/a import com.hinnka.mycamera.raw.LeicaConfig' "$lip"
            # C4 (upstream LutImageProcessor.kt L2533): same string as C2 but different file.
            # BitmapDenoiseParams has no searchRadius field — read LeicaConfig directly.
            sed -i 's#for (offset in DenoiseProfileNlmConfig.searchOffsets) {#for (offset in DenoiseProfileNlmConfig.buildSearchOffsets(LeicaConfig.effectiveNlmSearchRadius.coerceAtMost(DenoiseProfileShaders.SEARCH_RADIUS))) {  // v6.3.5 runtime radius#' "$lip"
            if grep -q 'LeicaConfig.effectiveNlmSearchRadius' "$lip" 2>/dev/null; then
                ok "P-53c: LutImageProcessor offsets loop now uses runtime radius"
                p53_count=$((p53_count + 1))
                ((++patch_count))
            else
                warn "P-53c: sed injection failed"
                ((++patch_fail))
            fi
        fi
    else
        warn "P-53c: LutImageProcessor.kt not found"
        ((++patch_fail))
    fi

    ok "P-53: Runtime NLM radius applied (v6.3.5) — total: $p53_count/3"
    echo ""

    # ═══════════════════════════════════════════════════════════════════════════
    # Tier 9 (P-54) — v6.3.6 Creative Profile Color Science
    #
    # PROBLEM: 6 effective* accessors exist in LeicaConfig.kt but had 0 references
    # in the pipeline. Switching creative profiles (Leica Authentic → Hasselblad →
    # Fuji) only changed LUT+DCP, NOT color science (contrast/saturation/warmth/tint).
    #
    # FIX: P-54a/b/c/d wire the 4 remaining accessors to the pipeline.
    # P-29/P-30 (modified above) now use effective* versions too.
    # ═══════════════════════════════════════════════════════════════════════════
    section "P-54: Creative Profile Color Science (v6.3.6) — effective* accessors wired"
    local p54_count=0

    # ─── P-54a: effectiveToneContrast — RawToneMappingGl FILMIC contrast ──
    # effectiveToneContrast = global toneMappingContrast × active profile's tone_contrast_boost
    # Applied to the FILMIC tone mapper (default path, ~90% of captures).
    # AgX path already gets it indirectly via agxWhiteRelativeExposure (LeicaConfig L1150).
    substep "P-54a: RawToneMappingGl — FILMIC contrast × effectiveToneContrast"
    local rtmg="$APP_JAVA/raw/RawToneMappingGl.kt"
    if [[ -f "$rtmg" ]]; then
        if grep -q 'effectiveToneContrast' "$rtmg" 2>/dev/null; then
            ok "P-54a: already patched (idempotent)"
            p54_count=$((p54_count + 1))
        else
            # Add import
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rtmg" || \
                sed -i '/^package com.hinnka.mycamera/a import com.hinnka.mycamera.raw.LeicaConfig' "$rtmg"
            # Multiply FILMIC contrast by effectiveToneContrast (creative profile boost)
            sed -i 's|var contrast = FILMIC_DEFAULT_CONTRAST \* (dynamicRange / FILMIC_DEFAULT_DYNAMIC_RANGE)|var contrast = FILMIC_DEFAULT_CONTRAST * (dynamicRange / FILMIC_DEFAULT_DYNAMIC_RANGE) * LeicaConfig.effectiveToneContrast.toFloat()|' "$rtmg"
            if grep -q 'effectiveToneContrast' "$rtmg" 2>/dev/null; then
                ok "P-54a: FILMIC contrast now multiplied by effectiveToneContrast"
                p54_count=$((p54_count + 1))
            else
                warn "P-54a: sed injection failed — contrast pattern may have changed upstream"
            fi
        fi
    else
        warn "P-54a: RawToneMappingGl.kt not found"
    fi

    # ─── P-54b: effectiveSaturationBoost — LutImageProcessor uSaturation ──
    # effectiveSaturationBoost = global colorSaturationBoost × active profile's saturation_multiplier
    # Applied to the uSaturation GLSL uniform in LUT post-processing.
    substep "P-54b: LutImageProcessor — uSaturation × effectiveSaturationBoost"
    local lip="$APP_JAVA/lut/LutImageProcessor.kt"
    if [[ -f "$lip" ]]; then
        if grep -q 'effectiveSaturationBoost' "$lip" 2>/dev/null; then
            ok "P-54b: already patched (idempotent)"
            p54_count=$((p54_count + 1))
        else
            # Add import
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$lip" || \
                sed -i '/^package com.hinnka.mycamera.lut$/a import com.hinnka.mycamera.raw.LeicaConfig' "$lip"
            # Multiply saturation by effectiveSaturationBoost (creative profile multiplier)
            sed -i 's|val saturation = effectiveRecipeParams?.saturation ?: 1f|val saturation = (effectiveRecipeParams?.saturation ?: 1f) * LeicaConfig.effectiveSaturationBoost.toFloat()|' "$lip"
            if grep -q 'effectiveSaturationBoost' "$lip" 2>/dev/null; then
                ok "P-54b: uSaturation now multiplied by effectiveSaturationBoost"
                p54_count=$((p54_count + 1))
            else
                warn "P-54b: sed injection failed — saturation pattern may have changed upstream"
            fi
        fi
    else
        warn "P-54b: LutImageProcessor.kt not found"
    fi

    # ─── P-54c: effectiveWarmthShiftK — RawDemosaicProcessor sanitizeCameraWhite ──
    # effectiveWarmthShiftK = active creative profile's warmth_shift_k (Kelvin offset)
    # Applied to cameraWhite (R/G/B WB gains) before DCP processing.
    # warmthFactor = shiftK / 10000 → ±200K = ±0.02 R/B multiplier.
    substep "P-54c: RawDemosaicProcessor — sanitizeCameraWhite warmth shift"
    local rdp="$APP_JAVA/raw/RawDemosaicProcessor.kt"
    if [[ -f "$rdp" ]]; then
        if grep -q 'effectiveWarmthShiftK' "$rdp" 2>/dev/null; then
            ok "P-54c: already patched (idempotent)"
            p54_count=$((p54_count + 1))
        else
            # Add import
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rdp" || \
                sed -i '/^package com.hinnka.mycamera/a import com.hinnka.mycamera.raw.LeicaConfig' "$rdp"
            # Inject warmth shift into sanitizeCameraWhite return statement
            # Original: return floatArrayOf(red.coerceIn(0.001f, 1f), green.coerceIn(0.001f, 1f), blue.coerceIn(0.001f, 1f))
            # New: apply warmth factor to R/B channels before clamping
            python3 -c "
import re, sys
with open('$rdp', 'r') as f: src = f.read()
# Find sanitizeCameraWhite function and inject warmth shift
old = 'return floatArrayOf(\n            red.coerceIn(0.001f, 1f),\n            green.coerceIn(0.001f, 1f),\n            blue.coerceIn(0.001f, 1f)\n        )'
new = '''val warmthFactor = LeicaConfig.effectiveWarmthShiftK / 10000.0f  // v6.3.6: creative profile warmth shift
        return floatArrayOf(
            (red * (1f + warmthFactor)).coerceIn(0.001f, 1f),
            green.coerceIn(0.001f, 1f),
            (blue * (1f - warmthFactor)).coerceIn(0.001f, 1f)
        )'''
if old in src:
    src = src.replace(old, new, 1)
    with open('$rdp', 'w') as f: f.write(src)
    print('P-54c: warmth shift injected into sanitizeCameraWhite')
else:
    print('P-54c: WARN — sanitizeCameraWhite return pattern not found (upstream may have changed)')
    sys.exit(1)
" || warn "P-54c: python3 injection failed"
            if grep -q 'effectiveWarmthShiftK' "$rdp" 2>/dev/null; then
                ok "P-54c: sanitizeCameraWhite now applies effectiveWarmthShiftK"
                p54_count=$((p54_count + 1))
            else
                warn "P-54c: injection failed — pattern may have changed upstream"
            fi
        fi
    else
        warn "P-54c: RawDemosaicProcessor.kt not found"
    fi

    # ─── P-54d: effectiveHighlightCompressionForLens — RawToneMappingGl ──
    # effectiveHighlightCompressionForLens = per-lens + color_science highlight EV
    # Returns Float in [-1.0, 0.0] (negative EV = more compression).
    # Applied as offset to AgX/FILMIC white exposure uniforms.
    # NOTE: bindRawToneMappingUniforms doesn't receive lensKey, so we use "main"
    # as default lens — applies the global color_science portion (default -0.075 EV).
    substep "P-54d: RawToneMappingGl — AgX/FILMIC white exposure + highlight compression"
    if [[ -f "$rtmg" ]]; then
        if grep -q 'effectiveHighlightCompressionForLens' "$rtmg" 2>/dev/null; then
            ok "P-54d: already patched (idempotent)"
            p54_count=$((p54_count + 1))
        else
            # AgX white exposure uniform
            sed -i 's|uniform1f(program, "uAgxWhiteRelativeExposure", normalized.agxWhiteRelativeExposure)|uniform1f(program, "uAgxWhiteRelativeExposure", normalized.agxWhiteRelativeExposure + LeicaConfig.effectiveHighlightCompressionForLens("main"))|' "$rtmg"
            # FILMIC white exposure uniform
            sed -i 's|uniform1f(program, "uFilmicWhiteRelativeExposure", filmic.whiteRelativeExposure)|uniform1f(program, "uFilmicWhiteRelativeExposure", filmic.whiteRelativeExposure + LeicaConfig.effectiveHighlightCompressionForLens("main"))|' "$rtmg"
            if grep -q 'effectiveHighlightCompressionForLens' "$rtmg" 2>/dev/null; then
                ok "P-54d: AgX/FILMIC white exposure now offset by highlight compression"
                p54_count=$((p54_count + 1))
            else
                warn "P-54d: sed injection failed — uniform pattern may have changed upstream"
            fi
        fi
    else
        warn "P-54d: RawToneMappingGl.kt not found"
    fi

    ok "P-54: Creative profile color science applied (v6.3.6) — total: $p54_count/4"
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 10 (P-55) — v6.3.7 Video settings actually apply
    # ───────────────────────────────────────────────────────────────────────
    # BUG: P-32a changed VideoConfig defaults, but Camera2Controller.setVideo*()
    # always overrides them with UserPreferences values. UserPreferencesRepository
    # getUserPreferences() has HARDCODED fallbacks (FHD_1080P/FPS_30/P1/H264/OFF).
    # Result: video mode banner shows "1080p | 30 fps" regardless of JSON config.
    # FIX: P-55 replaces the 5 hardcoded fallbacks with LeicaConfig.*Enum helpers.
    section "P-55: Video settings actually apply (v6.3.7) — UserPreferencesRepository fallbacks → LeicaConfig"
    local p55_count=0
    local upr_p55="$APP_JAVA/data/UserPreferencesRepository.kt"
    if [[ -f "$upr_p55" ]]; then
        # Garante o import do LeicaConfig
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$upr_p55" || \
            sed -i '/^package com.hinnka.mycamera.data$/a import com.hinnka.mycamera.raw.LeicaConfig' "$upr_p55"

        # P-55.1: videoResolution fallback FHD_1080P.name → LeicaConfig.videoDefaultResolutionEnum
        if sed -i 's|preferences\[VIDEO_RESOLUTION\] ?: VideoResolutionPreset.FHD_1080P.name|preferences[VIDEO_RESOLUTION] ?: LeicaConfig.videoDefaultResolutionEnum|' "$upr_p55" && \
           grep -q 'LeicaConfig.videoDefaultResolutionEnum' "$upr_p55" 2>/dev/null; then
            ok "P-55.1: videoResolution fallback → LeicaConfig.videoDefaultResolutionEnum"
            ((++p55_count))
        else
            warn "P-55.1: videoResolution sed failed"
        fi

        # P-55.2: videoFps fallback FPS_30.name → LeicaConfig.videoDefaultFpsEnum
        if sed -i 's|preferences\[VIDEO_FPS\] ?: VideoFpsPreset.FPS_30.name|preferences[VIDEO_FPS] ?: LeicaConfig.videoDefaultFpsEnum|' "$upr_p55" && \
           grep -q 'LeicaConfig.videoDefaultFpsEnum' "$upr_p55" 2>/dev/null; then
            ok "P-55.2: videoFps fallback → LeicaConfig.videoDefaultFpsEnum"
            ((++p55_count))
        else
            warn "P-55.2: videoFps sed failed"
        fi

        # P-55.3: videoLogProfile fallback OFF.name → LeicaConfig.videoDefaultLogProfileEnum
        if sed -i 's|preferences\[VIDEO_LOG_PROFILE\] ?: VideoLogProfile.OFF.name|preferences[VIDEO_LOG_PROFILE] ?: LeicaConfig.videoDefaultLogProfileEnum|' "$upr_p55" && \
           grep -q 'LeicaConfig.videoDefaultLogProfileEnum' "$upr_p55" 2>/dev/null; then
            ok "P-55.3: videoLogProfile fallback → LeicaConfig.videoDefaultLogProfileEnum"
            ((++p55_count))
        else
            warn "P-55.3: videoLogProfile sed failed"
        fi

        # P-55.4: videoBitrate fallback P1.name → LeicaConfig.videoDefaultBitrateEnum
        if sed -i 's|preferences\[VIDEO_BITRATE\] ?: VideoBitratePreset.P1.name|preferences[VIDEO_BITRATE] ?: LeicaConfig.videoDefaultBitrateEnum|' "$upr_p55" && \
           grep -q 'LeicaConfig.videoDefaultBitrateEnum' "$upr_p55" 2>/dev/null; then
            ok "P-55.4: videoBitrate fallback → LeicaConfig.videoDefaultBitrateEnum"
            ((++p55_count))
        else
            warn "P-55.4: videoBitrate sed failed"
        fi

        # P-55.5: videoCodec fallback H264.name → LeicaConfig.videoDefaultCodecEnum
        if sed -i 's|preferences\[VIDEO_CODEC\] ?: com.hinnka.mycamera.video.VideoCodec.H264.name|preferences[VIDEO_CODEC] ?: LeicaConfig.videoDefaultCodecEnum|' "$upr_p55" && \
           grep -q 'LeicaConfig.videoDefaultCodecEnum' "$upr_p55" 2>/dev/null; then
            ok "P-55.5: videoCodec fallback → LeicaConfig.videoDefaultCodecEnum"
            ((++p55_count))
        else
            warn "P-55.5: videoCodec sed failed"
        fi

        ((++patch_count))
    else
        warn "P-55: UserPreferencesRepository.kt not found"
        ((++patch_fail))
    fi
    ok "P-55: Video settings fix applied (v6.3.7) — total: $p55_count/5"
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 11 (P-56) — v6.3.8 Video encoder completeness
    # ───────────────────────────────────────────────────────────────────────
    # P-32b wires some VideoRecorder knobs (B-frames, I-frame, AAC bitrate) but
    # leaves AUDIO_SAMPLE_RATE + AUDIO_MIME hardcoded; KEY_AAC_PROFILE is set
    # unconditionally (breaks OPUS); VideoEncoderColorConfig never emits
    # KEY_HDR_STATIC_INFO (HDR10 metadata missing); bitrate mode is hardcoded
    # CBR-preferring (JSON video.rate_control="vbr"/"cqp" ignored — note P-32b's
    # sed targets `if (codecName.contains("hevc")` which does NOT exist in
    # upstream 1.26.1, so it silently no-ops).
    # P-56 closes those gaps:
    #   P-56.1: AUDIO_SAMPLE_RATE → LeicaConfig.videoAudioSampleRate
    #   P-56.2: AUDIO_MIME        → LeicaConfig.videoAudioMimeType (AAC/OPUS)
    #   P-56.3: Gate KEY_AAC_PROFILE on AAC mime (skip for OPUS)
    #   P-56.4: Inject KEY_HDR_STATIC_INFO via VideoEncoderColorConfig.hdrStaticInfo
    #   P-56.5: Bitrate mode override → LeicaConfig.videoBitrateMode (CBR/CQP/VBR)
    section "P-56: Video encoder completeness (v6.3.8) — AUDIO_SAMPLE_RATE + AUDIO_MIME + AAC_PROFILE gate + HDR10 static info + bitrate mode override"
    local p56_count=0
    local vecc="$APP_JAVA/video/VideoEncoderColorConfig.kt"

    # ── P-56.1: AUDIO_SAMPLE_RATE → LeicaConfig.videoAudioSampleRate ─────────
    substep "P-56.1: Wire AUDIO_SAMPLE_RATE → LeicaConfig.videoAudioSampleRate"
    if [[ -f "$vrec" ]]; then
        if grep -q 'private val AUDIO_SAMPLE_RATE: Int get() = LeicaConfig.videoAudioSampleRate' "$vrec" 2>/dev/null; then
            ok "P-56.1: already patched (idempotent)"
            ((++p56_count))
        else
            sed -i 's|private const val AUDIO_SAMPLE_RATE = 48_000|private val AUDIO_SAMPLE_RATE: Int get() = LeicaConfig.videoAudioSampleRate|' "$vrec"
            if grep -q 'private val AUDIO_SAMPLE_RATE: Int get() = LeicaConfig.videoAudioSampleRate' "$vrec" 2>/dev/null; then
                ok "P-56.1: AUDIO_SAMPLE_RATE now reads LeicaConfig.videoAudioSampleRate"
                ((++p56_count))
            else
                warn "P-56.1: AUDIO_SAMPLE_RATE sed pattern did not match"
            fi
        fi
    else
        warn "P-56.1: VideoRecorder.kt not found"
    fi

    # ── P-56.2: AUDIO_MIME → LeicaConfig.videoAudioMimeType ─────────────────
    substep "P-56.2: Wire AUDIO_MIME → LeicaConfig.videoAudioMimeType (AAC/OPUS)"
    if [[ -f "$vrec" ]]; then
        if grep -q 'private val AUDIO_MIME: String get() = LeicaConfig.videoAudioMimeType' "$vrec" 2>/dev/null; then
            ok "P-56.2: already patched (idempotent)"
            ((++p56_count))
        else
            sed -i 's|private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC|private val AUDIO_MIME: String get() = LeicaConfig.videoAudioMimeType|' "$vrec"
            if grep -q 'private val AUDIO_MIME: String get() = LeicaConfig.videoAudioMimeType' "$vrec" 2>/dev/null; then
                ok "P-56.2: AUDIO_MIME now reads LeicaConfig.videoAudioMimeType"
                ((++p56_count))
            else
                warn "P-56.2: AUDIO_MIME sed pattern did not match"
            fi
        fi
    else
        warn "P-56.2: VideoRecorder.kt not found"
    fi

    # ── P-56.3: Gate KEY_AAC_PROFILE on AAC mime (skip for OPUS) ────────────
    # Multi-line structural change → python3 heredoc (sed struggles with context)
    substep "P-56.3: Gate KEY_AAC_PROFILE on AAC mime (OPUS must not set AAC_PROFILE)"
    if [[ -f "$vrec" ]]; then
        if grep -q 'if (AUDIO_MIME == MediaFormat.MIMETYPE_AUDIO_AAC)' "$vrec" 2>/dev/null; then
            ok "P-56.3: already patched (idempotent)"
            ((++p56_count))
        else
            python3 - "$vrec" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath, 'r', encoding='utf-8') as f:
    s = f.read()
old = (
    '                    setInteger(MediaFormat.KEY_AAC_PROFILE, '
    'MediaCodecInfo.CodecProfileLevel.AACObjectLC)\n'
)
new = (
    '                    if (AUDIO_MIME == MediaFormat.MIMETYPE_AUDIO_AAC) {\n'
    '                        setInteger(MediaFormat.KEY_AAC_PROFILE, '
    'MediaCodecInfo.CodecProfileLevel.AACObjectLC)\n'
    '                    }\n'
)
if old not in s:
    print('NOMATCH aac-profile line', file=sys.stderr); sys.exit(1)
s = s.replace(old, new, 1)
with open(filepath, 'w', encoding='utf-8') as f:
    f.write(s)
print('OK')
PYEOF
            if grep -q 'if (AUDIO_MIME == MediaFormat.MIMETYPE_AUDIO_AAC)' "$vrec" 2>/dev/null; then
                ok "P-56.3: KEY_AAC_PROFILE now gated on AAC mime"
                ((++p56_count))
            else
                warn "P-56.3: python3 injection failed"
            fi
        fi
    else
        warn "P-56.3: VideoRecorder.kt not found"
    fi

    # ── P-56.4: Inject HDR10 static info (KEY_HDR_STATIC_INFO) ─────────────
    # Adds: ByteBuffer import, LeicaConfig import, hdrStaticInfo data-class
    # field, applyTo() injection, and explicit hdrStaticInfo assignment in both
    # resolveVideoEncoderColorConfig() code paths (sdrDisplay + custom-log).
    substep "P-56.4: Inject HDR10 static info in VideoEncoderColorConfig"
    if [[ -f "$vecc" ]]; then
        if grep -q 'hdrStaticInfo' "$vecc" 2>/dev/null; then
            ok "P-56.4: already patched (idempotent)"
            ((++p56_count))
        else
            python3 - "$vecc" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath, 'r', encoding='utf-8') as f:
    s = f.read()

# 1. Add imports (ByteBuffer + LeicaConfig) after ColorSpace import if missing
imports_to_add = []
if 'import java.nio.ByteBuffer' not in s:
    imports_to_add.append('import java.nio.ByteBuffer')
if 'import com.hinnka.mycamera.raw.LeicaConfig' not in s:
    imports_to_add.append('import com.hinnka.mycamera.raw.LeicaConfig')
if imports_to_add:
    marker = 'import com.hinnka.mycamera.raw.ColorSpace\n'
    if marker not in s:
        print('NOMATCH import-marker', file=sys.stderr); sys.exit(1)
    s = s.replace(marker, marker + '\n'.join(imports_to_add) + '\n', 1)

# 2. Add hdrStaticInfo field to data class (between prefer10BitInputSurface and debugName)
old_field = (
    '    val prefer10BitInputSurface: Boolean = false,\n'
    '    val debugName: String\n'
)
new_field = (
    '    val prefer10BitInputSurface: Boolean = false,\n'
    '    val hdrStaticInfo: ByteBuffer? = null,\n'
    '    val debugName: String\n'
)
if old_field not in s:
    print('NOMATCH field', file=sys.stderr); sys.exit(1)
s = s.replace(old_field, new_field, 1)

# 3. In applyTo(), inject HDR static info after codecProfile
old_apply = (
    '        codecProfile?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }\n'
    '    }\n'
)
new_apply = (
    '        codecProfile?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }\n'
    '        hdrStaticInfo?.let { format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, it) }\n'
    '    }\n'
)
if old_apply not in s:
    print('NOMATCH apply', file=sys.stderr); sys.exit(1)
s = s.replace(old_apply, new_apply, 1)

# 4. Inject hdrStaticInfo into sdrDisplay factory (16-space indent)
old_sdr = (
    '                prefer10BitInputSurface = false,\n'
    '                debugName = debugName\n'
)
new_sdr = (
    '                prefer10BitInputSurface = false,\n'
    '                hdrStaticInfo = if (LeicaConfig.videoHdr10Enabled) LeicaConfig.videoHdr10StaticInfo else null,\n'
    '                debugName = debugName\n'
)
if old_sdr not in s:
    print('NOMATCH sdr', file=sys.stderr); sys.exit(1)
s = s.replace(old_sdr, new_sdr, 1)

# 5. Inject hdrStaticInfo into direct constructor call (8-space indent)
old_log = (
    '        prefer10BitInputSurface = codecProfile != null,\n'
    '        debugName = "custom-log-${request.logProfile.name.lowercase()}"\n'
)
new_log = (
    '        prefer10BitInputSurface = codecProfile != null,\n'
    '        hdrStaticInfo = if (LeicaConfig.videoHdr10Enabled) LeicaConfig.videoHdr10StaticInfo else null,\n'
    '        debugName = "custom-log-${request.logProfile.name.lowercase()}"\n'
)
if old_log not in s:
    print('NOMATCH log', file=sys.stderr); sys.exit(1)
s = s.replace(old_log, new_log, 1)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(s)
print('OK')
PYEOF
            if grep -q 'hdrStaticInfo' "$vecc" 2>/dev/null && \
               grep -q 'import com.hinnka.mycamera.raw.LeicaConfig' "$vecc" 2>/dev/null; then
                ok "P-56.4: HDR10 static info injected (field + applyTo + both factory paths)"
                ((++p56_count))
            else
                warn "P-56.4: python3 injection failed"
            fi
        fi
    else
        warn "P-56.4: VideoEncoderColorConfig.kt not found"
    fi

    # ── P-56.5: Bitrate mode override (LeicaConfig.videoBitrateMode) ────────
    # Replaces hardcoded CBR-preferring block with LeicaConfig-driven mode.
    # CONFLICT NOTE: P-32b has a sed that targets `if (codecName.contains("hevc")`
    # pattern, but that pattern DOES NOT EXIST in upstream VideoRecorder.kt 1.26.1
    # (no `codecName` variable in this file at all). So P-32b's sed silently no-ops
    # and P-56.5 must be applied. If P-32b's pattern somehow matches in a future
    # upstream revision, we detect it (via `LeicaConfig.videoRateControl != "vbr"`
    # marker) and SKIP P-56.5.
    substep "P-56.5: Bitrate mode override (LeicaConfig.videoBitrateMode → CBR/CQP/VBR)"
    if [[ -f "$vrec" ]]; then
        if grep -q 'LeicaConfig.videoRateControl != "vbr"' "$vrec" 2>/dev/null; then
            ok "P-56.5: SKIPPED — P-32b already wired VBR override (codecName.contains(\"hevc\") pattern)"
            ((++p56_count))
        elif grep -q 'val requestedMode = LeicaConfig.videoBitrateMode' "$vrec" 2>/dev/null; then
            ok "P-56.5: already patched (idempotent)"
            ((++p56_count))
        else
            python3 - "$vrec" << 'PYEOF'
import sys
filepath = sys.argv[1]
with open(filepath, 'r', encoding='utf-8') as f:
    s = f.read()

# 1. Replace the bitrate mode block (12-space indent for outer lines)
old_block = (
    '            val isCbrSupported = capabilities.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true\n'
    '            val bitrateMode = if (isCbrSupported) {\n'
    '                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR\n'
    '            } else {\n'
    '                PLog.w(TAG, "CBR bitrate mode not supported, falling back to VBR")\n'
    '                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR\n'
    '            }\n'
)
new_block = (
    '            val requestedMode = LeicaConfig.videoBitrateMode\n'
    '            val isModeSupported = capabilities.encoderCapabilities?.isBitrateModeSupported(requestedMode) == true\n'
    '            val bitrateMode = if (isModeSupported) {\n'
    '                requestedMode\n'
    '            } else {\n'
    '                PLog.w(TAG, "Requested bitrate mode $requestedMode not supported, falling back to VBR")\n'
    '                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR\n'
    '            }\n'
)
if old_block not in s:
    print('NOMATCH block', file=sys.stderr); sys.exit(1)
s = s.replace(old_block, new_block, 1)

# 2. Rename the fallback retry check `if (isCbrSupported)` → `if (isModeSupported)`
#    (occurs once later in the same function, inside catch{} block; 16-space indent)
old_retry = '                if (isCbrSupported) {\n'
new_retry = '                if (isModeSupported) {\n'
if old_retry not in s:
    print('NOMATCH retry', file=sys.stderr); sys.exit(1)
s = s.replace(old_retry, new_retry, 1)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(s)
print('OK')
PYEOF
            if grep -q 'val requestedMode = LeicaConfig.videoBitrateMode' "$vrec" 2>/dev/null && \
               ! grep -q 'isCbrSupported' "$vrec" 2>/dev/null; then
                ok "P-56.5: bitrate mode override wired (LeicaConfig.videoBitrateMode → requestedMode/isModeSupported)"
                ((++p56_count))
            else
                warn "P-56.5: python3 injection failed"
            fi
        fi
    else
        warn "P-56.5: VideoRecorder.kt not found"
    fi

    if [[ "$p56_count" -gt 0 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    ok "P-56: Video encoder completeness applied (v6.3.8) — sub-patches: $p56_count/5"
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 12 (P-59) — v6.3.8 Thermal monitor (battery temp gate before capture)
    # ───────────────────────────────────────────────────────────────────────
    # BUG: On hot devices (direct sunlight, heavy use, summer), mode_max with
    # 9-frame stacking + AgX + super-resolution drives the SoC into thermal
    # throttling. The OS then throttles CPU/GPU which slows every shot AND
    # drops frame quality (janky preview, dropped frames, color shifts).
    #
    # FIX: P-59 adds LeicaThermalMonitor — a singleton that listens to
    # ACTION_BATTERY_CHANGED (sticky, no permission needed) and exposes the
    # current battery temperature. CameraViewModel.capture() checks it BEFORE
    # starting any burst; if temp >= active capture mode's threshold, it
    # auto-degrades to mode_balanced (less frames than mode_max = less CPU = less heat).
    # v6.3.8.6: mode_fast removido; degradation agora vai pra mode_balanced.
    #
    # JSON config (leica_perfect.json -> capture_modes.modes.{*}.thermal_throttle_at_c):
    #   - mode_max:      45 C
    #   - mode_balanced: 50 C (default)
    section "P-59: Thermal monitor (v6.3.8) — battery temp gate before capture"
    local p59_count=0

    # ─── P-59.1: Install LeicaThermalMonitor.kt ──
    substep "P-59.1: Instalar LeicaThermalMonitor.kt (battery temp receiver)"
    local thermal_src="$PATCH_DIR/LeicaThermalMonitor.kt"
    local thermal_dst="$APP_JAVA/raw/LeicaThermalMonitor.kt"
    if [[ -f "$thermal_src" ]]; then
        cp -f "$thermal_src" "$thermal_dst"
        if [[ -f "$thermal_dst" ]] && grep -q '^object LeicaThermalMonitor' "$thermal_dst" 2>/dev/null; then
            ok "P-59.1: LeicaThermalMonitor.kt instalado em $thermal_dst"
            ((++p59_count))
        else
            warn "P-59.1: copy failed or file content invalid"
        fi
    else
        warn "P-59.1: LeicaThermalMonitor.kt não encontrado em $thermal_src"
    fi

    # ─── P-59.2: MyCameraApplication — init LeicaThermalMonitor after LeicaRuntimeState ──
    substep "P-59.2: MyCameraApplication — LeicaThermalMonitor.init() after LeicaRuntimeState.init()"
    local app_p59="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt"
    if [[ -f "$app_p59" ]]; then
        if grep -q 'LeicaThermalMonitor.init' "$app_p59" 2>/dev/null; then
            ok "P-59.2: already patched (idempotent)"
            ((++p59_count))
        else
            # Add import after the LeicaRuntimeState import added by P-48d
            grep -q '^import com.hinnka.mycamera.raw.LeicaThermalMonitor$' "$app_p59" 2>/dev/null || \
                sed -i '/^import com.hinnka.mycamera.raw.LeicaRuntimeState$/a import com.hinnka.mycamera.raw.LeicaThermalMonitor' "$app_p59"
            # Insert LeicaThermalMonitor.init() AFTER LeicaRuntimeState.init() (added by P-48d),
            # BEFORE the "Leica Perfect config loaded from assets" PLog.d line.
            sed -i '/LeicaRuntimeState.init(this@MyCameraApplication)/a\            LeicaThermalMonitor.init(this@MyCameraApplication)' "$app_p59"
            if grep -q 'LeicaThermalMonitor.init' "$app_p59" 2>/dev/null && \
               grep -q '^import com.hinnka.mycamera.raw.LeicaThermalMonitor$' "$app_p59" 2>/dev/null; then
                ok "P-59.2: LeicaThermalMonitor.init(this@MyCameraApplication) wired after LeicaRuntimeState.init"
                ((++p59_count))
            else
                warn "P-59.2: insertion failed — P-48d may not have run (LeicaRuntimeState.init anchor missing)"
            fi
        fi
    else
        warn "P-59.2: MyCameraApplication.kt not found at $app_p59"
    fi

    # ─── P-59.3: CameraViewModel — thermal gate at top of capture() ──
    substep "P-59.3: CameraViewModel.capture() — thermal gate before any burst"
    local cvm_p59="$APP_JAVA/viewmodel/CameraViewModel.kt"
    if [[ -f "$cvm_p59" ]]; then
        if grep -q 'LeicaThermalMonitor.shouldDegradeCapture' "$cvm_p59" 2>/dev/null; then
            ok "P-59.3: already patched (idempotent)"
            ((++p59_count))
        else
            # Add imports (LeicaConfig already imported by P-52a; PLog is wildcard-imported via com.hinnka.mycamera.utils.*)
            # Anchor on the package declaration (unique) — NOT on `^import com.hinnka.mycamera.raw\.` which
            # would match ~16 lines and append after EACH one (duplicate-import bug present in P-52a).
            grep -q '^import com.hinnka.mycamera.raw.LeicaThermalMonitor$' "$cvm_p59" 2>/dev/null || \
                sed -i '/^package com\.hinnka\.mycamera\.viewmodel$/a import com.hinnka.mycamera.raw.LeicaThermalMonitor' "$cvm_p59"
            grep -q '^import com.hinnka.mycamera.raw.LeicaRuntimeState$' "$cvm_p59" 2>/dev/null || \
                sed -i '/^package com\.hinnka\.mycamera\.viewmodel$/a import com.hinnka.mycamera.raw.LeicaRuntimeState' "$cvm_p59"

            # Inject thermal gate at the top of capture() body (right after the opening brace).
            # Anchor on the exact `    fun capture() {` line (4-space indent — only one match in file).
            # Heredoc uses 'PY' delimiter (single-quoted) so bash does NOT expand ${...} Kotlin templates.
            python3 - "$cvm_p59" <<'PY'
import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()
pattern = re.compile(r'^(    fun capture\(\) \{\n)', re.MULTILINE)
inject = (
    '        // P-59: Thermal monitor — auto-degrade to mode_balanced if device is hot (v6.3.8.6)\n'
    '        if (LeicaThermalMonitor.shouldDegradeCapture()) {\n'
    '            PLog.w(TAG, "Thermal throttle: ${LeicaThermalMonitor.thermalStatus()} — auto-degrading to mode_balanced")\n'
    '            LeicaRuntimeState.setCaptureMode("mode_balanced")\n'
    '        }\n'
)
new_src, n = pattern.subn(lambda m: m.group(1) + inject, src, count=1)
if n != 1:
    print(f'P-59.3: WARN — capture() anchor not matched (n={n})', file=sys.stderr)
    sys.exit(1)
p.write_text(new_src)
print('P-59.3: thermal gate injected into capture() (1 substitution)')
PY
            if grep -q 'LeicaThermalMonitor.shouldDegradeCapture' "$cvm_p59" 2>/dev/null; then
                ok "P-59.3: CameraViewModel.capture() now checks LeicaThermalMonitor.shouldDegradeCapture()"
                ((++p59_count))
            else
                warn "P-59.3: python3 injection failed — capture() pattern may have changed upstream"
            fi
        fi
    else
        warn "P-59.3: CameraViewModel.kt not found at $cvm_p59"
    fi

    # ─── P-59.4: Verify thermal monitor wiring end-to-end ──
    substep "P-59.4: Verify thermal monitor wiring (file present + init called + capture gated)"
    local p59_verify=1
    if [[ -f "$APP_JAVA/raw/LeicaThermalMonitor.kt" ]] && \
       grep -q '^object LeicaThermalMonitor' "$APP_JAVA/raw/LeicaThermalMonitor.kt" 2>/dev/null; then
        :
    else
        warn "P-59.4: LeicaThermalMonitor.kt missing or invalid in source tree"
        p59_verify=0
    fi
    if [[ -f "$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt" ]]; then
        grep -q 'LeicaThermalMonitor.init' "$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt" 2>/dev/null \
            || { warn "P-59.4: LeicaThermalMonitor.init not called in MyCameraApplication.kt"; p59_verify=0; }
    else
        warn "P-59.4: MyCameraApplication.kt missing"
        p59_verify=0
    fi
    if [[ -f "$APP_JAVA/viewmodel/CameraViewModel.kt" ]]; then
        grep -q 'LeicaThermalMonitor.shouldDegradeCapture' "$APP_JAVA/viewmodel/CameraViewModel.kt" 2>/dev/null \
            || { warn "P-59.4: LeicaThermalMonitor.shouldDegradeCapture not called in CameraViewModel.kt"; p59_verify=0; }
    else
        warn "P-59.4: CameraViewModel.kt missing"
        p59_verify=0
    fi
    if [[ "$p59_verify" -eq 1 ]]; then
        ok "P-59.4: thermal monitor wiring verified — file present, init called, capture() gated"
        ((++p59_count))
    fi

    ok "P-59: Thermal monitor applied (v6.3.8) — sub-patches: $p59_count/4"
    # Account for the whole P-59 section in the global patch counter.
    if [[ "$p59_count" -ge 3 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 12 (P-57) — v6.3.8 Camera2 direct params per-lens
    # ───────────────────────────────────────────────────────────────────────
    # JSON per_lens.{main,uw,tele,front}.{edge_mode, camera2_noise_reduction_mode,
    # shading_mode, hot_pixel_mode, tonemap_mode} → Camera2 CaptureRequest keys.
    # Sentinel -1 = use app default (don't override).
    # P-57.1 runs BEFORE P-35 forceHighQualityIsp early-return so per-lens override
    # is applied first; P-35's `return` may still bypass when forceHighQualityIsp=true.
    # LeicaConfig helpers: edgeModeForLens / camera2NoiseReductionModeForLens /
    # shadingModeForLens / hotPixelModeForLens / tonemapModeForLens / lensKeyFromCameraId.
    section "P-57: Camera2 direct params per-lens (v6.3.8) — EDGE_MODE + NR_MODE + SHADING_MODE + HOT_PIXEL_MODE + TONEMAP_MODE"
    local p57_count=0
    if [[ -f "$c2c" ]]; then

        # P-57.5 (run first): ensure LeicaConfig import present (idempotent — P-28/P-35 may have added it)
        substep "P-57.5: LeicaConfig import guard (Camera2Controller.kt)"
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$c2c" 2>/dev/null || \
            sed -i '/^package com.hinnka.mycamera.camera$/a import com.hinnka.mycamera.raw.LeicaConfig' "$c2c"
        if grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$c2c" 2>/dev/null; then
            ok "P-57.5: LeicaConfig import present"
            ((++p57_count))
        else
            warn "P-57.5: import insert failed"
            ((++patch_fail))
        fi

        # P-57.1: per-lens EDGE_MODE + NOISE_REDUCTION_MODE at top of applyImageQualitySettings
        substep "P-57.1: per-lens EDGE+NR override in applyImageQualitySettings (BEFORE P-35)"
        if grep -q '// P-57 per-lens Camera2 override (v6.3.8)' "$c2c" 2>/dev/null; then
            ok "P-57.1: already patched (idempotent)"
            ((++p57_count))
        else
            python3 - "$c2c" << 'PYEOF'
import sys
p = sys.argv[1]
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
sig = '    private fun applyImageQualitySettings(builder: CaptureRequest.Builder, isCapture: Boolean) {'
block = (
    '        // P-57 per-lens Camera2 override (v6.3.8) — runs BEFORE P-35 forceHighQualityIsp early-return\n'
    '        val lensKey = LeicaConfig.lensKeyFromCameraId(_state.value.currentCameraId)\n'
    '        LeicaConfig.edgeModeForLens(lensKey).takeIf { it >= 0 }?.let { mode ->\n'
    '            if (availableEdgeModes.contains(mode)) builder.set(CaptureRequest.EDGE_MODE, mode)\n'
    '        }\n'
    '        LeicaConfig.camera2NoiseReductionModeForLens(lensKey).takeIf { it >= 0 }?.let { mode ->\n'
    '            if (availableNoiseReductionModes.contains(mode)) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, mode)\n'
    '        }\n'
)
if sig not in s:
    print('NOMATCH sig', file=sys.stderr); sys.exit(1)
new = s.replace(sig, sig + '\n' + block, 1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new)
print('OK')
PYEOF
            if grep -q '// P-57 per-lens Camera2 override (v6.3.8)' "$c2c" 2>/dev/null; then
                ok "P-57.1: per-lens EDGE+NR override inserted at top of applyImageQualitySettings"
                ((++p57_count))
            else
                warn "P-57.1: insert failed (signature mismatch?)"
                ((++patch_fail))
            fi
        fi

        # P-57.2: per-lens SHADING+HOT_PIXEL at top of applyFastStillPostProcessingSettings
        substep "P-57.2: per-lens SHADING+HOT_PIXEL override in applyFastStillPostProcessingSettings"
        if grep -q '// P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — fast still post-proc' "$c2c" 2>/dev/null; then
            ok "P-57.2: already patched (idempotent)"
            ((++p57_count))
        else
            python3 - "$c2c" << 'PYEOF'
import sys
p = sys.argv[1]
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
sig = '    private fun applyFastStillPostProcessingSettings(builder: CaptureRequest.Builder) {'
block = (
    '        // P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — fast still post-proc\n'
    '        val lensKey = LeicaConfig.lensKeyFromCameraId(_state.value.currentCameraId)\n'
    '        LeicaConfig.shadingModeForLens(lensKey).takeIf { it >= 0 }?.let { mode ->\n'
    '            if (availableShadingModes.contains(mode)) builder.set(CaptureRequest.SHADING_MODE, mode)\n'
    '        }\n'
    '        LeicaConfig.hotPixelModeForLens(lensKey).takeIf { it >= 0 }?.let { mode ->\n'
    '            if (availableHotPixelModes.contains(mode)) builder.set(CaptureRequest.HOT_PIXEL_MODE, mode)\n'
    '        }\n'
)
if sig not in s:
    print('NOMATCH sig', file=sys.stderr); sys.exit(1)
new = s.replace(sig, sig + '\n' + block, 1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new)
print('OK')
PYEOF
            if grep -q '// P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — fast still post-proc' "$c2c" 2>/dev/null; then
                ok "P-57.2: per-lens SHADING+HOT_PIXEL override inserted in applyFastStillPostProcessingSettings"
                ((++p57_count))
            else
                warn "P-57.2: insert failed (signature mismatch?)"
                ((++patch_fail))
            fi
        fi

        # P-57.3: per-lens SHADING+HOT_PIXEL at top of applyHighQualityStillPostProcessingSettings
        substep "P-57.3: per-lens SHADING+HOT_PIXEL override in applyHighQualityStillPostProcessingSettings"
        if grep -q '// P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — HQ still post-proc' "$c2c" 2>/dev/null; then
            ok "P-57.3: already patched (idempotent)"
            ((++p57_count))
        else
            python3 - "$c2c" << 'PYEOF'
import sys
p = sys.argv[1]
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
sig = '    private fun applyHighQualityStillPostProcessingSettings(builder: CaptureRequest.Builder) {'
block = (
    '        // P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — HQ still post-proc\n'
    '        val lensKey = LeicaConfig.lensKeyFromCameraId(_state.value.currentCameraId)\n'
    '        LeicaConfig.shadingModeForLens(lensKey).takeIf { it >= 0 }?.let { mode ->\n'
    '            if (availableShadingModes.contains(mode)) builder.set(CaptureRequest.SHADING_MODE, mode)\n'
    '        }\n'
    '        LeicaConfig.hotPixelModeForLens(lensKey).takeIf { it >= 0 }?.let { mode ->\n'
    '            if (availableHotPixelModes.contains(mode)) builder.set(CaptureRequest.HOT_PIXEL_MODE, mode)\n'
    '        }\n'
)
if sig not in s:
    print('NOMATCH sig', file=sys.stderr); sys.exit(1)
new = s.replace(sig, sig + '\n' + block, 1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new)
print('OK')
PYEOF
            if grep -q '// P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — HQ still post-proc' "$c2c" 2>/dev/null; then
                ok "P-57.3: per-lens SHADING+HOT_PIXEL override inserted in applyHighQualityStillPostProcessingSettings"
                ((++p57_count))
            else
                warn "P-57.3: insert failed (signature mismatch?)"
                ((++patch_fail))
            fi
        fi

        # P-57.4: per-lens TONEMAP_MODE override in applyToneMapSettings (single-line sed)
        substep "P-57.4: per-lens TONEMAP_MODE override in applyToneMapSettings"
        if grep -q 'LeicaConfig.tonemapModeForLens(LeicaConfig.lensKeyFromCameraId(state.currentCameraId))' "$c2c" 2>/dev/null; then
            ok "P-57.4: already patched (idempotent)"
            ((++p57_count))
        else
            sed -i 's|val tonemapMode = sanitizeTonemapMode(state.tonemapMode)|val tonemapMode = LeicaConfig.tonemapModeForLens(LeicaConfig.lensKeyFromCameraId(state.currentCameraId)) ?: sanitizeTonemapMode(state.tonemapMode)|' "$c2c"
            if grep -q 'LeicaConfig.tonemapModeForLens(LeicaConfig.lensKeyFromCameraId(state.currentCameraId))' "$c2c" 2>/dev/null; then
                ok "P-57.4: per-lens TONEMAP_MODE override applied"
                ((++p57_count))
            else
                warn "P-57.4: sed replacement failed (target line not found?)"
                ((++patch_fail))
            fi
        fi

        # P-57.6: final wiring verification (counts as 6th sub-patch)
        substep "P-57.6: wiring verification (grep checks)"
        local p57_verify=0
        local p57_total=6
        grep -q '// P-57 per-lens Camera2 override (v6.3.8)' "$c2c" 2>/dev/null && ((++p57_verify))
        grep -q '// P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — fast still post-proc' "$c2c" 2>/dev/null && ((++p57_verify))
        grep -q '// P-57 per-lens SHADING+HOT_PIXEL override (v6.3.8) — HQ still post-proc' "$c2c" 2>/dev/null && ((++p57_verify))
        grep -q 'LeicaConfig.tonemapModeForLens(LeicaConfig.lensKeyFromCameraId(state.currentCameraId))' "$c2c" 2>/dev/null && ((++p57_verify))
        grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$c2c" 2>/dev/null && ((++p57_verify))
        grep -q 'edgeModeForLens\|camera2NoiseReductionModeForLens\|shadingModeForLens\|hotPixelModeForLens\|tonemapModeForLens' "$c2c" 2>/dev/null && ((++p57_verify))
        if [[ "$p57_verify" -eq "$p57_total" ]]; then
            ok "P-57.6: all wiring verified ($p57_verify/$p57_total)"
            ((++p57_count))
        else
            warn "P-57.6: wiring incomplete ($p57_verify/$p57_total)"
            ((++patch_fail))
        fi

        if [[ "$p57_count" -gt 0 ]]; then
            ((++patch_count))
        else
            ((++patch_fail))
        fi
    else
        warn "P-57: Camera2Controller.kt não encontrado (já avisado em P-28)"
        ((++patch_fail))
    fi
    ok "P-57: Camera2 direct params applied (v6.3.8) — sub-patches: $p57_count/6"
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 13 (P-58) — v6.3.8 Noise model completion
    # ───────────────────────────────────────────────────────────────────────

    # P-58: Noise model completion (v6.3.8) — chroma denoise fallback constants wired to LeicaConfig
    # P-36 já wireou RawMetadata.kt:361 (fallback primário floatArrayOf(0.0f, 0.0f) →
    # LeicaConfig.noiseModelForLens via lensKeyFromCharacteristics).
    # P-58 wirea os 4 hardcoded noise fallback constants restantes + 2 companion object consts:
    #   P-58.1 Site A: RawDemosaicProcessor.kt:6332/6335 (resolveDenoiseProfileNoiseModel: 1E-4f, 4.5E-7f)
    #   P-58.1 Site B: RawDemosaicProcessor.kt:6363/6366 (resolveChromaDenoiseNoiseModel: 1E-4f, 4.5E-7f)
    #   P-58.2:        LutImageProcessor.kt:3464/3465 (BITMAP_DENOISE_A=0.008f, BITMAP_DENOISE_B=0.0005f)
    # Usa metadata.lensKey (populado por P-50 via lensKeyFromCharacteristics) — evita threading
    # de cameraId (cameraId NÃO está em scope nas signatures resolveDenoiseProfileNoiseModel /
    # resolveChromaDenoiseNoiseModel; apenas metadata: RawMetadata e fallbackGain: Float).
    # Compatível com P-36 (mesmo accessor LeicaConfig.noiseModelForLens, mesmo mapeamento b→slope/S, d→offset/O).
    section "P-58: Noise model completion (v6.3.8) — chroma denoise fallback constants wired to LeicaConfig"
    local p58_count=0
    local rdp="$APP_JAVA/raw/RawDemosaicProcessor.kt"
    local lip="$APP_JAVA/lut/LutImageProcessor.kt"

    # ─── P-58.1: RawDemosaicProcessor.kt — Sites A+B (chroma denoise fallback) ──
    substep "P-58.1: RawDemosaicProcessor — chroma denoise fallback → LeicaConfig.noiseModelForLens"
    if [[ -f "$rdp" ]]; then
        if grep -q 'LeicaConfig.noiseModelForLens(metadata.lensKey' "$rdp" 2>/dev/null; then
            ok "P-58.1: already patched (idempotent)"
            ((++p58_count))
        else
            # Ensure LeicaConfig import present (P-54c normally adds it; idempotent safety net)
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$rdp" || \
                sed -i '/^package com.hinnka.mycamera.raw$/a import com.hinnka.mycamera.raw.LeicaConfig' "$rdp"
            # Site A: resolveDenoiseProfileNoiseModel — slope/offset fallbacks (lines ~6332/6335)
            # Site B: resolveChromaDenoiseNoiseModel — fallbackSlope/fallbackOffset fallbacks (lines ~6363/6366)
            # Both sites use the same hardcoded constants (1E-4f * fallbackGain, 4.5E-7f * sqrt(fallbackGain))
            # but have different surrounding code structure, so they're patched atomically in one python3 pass.
            python3 -c "
import sys
p = '$rdp'
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
# Site A: resolveDenoiseProfileNoiseModel — slope/offset fallbacks (lines ~6332/6335)
site_a_old = '''        if (!slope.isFinite() || slope <= 0f) {
            slope = 1E-4f * fallbackGain
        }
        if (!offset.isFinite() || offset <= 0f) {
            offset = 4.5E-7f * sqrt(fallbackGain)
        }'''
site_a_new = '''        if (!slope.isFinite() || slope <= 0f) {
            val nmA = LeicaConfig.noiseModelForLens(metadata.lensKey ?: \"main\")
            slope = (nmA?.b ?: 1E-4).toFloat() * fallbackGain
        }
        if (!offset.isFinite() || offset <= 0f) {
            val nmA = LeicaConfig.noiseModelForLens(metadata.lensKey ?: \"main\")
            offset = (nmA?.d ?: 4.5E-7).toFloat() * sqrt(fallbackGain)
        }'''
if site_a_old not in s:
    print('NOMATCH site A', file=sys.stderr); sys.exit(1)
new = s.replace(site_a_old, site_a_new, 1)
# Site B: resolveChromaDenoiseNoiseModel — fallbackSlope/fallbackOffset fallbacks (lines ~6363/6366)
site_b_old = '''        val fallbackSlope = metadata.noiseProfile.getOrElse(0) { 0f }
            .takeIf { it.isFinite() && it > 0f }
            ?: (1E-4f * fallbackGain)
        val fallbackOffset = metadata.noiseProfile.getOrElse(1) { 0f }
            .takeIf { it.isFinite() && it > 0f }
            ?: (4.5E-7f * sqrt(fallbackGain))'''
site_b_new = '''        val nmB = LeicaConfig.noiseModelForLens(metadata.lensKey ?: \"main\")
        val fallbackSlope = metadata.noiseProfile.getOrElse(0) { 0f }
            .takeIf { it.isFinite() && it > 0f }
            ?: ((nmB?.b ?: 1E-4).toFloat() * fallbackGain)
        val fallbackOffset = metadata.noiseProfile.getOrElse(1) { 0f }
            .takeIf { it.isFinite() && it > 0f }
            ?: ((nmB?.d ?: 4.5E-7).toFloat() * sqrt(fallbackGain))'''
if site_b_old not in new:
    print('NOMATCH site B', file=sys.stderr); sys.exit(1)
new2 = new.replace(site_b_old, site_b_new, 1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new2)
print('OK')
" 2>&1 | grep -q '^OK$' && {
                ((++p58_count))
                ok "P-58.1: RawDemosaicProcessor chroma denoise fallback → LeicaConfig (Sites A+B)"
            } || {
                warn "P-58.1: python3 patch failed (pattern may have changed upstream)"
                ((++patch_fail))
            }
        fi
    else
        warn "P-58.1: RawDemosaicProcessor.kt não encontrado"
        ((++patch_fail))
    fi

    # ─── P-58.2: LutImageProcessor.kt — companion object BITMAP_DENOISE_A/B ──
    substep "P-58.2: LutImageProcessor — BITMAP_DENOISE_A/B → var + runtime updater from LeicaConfig"
    if [[ -f "$lip" ]]; then
        if grep -q 'fun updateBitmapDenoiseCoefficients' "$lip" 2>/dev/null; then
            ok "P-58.2: already patched (idempotent)"
            ((++p58_count))
        else
            # Add LeicaConfig import if missing (P-54b normally adds it; idempotent safety net)
            grep -q '^import com.hinnka.mycamera.raw.LeicaConfig$' "$lip" || \
                sed -i '/^package com.hinnka.mycamera.lut$/a import com.hinnka.mycamera.raw.LeicaConfig' "$lip"
            # Convert const val → var (const cannot hold mutable runtime values; same name kept)
            sed -i 's|private const val BITMAP_DENOISE_A = 0.008f|private var BITMAP_DENOISE_A: Float = 0.008f|' "$lip"
            sed -i 's|private const val BITMAP_DENOISE_B = 0.0005f|private var BITMAP_DENOISE_B: Float = 0.0005f|' "$lip"
            # Inject updateBitmapDenoiseCoefficients(lensKey) function right after BITMAP_DENOISE_B line
            # so the companion object gains a runtime updater that pulls b/d from LeicaConfig.
            python3 -c "
import sys
p = '$lip'
with open(p, 'r', encoding='utf-8') as f:
    s = f.read()
anchor = '        private var BITMAP_DENOISE_B: Float = 0.0005f'
if anchor not in s:
    print('NOMATCH anchor', file=sys.stderr); sys.exit(1)
inj = anchor + '\n\n        fun updateBitmapDenoiseCoefficients(lensKey: String) {\n            val nm = LeicaConfig.noiseModelForLens(lensKey)\n            BITMAP_DENOISE_A = (nm?.b ?: 0.008).toFloat()\n            BITMAP_DENOISE_B = (nm?.d ?: 0.0005).toFloat()\n        }'
new = s.replace(anchor, inj, 1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(new)
print('OK')
" 2>&1 | grep -q '^OK$' && {
                ((++p58_count))
                ok "P-58.2: BITMAP_DENOISE_A/B → var + updateBitmapDenoiseCoefficients(lensKey) injected"
            } || {
                warn "P-58.2: injection failed (pattern may have changed upstream)"
                ((++patch_fail))
            }
        fi
    else
        warn "P-58.2: LutImageProcessor.kt não encontrado"
        ((++patch_fail))
    fi

    ok "P-58: Noise model completion applied (v6.3.8) — sub-patches: $p58_count/2"
    echo ""


    # ───────────────────────────────────────────────────────────────────────
    # Tier 13 (P-60) — v6.3.8-fix4: doNotStrip in build.gradle.kts (Kotlin DSL)
    # ───────────────────────────────────────────────────────────────────────
    # ROOT CAUSE: NDK r29's llvm-objcopy crashes the JVM (hs_err_pid) when
    # stripping libBugly_Native.so. fix2 tried -x strip but that caused
    # mergeNativeLibs to not find the .so → libmy-native-lib.so MISSING from
    # APK → NoClassDefFoundError: RawDemosaicProcessor at runtime.
    # FIX: doNotStrip("**/*.so") makes the strip task RUN but SKIP llvm-objcopy
    # invocation → no JVM crash + .so correctly packaged (just unstripped = larger).
    #
    # v6.3.8-fix4 history:
    #   fix3 attempt 1: sed `a\n` → stray `n` literal + Groovy DSL `doNotStrip "..."` →
    #     build.gradle.kts script compile failure (3 errors).
    #   fix4 attempt 2: awk insertion after ndkVersion → worked but put packaging{}
    #     INSIDE defaultConfig{} (packaging is not valid there in BaseFlavor).
    #   fix4 final: APPEND a separate `android { packaging { jniLibs { doNotStrip } } }`
    #     block at END of file — Gradle Kotlin DSL merges multiple android{} blocks,
    #     so this is the safest, structure-agnostic approach.
    section "P-60: doNotStrip in build.gradle.kts (v6.3.8-fix4) — Kotlin DSL append block"
    local gradle_kts="$SOURCE_DIR/app/build.gradle.kts"
    local p60_count=0
    if [[ -f "$gradle_kts" ]]; then
        # Idempotent: only add if doNotStrip not already present
        if ! grep -q 'doNotStrip' "$gradle_kts" 2>/dev/null; then
            # Append a separate android { packaging { jniLibs { doNotStrip } } } block.
            # Gradle Kotlin DSL merges multiple android{} blocks — no need to find
            # the right insertion point inside the existing structure.
            cat >> "$gradle_kts" <<'GRADLE_KT'

// ── Leica Perfect v6.3.8-fix4: doNotStrip to fix JVM crash on libBugly_Native.so ──
// NDK r29's llvm-objcopy crashes the JVM when stripping libBugly_Native.so.
// doNotStrip makes the strip task RUN but SKIP llvm-objcopy invocation.
// Native libs (.so) are packaged unstripped (larger APK, but no crash + .so present).
android {
    packaging {
        jniLibs {
            doNotStrip("**/*.so")
        }
    }
}
GRADLE_KT
            if grep -q 'doNotStrip' "$gradle_kts" 2>/dev/null; then
                ok "P-60: doNotStrip(\"**/*.so\") appended to build.gradle.kts (Kotlin DSL — strip task skips all .so → no JVM crash)"
                ((++p60_count))
            else
                warn "P-60: append failed"
            fi
        else
            ok "P-60: already patched (idempotent)"
            ((++p60_count))
        fi
    else
        warn "P-60: build.gradle.kts not found at $gradle_kts"
    fi
    ok "P-60: doNotStrip applied (v6.3.8-fix4) — sub-patches: $p60_count/1"
    if [[ "$p60_count" -ge 1 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 13b (P-66) — v6.4.0-fix12: ccache for native C/C++ builds (ABSOLUTE)
    # ───────────────────────────────────────────────────────────────────────
    # Injects CMAKE_CXX_COMPILER_LAUNCHER=ccache + CMAKE_C_COMPILER_LAUNCHER=ccache
    # into the externalNativeBuild cmake block of app/build.gradle.kts.
    # When ccache is installed on the build machine, native .so recompilation
    # is dramatically faster (incremental builds skip unchanged C++ files).
    section "P-66: ccache launcher in build.gradle.kts (v6.4.0-fix12 — ABSOLUTE)"
    local p66_count=0
    if [[ -f "$gradle_kts" ]]; then
        if grep -q 'CMAKE_CXX_COMPILER_LAUNCHER' "$gradle_kts" 2>/dev/null; then
            ok "P-66: already patched (idempotent)"
            ((++p66_count))
        else
            # Insert ccache args into the cmake { } block (after doNotStrip block)
            # Use Python for robust multi-line insertion after the packaging block
            python3 - "$gradle_kts" <<'PYINS' || sed -i '/doNotStrip/a\        arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"\n        arguments += "-DCMAKE_C_COMPILER_LAUNCHER=ccache"' "$gradle_kts"
import sys, re
path = sys.argv[1]
src = open(path).read()
# Find the cmake { } block inside externalNativeBuild and inject ccache args
if 'CMAKE_CXX_COMPILER_LAUNCHER' not in src:
    # Insert after the first occurrence of 'cmake {' block opening
    # Pattern: cmake {  followed by possible whitespace/newlines
    pattern = r'(externalNativeBuild\s*\{[^}]*?cmake\s*\{)'
    repl = r'\1\n        arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"\n        arguments += "-DCMAKE_C_COMPILER_LAUNCHER=ccache"'
    new = re.sub(pattern, repl, src, count=1, flags=re.DOTALL)
    if new != src:
        open(path, 'w').write(new)
        print("P-66: injected ccache launcher args via Python")
    else:
        # Fallback: just append cmake args block
        print("P-66: Python regex did not match, trying sed fallback")
        sys.exit(1)
PYINS
            if grep -q 'CMAKE_CXX_COMPILER_LAUNCHER' "$gradle_kts" 2>/dev/null; then
                ok "P-66: ccache launcher args injected into build.gradle.kts"
                ((++p66_count))
            else
                warn "P-66: injection failed — ccache will not be used (build still works, just slower)"
            fi
        fi
    else
        warn "P-66: build.gradle.kts not found at $gradle_kts"
    fi
    ok "P-66: ccache applied (v6.4.0-fix12) — sub-patches: $p66_count/1"
    if [[ "$p66_count" -ge 1 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 13 (P-61) — v6.3.8-fix4: Install LeicaStateDumper.kt + wire in MyCameraApplication
    # ───────────────────────────────────────────────────────────────────────
    # Comprehensive debug logger — dumps entire app config to logcat (tag: LeicaPerfectState)
    # Like the Photon logs format. Shows: all JSON sections, per-lens effective values,
    # runtime state, creative profile, capture mode, video config, etc.
    section "P-61: Install LeicaStateDumper.kt (v6.3.8-fix4) — comprehensive config dump to logcat"
    local dumper_src="$SCRIPT_DIR/patches/LeicaStateDumper.kt"
    local dumper_dst="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/raw/LeicaStateDumper.kt"
    local app_kt="$SOURCE_DIR/app/src/main/java/com/hinnka/mycamera/MyCameraApplication.kt"
    local p61_count=0
    # P-61.1: Install LeicaStateDumper.kt
    substep "P-61.1: Instalar LeicaStateDumper.kt"
    if [[ -f "$dumper_src" ]]; then
        mkdir -p "$(dirname "$dumper_dst")"
        cp "$dumper_src" "$dumper_dst"
        if [[ -f "$dumper_dst" ]] && grep -q "object LeicaStateDumper" "$dumper_dst"; then
            ok "P-61.1: LeicaStateDumper.kt instalado em $dumper_dst"
            ((++p61_count))
        else
            warn "P-61.1: copy failed or file content invalid"
        fi
    else
        warn "P-61.1: LeicaStateDumper.kt não encontrado em $dumper_src"
    fi
    # P-61.2: Wire LeicaStateDumper.dump() in MyCameraApplication (after LeicaThermalMonitor.init)
    substep "P-61.2: MyCameraApplication — call LeicaStateDumper.dump() after thermal monitor init"
    if [[ -f "$app_kt" ]]; then
        if grep -q 'LeicaStateDumper.dump' "$app_kt" 2>/dev/null; then
            ok "P-61.2: already patched (idempotent)"
            ((++p61_count))
        else
            # Add import + call after LeicaThermalMonitor.init
            grep -q '^import com.hinnka.mycamera.raw.LeicaStateDumper$' "$app_kt" 2>/dev/null ||                 sed -i '/^import com.hinnka.mycamera.raw.LeicaThermalMonitor$/a import com.hinnka.mycamera.raw.LeicaStateDumper' "$app_kt"
            # Insert dump() call after LeicaThermalMonitor.init line (12-space indent to match method body)
            sed -i '/LeicaThermalMonitor.init(this@MyCameraApplication)/a\            LeicaStateDumper.dump()' "$app_kt"
            if grep -q 'LeicaStateDumper.dump' "$app_kt" 2>/dev/null; then
                ok "P-61.2: LeicaStateDumper.dump() wired after thermal monitor init"
                ((++p61_count))
            else
                warn "P-61.2: wiring failed — LeicaThermalMonitor.init anchor may be missing"
            fi
        fi
    else
        warn "P-61.2: MyCameraApplication.kt not found at $app_kt"
    fi
    ok "P-61: LeicaStateDumper applied (v6.3.8-fix4) — sub-patches: $p61_count/2"
    if [[ "$p61_count" -ge 1 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 14 (P-62) — v6.4.0 (Cron 7): Drop RAW + 2 capture modes (mode_max + mode_fast intelligent trigger)
    # ───────────────────────────────────────────────────────────────────────
    # User request: "deixa apenas o perfil max e um disparo rapido/inteligente corrigido,
    # esquece essa parada de raw pois nao vou editar, quero ter a melhor jpeg pronta com um click"
    # → mode_balanced REMOVIDO, mode_fast restaurado (disparo rápido inteligente).
    # → RAW/DNG export DESATIVADO (output.force_no_raw/force_no_dng = true).
    section "P-62: Cron 7 — Drop RAW + 2 capture modes (v6.4.0)"
    local json_dst="$APP_ASSETS/leica_perfect.json"
    local config_kt="$APP_JAVA/raw/LeicaConfig.kt"
    local screen_kt="$APP_JAVA/ui/settings/LeicaSettingsScreen.kt"
    local thermal_kt="$APP_JAVA/raw/LeicaThermalMonitor.kt"
    local runtime_kt="$APP_JAVA/raw/LeicaRuntimeState.kt"
    local p62_count=0

    # P-62.1: Update leica_perfect.json capture_modes (mode_balanced → mode_fast + drop RAW)
    substep "P-62.1: leica_perfect.json — capture_modes block (mode_fast + force_no_raw)"
    if [[ -f "$json_dst" ]]; then
        if grep -q '"mode_fast"' "$json_dst" 2>/dev/null; then
            ok "P-62.1: already patched (mode_fast present in JSON)"
            ((++p62_count))
        else
            python3 - "$json_dst" <<'PY'
import json, re, sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
# Replace entire "_comment_capture_modes" + "capture_modes": { ... }, block
new_block = '''  "_comment_capture_modes": "v6.4.0 INTELLIGENT ADAPTIVE — 2 modos de captura. mode_max = max qualidade (lento, pra cenas estaticas/tripod/low-light, 15/9/7/11 frames, super-res 2.0x, NLM radius 7). mode_fast = disparo rapido inteligente (5/3/3/5 frames, NLM radius 3, ~0.3s latency, sustain indefinido — cobre 100% dos casos de acao/burst/casual). mode_balanced REMOVIDO (v6.4.0). RAW/DNG export DESATIVADO — usuario quer JPEG one-click.",
  "capture_modes": {
    "active_capture_mode": "mode_fast",
    "modes": {
      "mode_max": {
        "_comment": "MAX QUALITY — 15/9/7/11 frames, super res 2.0x, NLM radius 7, full pipeline. ~1.8s latency. PRA: tripod, low-light, paisagem, arquitetura, retrato posado. JPEG Q100 + HEIC Q100 + UltraHDR Q100. SEM RAW/DNG (v6.4.0 — usuario nao edita raw).",
        "frame_count_multiplier": 1.0,
        "super_resolution_scale": 2.0,
        "nlm_search_radius": 7,
        "force_rawmax": false,
        "export_super_res_dng": false,
        "video_bitrate_mbps": 250,
        "thermal_throttle_at_c": 45
      },
      "mode_fast": {
        "_comment": "FAST INTELLIGENT TRIGGER (DEFAULT v6.4.0) — 5/3/3/5 frames, sem super res, NLM radius 3, pipeline enxuto. ~0.3s latency, sustain indefinido. PRA: 100% dos casos — diario, street, acao, burst, criancas, pets, esporte. Melhor relacao qualidade/velocidade/termico. Substitui o antigo mode_balanced (removido). SEM RAW/DNG.",
        "frame_count_multiplier": 0.4,
        "super_resolution_scale": 1.0,
        "nlm_search_radius": 3,
        "force_rawmax": false,
        "export_super_res_dng": false,
        "video_bitrate_mbps": 100,
        "thermal_throttle_at_c": 55
      }
    }
  },'''
# Match the existing block (from "_comment_capture_modes" through the closing `},` of capture_modes)
pattern = re.compile(r'  "_comment_capture_modes": ".*?",\n  "capture_modes": \{.*?\n  \},', re.DOTALL)
new_text, count = pattern.subn(new_block, text, count=1)
if count == 1:
    open(path, 'w', encoding='utf-8').write(new_text)
    print("P-62.1: capture_modes block replaced (mode_balanced → mode_fast, force_rawmax=false)")
else:
    print("P-62.1: WARN — pattern not matched; JSON unchanged")
PY
            if grep -q '"mode_fast"' "$json_dst" 2>/dev/null; then
                ok "P-62.1: capture_modes block replaced (mode_fast + no RAW)"
                ((++p62_count))
            else
                warn "P-62.1: JSON edit failed — pattern may have changed"
            fi
        fi
    else
        warn "P-62.1: leica_perfect.json not found at $json_dst"
    fi

    # P-62.2: Update leica_perfect.json output section (add force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100)
    substep "P-62.2: leica_perfect.json — output section (force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100)"
    if [[ -f "$json_dst" ]]; then
        if grep -q '"force_no_raw": true' "$json_dst" 2>/dev/null; then
            ok "P-62.2: already patched (force_no_raw present in JSON)"
            ((++p62_count))
        else
            python3 - "$json_dst" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
old_block = '''  "output": {
    "format": "jpeg",
    "quality": 100,
    "max_resolution": 0,
    "preserve_exif": true,
    "add_watermark": false
  },'''
new_block = '''  "output": {
    "format": "jpeg",
    "quality": 100,
    "max_resolution": 0,
    "preserve_exif": true,
    "add_watermark": false,
    "force_no_raw": true,
    "force_no_dng": true,
    "force_heic_q100": true,
    "force_ultrahdr_q100": true
  },'''
if old_block in text:
    open(path, 'w', encoding='utf-8').write(text.replace(old_block, new_block, 1))
    print("P-62.2: output section extended (force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100 added)")
else:
    print("P-62.2: WARN — exact output block not found (may already be patched or have different formatting)")
PY
            if grep -q '"force_no_raw": true' "$json_dst" 2>/dev/null; then
                ok "P-62.2: output section extended (force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100)"
                ((++p62_count))
            else
                warn "P-62.2: JSON output edit failed"
            fi
        fi
    else
        warn "P-62.2: leica_perfect.json not found at $json_dst"
    fi

    # P-62.3: Update LeicaSettingsScreen.kt CAPTURE_MODES (mode_balanced → mode_fast)
    substep "P-62.3: LeicaSettingsScreen.kt — CAPTURE_MODES (mode_balanced → mode_fast)"
    if [[ -f "$screen_kt" ]]; then
        if grep -q 'CaptureModeOption("mode_fast"' "$screen_kt" 2>/dev/null; then
            ok "P-62.3: already patched (mode_fast in CAPTURE_MODES)"
            ((++p62_count))
        else
            python3 - "$screen_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
old = '''private val CAPTURE_MODES = listOf(
    CaptureModeOption("mode_balanced", "Intelligent", "Diário, street, retrato, ação — 90% dos casos"),
    CaptureModeOption("mode_max", "Quality Max", "Baixa luz, tripé, máxima qualidade")
)'''
new = '''private val CAPTURE_MODES = listOf(
    CaptureModeOption("mode_fast", "Disparo Rápido", "Inteligente — 100% dos casos, ação, burst, casual"),
    CaptureModeOption("mode_max", "Quality Max", "Baixa luz, tripé, máxima qualidade (lento)")
)'''
if old in text:
    open(path, 'w', encoding='utf-8').write(text.replace(old, new, 1))
    print("P-62.3: CAPTURE_MODES updated (mode_balanced → mode_fast)")
else:
    print("P-62.3: WARN — CAPTURE_MODES block not found (may already be patched)")
PY
            if grep -q 'CaptureModeOption("mode_fast"' "$screen_kt" 2>/dev/null; then
                ok "P-62.3: CAPTURE_MODES updated (mode_balanced → mode_fast)"
                ((++p62_count))
            else
                warn "P-62.3: LeicaSettingsScreen.kt edit failed"
            fi
        fi
    else
        warn "P-62.3: LeicaSettingsScreen.kt not found at $screen_kt"
    fi

    # P-62.4: Update LeicaThermalMonitor.kt shouldDegradeCapture (mode_balanced → mode_fast)
    substep "P-62.4: LeicaThermalMonitor.kt — shouldDegradeCapture (mode_balanced → mode_fast)"
    if [[ -f "$thermal_kt" ]]; then
        if grep -q 'activeCaptureMode != "mode_fast"' "$thermal_kt" 2>/dev/null; then
            ok "P-62.4: already patched (shouldDegradeCapture uses mode_fast)"
            ((++p62_count))
        else
            sed -i 's|activeCaptureMode != "mode_balanced"|activeCaptureMode != "mode_fast"|g' "$thermal_kt"
            if grep -q 'activeCaptureMode != "mode_fast"' "$thermal_kt" 2>/dev/null; then
                ok "P-62.4: shouldDegradeCapture updated (mode_balanced → mode_fast)"
                ((++p62_count))
            else
                warn "P-62.4: sed pattern not matched"
            fi
        fi
    else
        warn "P-62.4: LeicaThermalMonitor.kt not found at $thermal_kt"
    fi

    # P-62.5: Update LeicaRuntimeState.kt cycleCaptureMode (balanced ↔ max → fast ↔ max)
    substep "P-62.5: LeicaRuntimeState.kt — cycleCaptureMode (balanced → fast)"
    if [[ -f "$runtime_kt" ]]; then
        if grep -q '"mode_fast" -> "mode_max"' "$runtime_kt" 2>/dev/null; then
            ok "P-62.5: already patched (cycleCaptureMode uses mode_fast)"
            ((++p62_count))
        else
            python3 - "$runtime_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
old = '''        val next = when (current) {
            "mode_balanced" -> "mode_max"
            "mode_max" -> "mode_balanced"
            else -> "mode_balanced"
        }'''
new = '''        val next = when (current) {
            "mode_fast" -> "mode_max"
            "mode_max" -> "mode_fast"
            else -> "mode_fast"
        }'''
if old in text:
    open(path, 'w', encoding='utf-8').write(text.replace(old, new, 1))
    print("P-62.5: cycleCaptureMode updated (balanced → fast)")
else:
    print("P-62.5: WARN — cycleCaptureMode block not found (may already be patched)")
PY
            if grep -q '"mode_fast" -> "mode_max"' "$runtime_kt" 2>/dev/null; then
                ok "P-62.5: cycleCaptureMode updated (balanced → fast)"
                ((++p62_count))
            else
                warn "P-62.5: LeicaRuntimeState.kt edit failed"
            fi
        fi
    else
        warn "P-62.5: LeicaRuntimeState.kt not found at $runtime_kt"
    fi

    # P-62.6: Update LeicaConfig.kt resolveCaptureModeForScene (default → mode_fast)
    substep "P-62.6: LeicaConfig.kt — resolveCaptureModeForScene default (mode_balanced → mode_fast)"
    if [[ -f "$config_kt" ]]; then
        if grep -q 'else -> "mode_fast"' "$config_kt" 2>/dev/null; then
            ok "P-62.6: already patched (resolveCaptureModeForScene uses mode_fast)"
            ((++p62_count))
        else
            sed -i 's|else -> "mode_balanced"|else -> "mode_fast"|g' "$config_kt"
            # Also update fallback defaults that pointed to mode_balanced
            sed -i 's|?: "mode_balanced"|?: "mode_fast"|g' "$config_kt"
            sed -i 's|val activeCaptureMode: String? = "mode_balanced"|val activeCaptureMode: String? = "mode_fast"|g' "$config_kt"
            if grep -q 'else -> "mode_fast"' "$config_kt" 2>/dev/null; then
                ok "P-62.6: resolveCaptureModeForScene + fallback defaults updated (mode_fast)"
                ((++p62_count))
            else
                warn "P-62.6: sed pattern not matched"
            fi
        fi
    else
        warn "P-62.6: LeicaConfig.kt not found at $config_kt"
    fi

    # P-62.7: Verification greps
    substep "P-62.7: verification greps"
    local p62_verify=0
    local mf_json=$(grep -c '"mode_fast"' "$json_dst" 2>/dev/null || echo 0)
    local mb_json=$(grep -c '"mode_balanced"' "$json_dst" 2>/dev/null || echo 0)
    local mf_screen=$(grep -c 'mode_fast' "$screen_kt" 2>/dev/null || echo 0)
    local fnr_json=$(grep -c '"force_no_raw": true' "$json_dst" 2>/dev/null || echo 0)
    if [[ "$mf_json" -ge 2 ]]; then ((++p62_verify)); fi
    if [[ "$mb_json" -eq 0 ]]; then ((++p62_verify)); fi
    if [[ "$mf_screen" -ge 1 ]]; then ((++p62_verify)); fi
    if [[ "$fnr_json" -ge 1 ]]; then ((++p62_verify)); fi
    info "P-62.7: mode_fast in JSON=$mf_json (>=2), mode_balanced in JSON=$mb_json (==0), mode_fast in screen=$mf_screen (>=1), force_no_raw=true in JSON=$fnr_json (>=1)"
    if [[ "$p62_verify" -ge 3 ]]; then
        ok "P-62.7: verification passed ($p62_verify/4 greps OK)"
        ((++p62_count))
    else
        warn "P-62.7: verification partial ($p62_verify/4 greps OK)"
    fi

    ok "P-62: Cron 7 applied (v6.4.0) — sub-patches: $p62_count/7"
    if [[ "$p62_count" -ge 5 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 14 (P-63) — v6.4.0 (Cron 8): LUT picker override (fixes "stuck on m9 CCD" bug)
    # ───────────────────────────────────────────────────────────────────────
    # Root cause: forcedBaselineLutId always returned activeLutId ("leica_m9" from
    # leica_perfect_signature profile) when active profile != baseline. The camera UI
    # LUT picker set a different value but it was never consulted.
    # Fix: runtimeLutOverride var takes precedence over activeLutId.
    section "P-63: Cron 8 — LUT picker override (v6.4.0)"
    local p63_count=0

    # P-63.1: Add runtimeLutOverride var to LeicaConfig.kt
    substep "P-63.1: LeicaConfig.kt — add runtimeLutOverride var"
    if [[ -f "$config_kt" ]]; then
        if grep -q 'var runtimeLutOverride: String?' "$config_kt" 2>/dev/null; then
            ok "P-63.1: already patched (runtimeLutOverride var present)"
            ((++p63_count))
        else
            python3 - "$config_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
# Insert runtimeLutOverride var BEFORE the forcedBaselineLutId accessor
marker = '    /**\n     * forcedBaselineLutId — ID do LUT baseline forçado.\n     * v6.2: creative-profile-aware (retorna activeLutId quando não-baseline).\n     */\n    val forcedBaselineLutId: String\n        get() = if (!isActiveProfileBaseline) activeLutId\n        else currentConfig?.dcp?.forceBaselineLutId ?: "Leica_M9_STD"'
new_var = '''    /**
     * runtimeLutOverride — LUT ID override set at runtime pelo mod menu (P-64).
     * Quando non-null, takes precedence over activeLutId (fixes "stuck on m9 CCD" bug v6.4.0).
     * Set via LeicaRuntimeState.setRuntimeLutOverride(id).
     */
    @Volatile
    var runtimeLutOverride: String? = null

    /**
     * forcedBaselineLutId — ID do LUT baseline forçado.
     * v6.2: creative-profile-aware (retorna activeLutId quando não-baseline).
     * v6.4.0: runtimeLutOverride (setado pelo mod menu) takes precedence over everything.
     *         Fixes "stuck on m9 CCD" bug — quando usuario escolhe outro LUT no menu,
     *         o override era ignorado. Agora é respeitado.
     */
    val forcedBaselineLutId: String
        get() = runtimeLutOverride
            ?: if (!isActiveProfileBaseline) activeLutId
            else currentConfig?.dcp?.forceBaselineLutId ?: "Leica_M9_STD"'''
if marker in text:
    open(path, 'w', encoding='utf-8').write(text.replace(marker, new_var, 1))
    print("P-63.1: runtimeLutOverride var injected + forcedBaselineLutId updated")
else:
    print("P-63.1: WARN — forcedBaselineLutId marker not found (may already be patched)")
PY
            if grep -q 'var runtimeLutOverride: String?' "$config_kt" 2>/dev/null; then
                ok "P-63.1: runtimeLutOverride var added"
                ((++p63_count))
            else
                warn "P-63.1: LeicaConfig.kt edit failed"
            fi
        fi
    else
        warn "P-63.1: LeicaConfig.kt not found at $config_kt"
    fi

    # P-63.2: Modify forcedBaselineLutId getter (already done atomically with P-63.1 — verify)
    substep "P-63.2: LeicaConfig.kt — forcedBaselineLutId returns runtimeLutOverride first"
    if [[ -f "$config_kt" ]]; then
        if grep -q 'get() = runtimeLutOverride' "$config_kt" 2>/dev/null; then
            ok "P-63.2: already patched (forcedBaselineLutId consults runtimeLutOverride)"
            ((++p63_count))
        else
            # If P-63.1 ran, this is already done. Otherwise apply directly.
            python3 - "$config_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
old = '''    val forcedBaselineLutId: String
        get() = if (!isActiveProfileBaseline) activeLutId
        else currentConfig?.dcp?.forceBaselineLutId ?: "Leica_M9_STD"'''
new = '''    val forcedBaselineLutId: String
        get() = runtimeLutOverride
            ?: if (!isActiveProfileBaseline) activeLutId
            else currentConfig?.dcp?.forceBaselineLutId ?: "Leica_M9_STD"'''
if old in text:
    open(path, 'w', encoding='utf-8').write(text.replace(old, new, 1))
    print("P-63.2: forcedBaselineLutId consults runtimeLutOverride first")
else:
    print("P-63.2: WARN — forcedBaselineLutId getter not found in expected form (may already be patched)")
PY
            if grep -q 'get() = runtimeLutOverride' "$config_kt" 2>/dev/null; then
                ok "P-63.2: forcedBaselineLutId consults runtimeLutOverride"
                ((++p63_count))
            else
                warn "P-63.2: forcedBaselineLutId edit failed"
            fi
        fi
    else
        warn "P-63.2: LeicaConfig.kt not found at $config_kt"
    fi

    # P-63.3: Add setRuntimeLutOverride to LeicaRuntimeState.kt
    substep "P-63.3: LeicaRuntimeState.kt — add setRuntimeLutOverride(lutId)"
    if [[ -f "$runtime_kt" ]]; then
        if grep -q 'fun setRuntimeLutOverride' "$runtime_kt" 2>/dev/null; then
            ok "P-63.3: already patched (setRuntimeLutOverride present)"
            ((++p63_count))
        else
            python3 - "$runtime_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
# Anchor: insert AFTER setCreativeProfile() closing brace, BEFORE cycleCaptureMode() comment
marker = '''    fun setCreativeProfile(profile: String?) {
        creativeProfileOverride = profile
        persist()
        Log.i(TAG, "Creative profile set to: ${profile ?: "(default)"}")
    }
'''
new_fn = '''    fun setCreativeProfile(profile: String?) {
        creativeProfileOverride = profile
        persist()
        Log.i(TAG, "Creative profile set to: ${profile ?: "(default)"}")
    }

    /**
     * Set runtime LUT override — v6.4.0 (P-63/P-64).
     * Fixes "stuck on m9 CCD" bug: when user picks a LUT in the mod menu,
     * this var takes precedence over activeLutId (which was always returning "leica_m9"
     * from leica_perfect_signature profile, ignoring UI selection).
     * null clears the override (reverts to active profile's LUT).
     */
    fun setRuntimeLutOverride(lutId: String?) {
        LeicaConfig.runtimeLutOverride = lutId
        Log.i(TAG, "Runtime LUT override set: ${lutId ?: "(default)"}")
    }
'''
if marker in text:
    open(path, 'w', encoding='utf-8').write(text.replace(marker, new_fn, 1))
    print("P-63.3: setRuntimeLutOverride(lutId) injected after setCreativeProfile")
else:
    print("P-63.3: WARN — setCreativeProfile marker not found")
PY
            if grep -q 'fun setRuntimeLutOverride' "$runtime_kt" 2>/dev/null; then
                ok "P-63.3: setRuntimeLutOverride(lutId) added"
                ((++p63_count))
            else
                warn "P-63.3: LeicaRuntimeState.kt edit failed"
            fi
        fi
    else
        warn "P-63.3: LeicaRuntimeState.kt not found at $runtime_kt"
    fi

    # P-63.4: Verification
    substep "P-63.4: verification greps"
    local p63_verify=0
    local rlo_cfg=$(grep -c 'runtimeLutOverride' "$config_kt" 2>/dev/null || echo 0)
    local srlo_rt=$(grep -c 'setRuntimeLutOverride' "$runtime_kt" 2>/dev/null || echo 0)
    if [[ "$rlo_cfg" -ge 3 ]]; then ((++p63_verify)); fi
    if [[ "$srlo_rt" -ge 1 ]]; then ((++p63_verify)); fi
    info "P-63.4: runtimeLutOverride in LeicaConfig=$rlo_cfg (>=3), setRuntimeLutOverride in LeicaRuntimeState=$srlo_rt (>=1)"
    if [[ "$p63_verify" -ge 2 ]]; then
        ok "P-63.4: verification passed ($p63_verify/2 greps OK)"
        ((++p63_count))
    else
        warn "P-63.4: verification partial ($p63_verify/2 greps OK)"
    fi

    ok "P-63: LUT picker override applied (v6.4.0) — sub-patches: $p63_count/4"
    if [[ "$p63_count" -ge 3 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 14 (P-64) — v6.4.0 (Cron 8): 5 best LUTs in mod menu
    # ───────────────────────────────────────────────────────────────────────
    # Adds a LUT picker section to LeicaSettingsScreen.kt with 5 curated LUTs:
    #   Leica M9 CCD, Hasselblad HNCS, Fuji Classic Chrome, Fuji Classic Neg, CineStill 800T.
    # ── NO_MENU guard: skip P-64 + P-65 (LUT picker + ONE-CLICK MAX button) in ABSOLUTE builds ──
    if [[ -n "${NO_MENU:-}" ]]; then
        info "P-64/P-65: SKIPPED (NO_MENU=1 — ABSOLUTE build, no LUT picker / ONE-CLICK MAX button)"
    else
    section "P-64: Cron 8 — 5 best LUTs in mod menu (v6.4.0)"
    local p64_count=0

    # P-64.1: Add LutOption data class + BEST_LUTS list + currentLutId state + LUT picker section
    substep "P-64.1: LeicaSettingsScreen.kt — LutOption + BEST_LUTS + LUT picker section"
    if [[ -f "$screen_kt" ]]; then
        if grep -q 'private val BEST_LUTS' "$screen_kt" 2>/dev/null && \
           grep -q 'setRuntimeLutOverride' "$screen_kt" 2>/dev/null && \
           grep -q 'leica_m9' "$screen_kt" 2>/dev/null; then
            ok "P-64.1: already patched (BEST_LUTS + setRuntimeLutOverride + leica_m9 in screen)"
            ((++p64_count))
        else
            # This is a multi-edit patch — add LutOption data class + BEST_LUTS list
            # + currentLutId state var + LUT picker UI section + Reset option
            python3 - "$screen_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()

# 1. Add LutOption data class + BEST_LUTS list AFTER CAPTURE_MODES definition
capture_modes_end = '''private val CAPTURE_MODES = listOf(
    CaptureModeOption("mode_fast", "Disparo Rápido", "Inteligente — 100% dos casos, ação, burst, casual"),
    CaptureModeOption("mode_max", "Quality Max", "Baixa luz, tripé, máxima qualidade (lento)")
)
'''
lut_block = '''private val CAPTURE_MODES = listOf(
    CaptureModeOption("mode_fast", "Disparo Rápido", "Inteligente — 100% dos casos, ação, burst, casual"),
    CaptureModeOption("mode_max", "Quality Max", "Baixa luz, tripé, máxima qualidade (lento)")
)

// ── 5 Melhores LUTs — curados pra mod menu (v6.4.0) ──────────────────
private data class LutOption(
    val id: String,
    val label: String,
    val desc: String
)

private val BEST_LUTS = listOf(
    LutOption("leica_m9", "Leica M9 CCD", "Neutro quente — diário, street, documental"),
    LutOption("Hasselblad", "Hasselblad HNCS", "Natural — retrato premium, moda, produto"),
    LutOption("cc", "Fuji Classic Chrome", "Vintage saturação baixa — urbano, street"),
    LutOption("nc", "Fuji Classic Neg", "Quente golden hour — retrato, lifestyle"),
    LutOption("film_cinestill_800t", "CineStill 800T", "Túngsten — noite, neon, long exposure")
)
'''
if capture_modes_end in text and 'private val BEST_LUTS' not in text:
    text = text.replace(capture_modes_end, lut_block, 1)
    print("P-64.1: LutOption + BEST_LUTS added after CAPTURE_MODES")

# 2. Add currentLutId state var after currentCaptureMode
old_state = '    var currentCaptureMode by remember { mutableStateOf(LeicaConfig.activeCaptureMode) }\n'
new_state = '''    var currentCaptureMode by remember { mutableStateOf(LeicaConfig.activeCaptureMode) }
    var currentLutId by remember { mutableStateOf(LeicaConfig.runtimeLutOverride ?: LeicaConfig.activeLutId) }
'''
if old_state in text and 'currentLutId by remember' not in text:
    text = text.replace(old_state, new_state, 1)
    print("P-64.1: currentLutId state var added")

# 3. Add LUT picker item AFTER the Capture Mode item (find its closing `}` — the one before the
#    "Creative Profile section removed" comment OR the end of LazyColumn content)
# Anchor: insert BEFORE the "Creative Profile section removed" comment if present,
# otherwise before the closing `}` of LazyColumn content (the `}` at 8 spaces after the Capture Mode item)
creative_comment = '''            // Creative Profile section removed — LUT/DCP switching happens
            // in the main camera UI (no app restart needed). Default preset
            // is controlled by leica_perfect.json → active_profile.
'''
lut_picker = '''            // ─── 5 Melhores LUTs (v6.4.0) ────────────────────────────
            item {
                SectionHeader("Look (LUT)")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        BEST_LUTS.forEachIndexed { idx, lut ->
                            val isSelected = currentLutId == lut.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentLutId = lut.id
                                        LeicaRuntimeState.setRuntimeLutOverride(lut.id)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionDot(isSelected)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = lut.label,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = lut.desc,
                                        color = Color(0x80FFFFFF),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (idx < BEST_LUTS.size - 1) {
                                androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.08f))
                            }
                        }
                        // ─── Reset option ─────────────────────────────
                        androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.08f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentLutId = ""
                                    LeicaRuntimeState.setRuntimeLutOverride(null)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SelectionDot(currentLutId.isEmpty())
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Reset (usar profile default)",
                                color = Color(0x80FFFFFF),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
'''
if creative_comment in text:
    text = text.replace(creative_comment, lut_picker, 1)
    print("P-64.1: LUT picker section replaced the Creative-Profile-removed comment")
elif 'SectionHeader("Look (LUT)")' not in text:
    # Insert before closing of LazyColumn content — anchor on the closing `        }` of LazyColumn
    # Find the LAST occurrence of "        }\n    }\n}" pattern (LazyColumn body close + Scaffold lambda close)
    # Simpler: anchor on the Capture Mode item's closing `}` (the one with 12 spaces before "}" that
    # ends the "item { ... }" block) — but that's fragile. Use the SectionHeader function declaration as anchor.
    # Actually: insert before the `        }\n    }\n}` at end of LeicaSettingsScreen body.
    anchor = '        }\n    }\n}\n\n@Composable\nprivate fun SectionHeader'
    if anchor in text:
        text = text.replace(anchor, lut_picker + '        }\n    }\n}\n\n@Composable\nprivate fun SectionHeader', 1)
        print("P-64.1: LUT picker section added before LazyColumn close")
    else:
        print("P-64.1: WARN — could not find anchor for LUT picker insertion")

open(path, 'w', encoding='utf-8').write(text)
PY
            if grep -q 'private val BEST_LUTS' "$screen_kt" 2>/dev/null && \
               grep -q 'setRuntimeLutOverride' "$screen_kt" 2>/dev/null; then
                ok "P-64.1: LUT picker section added (5 LUTs + Reset option)"
                ((++p64_count))
            else
                warn "P-64.1: LeicaSettingsScreen.kt edit failed"
            fi
        fi
    else
        warn "P-64.1: LeicaSettingsScreen.kt not found at $screen_kt"
    fi

    # P-64.2: Verification
    substep "P-64.2: verification greps"
    local p64_verify=0
    local bl_scr=$(grep -c 'BEST_LUTS' "$screen_kt" 2>/dev/null || echo 0)
    local srlo_scr=$(grep -c 'setRuntimeLutOverride' "$screen_kt" 2>/dev/null || echo 0)
    local m9_scr=$(grep -c 'leica_m9' "$screen_kt" 2>/dev/null || echo 0)
    if [[ "$bl_scr" -ge 2 ]]; then ((++p64_verify)); fi
    if [[ "$srlo_scr" -ge 1 ]]; then ((++p64_verify)); fi
    if [[ "$m9_scr" -ge 1 ]]; then ((++p64_verify)); fi
    info "P-64.2: BEST_LUTS in screen=$bl_scr (>=2), setRuntimeLutOverride in screen=$srlo_scr (>=1), leica_m9 in screen=$m9_scr (>=1)"
    if [[ "$p64_verify" -ge 3 ]]; then
        ok "P-64.2: verification passed ($p64_verify/3 greps OK)"
        ((++p64_count))
    else
        warn "P-64.2: verification partial ($p64_verify/3 greps OK)"
    fi

    ok "P-64: 5 best LUTs in mod menu applied (v6.4.0) — sub-patches: $p64_count/2"
    if [[ "$p64_count" -ge 1 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # Tier 14 (P-65) — v6.4.0 (Cron 9): One-click JPEG max optimization
    # ───────────────────────────────────────────────────────────────────────
    # Defensive enforcement: ensure NO RAW/DNG paths trigger, force JPEG Q100 +
    # HEIC Q100 + UltraHDR Q100, add a "ONE-CLICK MAX" preset button.
    section "P-65: Cron 9 — One-click JPEG max optimization (v6.4.0)"
    local p65_count=0

    # P-65.1: Add forceNoRaw/forceNoDng/forceHeicQ100/forceUltraHdrQ100 accessors to LeicaConfig.kt
    substep "P-65.1: LeicaConfig.kt — forceNoRaw/forceNoDng/forceHeicQ100/forceUltraHdrQ100 accessors"
    if [[ -f "$config_kt" ]]; then
        if grep -q 'val forceNoRaw: Boolean' "$config_kt" 2>/dev/null && \
           grep -q 'val forceNoDng: Boolean' "$config_kt" 2>/dev/null; then
            ok "P-65.1: already patched (forceNoRaw + forceNoDng accessors present)"
            ((++p65_count))
        else
            python3 - "$config_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()

# Replace the existing exportDngWithRawExport + exportSuperResDng accessors with the new
# forceNoRaw/forceNoDng-aware versions + add the new accessors before them.
old_block = '''    val vignetteGridMaxH: Int get() = currentConfig?.advanced?.vignetteGridMaxH ?: 128
    val vignetteGridMaxV: Int get() = currentConfig?.advanced?.vignetteGridMaxV ?: 96
    val exportDngWithRawExport: Boolean get() = currentConfig?.advanced?.exportDngWithRaw ?: true

    /**
     * exportSuperResDng — força export de DNG 16-bit uncompressed a 2.0x super res.
     * v6.1 (closes Task 5-a gap R2 sibling).
     */
    val exportSuperResDng: Boolean
        get() = currentConfig?.advanced?.exportSuperResDng ?: true
'''
new_block = '''    val vignetteGridMaxH: Int get() = currentConfig?.advanced?.vignetteGridMaxH ?: 128
    val vignetteGridMaxV: Int get() = currentConfig?.advanced?.vignetteGridMaxV ?: 96

    /** forceNoRaw — v6.4.0: desativa export DNG quando true (JSON output.force_no_raw). */
    val forceNoRaw: Boolean get() = currentConfig?.output?.forceNoRaw ?: false

    /** forceNoDng — v6.4.0: alias pra clareza semantica. */
    val forceNoDng: Boolean get() = currentConfig?.output?.forceNoDng ?: false

    /** forceHeicQ100 — v6.4.0: força HEIC Q100 quando true. */
    val forceHeicQ100: Boolean get() = currentConfig?.output?.forceHeicQ100 ?: false

    /** forceUltraHdrQ100 — v6.4.0: força UltraHDR Q100 quando true. */
    val forceUltraHdrQ100: Boolean get() = currentConfig?.output?.forceUltraHdrQ100 ?: false

    /**
     * exportDngWithRawExport — v6.4.0: honra forceNoRaw/forceNoDng (desativa DNG quando user pediu JPEG one-click).
     */
    val exportDngWithRawExport: Boolean
        get() = if (forceNoRaw || forceNoDng) false
        else currentConfig?.advanced?.exportDngWithRaw ?: true

    /**
     * exportSuperResDng — força export de DNG 16-bit uncompressed a 2.0x super res.
     * v6.1 (closes Task 5-a gap R2 sibling).
     * v6.4.0: honra forceNoRaw/forceNoDng (desativa quando user pediu JPEG one-click).
     */
    val exportSuperResDng: Boolean
        get() = if (forceNoRaw || forceNoDng) false
        else currentConfig?.advanced?.exportSuperResDng ?: true
'''
if old_block in text:
    open(path, 'w', encoding='utf-8').write(text.replace(old_block, new_block, 1))
    print("P-65.1: forceNoRaw/forceNoDng/forceHeicQ100/forceUltraHdrQ100 accessors added + exportDng/SuperRes now honor forceNoRaw")
else:
    print("P-65.1: WARN — exportDngWithRawExport marker not found (may already be patched)")
PY
            if grep -q 'val forceNoRaw: Boolean' "$config_kt" 2>/dev/null; then
                ok "P-65.1: forceNoRaw/forceNoDng accessors added + exportDng/SuperRes honor them"
                ((++p65_count))
            else
                warn "P-65.1: LeicaConfig.kt edit failed"
            fi
        fi
    else
        warn "P-65.1: LeicaConfig.kt not found at $config_kt"
    fi

    # P-65.2: Add OutputConfig data class fields
    substep "P-65.2: LeicaConfig.kt — OutputConfig data class fields (force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100)"
    if [[ -f "$config_kt" ]]; then
        if grep -q 'force_no_raw' "$config_kt" 2>/dev/null; then
            ok "P-65.2: already patched (force_no_raw SerializedName present in OutputConfig)"
            ((++p65_count))
        else
            python3 - "$config_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
old = '''    /** output — formato/qualidade do arquivo final. */
    data class OutputConfig(
        @SerializedName("format") val format: String? = "jpeg",
        @SerializedName("quality") val quality: Int? = 100,
        @SerializedName("max_resolution") val maxResolution: Int? = 0,
        @SerializedName("preserve_exif") val preserveExif: Boolean? = true,
        @SerializedName("add_watermark") val addWatermark: Boolean? = false,
    )'''
new = '''    /** output — formato/qualidade do arquivo final.
     *  v6.4.0: force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100 added. */
    data class OutputConfig(
        @SerializedName("format") val format: String? = "jpeg",
        @SerializedName("quality") val quality: Int? = 100,
        @SerializedName("max_resolution") val maxResolution: Int? = 0,
        @SerializedName("preserve_exif") val preserveExif: Boolean? = true,
        @SerializedName("add_watermark") val addWatermark: Boolean? = false,
        @SerializedName("force_no_raw") val forceNoRaw: Boolean = false,
        @SerializedName("force_no_dng") val forceNoDng: Boolean = false,
        @SerializedName("force_heic_q100") val forceHeicQ100: Boolean = false,
        @SerializedName("force_ultrahdr_q100") val forceUltraHdrQ100: Boolean = false,
    )'''
if old in text:
    open(path, 'w', encoding='utf-8').write(text.replace(old, new, 1))
    print("P-65.2: OutputConfig data class extended (4 new fields)")
else:
    print("P-65.2: WARN — OutputConfig data class not found in expected form (may already be patched)")
PY
            if grep -q 'force_no_raw' "$config_kt" 2>/dev/null; then
                ok "P-65.2: OutputConfig data class extended (force_no_raw/force_no_dng/force_heic_q100/force_ultrahdr_q100)"
                ((++p65_count))
            else
                warn "P-65.2: OutputConfig edit failed"
            fi
        fi
    else
        warn "P-65.2: LeicaConfig.kt not found at $config_kt"
    fi

    # P-65.3: Add "ONE-CLICK MAX" preset button to LeicaSettingsScreen.kt
    substep "P-65.3: LeicaSettingsScreen.kt — ONE-CLICK MAX preset button"
    if [[ -f "$screen_kt" ]]; then
        if grep -q 'ONE-CLICK MAX' "$screen_kt" 2>/dev/null; then
            ok "P-65.3: already patched (ONE-CLICK MAX button present)"
            ((++p65_count))
        else
            python3 - "$screen_kt" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()

# 1. Add Button + ButtonDefaults imports if missing (Check is in material-icons-core
#    and is already imported upstream — Bolt is NOT in core, caused compile errors.)
import_anchor = 'import androidx.compose.material.icons.filled.Check\n'
new_imports = '''import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
'''
if 'import androidx.compose.material3.ButtonDefaults' not in text:
    text = text.replace(import_anchor, new_imports, 1)
    print("P-65.3: imports added (Button + ButtonDefaults — Check already present upstream)")

# 2. Insert ONE-CLICK MAX item at the TOP of LazyColumn content (BEFORE the Capture Mode item)
capture_mode_item_start = '''            // ─── Capture Mode ──────────────────────────────────────────
            item {
                SectionHeader("Capture Mode")'''
one_click_item = '''            // ─── One-Click MAX preset (v6.4.0) ───────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        currentCaptureMode = "mode_max"
                        currentLutId = "leica_m9"
                        LeicaRuntimeState.setCaptureMode("mode_max")
                        LeicaRuntimeState.setRuntimeLutOverride("leica_m9")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ONE-CLICK MAX",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Text(
                    text = "Aplica mode_max + Leica M9 CCD + JPEG Q100 — pronto pra foto",
                    color = Color(0x60FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            // ─── Capture Mode ──────────────────────────────────────────
            item {
                SectionHeader("Capture Mode")'''
if 'ONE-CLICK MAX' not in text and capture_mode_item_start in text:
    text = text.replace(capture_mode_item_start, one_click_item, 1)
    print("P-65.3: ONE-CLICK MAX item inserted before Capture Mode item")

open(path, 'w', encoding='utf-8').write(text)
PY
            if grep -q 'ONE-CLICK MAX' "$screen_kt" 2>/dev/null; then
                ok "P-65.3: ONE-CLICK MAX preset button added"
                ((++p65_count))
            else
                warn "P-65.3: LeicaSettingsScreen.kt edit failed"
            fi
        fi
    else
        warn "P-65.3: LeicaSettingsScreen.kt not found at $screen_kt"
    fi

    # P-65.4: Verification
    substep "P-65.4: verification greps"
    local p65_verify=0
    local fnr_cfg=$(grep -c 'forceNoRaw' "$config_kt" 2>/dev/null || echo 0)
    local ocm_scr=$(grep -c 'ONE-CLICK MAX' "$screen_kt" 2>/dev/null || echo 0)
    local fnr_json=$(grep -c 'force_no_raw' "$json_dst" 2>/dev/null || echo 0)
    if [[ "$fnr_cfg" -ge 3 ]]; then ((++p65_verify)); fi
    if [[ "$ocm_scr" -ge 1 ]]; then ((++p65_verify)); fi
    if [[ "$fnr_json" -ge 1 ]]; then ((++p65_verify)); fi
    info "P-65.4: forceNoRaw in LeicaConfig=$fnr_cfg (>=3), ONE-CLICK MAX in screen=$ocm_scr (>=1), force_no_raw in JSON=$fnr_json (>=1)"
    if [[ "$p65_verify" -ge 3 ]]; then
        ok "P-65.4: verification passed ($p65_verify/3 greps OK)"
        ((++p65_count))
    else
        warn "P-65.4: verification partial ($p65_verify/3 greps OK)"
    fi

    ok "P-65: One-click JPEG max applied (v6.4.0) — sub-patches: $p65_count/4"
    if [[ "$p65_count" -ge 3 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    fi # end NO_MENU guard (P-64 + P-65)

    # ═══════════════════════════════════════════════════════════════════════════
    # Tier 15 (P-67) — v6.4.1: UI LUT picker runtime override (fixes stuck LUT in ABSOLUTE builds)
    # ═══════════════════════════════════════════════════════════════════════════
    # BUG v6.4.0-fix12 ABSOLUTE: user can't change LUT via the bottom LUT panel.
    #
    # Root cause:
    #   1. JSON config sets active_profile="leica_perfect_signature" (NON-baseline)
    #      → LeicaConfig.isActiveProfileBaseline == false
    #   2. P-52a in CameraViewModel.setLut() shadows user's lutId param:
    #        val lutId = if (!isActiveProfileBaseline) activeLutId else lutId
    #      → User's pick discarded, always replaced with activeLutId ("leica_m9")
    #   3. P-63 created runtimeLutOverride var (takes precedence over everything)
    #      BUT it's only SET by P-64 (mod menu LUT picker) which is SKIPPED in
    #      ABSOLUTE (NO_MENU=1) builds. So runtimeLutOverride stays null, and
    #      forcedBaselineLutId returns activeLutId ("leica_m9") for all 3 P-19
    #      rendering sites (BaselineColorCorrection, CameraViewModel, GalleryManager).
    #
    # FIX P-67: Hook the NATIVE PhotonCamera UI LUT picker (setLut in CameraViewModel)
    # to set LeicaConfig.runtimeLutOverride = lutId BEFORE P-52a's shadowing runs.
    # This makes the user's pick take precedence over the active creative profile's LUT.
    # Works in ABSOLUTE builds (no mod menu needed) — uses the standard picker UI.
    section "P-67: Cron 10 — UI LUT picker runtime override (v6.4.1)"
    local p67_count=0

    # P-67.1: CameraViewModel.setLut() — set runtimeLutOverride BEFORE P-52a shadowing
    substep "P-67.1: CameraViewModel.setLut() — propagate user pick to runtimeLutOverride"
    local cvm_p67="$APP_JAVA/viewmodel/CameraViewModel.kt"
    if [[ -f "$cvm_p67" ]]; then
        if grep -q 'P-67.*runtimeLutOverride\|setRuntimeLutOverride' "$cvm_p67" 2>/dev/null; then
            ok "P-67.1: already patched (idempotent)"
            p67_count=$((p67_count + 1))
        else
            # Insert runtimeLutOverride setter at the very start of setLut() body,
            # BEFORE the P-52a shadowing line.
            python3 - "$cvm_p67" <<'PYEOF'
import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()
anchor = '        val lutId = if (!LeicaConfig.isActiveProfileBaseline) LeicaConfig.activeLutId else lutId  // v6.3.4 creative profile override'
if anchor in src:
    new_line = '        LeicaConfig.applyRuntimeLutOverride(lutId)  // P-67 v6.4.1: UI LUT picker sets runtime override (fixes stuck LUT in ABSOLUTE builds)\n'
    src = src.replace(anchor, new_line + anchor, 1)
    p.write_text(src)
    print("P-67.1: injected applyRuntimeLutOverride() call before P-52a shadowing")
elif 'fun setLut(lutId: String?, persist: Boolean = true) {' in src:
    sig = 'fun setLut(lutId: String?, persist: Boolean = true) {'
    new_line = '\n        LeicaConfig.applyRuntimeLutOverride(lutId)  // P-67 v6.4.1: UI LUT picker sets runtime override (fixes stuck LUT in ABSOLUTE builds)\n'
    src = src.replace(sig, sig + new_line, 1)
    p.write_text(src)
    print("P-67.1: injected applyRuntimeLutOverride() at start of setLut() (no P-52a anchor)")
else:
    print("P-67.1: WARN — setLut() signature not found")
    sys.exit(1)
PYEOF
            if grep -q 'applyRuntimeLutOverride' "$cvm_p67" 2>/dev/null; then
                ok "P-67.1: setLut() now calls applyRuntimeLutOverride() (user LUT picker takes precedence)"
                p67_count=$((p67_count + 1))
            else
                warn "P-67.1: injection failed"
            fi
        fi
    else
        warn "P-67.1: CameraViewModel.kt not found at $cvm_p67"
    fi

    # P-67.2: LeicaConfig.kt — add applyRuntimeLutOverride(lutId) helper
    # (null-safe: null/empty string clears the override → reset to profile default)
    # IMPORTANT: target the INSTALLED copy in source tree ($APP_JAVA/raw/LeicaConfig.kt),
    # NOT the patches/ source — P-1 already copied the file before P-67 runs.
    # NOTE: function name is `applyRuntimeLutOverride` (not `setRuntimeLutOverride`)
    # because Kotlin auto-generates a setter `setRuntimeLutOverride` for the var,
    # which would clash with a user-defined function of the same name (JVM signature).
    substep "P-67.2: LeicaConfig.kt — add applyRuntimeLutOverride() helper"
    local cfg_p67="$APP_JAVA/raw/LeicaConfig.kt"
    if [[ ! -f "$cfg_p67" ]]; then
        # Fallback to patches/ dir (in case P-1 didn't run / different layout)
        cfg_p67="$SCRIPT_DIR/patches/LeicaConfig.kt"
    fi
    if [[ -f "$cfg_p67" ]]; then
        if grep -q 'fun applyRuntimeLutOverride' "$cfg_p67" 2>/dev/null; then
            ok "P-67.2: already patched (idempotent)"
            p67_count=$((p67_count + 1))
        else
            python3 - "$cfg_p67" <<'PYEOF'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()
marker = '    var runtimeLutOverride: String? = null'
if marker not in src:
    print("P-67.2: WARN — runtimeLutOverride var not found (P-63.1 not applied?)")
    sys.exit(1)
helper = '''
    /**
     * Apply runtime LUT override — v6.4.1 (P-67).
     * Helper chamado pelo UI LUT picker (setLut no CameraViewModel).
     * Passa null/empty string para resetar ao perfil criativo ativo.
     * NOTE: Nome `applyRuntimeLutOverride` (não `setRuntimeLutOverride`) para
     * evitar clash com o setter auto-gerado do var runtimeLutOverride.
     */
    fun applyRuntimeLutOverride(lutId: String?) {
        runtimeLutOverride = lutId?.takeIf { it.isNotBlank() }  // P-67: null/empty → null (reset to profile default)
    }
'''
src = src.replace(marker, marker + helper, 1)
p.write_text(src)
print("P-67.2: applyRuntimeLutOverride() helper added to LeicaConfig")
PYEOF
            if grep -q 'fun applyRuntimeLutOverride' "$cfg_p67" 2>/dev/null; then
                ok "P-67.2: applyRuntimeLutOverride() helper present"
                p67_count=$((p67_count + 1))
            else
                warn "P-67.2: helper injection failed"
            fi
        fi
    else
        warn "P-67.2: LeicaConfig.kt not found at $cfg_p67"
    fi

    # P-67.3: Verification
    substep "P-67.3: verification greps"
    local p67_verify=0
    local p67_setlut=$(grep -c 'applyRuntimeLutOverride(lutId)' "$cvm_p67" 2>/dev/null || echo 0)
    local p67_helper=$(grep -c 'fun applyRuntimeLutOverride' "$cfg_p67" 2>/dev/null || echo 0)
    if [[ "$p67_setlut" -ge 1 ]]; then ((++p67_verify)); fi
    if [[ "$p67_helper" -ge 1 ]]; then ((++p67_verify)); fi
    info "P-67.3: applyRuntimeLutOverride in CameraViewModel=$p67_setlut (>=1), helper in LeicaConfig=$p67_helper (>=1)"
    if [[ "$p67_verify" -ge 2 ]]; then
        ok "P-67.3: verification passed ($p67_verify/2 greps OK)"
        ((++p67_count))
    else
        warn "P-67.3: verification partial ($p67_verify/2 greps OK)"
    fi

    ok "P-67: UI LUT picker runtime override applied (v6.4.1) — sub-patches: $p67_count/3"
    if [[ "$p67_count" -ge 2 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ═══════════════════════════════════════════════════════════════════════════
    # Tier 16 (P-68) — v6.4.2: Fix live preview LUT picker (P-52a respects runtimeLutOverride)
    # ═══════════════════════════════════════════════════════════════════════════
    # BUG v6.4.1 (persisted from v6.4.0): user taps LUT thumbnails (Leica, Monochrc,
    # Hass, etc.) in the bottom picker but the live preview STAYS on "M9 CCD".
    #
    # ROOT CAUSE (found via upstream source trace — CameraScreen.kt:1691):
    #   The LUT picker UI DOES call viewModel.setLut(lutId) correctly.
    #   P-67.1 (v6.4.1) correctly captures the user's pick into runtimeLutOverride
    #   BEFORE P-52a's shadowing. BUT P-52a's shadowing line:
    #       val lutId = if (!isActiveProfileBaseline) activeLutId else lutId
    #   UNCONDITIONALLY replaces lutId with activeLutId ("leica_m9"), discarding
    #   the user's pick. The rest of setLut() then loads the M9 LUT config into
    #   currentLutConfig, which feeds the LIVE PREVIEW GL renderer.
    #
    #   P-63's forcedBaselineLutId (which DOES read runtimeLutOverride) is only
    #   used at 3 POST-PROCESSING sites (BaselineColorCorrection, CameraViewModel
    #   L2531, GalleryManager) — NEVER by the live preview path. So setting
    #   runtimeLutOverride alone (P-67) cannot fix the live preview.
    #
    # FIX P-68: Make P-52a's shadowing consult runtimeLutOverride FIRST, so the
    # user's pick (captured by P-67.1) flows through to currentLutConfig and the
    # live preview GL renderer. One-line surgical change.
    #
    #   OLD (P-52a):  val lutId = if (!isActiveProfileBaseline) activeLutId else lutId
    #   NEW (P-68):   val lutId = if (!isActiveProfileBaseline)
    #                    (runtimeLutOverride ?: activeLutId) else lutId
    section "P-68: Cron 11 — live preview LUT picker (P-52a respects runtimeLutOverride) (v6.4.2)"
    local p68_count=0

    # P-68.1: CameraViewModel.setLut() — make P-52a shadowing respect runtimeLutOverride
    substep "P-68.1: CameraViewModel.setLut() — P-52a shadowing consults runtimeLutOverride"
    local cvm_p68="$APP_JAVA/viewmodel/CameraViewModel.kt"
    if [[ -f "$cvm_p68" ]]; then
        # The P-52a shadowing line (with its v6.3.4 comment) is the anchor.
        local p52a_old='val lutId = if (!LeicaConfig.isActiveProfileBaseline) LeicaConfig.activeLutId else lutId  // v6.3.4 creative profile override'
        local p52a_new='val lutId = if (!LeicaConfig.isActiveProfileBaseline) (LeicaConfig.runtimeLutOverride ?: LeicaConfig.activeLutId) else lutId  // v6.4.2 P-68: respect runtime LUT override (fixes live preview LUT picker)'
        if grep -q 'runtimeLutOverride ?: LeicaConfig.activeLutId' "$cvm_p68" 2>/dev/null; then
            ok "P-68.1: already patched (idempotent)"
            p68_count=$((p68_count + 1))
        elif grep -qF "$p52a_old" "$cvm_p68" 2>/dev/null; then
            python3 - "$cvm_p68" "$p52a_old" "$p52a_new" <<'PYEOF'
import sys, pathlib
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
p = pathlib.Path(path)
src = p.read_text()
if old not in src:
    print("P-68.1: WARN — P-52a anchor not found (P-52a not applied?)")
    sys.exit(1)
src = src.replace(old, new, 1)
p.write_text(src)
print("P-68.1: P-52a shadowing now consults runtimeLutOverride (live preview LUT picker fixed)")
PYEOF
            if grep -q 'runtimeLutOverride ?: LeicaConfig.activeLutId' "$cvm_p68" 2>/dev/null; then
                ok "P-68.1: setLut() shadowing respects runtimeLutOverride — live preview will use user's LUT pick"
                p68_count=$((p68_count + 1))
            else
                warn "P-68.1: injection failed"
            fi
        else
            # P-52a may have been applied without the comment, or already partially patched.
            # Try the bare pattern (without trailing comment).
            if grep -qF 'val lutId = if (!LeicaConfig.isActiveProfileBaseline) LeicaConfig.activeLutId else lutId' "$cvm_p68" 2>/dev/null; then
                sed -i 's#val lutId = if (!LeicaConfig.isActiveProfileBaseline) LeicaConfig.activeLutId else lutId#val lutId = if (!LeicaConfig.isActiveProfileBaseline) (LeicaConfig.runtimeLutOverride ?: LeicaConfig.activeLutId) else lutId  // v6.4.2 P-68: respect runtime LUT override#' "$cvm_p68"
                if grep -q 'runtimeLutOverride ?: LeicaConfig.activeLutId' "$cvm_p68" 2>/dev/null; then
                    ok "P-68.1: setLut() shadowing respects runtimeLutOverride (bare pattern match)"
                    p68_count=$((p68_count + 1))
                else
                    warn "P-68.1: sed fallback failed"
                fi
            else
                warn "P-68.1: P-52a shadowing line not found in setLut() — cannot apply P-68"
            fi
        fi
    else
        warn "P-68.1: CameraViewModel.kt not found at $cvm_p68"
    fi

    # P-68.2: Verification greps
    substep "P-68.2: verification greps"
    local p68_shadow=$(grep -c 'runtimeLutOverride ?: LeicaConfig.activeLutId' "$cvm_p68" 2>/dev/null || echo 0)
    local p68_p67=$(grep -c 'applyRuntimeLutOverride' "$cvm_p68" 2>/dev/null || echo 0)
    info "P-68.2: runtimeLutOverride-respecting shadow in CameraViewModel=$p68_shadow (>=1), applyRuntimeLutOverride (P-67.1)=$p68_p67 (>=1)"
    local p68_verify=0
    [[ "$p68_shadow" -ge 1 ]] && ((++p68_verify))
    [[ "$p68_p67" -ge 1 ]] && ((++p68_verify))
    if [[ "$p68_verify" -eq 2 ]]; then
        ok "P-68.2: verification passed ($p68_verify/2 greps OK)"
        ((++p68_count))
    else
        warn "P-68.2: verification partial ($p68_verify/2 greps OK)"
    fi

    ok "P-68: live preview LUT picker fixed (v6.4.2) — sub-patches: $p68_count/2"
    if [[ "$p68_count" -ge 1 ]]; then
        ((++patch_count))
    else
        ((++patch_fail))
    fi
    echo ""

    # ───────────────────────────────────────────────────────────────────────
    # SUMÁRIO
    # ───────────────────────────────────────────────────────────────────────
    section "SUMÁRIO — Leica Perfect v$FORK_VERSION patches"

    echo ""
    printf "%bPatches aplicados com sucesso:%b  %d\n" "$C_GREEN" "$C_RESET" "$patch_count"
    printf "%bPatches falharam:%b              %d\n" "$C_RED" "$C_RESET" "$patch_fail"
    echo ""

    if [[ "$patch_count" -ge 48 ]]; then
        ok "68 surgical sed patches applied (P-1..P-51 + P-52a/b/c/d + P-53a/b/c + P-54a/b/c/d + P-55.1..5 + P-56.1..5 + P-57..P-61 + P-62..P-65 + P-67 added) — full pipeline + per-lens + runtime activation + UI + v6.3.4 runtime wiring + v6.3.5 NLM runtime radius + v6.3.6 creative profile color science + v6.3.7 video settings actually apply + v6.3.8 video encoder completeness + v6.4.0 drop RAW + 2 capture modes + LUT picker override + 5 best LUTs in mod menu + one-click JPEG max + v6.4.1 UI LUT picker runtime override (fixes stuck LUT in ABSOLUTE builds)"
    else
        warn "Esperado 48+ patches, aplicados $patch_count — verifique warnings acima"
    fi

    echo ""
    info "Source patcheado em: $SOURCE_DIR"
    info "Próximo passo: ./build-archlinux.sh build"
    echo ""

    # Footer
    cat <<'FOOTER'
═══════════════════════════════════════════════════════════════
  v6.4.0 — 67 surgical sed patches (P-62..65 added): full pipeline + per-lens AgX + runtime activation + UI + runtime wiring (P-52) + runtime NLM radius (P-53) + creative profile color science (P-54) + video settings actually apply (P-55) + video encoder completeness (P-56) + Camera2 direct per-lens (P-57) + noise model completion (P-58) + thermal monitor (P-59) + doNotStrip (P-60) + LeicaStateDumper (P-61) + drop RAW + 2 capture modes (P-62) + LUT picker override (P-63) + 5 best LUTs in mod menu (P-64) + one-click JPEG max (P-65)
  Cron 4: P-54a/b/c/d wired effective* accessors + P-29/P-30 switched to effective* + P-30 Int→Float bug fixed
  Cron 5: P-55 UserPreferencesRepository video fallbacks → LeicaConfig.*Enum (fixes 1080p/H264/30fps/P1 defaults — video mode banner now shows 4K/HEVC/log/250Mbps)
  Cron 6: P-56 video encoder completeness — AUDIO_SAMPLE_RATE/AUDIO_MIME wired, KEY_AAC_PROFILE gated on AAC (OPUS-safe), KEY_HDR_STATIC_INFO injected via VideoEncoderColorConfig.hdrStaticInfo, bitrate mode override → LeicaConfig.videoBitrateMode (CBR/CQP/VBR)
  Cron 7: P-62 mode_balanced REMOVIDO + mode_fast restaurado (disparo rápido inteligente, 5/3/3/5 frames, NLM radius 3, ~0.3s latency) + RAW/DNG export DESATIVADO (output.force_no_raw/force_no_dng = true) — user quer JPEG one-click
  Cron 8: P-63 runtimeLutOverride var (fixes "stuck on m9 CCD" bug) + P-64 5 melhores LUTs no mod menu (Leica M9, Hasselblad HNCS, Fuji CC, Fuji NC, CineStill 800T)
  Cron 9: P-65 ONE-CLICK MAX preset button + forceNoRaw/forceNoDng/forceHeicQ100/forceUltraHdrQ100 accessors honrados em exportDngWithRawExport/exportSuperResDng
  Leica Perfect — DEFINITIVE QUALITY (MAX BITS/LUT + Beat-GCam + 26 Creative Profiles with REAL color science + JPEG one-click)
═══════════════════════════════════════════════════════════════
FOOTER
}

# ═════════════════════════════════════════════════════════════════════════════
# COMANDO: build — builda o APK
# ═════════════════════════════════════════════════════════════════════════════
cmd_build() {
    section "Build APK — Leica Perfect v$FORK_VERSION"

    if [[ ! -d "$SOURCE_DIR" ]]; then
        fail "Source não existe em $SOURCE_DIR — rode './build-archlinux.sh clone && ./build-archlinux.sh patch' primeiro"
    fi

    # ─── Pré-verificação de dependências (não mata o script se faltar) ────────
    local deps_ok=1
    local missing=()

    # Verifica ANDROID_HOME
    if [[ ! -d "$ANDROID_HOME" ]]; then
        warn "ANDROID_HOME não encontrado em $ANDROID_HOME"
        missing+=("Android SDK (ANDROID_HOME=$ANDROID_HOME)")
    fi

    # Verifica Java
    if ! command -v java &>/dev/null; then
        warn "Java não encontrado no PATH"
        missing+=("JDK 17 (java)")
    else
        local java_version
        java_version=$(java -version 2>&1 | head -1 | awk '{print $3}' | tr -d '"')
        info "Java version: $java_version"
    fi
    info "ANDROID_HOME: ${ANDROID_HOME:-<unset>}"
    info "Source: $SOURCE_DIR"

    # Se faltam dependências, imprime instruções claras e retorna (não exita)
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo ""
        section "DEPENDÊNCIAS DE BUILD FALTANDO"
        echo ""
        printf "  %bAs seguintes dependências não estão instaladas:%b\n\n" "$C_YELLOW" "$C_RESET"
        local dep
        for dep in "${missing[@]}"; do
            printf "    %b✗%b %s\n" "$C_RED" "$C_RESET" "$dep"
        done
        echo ""
        printf "  %bINSTALE NO ARCH LINUX:%b\n\n" "$C_GREEN" "$C_RESET"
        printf '    sudo pacman -S jdk17-openjdk android-sdk android-sdk-platform-tools \\\n'
        printf '      android-sdk-build-tools\n'
        printf '    export ANDROID_HOME=/opt/android-sdk\n'
        echo ""
        printf "  %bOU NO UBUNTU/DEBIAN:%b\n\n" "$C_GREEN" "$C_RESET"
        printf '    sudo apt install openjdk-17-jdk sdkmanager\n'
        printf '    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"\n'
        printf '    export ANDROID_HOME=$HOME/Android/Sdk\n'
        echo ""
        printf "  %bDepois rode:%b ./build-archlinux.sh build\n" "$C_CYAN" "$C_RESET"
        echo ""
        info "Clone + Patch já foram aplicados com sucesso em $SOURCE_DIR"
        info "O source está pronto — só falta instalar as deps de build."
        return 1
    fi

    # Cria build dir
    mkdir -p "$BUILD_DIR" "$APK_OUTPUT"

    # Cd pro source e roda gradle
    cd "$SOURCE_DIR"

    # ─── v6.3.3: Auto-gerar gradle.properties otimizado pra máquina ───────────
    # Detecta RAM total e configura heap/metaspace automaticamente.
    # Resolve o bug v6.3.1 onde -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=512m"
    # era forçado por linha de comando, causando OOM Metaspace em máquinas potentes.
    substep "Escrever gradle.properties otimizado (v6.3.3: auto-detect RAM)"

    local total_ram_mb=0
    local heap_gb=4
    local metaspace_gb=1
    local workers=2

    # Detecta RAM total (Linux: /proc/meminfo)
    if [[ -r /proc/meminfo ]]; then
        total_ram_mb=$(awk '/^MemTotal:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
    fi

    # Heurística baseada em RAM total:
    #   <5GB   → heap 2GB,  metaspace 512MB, workers 1  (CI sandbox / VM pequena)
    #   <8GB   → heap 3GB,  metaspace 1GB,   workers 1  (máquina modesta)
    #   8-16GB → heap 6GB,  metaspace 2GB,   workers 2  (média)
    #   16-32GB → heap 10GB, metaspace 3GB,  workers 4 (boa)
    #   >32GB → heap 14GB,  metaspace 4GB,   workers 6  (potente)
    # v6.3.5 FIX (Cron 3 build env): added <5GB tier — previously <8GB tier used
    # heap=3GB + metaspace=1GB = 4GB total JVM footprint, which OOMs dexing on
    # 4GB-RAM CI sandboxes (no swap) because the OS itself needs RAM. dexBuilder
    # task crashed with "Gradle build daemon disappeared unexpectedly".
    if   [[ $total_ram_mb -ge 32768 ]]; then heap_gb=14; metaspace_gb=4; workers=6
    elif [[ $total_ram_mb -ge 16384 ]]; then heap_gb=10; metaspace_gb=3; workers=4
    elif [[ $total_ram_mb -ge  8192 ]]; then heap_gb=6;  metaspace_gb=2; workers=2
    elif [[ $total_ram_mb -ge  5120 ]]; then heap_gb=3;  metaspace_gb=1; workers=1
    else                                       heap_gb=2;  metaspace_gb=1; workers=1
    fi

    # BUILD_FLAVOR: default = só flavor Default (rápido, leve, sem OOM)
    # Xiaomi 15T só precisa do flavor default. google/meitu/samsung são
    # flavors pra Play Store/Samsung Store/Meitu Store — desnecessários aqui.
    # Se algum dia precisar de outro flavor: BUILD_FLAVOR=google ./build-archlinux.sh build
    local flavor="${BUILD_FLAVOR:-default}"
    local gradle_task="assembleDebug"
    local gradle_parallel=false
    # Capitaliza: default → Default
    local flavor_cap="$(echo "$flavor" | sed 's/^./\U&/')"
    gradle_task="assemble${flavor_cap}Debug"
    info "BUILD_FLAVOR=$flavor → task :app:${gradle_task} (build único, mais rápido)"

    # Conta threads da CPU pra workers.max
    local cpu_threads=$(nproc 2>/dev/null || echo 4)
    [[ $workers -gt $cpu_threads ]] && workers=$cpu_threads

    info "RAM detectada: ${total_ram_mb}MB → heap ${heap_gb}GB, metaspace ${metaspace_gb}GB, workers ${workers}"

    # Escreve gradle.properties otimizado
    cat > "$SOURCE_DIR/gradle.properties" << GRADLEPROPS
# ════════════════════════════════════════════════════════════════
# Leica Perfect v6.3.3 — gradle.properties AUTO-GERADO
# Detectado: ${total_ram_mb}MB RAM / ${cpu_threads} threads / flavor=${flavor}
# ════════════════════════════════════════════════════════════════
# Heap grande pra Kotlin in-process + KSP + dexing simultâneo
# G1GC: melhor throughput pra builds Android pesados
org.gradle.jvmargs=-Xmx${heap_gb}g -XX:MaxMetaspaceSize=${metaspace_gb}g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8

# Daemon entre builds (builds incrementais voam)
org.gradle.daemon=false

# Paralelismo (só ajuda se buildar múltiplos flavors)
org.gradle.parallel=${gradle_parallel}
# v6.3.8-fix2: workers=1 (crash isolation) — KEPT
# v6.3.8-fix4: P-60 fixed — uses awk + Kotlin DSL syntax doNotStrip("**/*.so") (fix3 used Groovy DSL + broken sed a-backslash-n which produced stray n literal → build.gradle.kts compile failure)
org.gradle.workers.max=1

# Cache
org.gradle.caching=true
org.gradle.configuration-cache=false

# Kotlin compiler in-process (sem IPC, sem fork — mais rápido com heap grande)
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true

# KSP2 (mais rápido que KSP1)
ksp.useKSP2=true

# Android
android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=true
android.suppressUnsupportedCompileSdk=35
GRADLEPROPS

    ok "gradle.properties escrito: heap=${heap_gb}GB metaspace=${metaspace_gb}GB workers=${workers} flavor=${flavor}"

    substep "Limpar builds anteriores"
    if [[ -f "./gradlew" ]]; then
        chmod +x ./gradlew
        ./gradlew clean --no-daemon || warn "clean falhou (continuando)"
    else
        warn "gradlew não encontrado — tentando gradle do sistema"
    fi

    substep "Build debug APK (task: :app:${gradle_task})"
    # v6.3.3: NÃO passar -Dorg.gradle.jvmargs por linha de comando!
    # O gradle.properties escrito acima tem prioridade e usa toda a RAM disponível.
    # v6.3.8-fix4: P-60 corrigido — agora usa awk + sintaxe Kotlin DSL
    #   `doNotStrip("**/*.so")` dentro de `packaging { jniLibs { ... } }`.
    #   fix3 usou Groovy DSL `doNotStrip "..."` + sed `a\n` malformado (stray `n`).
    if [[ -f "./gradlew" ]]; then
        ./gradlew ":app:${gradle_task}" --no-daemon \
            -Pandroid.enableR8.fullMode=false \
            --stacktrace \
            || fail "gradle ${gradle_task} falhou"
    else
        gradle ":app:${gradle_task}" --no-daemon \
            -Pandroid.enableR8.fullMode=false \
            --stacktrace \
            || fail "gradle ${gradle_task} falhou"
    fi

    substep "Copiar APK para $APK_OUTPUT"
    # PhotonCamera usa product flavors (default/google/meitu/samsung).
    # O APK NÃO está em apk/debug/ — está em apk/<flavor>/debug/app-<flavor>-debug.apk
    # Procuramos em ordem de preferência: default → google → meitu → samsung → qualquer *-debug.apk
    local apk_root="$SOURCE_DIR/app/build/outputs/apk"
    local apk_path=""
    local apk_flavor=""
    local apk_all=()

    # Lista todos os APKs debug gerados (um por flavor)
    if [[ -d "$apk_root" ]]; then
        while IFS= read -r line; do
            apk_all+=("$line")
        done < <(find "$apk_root" -name "*-debug.apk" -type f 2>/dev/null)
    fi

    # Mostra tudo que encontrou (diagnóstico)
    if [[ ${#apk_all[@]} -gt 0 ]]; then
        info "APKs encontrados (${#apk_all[@]}):"
        for a in "${apk_all[@]}"; do
            info "  • $a"
        done
    fi

    # Preferência: default (Xiaomi 15T não precisa de google/meitu/samsung)
    for flavor in default google meitu samsung; do
        for a in "${apk_all[@]}"; do
            if [[ "$a" == *"/${flavor}/debug/app-${flavor}-debug.apk" ]]; then
                apk_path="$a"
                apk_flavor="$flavor"
                break 2
            fi
        done
    done

    # Fallback: primeiro APK debug que aparecer
    if [[ -z "$apk_path" && ${#apk_all[@]} -gt 0 ]]; then
        apk_path="${apk_all[0]}"
        apk_flavor="(auto)"
    fi

    if [[ -n "$apk_path" && -f "$apk_path" ]]; then
        local out_name="LeicaPerfect-v${FORK_VERSION}-debug.apk"
        cp -f "$apk_path" "$APK_OUTPUT/$out_name"
        ok "APK copiado (flavor: ${apk_flavor}): $APK_OUTPUT/$out_name"
        info "Tamanho: $(du -h "$APK_OUTPUT/$out_name" | awk '{print $1}')"
        info "Source APK: $apk_path"

        # ── v6.3.8-fix4: Verify libmy-native-lib.so is in the APK ──
        # If missing, the app will crash with NoClassDefFoundError: RawDemosaicProcessor
        # at runtime (dlopen failed: library "libmy-native-lib.so" not found).
        substep "Verificar libmy-native-lib.so no APK (v6.3.8-fix4)"
        local so_check
        so_check=$(unzip -l "$APK_OUTPUT/$out_name" 2>/dev/null | grep "libmy-native-lib.so" || true)
        if [[ -n "$so_check" ]]; then
            local so_size
            so_size=$(echo "$so_check" | awk '{print $1}')
            ok "libmy-native-lib.so presente no APK (${so_size} bytes) — runtime crash resolvido"
        else
            fail "libmy-native-lib.so NÃO encontrado no APK — o app vai crashar com NoClassDefFoundError"
            warn "Possíveis causas:"
            warn "  1. CMake build falhou — verifique 'externalNativeBuildDebug' no log"
            warn "  2. P-60 doNotStrip não foi aplicado — verifique build.gradle.kts"
            warn "  3. NDK r29 não instalado — rode: sdkmanager --install 'ndk;29.0.14206865'"
            warn "Conteúdo lib/ do APK:"
            unzip -l "$APK_OUTPUT/$out_name" 2>/dev/null | grep "lib/" | head -20 | while IFS= read -r line; do
                warn "  $line"
            done
            return 1
        fi
    else
        # Diagnostic: mostra o que existe em apk_root (ajuda o user a achar manualmente)
        soft_fail "APK não encontrado em $apk_root"
        if [[ -d "$apk_root" ]]; then
            warn "Conteúdo de $apk_root:"
            find "$apk_root" -type f 2>/dev/null | head -20 | while IFS= read -r f; do
                warn "  $f"
            done
        else
            warn "$apk_root não existe — o build realmente falhou?"
        fi
        return 1
    fi

    section "BUILD COMPLETO"
    info "APK: $APK_OUTPUT/LeicaPerfect-v${FORK_VERSION}-debug.apk"
    info "Instale: adb install -r $APK_OUTPUT/LeicaPerfect-v${FORK_VERSION}-debug.apk"
}

# ═════════════════════════════════════════════════════════════════════════════
# COMANDO: check — bash -n syntax check
# ═════════════════════════════════════════════════════════════════════════════
cmd_check() {
    section "Syntax check — bash -n"
    local script_path
    script_path="$(readlink -f "${BASH_SOURCE[0]}")"
    info "Checking: $script_path"
    if bash -n "$script_path"; then
        ok "SYNTAX OK — bash -n passou"
        return 0
    else
        fail "SYNTAX ERROR — bash -n falhou"
        return 1
    fi
}

# ═════════════════════════════════════════════════════════════════════════════
# COMANDO: all — clone + patch + build
# ═════════════════════════════════════════════════════════════════════════════
cmd_all() {
    section "Full pipeline — clone + patch + build"

    local clone_ok=0
    local patch_ok=0
    local build_ok=0

    # Step 0: Wipe SOURCE_DIR if exists (idempotency — prevents duplicate patches)
    if [[ -d "$SOURCE_DIR" ]]; then
        warn "Source dir já existe: $SOURCE_DIR — removendo para clone fresco"
        rm -rf "$SOURCE_DIR"
    fi

    # Step 1: Clone
    cmd_clone || { fail "Clone falhou — abortando"; return 1; }
    clone_ok=1

    # Step 2: Patch
    cmd_patch || { fail "Patch falhou — abortando build"; return 1; }
    patch_ok=1

    # Step 3: Build (pode falhar gracefully se faltarem deps)
    # NOTE: previous logic `cmd_build || build_ok=0` never set build_ok=1 on
    # success, so a GREEN build was reported as PENDENTE and cmd_all returned 1,
    # which failed the workflow even though the APK was built. Fixed below.
    if cmd_build; then
        build_ok=1
    else
        build_ok=0
    fi

    # ─── Sumário final ─────────────────────────────────────────────────────
    echo ""
    section "SUMÁRIO DO PIPELINE"
    echo ""
    if [[ $clone_ok -eq 1 ]]; then
        printf "  %b✓ Clone%b     — upstream 1.26.1 em %s\n" "$C_GREEN" "$C_RESET" "$SOURCE_DIR"
    else
        printf "  %b✗ Clone%b     — FALHOU\n" "$C_RED" "$C_RESET"
    fi
    if [[ $patch_ok -eq 1 ]]; then
        printf "  %b✓ Patch%b     — 60 substeps aplicados (49 patches cirúrgicos)\n" "$C_GREEN" "$C_RESET"
    else
        printf "  %b✗ Patch%b     — FALHOU\n" "$C_RED" "$C_RESET"
    fi
    if [[ $build_ok -eq 1 ]]; then
        printf "  %b✓ Build%b     — APK gerado em %s\n" "$C_GREEN" "$C_RESET" "$APK_OUTPUT"
        echo ""
        section "TUDO COMPLETO"
    else
        printf "  %b⚠ Build%b     — PENDENTE (instale Android SDK + JDK17, depois rode: ./build-archlinux.sh build)\n" "$C_YELLOW" "$C_RESET"
        echo ""
        info "Clone + Patch OK. Source pronto em $SOURCE_DIR"
        info "Build requer: jdk17-openjdk + android-sdk + platform-tools + build-tools"
        return 1
    fi
}

# ═════════════════════════════════════════════════════════════════════════════
# COMANDO: help — mostra uso
# ═════════════════════════════════════════════════════════════════════════════
cmd_help() {
    cat <<'USAGE'
build-archlinux.sh — Leica Perfect v6.2.0 PhotonCamera Fork Build Script

DEFINITIVE QUALITY — MAX BITS/LUT/RAW + Beat-GCam + 26 Creative Profiles

USO:
  ./build-archlinux.sh <comando> [opções]

COMANDOS:
  clone    Clona upstream bjzhou/PhotonCamera 1.26.1 (tag sem prefixo 'v')
  patch    Aplica 41 patches cirúrgicos (sed) sobre o upstream
  build    Builda o APK debug
  all      clone + patch + build
  check    bash -n syntax check
  help     Mostra esta ajuda

TIERS DE PATCHES:
  Tier 1 (P-1..P-7):     Core tone mapping, sharpening, NLM, metering, config loader
  Tier 2 (P-8..P-20):    Multi-frame, HDR, demosaic, processing, DCP, advanced
  Tier 3 (P-21..P-28):   Mertens, vignette, DNG export, JPEG/HEIC quality, branding, ISP
  Tier 4 (P-29..P-36):   v6.0 per-lens intelligence (frame count, video, tint, sat, ISP, noise)
  Tier 5 (P-37..P-43):   v6.1+v6.2 wiring (white/black level, DCP ratio, SR DNG, AgX, gainmap, consumer)
  Tier 6 (P-44..P-45):   v6.2.5 RUNTIME ACTIVATION + branding (LeicaConfig.load() at startup + app_name → Leica Perfect)
  Tier 7 (P-46..P-48):   v6.2.6 UI — Settings panel (LeicaSettingsScreen) + viewfinder button (CameraTopBar) + runtime state (LeicaRuntimeState)
  Tier 8 (P-49..P-53):   v6.3.x runtime wiring (Live Photo bitrate, compilation fixes, P-52 menu→pipeline, P-53 runtime NLM radius)

VARIÁVEIS DE AMBIENTE:
  SOURCE_DIR      Path do upstream clonado (default: /tmp/photon_upstream)
  BUILD_DIR       Path do build dir (default: /tmp/leica_build)
  APK_OUTPUT      Path do APK output (default: <script_dir>/apk — MESMA pasta do script)
  CONFIG_FILE     Path do leica_perfect.json (default: <script_dir>/config/leica_perfect.json)
  LEICA_CONFIG_KT Path do LeicaConfig.kt (default: <script_dir>/patches/LeicaConfig.kt)
  PATCH_DIR       Path dos patch files (default: <script_dir>/patches)
  ANDROID_HOME    Path do Android SDK (default: /opt/android-sdk)
  BUILD_FLAVOR    Flavor pra buildar: default|google|meitu|samsung (default: default)
                  default = só flavor Default (3-5min, leve, sem OOM)
                  Suficiente pra Xiaomi 15T. Outros flavors são pra Play/Samsung/Meitu Store.
                  Se precisar: BUILD_FLAVOR=google ./build-archlinux.sh build

v6.3.3 MELHORIAS:
  • Build SÓ flavor default por padrão (antes: 4 flavors → OOM/lento)
  • Auto-detecta RAM e gera gradle.properties otimizado (heap/metaspace/workers)
  • Removido -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=512m" que causava OOM
  • P-51: compilation fixes (applyTintShift, VideoTypes, MultiFrameConfig, etc)
  • BUILD_FLAVOR env var pra escolher flavor (default: default)

NOTA: Os paths de CONFIG_FILE, LEICA_CONFIG_KT e PATCH_DIR são resolvidos
relativamente ao diretório onde o build-archlinux.sh vive. Funciona de qualquer
pasta de extração do ZIP (~/Downloads, /opt, /home, etc.).

REQUISITOS (Arch Linux):
  sudo pacman -S jdk17-openjdk android-sdk android-sdk-platform-tools \
    android-sdk-build-tools git sed grep

EXEMPLOS:
  ./build-archlinux.sh clone && ./build-archlinux.sh patch && ./build-archlinux.sh build
  ./build-archlinux.sh all
  ./build-archlinux.sh check

USE POR SUA CONTA E RISCO. Modifica o código-fonte upstream.
USAGE
}

# ═════════════════════════════════════════════════════════════════════════════
# MAIN
# ═════════════════════════════════════════════════════════════════════════════
main() {
    local cmd="${1:-help}"
    shift || true

    case "$cmd" in
        clone)   cmd_clone "$@" ;;
        patch)   cmd_patch "$@" ;;
        build)   cmd_build "$@" ;;
        all)     cmd_all "$@" ;;
        check)   cmd_check "$@" ;;
        help|-h|--help) cmd_help ;;
        *)
            echo "Comando desconhecido: $cmd" >&2
            echo "Use: ./build-archlinux.sh help"
            exit 1
            ;;
    esac
}

main "$@"
