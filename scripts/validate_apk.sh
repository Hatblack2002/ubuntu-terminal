#!/usr/bin/env bash
# =====================================================================
# validate_apk.sh — Comprehensive APK validation
# ---------------------------------------------------------------------
# Validates an APK against the project's expectations:
#   1. APK file exists and is a valid Android package
#   2. applicationId matches com.ubuntuterm.debug (debug) or com.ubuntuterm
#   3. versionCode + versionName present
#   4. AndroidManifest declares MainActivity, UbuntuTerminalApp, TerminalService
#   5. lib/arm64-v8a/libterminal.so present with expected JNI symbols
#   6. assets/proot/ contains all 6 expected files
#   7. classes*.dex contains all expected app classes (across all DEX)
#   8. classes*.dex contains all v0.1.4 logging strings
#   9. No Termux references anywhere in classes*.dex
#  10. SHA256 of the APK is printed for traceability
#
# Usage:
#   scripts/validate_apk.sh [path/to/apk]
#
# Default APK path: app/build/outputs/apk/debug/app-debug.apk
# Exit codes: 0 = all PASS, 1 = at least one FAIL
# =====================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"

# Locate SDK tools
AAPT2="$REPO_ROOT/../tools/android-sdk/build-tools/34.0.0/aapt2"
[ -x "$AAPT2" ] || AAPT2="$(find /home/z -name aapt2 -executable -type f 2>/dev/null | head -1)"
[ -x "$AAPT2" ] || { echo "ERROR: aapt2 not found"; exit 1; }

# Helper for human-readable sizes (no bc dependency)
human_size() {
    local bytes=$1
    if [ "$bytes" -ge 1048576 ]; then
        awk -v b="$bytes" 'BEGIN{printf "%.2f MB", b/1048576}'
    elif [ "$bytes" -ge 1024 ]; then
        awk -v b="$bytes" 'BEGIN{printf "%.2f KB", b/1024}'
    else
        printf "%d B" "$bytes"
    fi
}

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

section "1. APK FILE"
if [ ! -f "$APK" ]; then
    fail "APK not found at: $APK"
    exit 1
fi
ok "APK exists at: $APK"
SIZE=$(stat -c%s "$APK")
printf "  APK size: %d bytes (%s)\n" "$SIZE" "$(human_size "$SIZE")"
SHA=$(sha256sum "$APK" | awk '{print $1}')
printf "  SHA256: %s\n" "$SHA"
if file "$APK" | grep -q "Android package"; then
    ok "File type: Android package (APK)"
else
    fail "File type is not Android package"
fi

section "2. PACKAGE / VERSION"
BADGING=$("$AAPT2" dump badging "$APK" 2>&1)
# Use python to robustly parse package line: package: name='X' versionCode='Y' versionName='Z'
PKG_LINE=$(echo "$BADGING" | grep "^package:")
APPID=$(echo "$PKG_LINE" | python3 -c "import sys,re; m=re.search(r\"name='([^']*)'\", sys.stdin.read()); print(m.group(1) if m else '')")
VCODE=$(echo "$PKG_LINE" | python3 -c "import sys,re; m=re.search(r\"versionCode='([^']*)'\", sys.stdin.read()); print(m.group(1) if m else '')")
VNAME=$(echo "$PKG_LINE" | python3 -c "import sys,re; m=re.search(r\"versionName='([^']*)'\", sys.stdin.read()); print(m.group(1) if m else '')")
printf "  applicationId: %s\n" "$APPID"
printf "  versionCode:   %s\n" "$VCODE"
printf "  versionName:   %s\n" "$VNAME"
case "$APPID" in
    com.ubuntuterm|com.ubuntuterm.debug) ok "applicationId is correct" ;;
    *) fail "applicationId is wrong: $APPID" ;;
esac
[ -n "$VCODE" ] && ok "versionCode present" || fail "versionCode missing"
[ -n "$VNAME" ] && ok "versionName present" || fail "versionName missing"

