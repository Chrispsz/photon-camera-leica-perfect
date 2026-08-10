#!/usr/bin/env bash
# lint_bash.sh — Syntax-check all bash scripts in the repo.
#
# Uses `bash -n` (builtin, always available) as the baseline.
# If `shellcheck` is on PATH, runs it too (non-blocking — warnings only).
#
# Exit code: 0 on success, 1 on any syntax error.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -t 1 ]]; then
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_RESET=$'\033[0m'
else
    C_GREEN=''; C_RED=''; C_YELLOW=''; C_RESET=''
fi

ok()   { printf "  ${C_GREEN}✓${C_RESET} %s\n" "$1"; }
fail() { printf "  ${C_RED}✗${C_RESET} %s\n" "$1"; }
warn() { printf "  ${C_YELLOW}!${C_RESET} %s\n" "$1"; }

# Discover bash scripts
mapfile -t scripts < <(find "$REPO_ROOT" \
    -type f \( -name '*.sh' -o -name 'reassemble.sh' \) \
    -not -path '*/node_modules/*' \
    -not -path '*/.git/*' \
    -not -path '*/build/*' \
    2>/dev/null | sort)

if [[ ${#scripts[@]} -eq 0 ]]; then
    fail "No bash scripts found under $REPO_ROOT"
    exit 1
fi

echo "── bash -n syntax check (${#scripts[@]} scripts) ──"

errors=0
for s in "${scripts[@]}"; do
    rel="${s#$REPO_ROOT/}"
    if bash -n "$s" 2>/dev/null; then
        ok "$rel"
    else
        fail "$rel — syntax error:"
        bash -n "$s" 2>&1 | sed 's/^/      /'
        ((++errors))
    fi
done

# shellcheck (optional, non-blocking)
if command -v shellcheck &>/dev/null; then
    echo
    echo "── shellcheck (advisory) ──"
    for s in "${scripts[@]}"; do
        rel="${s#$REPO_ROOT/}"
        if shellcheck -S error "$s" 2>/dev/null; then
            ok "$rel"
        else
            warn "$rel — shellcheck findings (advisory, non-blocking):"
            shellcheck -S error "$s" 2>&1 | sed 's/^/      /' || true
        fi
    done
else
    echo
    warn "shellcheck not installed — skipping advisory checks (install: apt install shellcheck)"
fi

echo
if [[ $errors -gt 0 ]]; then
    printf "${C_RED}FAIL: %d script(s) with syntax errors${C_RESET}\n" "$errors"
    exit 1
fi
printf "${C_GREEN}OK: all %d bash scripts pass syntax check${C_RESET}\n" "${#scripts[@]}"
exit 0
