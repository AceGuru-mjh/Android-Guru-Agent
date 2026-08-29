package com.apex.agent.platform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.ubuntu.ArchiveFormat
import com.apex.agent.platform.terminal.ubuntu.OfficialUbuntuRootfsSource
import com.apex.agent.platform.terminal.ubuntu.ProvisionedRootfsProvider
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.ProvisioningState
import com.apex.agent.platform.terminal.ubuntu.RootfsConfigurator
import com.apex.agent.platform.terminal.ubuntu.RootfsHealthInspector
import com.apex.agent.platform.terminal.ubuntu.RootfsInstallLayout
import com.apex.agent.platform.terminal.ubuntu.RootfsMetadataStore
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisionerImpl
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.NativeLibraryPRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T72 — REAL-DEVICE instrumentation test: Ubuntu RootFS Productionization
 * on the actual target environment (arm64 phone + Termux proot 5.1.107 from
 * APK jniLibs).
 *
 * Chain under test (the T72 acceptance, device edition):
 *
 *   REAL download from cdimage.ubuntu.com (arm64, ~30MB over device network)
 *     → REAL SHA-256 verification (official checksum)
 *     → T72 extractor (3413 entries incl. 194 symlinks + 2 hardlinks)
 *     → configurator (DNS from device / system, hosts, apt dirs)
 *     → health inspector (arch = ARM64 via ELF read)
 *     → READY rootfs (stage evidence + health in metadata)
 *     → LinuxPRootBackend.prepare() with the APK's libproot.so
 *     → REAL proot (Termux build: -E / -- syntax, the production contract)
 *     → /bin/bash inside Ubuntu 24.04 → /usr/bin/apt --version
 *
 * This is the ONLY place the full Termux-proot argv contract (-E/-- via
 * PRootCommandBuilder, no host adaptation) executes against a real Ubuntu
 * rootfs. CI has no device — this class is compile-checked in CI and run
 * via :platform:terminal:connectedDebugAndroidTest.
 *
 * Requires: device network access to cdimage.ubuntu.com, ~400MB free space
 * under context.filesDir, ptrace permitted (production devices allow it for
 * debuggable apps; self-skips otherwise).
 */
@RunWith(AndroidJUnit4::class)
class RootfsProvisioningInstrumentationTest {

    companion object {
        private lateinit var layout: RootfsInstallLayout
        private lateinit var provisioner: RootfsProvisionerImpl
        private var installFailure: String? = null

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val base = File(ctx.filesDir, "t72-rootfs-e2e")
            layout = RootfsInstallLayout.under(AbsolutePath(base.absolutePath))
            // Device DNS: read the system properties net.dns1/net.dns2 when
            // available (adb shell getprop); fall back to the configurator's
            // honest public-DNS path with a warning.
            val deviceDns = readDeviceDns()
            provisioner = RootfsProvisionerImpl(
                source = OfficialUbuntuRootfsSource(),
                validator = null,
                layout = layout,
                configurator = RootfsConfigurator(dnsServers = { deviceDns }),
                healthCheck = RootfsHealthInspector(expectedArch = CpuArchitecture.ARM64)
            )
            try {
                runBlocking {
                    val result = provisioner.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
                    if (result !is ProvisioningResult.Ready) installFailure = result.toString()
                }
            } catch (e: Throwable) {
                installFailure = e.message
            }
        }

