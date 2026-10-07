package com.ubuntuterm.terminal

import com.ubuntuterm.ubuntu.PRootRunner
import com.ubuntuterm.util.FileLocations
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

/**
 * Unit tests for UbuntuSession using a fake NativeTerminalBridge.
 *
 * Uses RobolectricTestRunner to provide android.util.Log and other
 * Android stubs that UbuntuSession references internally.
 *
 * Per the validation strategy (Fase 3 & 6):
 *   - State normal: Idle -> Starting -> Running
 *   - State failure: Idle -> Starting -> Failed
 */
@RunWith(RobolectricTestRunner::class)
class UbuntuSessionTest {

    @Before
    fun setup() {
        // Initialize FileLocations with Robolectric's application context.
        // This is required because PRootRunner.buildArgv() calls
        // FileLocations.prootBinary which needs to be initialized.
        FileLocations.init(RuntimeEnvironment.getApplication())
        // Create a fake PRoot binary so buildArgv's require() passes.
        val proot = FileLocations.prootBinary
        proot.parentFile?.mkdirs()
        // Write a dummy file and make it executable
        proot.writeText("#!/bin/sh\n")
        proot.setExecutable(true, true)
    }

    private class FakeNativeTerminalBridge(
        val spawnResult: Long = 0x0000000100000001L,
        val spawnException: Throwable? = null
    ) : NativeTerminalBridge {
        var spawnCalled = false
        override fun spawn(cwd: String?, argv: Array<String>, envp: Array<String>?, cols: Int, rows: Int): Long {
            spawnCalled = true
            spawnException?.let { throw it }
            return spawnResult
        }
        override fun write(handle: Long, buf: ByteArray, off: Int, len: Int): Int = len
        override fun read(handle: Long, buf: ByteArray, off: Int, len: Int): Int = -2
        override fun setSize(handle: Long, cols: Int, rows: Int) {}
        override fun sendSignal(handle: Long, signo: Int) {}
        override fun waitExit(handle: Long, blocking: Boolean): Int = -2
        override fun close(handle: Long) {}
    }

    private fun testConfig(): PRootRunner.LaunchConfig {
        return PRootRunner.LaunchConfig(rootfs = FileLocations.ubuntuRootDir)
    }

    @Test
    fun session_should_transition_Starting_to_Running_when_spawn_succeeds() {
        val bridge = FakeNativeTerminalBridge(spawnResult = 0x0000000100000001L)
        val session = UbuntuSession(
            id = "test-1", title = "test", config = testConfig(),
            nativeBridge = bridge
        )

        assertEquals(UbuntuSession.SessionState.Idle, session.state.value)

        session.start()
        runBlocking {
            var attempts = 0
            while (session.state.value is UbuntuSession.SessionState.Starting && attempts < 50) {
                delay(100)
                attempts++
            }
        }

        assertTrue("spawn should have been called", bridge.spawnCalled)
        assertEquals(UbuntuSession.SessionState.Running, session.state.value)
    }

    @Test
    fun session_should_transition_to_Failed_when_spawn_throws_IOException() {
        val bridge = FakeNativeTerminalBridge(
            spawnException = IOException("Permission denied")
        )
        val session = UbuntuSession(
            id = "test-2", title = "test", config = testConfig(),
            nativeBridge = bridge
        )

        session.start()
        runBlocking {
            var attempts = 0
            while (session.state.value is UbuntuSession.SessionState.Starting && attempts < 50) {
                delay(100)
                attempts++
            }
        }

        assertTrue("spawn should have been called", bridge.spawnCalled)
        assertTrue("Session should be Failed",
            session.state.value is UbuntuSession.SessionState.Failed)
        val failed = session.state.value as UbuntuSession.SessionState.Failed
        assertTrue("Error should mention Permission denied",
            failed.reason.contains("Permission denied"))
    }

    @Test
    fun session_should_transition_to_Failed_when_spawn_returns_0() {
        val bridge = FakeNativeTerminalBridge(spawnResult = 0L)
        val session = UbuntuSession(
            id = "test-3", title = "test", config = testConfig(),
            nativeBridge = bridge
        )

        session.start()
        runBlocking {
            var attempts = 0
            while (session.state.value is UbuntuSession.SessionState.Starting && attempts < 50) {
                delay(100)
                attempts++
            }
        }

        assertTrue("Session should be Failed",
            session.state.value is UbuntuSession.SessionState.Failed)
    }

    @Test
    fun session_should_not_start_twice() {
        val bridge = FakeNativeTerminalBridge()
        val session = UbuntuSession(
            id = "test-4", title = "test", config = testConfig(),
            nativeBridge = bridge
        )

        session.start()
        runBlocking {
            var attempts = 0
            while (session.state.value is UbuntuSession.SessionState.Starting && attempts < 50) {
                delay(100)
                attempts++
            }
        }
        val firstState = session.state.value
        session.start()
        assertEquals(firstState, session.state.value)
    }
}
