#!/usr/bin/env bash
# =====================================================================
# fetch-proot.sh
# ---------------------------------------------------------------------
# Downloads the PRoot static binary for arm64.
#
# The APK already does this at first launch via PRootManager.kt;
# this host-side script is here so you can pre-bundle PRoot as an
# asset if you prefer to ship it inside the APK rather than fetch it
# at runtime.
# =====================================================================
set -euo pipefail

OUTDIR="${OUTDIR:-./proot-static}"
mkdir -p "$OUTDIR"

SOURCES=(
    "https://github.com/proot/proot/releases/download/v5.1.0/proot-v5.1.0-arm64-static"
)

ok=0
for url in "${SOURCES[@]}"; do
    echo "==> Trying $url"
    if curl -fL "$url" -o "$OUTDIR/proot-arm64"; then
        chmod +x "$OUTDIR/proot-arm64"
        ok=1
        break
    fi
    echo "    failed; trying next source"
done

if [[ $ok -eq 0 ]]; then
    echo "All PRoot sources failed"
    exit 1
fi

echo
echo "PRoot downloaded to: $OUTDIR/proot-arm64"
echo "SHA256: $(sha256sum "$OUTDIR/proot-arm64" | awk '{print $1}')"
echo
echo "To bundle in the APK, copy this binary to:"
echo "  app/src/main/assets/proot/proot-arm64"
