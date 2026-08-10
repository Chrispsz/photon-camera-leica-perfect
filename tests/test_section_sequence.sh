#!/usr/bin/env bash
# test_section_sequence.sh — Verify all P-01..P-79 patches are PRESENT in
# build-archlinux.sh (as section or substep titles).
#
# NOTE: We do NOT check numeric order. Patches are grouped by feature/tier in
# the build script, not by ID number (e.g., P-50 appears between P-28 and P-29
# by design). The invariant we enforce is PRESENCE: every P-01..P-79 must
# appear at least once. Sub-letters (P-32a, P-32b) count as P-32.
#
# Exit code: 0 on success, 1 if any P-NN is missing.
#
# NOTE: We do NOT use `set -o pipefail` here because the grep-in-loop pattern
# legitimately returns non-zero (when a patch IS missing, that's the signal,
# not an error). pipefail would cause spurious exits.
set -uo pipefail 2>/dev/null || set -uo pipefail
set +e  # explicitly disable errexit — we handle exit codes manually

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_SH="$REPO_ROOT/build-archlinux.sh"

if [[ -t 1 ]]; then
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_RESET=$'\033[0m'
else
    C_GREEN=''; C_RED=''; C_YELLOW=''; C_RESET=''
fi

ok()   { printf "  ${C_GREEN}✓${C_RESET} %s\n" "$1"; }
fail() { printf "  ${C_RED}✗${C_RESET} %s\n" "$1"; }
warn() { printf "  ${C_YELLOW}!${C_RESET} %s\n" "$1"; }

if [[ ! -f "$BUILD_SH" ]]; then
    fail "build-archlinux.sh not found at $BUILD_SH"
    exit 1
fi

echo "── Scanning build-archlinux.sh for P-NN section/substep titles ──"

# Extract all P-NN IDs (with optional sub-letter a-z) from section/substep titles.
# Use [[:space:]] for portability (not \s, which is a GNU grep extension).
# Capture the P-NN number (sub-letter dropped).
found_raw=$(grep -oE '(section|substep)[[:space:]]+"P-[0-9]{1,2}[a-z]?:' "$BUILD_SH" 2>/dev/null | \
    sed -E 's/.*"P-([0-9]{1,2})[a-z]?:.*/\1/' | \
    awk '{ printf "%02d\n", $1 }' | \
    sort -u)

if [[ -z "$found_raw" ]]; then
    fail "No P-NN section/substep titles found in build-archlinux.sh"
    exit 1
fi

# Build a set for O(1) lookup
declare -A found_set=()
while IFS= read -r line; do
    found_set["$line"]=1
done <<< "$found_raw"

found_count=${#found_set[@]}
ok "Found ${found_count} distinct P-NN patches in build-archlinux.sh"

# Check all P-01..P-79 are present (P-79 is the v6.5 dispatcher — a real section)
missing=()
for n in $(seq 1 79); do
    nn=$(printf '%02d' "$n")
    if [[ -z "${found_set[$nn]:-}" ]]; then
        missing+=("P-$nn")
    fi
done

# Check for unexpected extra P-NN (P-80+ shouldn't exist — U-01..U-06 are applied
# via the Python script, not section titles)
extras=()
for key in "${!found_set[@]}"; do
    n=$((10#$key))
    if (( n > 79 )); then
        extras+=("P-$key")
    fi
done

echo
exit_code=0
if [[ ${#missing[@]} -gt 0 ]]; then
    printf "${C_RED}FAIL: %d missing patch(es):${C_RESET}\n" "${#missing[@]}"
    for m in "${missing[@]}"; do
        fail "$m not found in build-archlinux.sh section/substep titles"
    done
    exit_code=1
fi

if [[ ${#extras[@]} -gt 0 ]]; then
    printf "${C_YELLOW}WARN: %d unexpected patch(es) beyond P-79:${C_RESET}\n" "${#extras[@]}"
    for e in "${extras[@]}"; do
        warn "$e — should be U-NN (applied via apply_upgrades_v65.py) or renumbered"
    done
    # extras are advisory, don't change exit_code
fi

if [[ ${#missing[@]} -eq 0 && ${#extras[@]} -eq 0 ]]; then
    printf "${C_GREEN}OK: all P-01..P-79 present in build-archlinux.sh (%d distinct titles)${C_RESET}\n" "$found_count"
fi

exit $exit_code
