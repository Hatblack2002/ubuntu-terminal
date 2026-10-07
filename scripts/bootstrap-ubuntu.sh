#!/usr/bin/env bash
# =====================================================================
# bootstrap-ubuntu.sh
# ---------------------------------------------------------------------
# Validates the same chain the APK will eventually run on-device, but
# on a Linux development machine:
#
#     1. Download the official Ubuntu 24.04 base rootfs (arm64 or host arch)
#     2. Extract it into ./ubuntu-rootfs/
#     3. Configure /etc/resolv.conf, /etc/hosts, /etc/hostname
#     4. Create a non-root user "ubuntu" with UID 1000
#     5. Drop a .bashrc that sets PS1 + PATH
#     6. Verify with: proot -r ubuntu-rootfs/ /bin/bash -c 'uname -a; pwd; ls'
#
# Per project spec (section 20): this is the host-side equivalent of
# the "first prototype" chain. Once this works, the same rootfs can be
# dropped into the APK assets and the in-app bootstrap step can be
# replaced with a simple "extract from assets" pass.
# =====================================================================
set -euo pipefail

ARCH="${1:-$(uname -m)}"
case "$ARCH" in
    aarch64|arm64)   ARCH_CODE="arm64" ;;
    x86_64|amd64)    ARCH_CODE="amd64" ;;
    *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

UBUNTU_VERSION="${UBUNTU_VERSION:-24.04}"
UBUNTU_POINT="${UBUNTU_POINT:-1}"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_VERSION}/release/ubuntu-base-${UBUNTU_VERSION}.${UBUNTU_POINT}-base-${ARCH_CODE}.tar.gz"

WORKDIR="${WORKDIR:-$(pwd)/ubuntu-rootfs-workspace}"
ROOTFS_DIR="${ROOTFS_DIR:-$WORKDIR/ubuntu-rootfs}"
TARBALL="${TARBALL:-$WORKDIR/ubuntu-base.tar.gz}"

mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "==> Target arch   : $ARCH_CODE"
echo "==> Ubuntu version: $UBUNTU_VERSION.$UBUNTU_POINT"
echo "==> Workdir       : $WORKDIR"
echo "==> Rootfs dir    : $ROOTFS_DIR"
echo "==> URL           : $ROOTFS_URL"
echo

# ---- 1. Download ----------------------------------------------------
if [[ ! -f "$TARBALL" ]]; then
    echo "==> Downloading rootfs…"
    curl -fL "$ROOTFS_URL" -o "$TARBALL"
fi

# ---- 2. Verify ------------------------------------------------------
EXPECTED_SHA_URL="${ROOTFS_URL}.SHA256"
if [[ -f /usr/bin/sha256sum ]]; then
    echo "==> Fetching expected SHA256…"
    EXPECTED_SHA="$(curl -fsL "$EXPECTED_SHA_URL" 2>/dev/null | awk '{print $1}' || true)"
    if [[ -n "$EXPECTED_SHA" ]]; then
        ACTUAL_SHA="$(sha256sum "$TARBALL" | awk '{print $1}')"
        if [[ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]]; then
            echo "SHA256 mismatch!"
            echo "  expected: $EXPECTED_SHA"
            echo "  actual:   $ACTUAL_SHA"
            exit 1
        fi
        echo "    OK: $ACTUAL_SHA"
    else
        echo "    (could not fetch $EXPECTED_SHA_URL — skipping verification)"
    fi
fi

# ---- 3. Extract -----------------------------------------------------
if [[ ! -d "$ROOTFS_DIR" || ! -f "$ROOTFS_DIR/.ubuntuterm_ready" ]]; then
    echo "==> Extracting rootfs to $ROOTFS_DIR…"
    mkdir -p "$ROOTFS_DIR"
    tar -xzf "$TARBALL" -C "$ROOTFS_DIR"
fi

# ---- 4. Configure ---------------------------------------------------
echo "==> Configuring rootfs…"

# resolv.conf
cat > "$ROOTFS_DIR/etc/resolv.conf" <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF

# hostname + hosts
echo "ubuntuterm" > "$ROOTFS_DIR/etc/hostname"
cat > "$ROOTFS_DIR/etc/hosts" <<'EOF'
127.0.0.1   localhost
127.0.1.1   ubuntuterm
::1         localhost ip6-localhost ip6-loopback
EOF

# non-root user
if ! grep -q "^ubuntu:" "$ROOTFS_DIR/etc/passwd"; then
    echo "ubuntu:x:1000:1000:Ubuntu:/home/ubuntu:/bin/bash" >> "$ROOTFS_DIR/etc/passwd"
fi
if ! grep -q "^ubuntu:" "$ROOTFS_DIR/etc/group"; then
    echo "ubuntu:x:1000:" >> "$ROOTFS_DIR/etc/group"
fi
mkdir -p "$ROOTFS_DIR/home/ubuntu"
cat > "$ROOTFS_DIR/home/ubuntu/.bashrc" <<'EOF'
# Added by UbuntuTerminal bootstrap
export PS1='\[\033[01;32m\]ubuntu\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$HOME/.local/bin"
export LANG=C.UTF-8
export TERM=xterm-256color
export HOME=/home/ubuntu
cd ~ 2>/dev/null || true
EOF

# Mark ready
cat > "$ROOTFS_DIR/.ubuntuterm_ready" <<EOF
ready=true
version=$UBUNTU_VERSION
arch=$ARCH_CODE
timestamp=$(date +%s)
EOF

# ---- 5. Verify via PRoot -------------------------------------------
if command -v proot >/dev/null 2>&1; then
    echo
    echo "==> proot is available — running smoke tests"
    proot --rootfs="$ROOTFS_DIR" --root-id --cwd=/home/ubuntu \
          /bin/bash -lc 'echo "=== uname -a ==="; uname -a; echo "=== pwd ==="; pwd; echo "=== ls ==="; ls -la'
else
    echo
    echo "!! proot not installed on this host — skipping in-rootfs smoke test"
    echo "    (on-device, the APK uses its own bundled PRoot static binary)"
fi

echo
echo "Done. Ubuntu rootfs is at: $ROOTFS_DIR"
echo
echo "Next: copy this directory into the APK assets, or re-run the in-app"
echo "bootstrap (which downloads the same tarball at first launch)."
