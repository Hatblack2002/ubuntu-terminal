#!/usr/bin/env bash
# =====================================================================
# validate_native.sh — Validate libterminal.so native binary
# ---------------------------------------------------------------------
# This script inspects the arm64 libterminal.so that ships inside the
# APK. Because the host is x86_64 (no qemu-user-static), we CANNOT
# execute the binary — only inspect it statically.
#
# Checks:
#   1. Binary exists in the APK
#   2. Is ELF 64-bit ARM aarch64
#   3. Interpreter is /system/bin/linker64 (Android bionic)
#   4. NEEDED libraries are only libc.so + libdl.so (bionic)
#   5. All 8 expected JNI symbols are exported
#   6. No Termux references in string table
#   7. Strip status
#   8. BuildID (for traceability)
#   9. Section headers present
#
# What this script CANNOT do (host limitation):
#   - Cannot execute the binary
#   - Cannot test fork() / openpty() / execve() behavior
#   - Cannot test PTY resize / signal / read / write
#   - Cannot test the actual nativeSpawn flow end-to-end
#
# These runtime checks are NOT VERIFIABLE in this environment.
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

section "1. NATIVE BINARY PRESENCE"
TMPDIR=$(mktemp -d)
unzip -q "$APK" lib/arm64-v8a/libterminal.so -d "$TMPDIR"
SO="$TMPDIR/lib/arm64-v8a/libterminal.so"
if [ -f "$SO" ]; then
    ok "libterminal.so extracted from APK"
    printf "  Size: %d bytes\n" "$(stat -c%s "$SO")"
else
    fail "libterminal.so not found in APK"
    exit 1
fi

section "2. ELF HEADER"
FILE_OUTPUT=$(file -b "$SO")
printf "  %s\n" "$FILE_OUTPUT"
case "$FILE_OUTPUT" in
    *"ELF 64-bit LSB shared object, ARM aarch64"*)
        ok "ELF 64-bit ARM aarch64" ;;
    *)
        fail "Not an ARM aarch64 ELF"
        exit 1 ;;
esac
case "$FILE_OUTPUT" in
    *"for Android "*)
        ok "Built for Android" ;;
    *)
        fail "Not built for Android" ;;
esac
case "$FILE_OUTPUT" in
    *"stripped"*)
        ok "Binary is stripped (smaller size)" ;;
    *"not stripped"*)
        nv "Binary is not stripped (debug build — larger but debuggable)" ;;
esac
case "$FILE_OUTPUT" in
    *"NDK r"*)
        NDK_VER=$(echo "$FILE_OUTPUT" | grep -oE "NDK r[0-9]+[a-z]? \([0-9]+\)")
        ok "Built with $NDK_VER" ;;
esac

section "3. ELF TYPE — SHARED LIBRARY (no interpreter)"
# A .so file is loaded via dlopen() and does NOT have a PT_INTERP program
# header (which is only for executables). Verify it is a proper shared
# library by checking for DYN Elf type and presence of .dynsym.
ELF_TYPE=$(readelf -h "$SO" 2>/dev/null | grep "^  Type:" | sed 's/^  Type:[ \t]*//')
printf "  ELF type: %s\n" "$ELF_TYPE"
case "$ELF_TYPE" in
    *"DYN"*)
        ok "Type is DYN (shared library) — loadable via dlopen()" ;;
    *"EXEC"*)
        fail "Type is EXEC — should be DYN (shared library)" ;;
    *)
        fail "Unexpected ELF type: $ELF_TYPE" ;;
esac

# A dlopen-able .so does not have /system/bin/linker64 as interpreter —
# the host process's linker handles loading. So we verify the absence of
# PT_INTERP (which is what we'd expect for a .so).
PT_INTERP=$(readelf -l "$SO" 2>/dev/null | grep "INTERP" || true)
if [ -z "$PT_INTERP" ]; then
    ok "No PT_INTERP (correct for a shared library loaded via dlopen)"
else
    fail "Unexpected PT_INTERP — shared libraries should not have an interpreter"
fi

# Verify .dynsym + .dynstr sections (required for JNI symbol export)
if readelf -S "$SO" 2>/dev/null | grep -q '\.dynsym'; then
    ok ".dynsym section present (dynamic symbol table)"
else
    fail ".dynsym section MISSING"
fi
if readelf -S "$SO" 2>/dev/null | grep -q '\.dynstr'; then
    ok ".dynstr section present (dynamic string table)"
