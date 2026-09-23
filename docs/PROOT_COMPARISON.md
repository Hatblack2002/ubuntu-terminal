# PRoot Comparison: Our APK vs proot-distro (working on same phone)

## Evidence

The same phone where our APK crashes can run Ubuntu via:
```
Termux → PRoot 5.1.107.94 → proot-distro → Ubuntu 24.04.5 ARM64
```

Inside Ubuntu: `uname -m` = `aarch64`, `apt update` works, bash 5.2.21 runs.

## PRoot binary comparison

| Property          | Our APK                      | Termux (working)             |
|-------------------|------------------------------|------------------------------|
| Version           | 5.1.0 (green-green-avk fork) | 5.1.107.94 (Termux fork)    |
| Built for Android | API 21 (NDK r23c)           | API 24                       |
| ELF               | ARM aarch64                  | ARM aarch64                  |
| Interpreter       | /system/bin/linker64         | /system/bin/linker64         |
| Dependencies      | libc.so, libdl.so (bionic)   | libc.so, libdl.so (bionic)  |

## PRoot argument comparison

### What proot-distro passes (from its source `proot-distro.sh`):

```
proot \
  --kill-on-exit \
  --rootfs=<rootfs> \
  --root-id \
  --link2symlink \
  --cwd=/root \
  --bind=/dev \
  --bind=/dev/urandom:/dev/random \
  --bind=/proc \
  --bind=/proc/self/fd:/dev/fd \
  --bind=/sys \
  --bind=/dev/null:/proc/sys/kernel/cap_last_cap \
  /bin/su -l root
```

### What our PRootRunner.buildArgv() passes:

```
proot \
  --rootfs=<rootfs> \
  --root-id \
  --link2symlink \
  --kill-on-exit \
  --cwd=/home/ubuntu \
  --hostname=ubuntuterm \
  --bind=/dev \
  --bind=/dev/urandom:/dev/random \
  --bind=/proc \
  --bind=/sys \
  --bind=<sdcard>:/sdcard \
  -- /bin/bash -l
```

### Differences:

| # | Difference                              | Impact                                           |
|---|-----------------------------------------|--------------------------------------------------|
| 1 | MISSING: `--bind=/proc/self/fd:/dev/fd` | bash can't do process substitution; many tools fail |
| 2 | MISSING: `--bind=/dev/null:/proc/sys/kernel/cap_last_cap` | PRoot may crash reading cap_last_cap from host /proc |
| 3 | WRONG: `--cwd=/home/ubuntu` vs `/root` | With `--root-id`, UID=0 inside; /root is the proper home |
| 4 | EXTRA: `--hostname=ubuntuterm`          | Harmless but different from proot-distro         |
| 5 | EXTRA: `--bind=<sdcard>:/sdcard`        | Harmless but different                           |
| 6 | WRONG: `/bin/bash -l` vs `/bin/su -l root` | With --root-id we ARE root already; su not needed |

## Environment variable comparison

### proot-distro environment:

```
PROOT_NO_SECCOMP=1
PROOT_L2S_DIR=<rootfs>/.link2symlink_dirs
PROOT_TMP_DIR=<writable tmp>
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/root
USER=root
TERM=xterm-256color
```

### Our environment:

```
PROOT_NO_SECCOMP=1
PROOT_TMP_DIR=<scoped storage path>     # may not be writable by PRoot
# MISSING: PROOT_L2S_DIR
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/home/ubuntu                       # wrong for --root-id
USER=ubuntu                             # wrong for --root-id
TERM=xterm-256color
LANG=C.UTF-8
LC_ALL=C.UTF-8
```

### Differences:

| # | Difference                    | Impact                                                        |
|---|-------------------------------|---------------------------------------------------------------|
| 1 | MISSING: `PROOT_L2S_DIR`     | `--link2symlink` doesn't know where to store metadata; may crash |
| 2 | WRONG: `PROOT_TMP_DIR`        | Scoped storage path may not be writable by PRoot child process |
| 3 | WRONG: `HOME=/home/ubuntu`   | With `--root-id`, UID=0; proper HOME is `/root`              |
| 4 | WRONG: `USER=ubuntu`          | With `--root-id`, should be `root`                            |

## Conclusion

Our PRoot invocation is missing critical binds and environment variables that
proot-distro (which works on the same phone) includes. The most critical:

1. `--bind=/dev/null:/proc/sys/kernel/cap_last_cap` (proot-distro includes this)
2. `--bind=/proc/self/fd:/dev/fd` (proot-distro includes this)
3. `PROOT_L2S_DIR` environment variable (required by `--link2symlink`)
4. Correct `HOME=/root` and `USER=root` for `--root-id` mode

These will be fixed in PRootRunner.kt without changing the architecture.
