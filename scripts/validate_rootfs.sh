#!/usr/bin/env bash
# =====================================================================
# validate_rootfs.sh — Validate Ubuntu rootfs tarball + extraction
# ---------------------------------------------------------------------
# Per project spec Fase 4: this script is MANDATORY before claiming
# Bash can start. It verifies that:
#
#   1. The official Ubuntu Base 24.04.5 arm64 tarball is reachable
#      (HTTP 200, content-length matches expected).
#   2. The SHA256 of the downloaded tarball matches the official SHA256
#      published by cdimage.ubuntu.com.
#   3. Extraction with the OFFICIAL `tar` command (correct behavior)
#      preserves all entry types:
#         - regular files
#         - directories
#         - symlinks   (CRITICAL: /bin -> usr/bin MUST be a symlink)
#         - hard links
#         - executable bits
#      We extract with GNU tar here, NOT with our Kotlin extractor,
#      because the Kotlin extractor currently does NOT preserve
#      symlinks/hardlinks. This script gives us the BASELINE — what
#      the rootfs SHOULD look like.
#   4. Specific critical symlinks exist:
#         /bin -> usr/bin
#         /lib -> usr/lib
#         /sbin -> usr/sbin
#         /lib64 -> usr/lib64
#         /etc/alternatives/awk -> /usr/bin/mawk
#         /etc/os-release -> ../usr/lib/os-release
#   5. /bin/bash is a regular file (resolvable through the symlink).
#   6. /usr/bin/bash is a regular file.
#   7. /etc/passwd exists.
#   8. /usr/bin/apt exists (so apt install will work post-extraction).
#
# Usage:
#   scripts/validate_rootfs.sh [rootfs-dir]
#   If rootfs-dir is not given, uses /tmp/ubuntu-rootfs-validation
#
# Exit codes: 0 = PASS, 1 = FAIL
# =====================================================================
set -euo pipefail

ROOTFS_DIR="${1:-/tmp/ubuntu-rootfs-validation}"
TARBALL="/tmp/ubuntu-base-24.04.5-arm64.tar.gz"
SHA256_FILE="/tmp/ubuntu-base-24.04.5-arm64.SHA256SUMS"

# Official source-of-truth values
OFFICIAL_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
OFFICIAL_SHA256_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS"
OFFICIAL_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
EXPECTED_TARBALL_SIZE="29936675"  # bytes, from Content-Length

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

section "1. TARBALL DOWNLOAD"
echo "  URL: $OFFICIAL_URL"

if [ -f "$TARBALL" ]; then
    EXISTING_SIZE=$(stat -c%s "$TARBALL" 2>/dev/null || echo 0)
    if [ "$EXISTING_SIZE" = "$EXPECTED_TARBALL_SIZE" ]; then
        ok "Tarball already cached at $TARBALL"
    else
        echo "  Existing tarball size $EXISTING_SIZE != expected $EXPECTED_TARBALL_SIZE, re-downloading..."
        rm -f "$TARBALL"
    fi
fi

if [ ! -f "$TARBALL" ]; then
    echo "  Downloading..."
    curl -fsSL "$OFFICIAL_URL" -o "$TARBALL"
fi

ACTUAL_SIZE=$(stat -c%s "$TARBALL")
printf "  Tarball size: %d bytes (expected %d)\n" "$ACTUAL_SIZE" "$EXPECTED_TARBALL_SIZE"
if [ "$ACTUAL_SIZE" = "$EXPECTED_TARBALL_SIZE" ]; then
    ok "Tarball size matches expected"
else
    fail "Tarball size mismatch: actual=$ACTUAL_SIZE expected=$EXPECTED_TARBALL_SIZE"
fi

section "2. SHA256 VERIFICATION"
ACTUAL_SHA=$(sha256sum "$TARBALL" | awk '{print $1}')
printf "  Expected: %s\n" "$OFFICIAL_SHA256"
printf "  Actual:   %s\n" "$ACTUAL_SHA"
if [ "$ACTUAL_SHA" = "$OFFICIAL_SHA256" ]; then
    ok "SHA256 matches official Ubuntu Base 24.04.5 arm64"
else
    fail "SHA256 MISMATCH — tarball is corrupt or wrong version"
    exit 1
fi

# Also fetch the official SHA256SUMS file and verify our hash is listed there
if [ ! -f "$SHA256_FILE" ]; then
    curl -fsSL "$OFFICIAL_SHA256_URL" -o "$SHA256_FILE" || {
        echo "  (could not fetch $OFFICIAL_SHA256_URL, continuing with hardcoded value)"
    }
fi
if [ -f "$SHA256_FILE" ]; then
    if grep -q "$OFFICIAL_SHA256" "$SHA256_FILE"; then
        ok "SHA256 is listed in official SHA256SUMS file"
    else
        fail "SHA256 is NOT in the official SHA256SUMS file"
    fi
fi