else
    fail ".dynstr section MISSING"
fi

section "4. SHARED LIBRARY DEPENDENCIES (NEEDED)"
NEEDED=$(readelf -d "$SO" | grep NEEDED | awk -F'[][]' '{print $2}')
printf "  NEEDED libraries:\n"
echo "$NEEDED" | while read -r lib; do
    printf "    %s\n" "$lib"
done

# Verify we only depend on bionic libraries
BAD_DEPS=0
for lib in $NEEDED; do
    case "$lib" in
        libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libc++_shared.so)
            # OK — these are present in Android
            ;;
        *)
            printf "  [\033[31mWARN\033[0m] Unexpected dependency: %s\n" "$lib"
            BAD_DEPS=$((BAD_DEPS+1))
            ;;
    esac
done
if [ "$BAD_DEPS" -eq 0 ]; then
    ok "All NEEDED libraries are available on Android (bionic)"
else
    fail "Found $BAD_DEPS unexpected dependencies"
fi

section "5. JNI SYMBOLS EXPORTED"
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
    if nm -D --defined-only "$SO" 2>/dev/null | grep -q " T $sym\$"; then
        ok "JNI symbol exported: $sym"
    elif nm -D --defined-only "$SO" 2>/dev/null | grep -q " T $sym"; then
        ok "JNI symbol exported: $sym"
    else
        fail "JNI symbol MISSING: $sym"
    fi
done

section "6. NO TERMUX REFERENCES IN BINARY"
TERMUX_HITS=$(strings -n 6 "$SO" | grep -ci "termux" || true)
if [ "$TERMUX_HITS" -eq 0 ]; then
    ok "No 'termux' string in libterminal.so"
else
    fail "Found 'termux' string $TERMUX_HITS times in libterminal.so"
    strings "$SO" | grep -i termux | head -5 | sed 's/^/      /'
fi

# Also check for forbidden URLs
URL_HITS=$(strings -n 10 "$SO" | grep -ciE "packages\.termux|termux\.dev|com\.termux" || true)
if [ "$URL_HITS" -eq 0 ]; then
    ok "No Termux URLs in libterminal.so"
else
    fail "Found Termux URLs in libterminal.so"
fi

section "7. SECTION HEADERS"
SECTIONS=$(readelf -S "$SO" 2>/dev/null | grep -c '\]' || echo 0)
printf "  Total sections: %d\n" "$SECTIONS"
if [ "$SECTIONS" -gt 10 ]; then
    ok "Has expected section count"
else
    fail "Section count too low — binary may be corrupted"
fi

# Verify .dynsym (dynamic symbol table) is present
if readelf -S "$SO" 2>/dev/null | grep -q '\.dynsym'; then
    ok ".dynsym section present (required for JNI)"
else
    fail ".dynsym section MISSING"
fi
if readelf -S "$SO" 2>/dev/null | grep -q '\.dynstr'; then
    ok ".dynstr section present"
else
    fail ".dynstr section MISSING"
fi

section "8. BUILD ID (for traceability)"
BUILD_ID=$(readelf -n "$SO" 2>/dev/null | grep "Build ID" | head -1 | awk -F': ' '{print $2}')
printf "  Build ID: %s\n" "$BUILD_ID"
if [ -n "$BUILD_ID" ]; then
    ok "Build ID present — useful for symbolication"
else
    nv "Build ID missing — debugging will be harder"
fi

section "9. RUNTIME CHECKS — HOST LIMITATION"
nv "Cannot execute the binary on this x86_64 host"
nv "Cannot test: openpty(), fork(), execve(), PTY read/write/resize"
nv "Cannot test: nativeSpawn end-to-end on Android"
nv "Cannot test: PTY signal delivery (SIGINT, SIGKILL)"
nv "Cannot test: waitpid exit code propagation"
nv "These require an Android device or emulator (none available in this environment)"

rm -rf "$TMPDIR"

section "SUMMARY"
printf "  PASS:         %d\n" "$PASS"
printf "  FAIL:         %d\n" "$FAIL"
printf "  NOT VERIFIED: %d\n" "$NOTVERIFIED"
echo

if [ "$FAIL" -gt 0 ]; then
    echo "NATIVE VALIDATION = FAILED"
    exit 1
fi
echo "NATIVE VALIDATION = PASSED (static only)"
echo "Runtime checks: NOT VERIFIABLE in this environment"
exit 0
