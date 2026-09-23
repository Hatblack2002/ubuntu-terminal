#!/usr/bin/env bash
# =====================================================================
# validate_dex.sh — Comprehensive DEX validation
# ---------------------------------------------------------------------
# Parses ALL classes*.dex in the APK and verifies that:
#
#   1. All expected app classes are present (in any DEX)
#   2. All v0.1.4 logging strings are present
#   3. All v0.1.4 UI strings are present
#   4. No Termux references anywhere
#   5. No Termux URLs (packages.termux.dev, etc.)
#   6. Per-DEX summary: which DEX contains which class
#
# Uses python3 to parse DEX binary format properly (MUTF-8 string table
# with ULEB128 length prefix).
#
# Usage:
#   scripts/validate_dex.sh [path/to/apk]
# =====================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"

PASS=0
FAIL=0
NOTVERIFIED=0

section() {
    echo
    echo "============================================================"
    echo "  $1"
    echo "============================================================"
}
ok()   { printf "  [\033[32mPASS\033[0m] %s\n" "$1"; PASS=$((PASS+1)); }
fail() { printf "  [\033[31mFAIL\033[0m] %s\n" "$1"; FAIL=$((FAIL+1)); }
nv()   { printf "  [\033[33mNOT VERIFIED\033[0m] %s\n" "$1"; NOTVERIFIED=$((NOTVERIFIED+1)); }

if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at: $APK"
    exit 1
fi

section "1. DEX INVENTORY"
TMPDIR=$(mktemp -d)
unzip -q "$APK" 'classes*.dex' -d "$TMPDIR"
DEX_FILES=$(cd "$TMPDIR" && ls classes*.dex 2>/dev/null | sort -V)
DEX_COUNT=$(echo "$DEX_FILES" | wc -l)
printf "  Total DEX files: %d\n" "$DEX_COUNT"
echo "$DEX_FILES" | while read -r d; do
    printf "    %s — %d bytes\n" "$d" "$(stat -c%s "$TMPDIR/$d")"
done

section "2. PARSING ALL DEX STRING TABLES"
ALL_STRINGS="$TMPDIR/all_strings.txt"
python3 - <<PYEOF > "$ALL_STRINGS"
import struct, os
dex_dir = "$TMPDIR"
for name in sorted(os.listdir(dex_dir)):
    if not name.endswith('.dex'): continue
    path = os.path.join(dex_dir, name)
    with open(path, 'rb') as f: data = f.read()
    if data[:4] != b'dex\n':
        continue
    sz = struct.unpack('<I', data[0x38:0x3c])[0]
    off = struct.unpack('<I', data[0x3c:0x40])[0]
    for i in range(sz):
        so = struct.unpack('<I', data[off + i*4 : off + i*4 + 4])[0]
        p = so
        while data[p] & 0x80: p += 1
        p += 1
        end = data.find(b'\x00', p)
        s = data[p:end].decode('utf-8', errors='replace')
        print(s)
PYEOF
TOTAL_STRINGS=$(wc -l < "$ALL_STRINGS")
printf "  Total strings across all DEX: %s\n" "$TOTAL_STRINGS"

section "3. APP CLASSES PRESENCE"
EXPECTED_CLASSES=(
    "Lcom/ubuntuterm/MainActivity;"
    "Lcom/ubuntuterm/UbuntuTerminalApp;"
    "Lcom/ubuntuterm/ui/TerminalViewModel;"
    "Lcom/ubuntuterm/ui/TerminalWorkspaceKt;"
    "Lcom/ubuntuterm/ui/BootstrapScreenKt;"
    "Lcom/ubuntuterm/terminal/TerminalService;"
    "Lcom/ubuntuterm/terminal/TerminalManager;"
    "Lcom/ubuntuterm/terminal/UbuntuSession;"
    "Lcom/ubuntuterm/terminal/UbuntuSession\$SessionState;"
    "Lcom/ubuntuterm/terminal/NativeTerminal;"
    "Lcom/ubuntuterm/bootstrap/BootstrapManager;"
    "Lcom/ubuntuterm/bootstrap/PRootManager;"
    "Lcom/ubuntuterm/ubuntu/PRootRunner;"
    "Lcom/ubuntuterm/util/FileLocations;"
    "Lcom/ubuntuterm/ui/terminal/TerminalBuffer;"
    "Lcom/ubuntuterm/ui/terminal/AnsiParser;"
    "Lcom/ubuntuterm/ui/terminal/TerminalViewKt;"
    "Lcom/ubuntuterm/ui/theme/ColorKt;"
)
for cls in "${EXPECTED_CLASSES[@]}"; do
    if grep -qF "$cls" "$ALL_STRINGS"; then
        ok "Class present: $cls"
    else
        fail "Class MISSING: $cls"
    fi
done