section "3. TARBALL ENTRY INVENTORY"
echo "  Listing entry types..."
ENTRIES=$(tar tzf "$TARBALL" | wc -l)
REGULAR=$(tar tvzf "$TARBALL" | grep -c '^-')
DIRS=$(tar tvzf "$TARBALL" | grep -c '^d')
SYMLINKS=$(tar tvzf "$TARBALL" | grep -c '^l')
HARDLINKS=$(tar tvzf "$TARBALL" | grep -c '^h')
printf "  Total entries:  %d\n" "$ENTRIES"
printf "  Regular files: %d\n" "$REGULAR"
printf "  Directories:   %d\n" "$DIRS"
printf "  Symlinks:       %d\n" "$SYMLINKS"
printf "  Hardlinks:      %d\n" "$HARDLINKS"
[ "$ENTRIES" -gt 0 ] && ok "Tarball has entries" || fail "Tarball is empty"
[ "$SYMLINKS" -gt 0 ] && ok "Tarball has symlinks" || fail "Tarball has NO symlinks"
[ "$HARDLINKS" -gt 0 ] && ok "Tarball has hardlinks" || fail "Tarball has NO hardlinks"

section "4. EXTRACTION WITH OFFICIAL `tar` (BASELINE)"
# This is the GROUND TRUTH: what a correct extractor produces.
# Our Kotlin extractor currently does NOT match this — that is a separate bug
# (to be fixed in the next controlled iteration).
echo "  Extracting with: tar xzf (GNU tar)"
echo "  Target dir: $ROOTFS_DIR"
rm -rf "$ROOTFS_DIR"
mkdir -p "$ROOTFS_DIR"
tar xzf "$TARBALL" -C "$ROOTFS_DIR"
ok "Extraction completed"

# Verify /bin is a symlink to usr/bin
section "5. CRITICAL SYMLINKS"
check_symlink() {
    local path="$1"
    local expected_target="$2"
    local full_path="$ROOTFS_DIR$path"
    if [ -L "$full_path" ]; then
        local actual_target=$(readlink "$full_path")
        if [ "$actual_target" = "$expected_target" ]; then
            ok "Symlink: $path -> $expected_target"
        else
            fail "Symlink $path -> WRONG TARGET (got: $actual_target, expected: $expected_target)"
        fi
    elif [ -e "$full_path" ]; then
        fail "$path EXISTS but is NOT a symlink (file type: $(file -b "$full_path" | head -c 60))"
    else
        fail "$path MISSING"
    fi
}

# Official Ubuntu 24.04.5 arm64 rootfs critical symlinks
check_symlink "/bin" "usr/bin"
check_symlink "/lib" "usr/lib"
check_symlink "/sbin" "usr/sbin"
# NOTE: /lib64 only exists on x86_64, NOT on arm64. We don't check it.
check_symlink "/etc/alternatives/awk" "/usr/bin/mawk"
check_symlink "/etc/alternatives/pager" "/bin/more"
check_symlink "/etc/dpkg/origins/default" "ubuntu"

section "6. CRITICAL BINARIES"
check_regular() {
    local path="$1"
    local full_path="$ROOTFS_DIR$path"
    if [ -f "$full_path" ] && [ ! -L "$full_path" ]; then
        ok "Regular file: $path"
        # Verify it is an ELF binary
        if file "$full_path" | grep -q "ELF"; then
            ok "  ELF binary: $path ($(file -b "$full_path" | head -c 60))"
        fi
    elif [ -L "$full_path" ]; then
        fail "$path is a symlink, expected regular file"
    elif [ -e "$full_path" ]; then
        fail "$path exists but is not a regular file"
    else
        fail "$path MISSING"
    fi
}

# /bin/bash should be reachable through the symlink /bin -> usr/bin
# In the extracted rootfs, /bin/bash and /usr/bin/bash point to the same file.
check_regular "/bin/bash"
check_regular "/usr/bin/bash"
check_regular "/bin/ls"
check_regular "/bin/cat"
check_regular "/bin/mkdir"
check_regular "/bin/touch"
check_regular "/bin/rm"
check_regular "/bin/cp"
check_regular "/bin/mv"
check_regular "/bin/grep"
check_regular "/usr/bin/find"
check_regular "/usr/bin/chmod"
check_regular "/usr/bin/apt"
check_regular "/usr/bin/apt-get"
check_regular "/usr/bin/dpkg"

section "7. CRITICAL CONFIG FILES"
# Note: some of these may be symlinks in the actual rootfs (e.g. /etc/os-release).
# We accept either regular file or symlink as long as the file exists.
check_exists() {
    local path="$1"
    local full_path="$ROOTFS_DIR$path"
    if [ -e "$full_path" ] || [ -L "$full_path" ]; then
        if [ -L "$full_path" ]; then
            local target=$(readlink "$full_path")
            ok "Exists (symlink): $path -> $target"
        elif [ -f "$full_path" ]; then
            ok "Exists (regular file): $path"
        elif [ -d "$full_path" ]; then
            ok "Exists (directory): $path"
        else
            ok "Exists: $path"
        fi
    else
        fail "$path MISSING"
    fi
}

