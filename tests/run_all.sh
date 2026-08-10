#!/usr/bin/env bash
# run_all.sh — Run the full quality gate (lint + tests) for the Photon Camera fork.
#
# This is the entry point used by .github/workflows/quality.yml on every PR/push.
# Runs every check in tests/ and aggregates the exit code.
#
# Usage:
#   ./tests/run_all.sh              # run everything
#   ./tests/run_all.sh --quiet      # only print failures + summary
#
# Exit code: 0 iff ALL checks pass, 1 otherwise.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

QUIET=0
[[ "${1:-}" == "--quiet" ]] && QUIET=1

if [[ -t 1 ]]; then
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
    C_GREEN=''; C_RED=''; C_YELLOW=''; C_BOLD=''; C_RESET=''
fi

# Each check: name | command (relative to repo root)
CHECKS=(
    "lint_manifest.py        | python3 tests/lint_manifest.py"
    "lint_bash.sh            | bash tests/lint_bash.sh"
    "lint_python.sh          | bash tests/lint_python.sh"
    "test_manifest_cross_ref | python3 tests/test_manifest_cross_ref.py"
    "test_section_sequence   | bash tests/test_section_sequence.sh"
    "test_upgrades_modular   | python3 tests/test_upgrades_modular.py"
)

echo "${C_BOLD}═══════════════════════════════════════════════════════════════════════════${C_RESET}"
echo "${C_BOLD}  Leica Perfect v6.5.0 — Quality Gate (${#CHECKS[@]} checks)${C_RESET}"
echo "${C_BOLD}═══════════════════════════════════════════════════════════════════════════${C_RESET}"
echo

passed=0
failed=0
failed_names=()

for entry in "${CHECKS[@]}"; do
    name="${entry%%|*}"
    name="${name// /}"
    cmd="${entry#*|}"
    cmd="${cmd# }"

    echo "── ${name} ──"
    cd "$REPO_ROOT"
    if [[ $QUIET -eq 1 ]]; then
        log=$(eval "$cmd" 2>&1)
        rc=$?
    else
        eval "$cmd"
        rc=$?
    fi
    if [[ $rc -eq 0 ]]; then
        if [[ $QUIET -eq 1 ]]; then
            printf "  ${C_GREEN}✓${C_RESET} %s\n" "$name"
        fi
        passed=$((passed + 1))
    else
        if [[ $QUIET -eq 1 ]]; then
            printf "  ${C_RED}✗${C_RESET} %s\n" "$name"
            echo "$log" | sed 's/^/      /'
        fi
        failed=$((failed + 1))
        failed_names+=("$name")
    fi
    echo
done

echo "${C_BOLD}═══════════════════════════════════════════════════════════════════════════${C_RESET}"
printf "  ${C_GREEN}Passed:%b  %d\n" "$C_RESET" "$passed"
printf "  ${C_RED}Failed:%b   %d\n" "$C_RESET" "$failed"
if [[ $failed -gt 0 ]]; then
    printf "  ${C_RED}Failed checks:%b %s\n" "$C_RESET" "${failed_names[*]}"
fi
echo "${C_BOLD}═══════════════════════════════════════════════════════════════════════════${C_RESET}"

if [[ $failed -gt 0 ]]; then
    exit 1
fi
exit 0