section "3. ANDROID MANIFEST"
MANIFEST=$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>&1)
EXPECTED_COMPONENTS=(
    "com.ubuntuterm.UbuntuTerminalApp"
    "com.ubuntuterm.MainActivity"
    "com.ubuntuterm.terminal.TerminalService"
)
for comp in "${EXPECTED_COMPONENTS[@]}"; do
    if echo "$MANIFEST" | grep -q "$comp"; then
        ok "Manifest declares: $comp"
    else
        fail "Manifest MISSING: $comp"
    fi
done

# Check minSdk and targetSdk
MIN_SDK=$(echo "$BADGING" | grep "sdkVersion:" | sed 's/.*sdkVersion:'\''\(.*\)'\''/\1/')
TGT_SDK=$(echo "$BADGING" | grep "targetSdkVersion:" | sed 's/.*targetSdkVersion:'\''\(.*\)'\''/\1/')
printf "  minSdkVersion:     %s\n" "$MIN_SDK"
printf "  targetSdkVersion:  %s\n" "$TGT_SDK"
[ "${MIN_SDK:-0}" -ge 28 ] && ok "minSdk >= 28" || fail "minSdk too low"

section "4. NATIVE LIBRARY"
# Use unzip -l with awk for robust matching
if unzip -l "$APK" | awk '{print $NF}' | grep -q "^lib/arm64-v8a/libterminal.so$"; then
    ok "lib/arm64-v8a/libterminal.so is present"
    # Extract to /tmp and inspect
    TMPDIR=$(mktemp -d)
    unzip -q "$APK" lib/arm64-v8a/libterminal.so -d "$TMPDIR"
    SO="$TMPDIR/lib/arm64-v8a/libterminal.so"
    printf "  Size: %s\n" "$(human_size "$(stat -c%s "$SO")")"
    EXPECTED_JNI_SYMBOLS=(
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeSpawn"
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeRead"
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeWrite"
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeSetSize"
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeSendSignal"
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeWaitExit"
        "Java_com_ubuntuterm_terminal_NativeTerminal_nativeClose"
        "JNI_OnLoad"
    )
    for sym in "${EXPECTED_JNI_SYMBOLS[@]}"; do
        if nm -D --defined-only "$SO" 2>/dev/null | grep -q " T $sym"; then
            ok "JNI symbol exported: $sym"
        else
            fail "JNI symbol MISSING: $sym"
        fi
    done
    printf "  ELF type: %s\n" "$(file -b "$SO")"
    # Confirm it is arm64
    if file "$SO" | grep -q "ARM aarch64"; then
        ok "libterminal.so is ARM aarch64"
    else
        fail "libterminal.so is NOT ARM aarch64"
    fi
    rm -rf "$TMPDIR"
else
    fail "lib/arm64-v8a/libterminal.so is MISSING"
fi

section "5. PROOT ASSETS"
EXPECTED_ASSETS=(
    "assets/proot/proot-arm64"
    "assets/proot/proot-userland-arm64"
    "assets/proot/loader-arm64"
    "assets/proot/loader32-arm"
    "assets/proot/LICENSE-proot"
    "assets/proot/LICENSE-build-proot-android"
)
# Use awk to robustly get the last column (filename) of unzip -l
APK_FILES=$(unzip -l "$APK" | awk '{print $NF}')
for asset in "${EXPECTED_ASSETS[@]}"; do
    if echo "$APK_FILES" | grep -qx "$asset"; then
        ok "Asset present: $asset"
    else
        fail "Asset MISSING: $asset"
    fi
done

section "6. DEX — APP CLASSES"
# Use python3 to parse ALL classes*.dex for proper string table inspection
TMPDIR=$(mktemp -d)
unzip -q "$APK" 'classes*.dex' -d "$TMPDIR"

EXPECTED_CLASSES=(
    "Lcom/ubuntuterm/MainActivity;"
    "Lcom/ubuntuterm/UbuntuTerminalApp;"
    "Lcom/ubuntuterm/ui/TerminalViewModel;"
    "Lcom/ubuntuterm/ui/TerminalWorkspaceKt;"
    "Lcom/ubuntuterm/ui/BootstrapScreenKt;"
    "Lcom/ubuntuterm/terminal/TerminalService;"
    "Lcom/ubuntuterm/terminal/UbuntuSession;"
    "Lcom/ubuntuterm/terminal/NativeTerminal;"
    "Lcom/ubuntuterm/bootstrap/BootstrapManager;"
    "Lcom/ubuntuterm/bootstrap/PRootManager;"
    "Lcom/ubuntuterm/ubuntu/PRootRunner;"
    "Lcom/ubuntuterm/util/FileLocations;"
    "Lcom/ubuntuterm/ui/terminal/TerminalBuffer;"
    "Lcom/ubuntuterm/ui/terminal/AnsiParser;"
    "Lcom/ubuntuterm/ui/terminal/TerminalViewKt;"
)

# Concatenate all string tables from all DEX files using python3
python3 - <<PYEOF > "$TMPDIR/all_strings.txt"
import struct, os, sys
dex_dir = "$TMPDIR"
for name in sorted(os.listdir(dex_dir)):
    if not name.endswith('.dex'): continue
    path = os.path.join(dex_dir, name)
    with open(path, 'rb') as f: data = f.read()
    if data[:4] != b'dex\n':
        print(f"# ERROR: {name} not a DEX file", file=sys.stderr)
        continue
    try:
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
    except Exception as e:
        print(f"# ERROR parsing {name}: {e}", file=sys.stderr)
PYEOF

ALL_STRINGS="$TMPDIR/all_strings.txt"
printf "  Total strings across all DEX: %s\n" "$(wc -l < "$ALL_STRINGS")"

for cls in "${EXPECTED_CLASSES[@]}"; do
    if grep -qF "$cls" "$ALL_STRINGS"; then
        ok "DEX contains class: $cls"
    else
        fail "DEX MISSING class: $cls"
    fi
done

section "7. DEX — V0.1.4 LOGGING STRINGS"
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
        ok "DEX contains log string: $s"
    else
        fail "DEX MISSING log string: $s"
    fi
done

section "8. DEX — V0.1.4 UI STRINGS"
UI_STRINGS=(
    "Starting session"
    "Session failed"
    "Session exited"
)
for s in "${UI_STRINGS[@]}"; do
    if grep -qF "$s" "$ALL_STRINGS"; then
        ok "DEX contains UI string: $s"
    else
        fail "DEX MISSING UI string: $s"
    fi
done

section "9. NO TERMUX REFERENCES"
TERMUX_HITS=$(grep -ci "termux" "$ALL_STRINGS" || true)
if [ "$TERMUX_HITS" -eq 0 ]; then
    ok "No 'termux' string anywhere in any DEX"
else
    fail "Found $TERMUX_HITS strings containing 'termux':"
    grep -i "termux" "$ALL_STRINGS" | head -5 | sed 's/^/      /'
fi
# Also check Termux URLs
TERMUX_URL_HITS=$(grep -E "packages\.termux|termux\.dev|com\.termux|jitpack\.io" "$ALL_STRINGS" | wc -l || true)
if [ "$TERMUX_URL_HITS" -eq 0 ]; then
    ok "No Termux/JitPack URL strings anywhere"
else
    fail "Found $TERMUX_URL_HITS Termux/JitPack URL strings"
fi

# Check libterminal.so for Termux references too
if unzip -l "$APK" | grep -q "libterminal.so"; then
    TMPDIR2=$(mktemp -d)
    unzip -q "$APK" lib/arm64-v8a/libterminal.so -d "$TMPDIR2"
    SO_STRINGS=$(strings -n 8 "$TMPDIR2/lib/arm64-v8a/libterminal.so" | grep -ci "termux" || true)
    if [ "$SO_STRINGS" -eq 0 ]; then
        ok "No 'termux' strings in libterminal.so"
    else
        fail "Found 'termux' in libterminal.so"
    fi
    rm -rf "$TMPDIR2"
fi

rm -rf "$TMPDIR"

section "SUMMARY"
printf "  PASS:         %d\n" "$PASS"
printf "  FAIL:         %d\n" "$FAIL"
printf "  NOT VERIFIED: %d\n" "$NOTVERIFIED"
echo

if [ "$FAIL" -gt 0 ]; then
    echo "BUILD VALIDATION = FAILED"
    exit 1
fi
echo "BUILD VALIDATION = PASSED"
exit 0