section "4. V0.1.4 LOGGING STRINGS"
LOG_STRINGS=(
    "PLUS_CLICK"
    "SESSION_CREATE_START"
    "PRoot_ARGS_READY"
    "NATIVE_SPAWN_START"
    "NATIVE_SPAWN_RETURN"
    "FG_SERVICE_FAILED"
    "SESSION_FAILED"
    "PTY_READER_START"
    "PTY_REAPER_START"
    "SESSION_RUNNING"
    "FG_SERVICE_START"
    "FG_SERVICE_STARTED"
    "SESSION_REGISTERED"
    "SESSION_START"
    "PTY_READER"
    "PTY_REAPER"
)
for s in "${LOG_STRINGS[@]}"; do
    if grep -qF "$s" "$ALL_STRINGS"; then
        ok "Log string present: $s"
    else
        fail "Log string MISSING: $s"
    fi
done

section "5. V0.1.4 UI STRINGS"
UI_STRINGS=(
    "Starting session"
    "Session failed"
    "Session exited"
    "Ubuntu Terminal"
    "No sessions. Tap + to open one."
)
for s in "${UI_STRINGS[@]}"; do
    if grep -qF "$s" "$ALL_STRINGS"; then
        ok "UI string present: $s"
    else
        fail "UI string MISSING: $s"
    fi
done

section "6. NO TERMUX REFERENCES"
TERMUX_HITS=$(grep -ci "termux" "$ALL_STRINGS" || true)
if [ "$TERMUX_HITS" -eq 0 ]; then
    ok "No 'termux' string anywhere in any DEX"
else
    fail "Found $TERMUX_HITS strings containing 'termux':"
    grep -i "termux" "$ALL_STRINGS" | head -5 | sed 's/^/      /'
fi

# Also check for forbidden URLs
URL_HITS=$(grep -cE "packages\.termux|termux\.dev|com\.termux|jitpack\.io" "$ALL_STRINGS" || true)
if [ "$URL_HITS" -eq 0 ]; then
    ok "No Termux/JitPack URLs in any DEX"
else
    fail "Found $URL_HITS Termux/JitPack URL references"
fi

# Also verify OkHttp is present (for rootfs download, which is legitimate)
if grep -q "Lokhttp3/" "$ALL_STRINGS"; then
    ok "OkHttp3 present (used for rootfs download from cdimage.ubuntu.com)"
else
    fail "OkHttp3 missing — BootstrapManager cannot download rootfs"
fi

# Apache Commons Compress (used for tarball extraction)
if grep -q "org/apache/commons/compress" "$ALL_STRINGS"; then
    ok "Apache Commons Compress present (used for tarball extraction)"
else
    fail "Apache Commons Compress missing — cannot extract rootfs tarball"
fi

section "7. PER-DEX CLASS LOCATION"
python3 - "$TMPDIR" <<'PYEOF'
import struct, os, sys
dex_dir = sys.argv[1]
EXPECTED = [
    "Lcom/ubuntuterm/MainActivity;",
    "Lcom/ubuntuterm/UbuntuTerminalApp;",
    "Lcom/ubuntuterm/ui/TerminalViewModel;",
    "Lcom/ubuntuterm/ui/TerminalWorkspaceKt;",
    "Lcom/ubuntuterm/terminal/TerminalService;",
    "Lcom/ubuntuterm/terminal/UbuntuSession;",
    "Lcom/ubuntuterm/terminal/NativeTerminal;",
    "Lcom/ubuntuterm/bootstrap/BootstrapManager;",
    "Lcom/ubuntuterm/bootstrap/PRootManager;",
    "Lcom/ubuntuterm/ubuntu/PRootRunner;",
    "Lcom/ubuntuterm/util/FileLocations;",
]
# Pre-parse all DEX strings
class_to_dex = {c: [] for c in EXPECTED}
for name in sorted(os.listdir(dex_dir)):
    if not name.endswith('.dex'): continue
    path = os.path.join(dex_dir, name)
    with open(path, 'rb') as f: data = f.read()
    if data[:4] != b'dex\n': continue
    sz = struct.unpack('<I', data[0x38:0x3c])[0]
    off = struct.unpack('<I', data[0x3c:0x40])[0]
    dex_strings = set()
    for i in range(sz):
        so = struct.unpack('<I', data[off + i*4 : off + i*4 + 4])[0]
        p = so
        while data[p] & 0x80: p += 1
        p += 1
        end = data.find(b'\x00', p)
        s = data[p:end].decode('utf-8', errors='replace')
        dex_strings.add(s)
    for c in EXPECTED:
        if c in dex_strings:
            class_to_dex[c].append(name)
for c in EXPECTED:
    loc = class_to_dex[c]
    if loc:
        print(f"  {c}")
        print(f"    → found in: {', '.join(loc)}")
    else:
        print(f"  {c}")
        print(f"    → NOT FOUND")
PYEOF

rm -rf "$TMPDIR"

section "SUMMARY"
printf "  PASS:         %d\n" "$PASS"
printf "  FAIL:         %d\n" "$FAIL"
printf "  NOT VERIFIED: %d\n" "$NOTVERIFIED"
echo

if [ "$FAIL" -gt 0 ]; then
    echo "DEX VALIDATION = FAILED"
    exit 1
fi
echo "DEX VALIDATION = PASSED"
exit 0
