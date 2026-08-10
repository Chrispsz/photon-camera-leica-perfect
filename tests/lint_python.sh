#!/usr/bin/env bash
# lint_python.sh — Compile-check all Python files in the repo.
#
# Uses `python3 -m py_compile` (builtin, always available) as the baseline.
# If `ruff` is on PATH, runs it too (non-blocking — warnings only).
#
# Exit code: 0 on success, 1 on any compile error.
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

# Discover python files
mapfile -t files < <(find "$REPO_ROOT" \
    -type f -name '*.py' \
    -not -path '*/node_modules/*' \
    -not -path '*/.git/*' \
    -not -path '*/build/*' \
    -not -path '*/__pycache__/*' \
    2>/dev/null | sort)

if [[ ${#files[@]} -eq 0 ]]; then
    fail "No python files found under $REPO_ROOT"
    exit 1
fi

echo "── python3 -m py_compile (${#files[@]} files) ──"

errors=0
for f in "${files[@]}"; do
    rel="${f#$REPO_ROOT/}"
    if python3 -m py_compile "$f" 2>/dev/null; then
        ok "$rel"
    else
        fail "$rel — compile error:"
        python3 -m py_compile "$f" 2>&1 | sed 's/^/      /'
        ((++errors))
    fi
done

# ruff (optional, non-blocking)
if python3 -c "import ruff" 2>/dev/null || command -v ruff &>/dev/null; then
    echo
    echo "── ruff check (advisory) ──"
    for f in "${files[@]}"; do
        rel="${f#$REPO_ROOT/}"
        if ruff check --select E,F "$f" 2>/dev/null; then
            ok "$rel"
        else
            warn "$rel — ruff findings (advisory, non-blocking):"
            ruff check --select E,F "$f" 2>&1 | sed 's/^/      /' || true
        fi
    done
else
    echo
    warn "ruff not installed — skipping advisory checks (install: pip install ruff)"
fi

echo
if [[ $errors -gt 0 ]]; then
    printf "${C_RED}FAIL: %d file(s) with compile errors${C_RESET}\n" "$errors"
    exit 1
fi
printf "${C_GREEN}OK: all %d python files compile${C_RESET}\n" "${#files[@]}"
exit 0
