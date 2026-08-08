#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# reassemble.sh — Reassemble an APK from 4 fragments + verify sha256
#
# Reads the expected sha256 from apk.sha256 (sibling file) at runtime, so the
# same script works for any build.
#
# Usage:
#   1. Download all 4 fragments (.parta .partb .partc .partd) + this script
#      + apk.sha256 + fragments.sha256 into the same folder.
#   2. bash reassemble.sh
#   3. Install: adb install -r <apk-name>
#
# Works on Linux / macOS / Termux (Android). Requires: cat, sha256sum (or shasum).
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

cd "$(dirname "$0")"

# Find the APK name from apk.sha256 (format: "<sha>  <apkname>")
if [[ ! -f apk.sha256 ]]; then
  echo "MISSING: apk.sha256 — download it alongside the fragments"
  exit 1
fi
APK=$(awk '{print $2}' apk.sha256 | sed 's|^\./||' | head -1)
EXPECTED_SHA=$(awk '{print $1}' apk.sha256 | head -1)

if [[ -z "$APK" || -z "$EXPECTED_SHA" ]]; then
  echo "ERROR: could not parse apk.sha256"
  cat apk.sha256
  exit 1
fi

FRAGMENTS=("${APK}.parta" "${APK}.partb" "${APK}.partc" "${APK}.partd")

echo "==> APK: $APK"
echo "==> Expected sha256: $EXPECTED_SHA"
echo "==> Checking fragments..."
for f in "${FRAGMENTS[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "MISSING: $f — download all 4 fragments first"
    exit 1
  fi
done

echo "==> Reassembling (cat fragments -> $APK)..."
rm -f "$APK"
cat "${FRAGMENTS[@]}" > "$APK"

echo "==> Verifying sha256..."
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$APK" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$APK" | awk '{print $1}')
else
  echo "WARN: no sha256sum/shasum — skipping verification"
  ACTUAL="unverified"
fi

if [[ "$ACTUAL" == "$EXPECTED_SHA" ]]; then
  echo "OK - $APK reassembled (${ACTUAL:0:16}...)"
  echo "  size: $(du -h "$APK" | awk '{print $1}')"
  echo "  install: adb install -r $APK"
else
  echo "FAIL - sha256 mismatch"
  echo "  expected: $EXPECTED_SHA"
  echo "  actual:   $ACTUAL"
  exit 1
fi
