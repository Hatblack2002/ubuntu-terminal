# Environment Capabilities

This document describes what the build environment actually supports.
It is generated from a real probe of the container, not from assumptions.

> Generated: 2026-09-22
> Host kernel: x86_64 Linux (no KVM, no binfmt_misc, no /dev/kvm)
> Disk available: ~1.5 GB free of 9.9 GB

## Tool availability

| Tool                  | Available | Notes                                                          |
|-----------------------|-----------|----------------------------------------------------------------|
| `adb`                 | YES       | Android Debug Bridge 1.0.41 — but **no device/emulator connected** |
| `emulator`            | NO        | Package not installed. ~400 MB. Without `/dev/kvm`, impractical. |
| `avdmanager`          | YES       | 12.0 — but cannot create usable AVD without emulator + system-image |
| `sdkmanager`          | YES       | 12.0                                                           |
| `aapt`                | YES       | Android Asset Packaging Tool                                   |
| `aapt2`               | YES       | Android Asset Packaging Tool v2                                |
| `apksigner`           | YES       | 0.9                                                            |
| `zipalign`            | YES       | Included in build-tools 34.0.0                                 |
| `dexdump`             | YES       | DEX inspector included in build-tools 34.0.0                    |
| `java`                | YES       | OpenJDK 21 (system) + Temurin JDK 17 (tools/)                   |
| `javac`               | YES       | Temurin JDK 17 (`/home/z/my-project/tools/jdk17/bin/javac`)     |
| `gradle`              | YES       | 8.9 (`/home/z/my-project/tools/gradle/bin/gradle`)              |
| `kotlinc`             | NO        | Standalone `kotlinc` not installed; Gradle uses the Kotlin compiler daemon |
| `cmake`               | YES       | 3.22.1 (via Android SDK)                                       |
| `python3`             | YES       | Used for DEX parsing, validation scripts                       |
| `qemu-aarch64`        | NO        | Cannot run arm64 binaries on this x86_64 host                  |
| `qemu-aarch64-static` | NO        | Same as above                                                  |
| `binfmt_misc`         | NO        | `/proc/sys/fs/binfmt_misc/` is empty                           |
| `/dev/kvm`            | NO        | No KVM acceleration available                                  |
| `Xvfb`                | YES       | `/usr/bin/Xvfb` available — can run headless X server          |
| `DISPLAY`             | NO        | Not set by default                                             |

## Android SDK components

| Component                       | Installed | Path                                                        |
|---------------------------------|-----------|-------------------------------------------------------------|
| Android SDK root                | YES       | `/home/z/my-project/tools/android-sdk`                      |
| platform-tools                  | YES       | `…/platform-tools` (adb, fastboot, etc.)                    |
| build-tools;34.0.0              | YES       | `…/build-tools/34.0.0` (aapt2, apksigner, dexdump, etc.)    |
| platforms;android-34            | YES       | `…/platforms/android-34`                                    |
| ndk;26.3.11579264               | YES       | `…/ndk/26.3.11579264` (slimmed: no shaders, no simpleperf)  |
| cmake;3.22.1                    | YES       | `…/cmake/3.22.1`                                            |
| cmdline-tools/latest            | YES       | `…/cmdline-tools/latest` (sdkmanager, avdmanager)           |
| emulator                        | NO        | Not installed                                               |
| system-images                   | NO        | None installed                                              |

## What this enables

### CAN do in this environment

1. **Compile the APK** with `./gradlew assembleDebug` (works — produced v0.1.4 successfully).
2. **Inspect APK artifacts** with `aapt2 dump`, `unzip`, `dexdump`, `python3` DEX parsing.
3. **Run JVM unit tests** with `./gradlew testDebugUnitTest` (Roboelectric for Android-independent logic).
4. **Run Compose UI tests** with `./gradlew connectedDebugAndroidTest` — **only if an emulator/device is available**, which it is NOT.
5. **Run host-side bash scripts** for rootfs validation (download + extract + verify symlinks).
6. **Inspect native binaries** with `readelf`, `nm`, `file`, `objdump`.
7. **Static analysis** of Kotlin/C++ source.
8. **Reproducible build validation** scripts.

### CANNOT do in this environment

1. **Cannot execute arm64 binaries** (PRoot, libterminal.so, Ubuntu binaries) — no qemu-user-static, no binfmt_misc.
2. **Cannot run an Android emulator** — no `emulator` package, no system-image, no `/dev/kvm`. Even if installed, ARM emulation without KVM is impractical (5–10 min boot, ~1.5 GB image).
3. **Cannot capture on-device `adb logcat`** — no device connected.
4. **Cannot execute `libterminal.so`** end-to-end — it is arm64 ELF; host is x86_64.
5. **Cannot execute PRoot** to validate the full chain (rootfs → /bin/bash → command output).
6. **Cannot run instrumented tests** that depend on `ActivityScenario`, `AndroidJUnit4`, real `Context`, real `Os.symlink()`, etc.

## Implications for validation strategy

Each test result in this project must be tagged with one of:

- **COMPILED** — the code compiles cleanly via Gradle.
- **VERIFIED STATICALLY** — manual inspection of source code (e.g., `nativeSpawn()` runs inside `withContext(Dispatchers.IO)`).
- **VERIFIED ON HOST** — a host-side test (bash, python3, file, readelf) confirms the property.
- **VERIFIED IN EMULATOR** — not possible in this environment.
- **VERIFIED ON PHYSICAL DEVICE** — not possible in this environment.
- **NOT VERIFIABLE IN THIS ENVIRONMENT** — explicitly declared, with reason.

Tests that depend on Android Runtime (`Os.symlink()`, `Context.getAssets()`, foreground service start, PTY fork+execve, Compose composition) are NOT VERIFIABLE in this environment without an Android device or emulator. They must be tagged accordingly.

## Why we cannot just install an emulator

1. **`/dev/kvm` is missing.** Without KVM, the emulator falls back to full software emulation of the guest CPU. Boot time grows to 5–15 minutes per launch. Interactive testing becomes impractical.
2. **Disk space is constrained.** ~1.5 GB free. A system-image (`system-images;android-34;google_apis;arm64-v8a`) is ~1.5 GB; the emulator package is ~400 MB. Combined ~2 GB; we are at 1.5 GB free.
3. **Even with an emulator, ARM system-images cannot run on x86_64 hosts without qemu-user-static + binfmt_misc** — which are also missing. The only viable emulator system-image would be `x86_64`, but then `libterminal.so` (compiled for `arm64-v8a` only) could not be loaded.
4. **No display server.** `DISPLAY` is not set. `Xvfb` is available so headless is theoretically possible, but combined with the above it is not worth the setup cost.

## Decision

This environment supports:
- **Static validation** of source and artifacts (most useful).
- **JVM unit tests** for non-Android-dependent logic.
- **Host-side scripts** for rootfs/native/APK inspection.

This environment does NOT support:
- End-to-end runtime validation (PTY + fork + PRoot + Ubuntu + bash).
- Compose UI instrumented tests.
- Foreground service lifecycle tests.
- adb logcat capture.

Any test that requires the Android Runtime will be tagged `NOT VERIFIED — ANDROID RUNTIME REQUIRED`. We will not invent or simulate results.
