package com.ubuntuterm.ubuntu

import com.ubuntuterm.util.FileLocations
import java.io.File

/**
 * Builds the argv and envp needed to launch a real Ubuntu shell via
 * PRoot.
 *
 * Per project spec (sections 3, 5, 6, 7):
 *   - We do NOT simulate. We invoke the real PRoot binary, which in
 *     turn executes the real /bin/bash inside the real Ubuntu rootfs.
 *   - The user types `ls` and the real GNU ls runs.
 *   - No HTTP, no REST — we just fork()/execve() from native code.
 *
 * v0.1.6 FIX: argv and envp now match what proot-distro (which works
 * on the same phone) uses. See docs/PROOT_COMPARISON.md for details.
 *
 * PRoot invocation (aligned with proot-distro's working setup):
 *
 *   proot \
 *     --kill-on-exit \
 *     --rootfs=<rootfs>      # where Ubuntu lives
 *     --root-id              # map our UID to 0 (root) inside the sandbox
 *     --link2symlink         # hardlinks work as expected
 *     --cwd=/root            # home directory for root
 *     --bind=/dev            # /dev is required by many tools
 *     --bind=/dev/urandom:/dev/random  # some tools need /dev/random
 *     --bind=/proc           # /proc is required by ps, top, etc.
 *     --bind=/proc/self/fd:/dev/fd   # process substitution in bash
 *     --bind=/sys            # /sys for kernel info
 *     --bind=/dev/null:/proc/sys/kernel/cap_last_cap  # CRITICAL on Android
 *     --bind=<sdcard>:/sdcard   # expose Android storage inside Ubuntu
 *     /bin/bash --login     # login shell
 */
object PRootRunner {

    data class LaunchConfig(
        val rootfs: File,
        // v0.1.6: changed from /home/ubuntu to /root to match --root-id.
        // When --root-id is used, UID inside the sandbox is 0 (root).
        // The home directory for root in Ubuntu is /root, not /home/ubuntu.
        val cwd: String = "/root",
        val shell: String = "/bin/bash",
        // v0.1.6: changed from "-l" to "--login" for clarity.
        val shellArgs: List<String> = listOf("--login"),
        val initialCols: Int = 80,
        val initialRows: Int = 24,
        val extraBinds: List<Pair<String, String>> = emptyList(),
        // v0.1.6: changed from "ubuntu" to "root" to match --root-id.
        val username: String = "root",
        val hostname: String = "ubuntuterm"
    )

    /**
     * Returns the argv array to pass to execve().
     * argv[0] is the PRoot binary itself.
     *
     * v0.1.6: aligned with proot-distro's working invocation.
     * See docs/PROOT_COMPARISON.md for the comparison.
     */
    fun buildArgv(config: LaunchConfig): List<String> {
        val proot = FileLocations.prootBinary
        require(proot.exists() && proot.canExecute()) {
            "PRoot binary not ready: ${proot.absolutePath}"
        }

        val argv = mutableListOf<String>()
        argv += proot.absolutePath
        argv += "--kill-on-exit"
        argv += "--rootfs=${config.rootfs.absolutePath}"
        argv += "--root-id"                // we become root inside
        argv += "--link2symlink"           // hardlinks work as expected
        argv += "--cwd=${config.cwd}"

        // Standard binds required for any Linux userspace to function.
        // These match proot-distro's working setup.
        argv += "--bind=/dev"
        argv += "--bind=/dev/urandom:/dev/random"
        argv += "--bind=/proc"
        argv += "--bind=/proc/self/fd:/dev/fd"  // v0.1.6: was missing
        argv += "--bind=/sys"
        // v0.1.6: CRITICAL — without this bind, PRoot may crash when
        // reading /proc/sys/kernel/cap_last_cap from the host kernel.
        // proot-distro includes this bind in every working setup.
        argv += "--bind=/dev/null:/proc/sys/kernel/cap_last_cap"

        // Expose the app's external storage as /sdcard so the user can
        // move files between Android and Ubuntu.
        argv += "--bind=${FileLocations.rootDir.parentFile?.parentFile?.absolutePath ?: "/sdcard"}:/sdcard"

        // User-defined binds
        for ((src, dst) in config.extraBinds) {
            argv += "--bind=$src:$dst"
        }

        argv += "--"
        argv += config.shell
        argv += config.shellArgs

        return argv
    }

    /**
     * Returns the envp array ("KEY=VALUE" strings) to pass to execve().
     *
     * v0.1.6: aligned with proot-distro's working environment.
     * Key changes:
     *   - HOME=/root (was /home/ubuntu) — matches --root-id
     *   - USER=root (was ubuntu) — matches --root-id
     *   - PROOT_L2S_DIR added — required by --link2symlink
     *   - PROOT_TMP_DIR points to a writable directory inside the rootfs
     */
    fun buildEnvp(config: LaunchConfig): List<String> {
        val env = LinkedHashMap<String, String>()

        // PATH inside Ubuntu
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // v0.1.6: HOME=/root because --root-id makes us UID 0 inside.
        env["HOME"] = "/root"
        env["USER"] = config.username
        env["LOGNAME"] = config.username
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["SHELL"] = "/bin/bash"
        env["HOSTNAME"] = config.hostname

        // PROOT_NO_SECCOMP: avoid seccomp issues on some kernels.
        env["PROOT_NO_SECCOMP"] = "1"

        // v0.1.6: PROOT_TMP_DIR — PRoot needs a writable temp directory.
        // We point it to the rootfs's /tmp which is writable because
        // --root-id makes us UID 0.
        env["PROOT_TMP_DIR"] = "/tmp"

        // v0.1.6: PROOT_L2S_DIR — required by --link2symlink.
        // This directory stores link2symlink metadata inside the rootfs.
        // We create it during bootstrap in configureRootfs().
        env["PROOT_L2S_DIR"] = "${config.rootfs.absolutePath}/.link2symlink_dirs"

        return env.entries.map { "${it.key}=${it.value}" }
    }

    /**
     * Shorthand for the standard launch config used by the app.
     */
    fun defaultConfig(): LaunchConfig = LaunchConfig(
        rootfs = FileLocations.ubuntuRootDir
    )
}
