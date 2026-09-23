package com.ubuntuterm.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for PRootRunner — the component that builds argv and envp
 * for the PRoot invocation.
 *
 * These tests verify the LOGIC of argument construction WITHOUT
 * requiring Android Runtime, JNI, or a real PRoot binary.
 *
 * Per the validation strategy (Fase 3):
 *   - State normal: Idle -> Starting -> Running
 *   - State failure: Idle -> Starting -> Failed
 */
class PRootRunnerTest {

    @Test
    fun buildEnvp_should_contain_PROOT_NO_SECCOMP() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        assertTrue("PROOT_NO_SECCOMP must be set",
            envp.any { it == "PROOT_NO_SECCOMP=1" })
    }

    @Test
    fun buildEnvp_should_contain_PROOT_L2S_DIR_pointing_to_rootfs() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        val l2s = envp.find { it.startsWith("PROOT_L2S_DIR=") }
        assertTrue("PROOT_L2S_DIR must be present", l2s != null)
        assertTrue("PROOT_L2S_DIR must point to rootfs",
            l2s!!.contains("/fake/rootfs/.link2symlink_dirs"))
    }

    @Test
    fun buildEnvp_should_contain_PROOT_TMP_DIR() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        assertTrue("PROOT_TMP_DIR must be set",
            envp.any { it.startsWith("PROOT_TMP_DIR=") })
    }

    @Test
    fun buildEnvp_should_set_HOME_to_root_for_root_id() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        assertTrue("HOME must be /root (matching --root-id)",
            envp.any { it == "HOME=/root" })
    }

    @Test
    fun buildEnvp_should_set_USER_to_root_for_root_id() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        assertTrue("USER must be root (matching --root-id)",
            envp.any { it == "USER=root" })
    }

    @Test
    fun buildEnvp_should_contain_PATH_with_standard_Ubuntu_paths() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        val path = envp.find { it.startsWith("PATH=") }
        assertTrue("PATH must be present", path != null)
        assertTrue("PATH must include usr-bin", path!!.contains("usr/bin"))
        assertTrue("PATH must include bin", path.contains("bin"))
    }

    @Test
    fun buildEnvp_should_contain_TERM_xterm_256color() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        assertTrue("TERM must be xterm-256color",
            envp.any { it == "TERM=xterm-256color" })
    }

    @Test
    fun buildEnvp_should_contain_LANG_C_UTF_8() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        val envp = PRootRunner.buildEnvp(config)
        assertTrue("LANG must be C.UTF-8",
            envp.any { it == "LANG=C.UTF-8" })
    }

    @Test
    fun default_config_should_have_cwd_root() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        assertEquals("Default cwd must be /root", "/root", config.cwd)
    }

    @Test
    fun default_config_should_have_username_root() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        assertEquals("Default username must be root", "root", config.username)
    }

    @Test
    fun default_config_should_have_shell_bash() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        assertEquals("Default shell must be /bin/bash", "/bin/bash", config.shell)
    }

    @Test
    fun default_config_should_have_shellArgs_with_login() {
        val config = PRootRunner.LaunchConfig(rootfs = File("/fake/rootfs"))
        assertTrue("shellArgs must contain --login",
            config.shellArgs.contains("--login"))
    }
}