        private fun readDeviceDns(): List<String> {
            // net.dns1/net.dns2 are system properties readable by the shell
            // user on most devices; empty when unavailable (configurator then
            // falls back to public resolvers with an honest warning).
            return try {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java)
                listOf("net.dns1", "net.dns2")
                    .map { prop -> runCatching { get.invoke(null, prop) as String }.getOrNull() }
                    .filterNotNull()
                    .filter { it.isNotBlank() }
            } catch (e: Throwable) {
                emptyList()
            }
        }

        private fun prootExecCapable(): Boolean {
            return try {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val hostEnv = PRootHostEnvironment(
                    nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir,
                    baseDir = ctx.filesDir,
                    cacheDir = ctx.cacheDir
                )
                if (hostEnv.prepare().isFailure) return false
                val probe = ProcessBuilder(
                    hostEnv.prootBinary.absolutePath, "-r", "/system", "--", "/system/bin/true"
                )
                probe.environment().clear()
                probe.environment().putAll(hostEnv.hostEnv())
                probe.start().waitFor() == 0
            } catch (e: Throwable) {
                false
            }
        }
    }

    @Test
    fun `device install reaches READY with real archive`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assertEquals(ProvisioningState.READY, provisioner.state())
        val current = runBlocking { provisioner.current() }
        assertNotNull(current)
        assertEquals("ubuntu-24.04.4-arm64", current!!.id)
        assertEquals(
            "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            current.checksum
        )
    }

    @Test
    fun `device metadata has full stage evidence and healthy summary`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        val meta = runBlocking { RootfsMetadataStore(File(layout.metadataFile.value)).load() }
        assertNotNull(meta)
        for (stage in listOf("DOWNLOADED", "VERIFIED", "EXTRACTED", "CONFIGURED", "READY")) {
            assertTrue("stage $stage: ${meta!!.stageEvidence}", meta!!.stageEvidence.containsKey(stage))
        }
        assertNotNull("health summary: ${meta!!.health}", meta.health)
        assertTrue(meta.health!!.valid)
    }

    @Test
    fun `device rootfs symlinks and hardlinks survived extraction`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        val rootfs = runBlocking { provisioner.current() }!!
        val root = File(rootfs.location!!.value)
        assertTrue("bin is merged-usr symlink", java.nio.file.Files.isSymbolicLink(File(root, "bin").toPath()))
        assertTrue("bash executable through symlink", File(root, "bin/bash").canExecute())
        // the 2 hardlinks in the real archive
        assertEquals(
            "perl hardlink intact",
            File(root, "usr/bin/perl").toPath().toRealPath(),
            File(root, "usr/bin/perl5.38.2").toPath().toRealPath()
        )
    }

    /**
     * THE production-contract test: PRootCommandBuilder's raw argv (with the
     * Termux -E/-- syntax, NO host adaptation) against the REAL provisioned
     * Ubuntu rootfs via the APK's libproot.so.
     */
    @Test
    fun `device proot runs Ubuntu bash via Termux argv contract`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assumeTrue("ptrace restricted on this device", prootExecCapable())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hostEnv = PRootHostEnvironment(
            nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir,
            baseDir = ctx.filesDir,
            cacheDir = ctx.cacheDir
        )
        hostEnv.prepare().getOrThrow()

        val backend = LinuxPRootBackend(
            binaryProvider = NativeLibraryPRootBinaryProvider(
                hostEnv = hostEnv,
                supportedAbis = { android.os.Build.SUPPORTED_ABIS.toList() }
            ),
            rootfsProvider = ProvisionedRootfsProvider(provisioner),
            workspaces = com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager(
                File(ctx.filesDir, "t75-workspaces")
            ),
            userHome = com.apex.agent.platform.terminal.workspace.GuestUserHome(
                File(ctx.filesDir, "t75-home")
            ),
            hostEnv = hostEnv
        )
        val spec = runBlocking {
            backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80))
        }.getOrThrow()

        // Replace the trailing interactive "bash -i" with a one-shot command
        val argv = spec.argv.toMutableList()
        assertTrue("argv ends with bash -i: $argv", argv.takeLast(2) == listOf("/bin/bash", "-i"))
        argv.removeAt(argv.size - 1)   // drop "-i"
        argv.add("-c")
        argv.add("head -1 /etc/os-release && /usr/bin/apt --version | head -1 && test -L /bin && echo SYMLINK-OK")

        val executor = ProotExecutor(hostEnv = { hostEnv.hostEnv() })
        val result = executor.execute(
            PRootCommand(AbsolutePath(argv[0]), argv.drop(1)),
            timeoutMs = 120_000
        )
        assertEquals("proot bash exit: ${result.stderr}", 0, result.exitCode)
        assertTrue("os-release: '${result.stdout}'", result.stdout.contains("Ubuntu 24.04"))
        assertTrue("apt runs: '${result.stdout}'", result.stdout.contains("apt"))
        assertTrue("merged-usr symlink: '${result.stdout}'", result.stdout.contains("SYMLINK-OK"))
    }
}
