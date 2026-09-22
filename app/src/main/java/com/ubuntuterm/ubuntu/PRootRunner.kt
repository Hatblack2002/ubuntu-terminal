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
 * PRoot invocation:
 *
 *   proot \
 *     --rootfs=<rootfs>      # where Ubuntu lives
 *     --root-id              # map our UID to 0 (root) inside the sandbox
 *                            # — required for apt to work
 *     --cwd=/home/ubuntu     # initial working directory
 *     --bind=/dev            # /dev is required by many tools
 *     --bind=/proc           # /proc is required by ps, top, etc.
 *     --bind=/sys            # /sys for kernel info
 *     --bind=<sdcard>:/sdcard   # expose Android storage inside Ubuntu
 *     --bind=<tmp>/tmp:/tmp     # private /tmp
 *     /bin/bash -l           # login shell
 */
object PRootRunner {

    data class LaunchConfig(
        val rootfs: File,
        val cwd: String = "/home/ubuntu",
        val shell: String = "/bin/bash",
        val shellArgs: List<String> = listOf("-l"),
        val initialCols: Int = 80,
        val initialRows: Int = 24,
        val extraBinds: List<Pair<String, String>> = emptyList(),
        val username: String = "ubuntu",
        val hostname: String = "ubuntuterm"
    )

    /**
     * Returns the argv array to pass to execve().
     * argv[0] is the PRoot binary itself.
     */
    fun buildArgv(config: LaunchConfig): List<String> {
        val proot = FileLocations.prootBinary
        require(proot.exists() && proot.canExecute()) {
            "PRoot binary not ready: ${proot.absolutePath}"
        }

        val argv = mutableListOf<String>()
        argv += proot.absolutePath
        argv += "--rootfs=${config.rootfs.absolutePath}"
        argv += "--root-id"                // we become root inside
        argv += "--link2symlink"           // hardlinks work as expected
        argv += "--kill-on-exit"           // ensure no zombies
        argv += "--cwd=${config.cwd}"
        argv += "--hostname=${config.hostname}"

        // Standard binds required for any Linux userspace to function.
        argv += "--bind=/dev"
        argv += "--bind=/dev/urandom:/dev/random"  // some tools need /dev/random
        argv += "--bind=/proc"
        argv += "--bind=/sys"

        // Expose the app's external storage as /sdcard so the user can
        // move files between Android and Ubuntu.
        argv += "--bind=${FileLocations.rootDir.parentFile?.parentFile?.absolutePath ?: "/sdcard"}:/sdcard"

        // Private /tmp inside the sandbox (rootfs has its own already).
        // We don't bind — let PRoot use the rootfs's own /tmp.

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
     */
    fun buildEnvp(config: LaunchConfig): List<String> {
        val env = LinkedHashMap<String, String>()

        // PATH inside Ubuntu
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["HOME"] = "/home/${config.username}"
        env["USER"] = config.username
        env["LOGNAME"] = config.username
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["SHELL"] = "/bin/bash"
        env["HOSTNAME"] = config.hostname
        env["PROOT_TMP_DIR"] = FileLocations.cacheDir.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"  // avoid seccomp issues on some kernels

        return env.entries.map { "${it.key}=${it.value}" }
    }

    /**
     * Shorthand for the standard launch config used by the app.
     */
    fun defaultConfig(): LaunchConfig = LaunchConfig(
        rootfs = FileLocations.ubuntuRootDir
    )
}