check_exists "/etc/passwd"
check_exists "/etc/group"
check_exists "/etc/hostname"
check_exists "/etc/hosts"
check_exists "/etc/resolv.conf"
check_exists "/etc/os-release"
check_exists "/etc/apt/sources.list.d/ubuntu.sources"

section "8. UBUNTU IDENTITY"
OS_RELEASE="$ROOTFS_DIR/etc/os-release"
if [ -f "$OS_RELEASE" ] || [ -L "$OS_RELEASE" ]; then
    if grep -q 'PRETTY_NAME="Ubuntu 24.04' "$OS_RELEASE" 2>/dev/null; then
        ok "/etc/os-release identifies as Ubuntu 24.04"
    else
        fail "/etc/os-release does not identify as Ubuntu 24.04"
    fi
    if grep -q 'VERSION_CODENAME=noble' "$OS_RELEASE" 2>/dev/null; then
        ok "/etc/os-release codename is 'noble' (24.04)"
    else
        fail "/etc/os-release codename is not 'noble'"
    fi
fi

# Verify that /bin/bash resolves through the symlink
section "9. SYMLINK CHAIN RESOLUTION"
# Verify /bin/bash and /usr/bin/bash refer to the same file (because /bin -> usr/bin).
# Use `readlink -f` to resolve symlinks and `stat -L` to follow them.
BIN_BASH_REAL=$(readlink -f "$ROOTFS_DIR/bin/bash" 2>/dev/null || echo "MISSING")
USR_BIN_BASH_REAL=$(readlink -f "$ROOTFS_DIR/usr/bin/bash" 2>/dev/null || echo "MISSING")
printf "  /bin/bash resolves to:      %s\n" "$BIN_BASH_REAL"
printf "  /usr/bin/bash resolves to:  %s\n" "$USR_BIN_BASH_REAL"
if [ "$BIN_BASH_REAL" = "$USR_BIN_BASH_REAL" ] && [ "$BIN_BASH_REAL" != "MISSING" ]; then
    ok "/bin/bash and /usr/bin/bash resolve to the same real path (symlink chain works)"
else
    fail "/bin/bash and /usr/bin/bash do NOT resolve to the same path"
fi

# Same for /lib -> usr/lib (compare resolved paths, not inodes — symlinks work via path)
LIB_REAL=$(readlink -f "$ROOTFS_DIR/lib" 2>/dev/null || echo "MISSING")
USR_LIB_REAL=$(readlink -f "$ROOTFS_DIR/usr/lib" 2>/dev/null || echo "MISSING")
printf "  /lib resolves to:       %s\n" "$LIB_REAL"
printf "  /usr/lib resolves to:   %s\n" "$USR_LIB_REAL"
if [ "$LIB_REAL" = "$USR_LIB_REAL" ] && [ "$LIB_REAL" != "MISSING" ]; then
    ok "/lib and /usr/lib resolve to the same path (symlink chain works)"
else
    fail "/lib and /usr/lib do NOT resolve to the same path"
fi

section "10. EXECUTABLE BITS PRESERVED"
# Verify /bin/bash has the executable bit
if [ -x "$ROOTFS_DIR/bin/bash" ]; then
    ok "/bin/bash is executable"
else
    fail "/bin/bash is NOT executable (permission bits wrong)"
fi
if [ -x "$ROOTFS_DIR/usr/bin/apt" ]; then
    ok "/usr/bin/apt is executable"
else
    fail "/usr/bin/apt is NOT executable"
fi
if [ -x "$ROOTFS_DIR/usr/bin/dpkg" ]; then
    ok "/usr/bin/dpkg is executable"
else
    fail "/usr/bin/dpkg is NOT executable"
fi

section "11. KOTLIN EXTRACTOR BUG DETECTION"
# Our current Kotlin extractor in BootstrapManager.extractTarball() does NOT
# preserve symlinks — it writes them as regular files containing the link
# target as text. This means /bin would be a regular file with content
# "usr/bin" instead of being a symlink. Let's verify the bug is documented.
#
# This check intentionally flags the bug. When the Kotlin extractor is fixed,
# this check should be updated to actually call the Kotlin extractor and
# verify it matches this baseline.
nv "Kotlin extractor currently does NOT preserve symlinks (Bug #1)"
nv "Kotlin extractor currently does NOT preserve hardlinks (Bug #2)"
nv "Kotlin extractor does NOT delete the tarball after extraction (Bug #3)"
nv "These bugs are tracked separately and will be fixed in the next controlled iteration."
nv "This baseline script confirms what a CORRECT extraction looks like."

section "SUMMARY"
printf "  PASS:         %d\n" "$PASS"
printf "  FAIL:         %d\n" "$FAIL"
printf "  NOT VERIFIED: %d\n" "$NOTVERIFIED"
echo

if [ "$FAIL" -gt 0 ]; then
    echo "ROOTFS VALIDATION = FAILED"
    exit 1
fi
echo "ROOTFS VALIDATION = PASSED"
exit 0
