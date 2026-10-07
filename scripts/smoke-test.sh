#!/usr/bin/env bash
# =====================================================================
# smoke-test.sh
# ---------------------------------------------------------------------
# Host-side smoke test for the Ubuntu rootfs.
# Requires: proot installed on the host (apt install proot).
#
# Runs the same "first prototype" chain specified in section 20:
#   $ uname -a
#   $ pwd
#   $ ls
#   $ echo Hello
#   $ mkdir test && cd test && touch file.txt && ls
#   $ python3 --version (if installed in the rootfs)
#   $ git --version      (if installed in the rootfs)
# =====================================================================
set -euo pipefail

ROOTFS="${1:-./ubuntu-rootfs-workspace/ubuntu-rootfs}"

if [[ ! -d "$ROOTFS" ]]; then
    echo "Rootfs not found at: $ROOTFS"
    echo "Run ./bootstrap-ubuntu.sh first."
    exit 1
fi

if ! command -v proot >/dev/null 2>&1; then
    echo "proot is required. Install with: sudo apt install proot"
    exit 1
fi

echo "==> Running smoke tests inside $ROOTFS"
echo

run() {
    echo "\$ $*"
    proot --rootfs="$ROOTFS" --root-id --cwd=/home/ubuntu \
          /bin/bash -lc "$*"
    echo
}

run uname -a
run pwd
run ls -la
run 'echo Hello'
run 'mkdir -p test && cd test && touch file.txt && ls -la'

# Optional tools
if proot --rootfs="$ROOTFS" --root-id --cwd=/home/ubuntu \
        /bin/bash -lc 'command -v python3' >/dev/null 2>&1; then
    run python3 --version
else
    echo "!! python3 not installed in rootfs — install with: proot -r $ROOTFS apt install -y python3"
fi

if proot --rootfs="$ROOTFS" --root-id --cwd=/home/ubuntu \
        /bin/bash -lc 'command -v git' >/dev/null 2>&1; then
    run git --version
fi

echo "==> Smoke tests complete"
