package com.local.voicenotes.inference

import com.local.voicenotes.inference.AcceleratorSetupException.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises accelerator selection with fakes. No LiteRT native code, Android
 * framework or Qualcomm library is loaded, so these run on any JVM.
 */
class AcceleratorEnvironmentFactoryTest {
    private class FakeEnvironment(
        val name: String,
        private val targets: Set<ExecutionTarget>,
        private val availableError: Throwable? = null
    ) : AcceleratorEnvironment {
        var closed = 0
        override fun availableTargets(): Set<ExecutionTarget> {
            availableError?.let { throw it }
            return targets
        }
        override fun close() { closed++ }
    }

    private class FakeNpuRuntime(
        private val deviceSupported: Boolean = true,
        private val libraryReady: Boolean = true,
        private val environment: () -> FakeEnvironment = {
            FakeEnvironment("npu", setOf(ExecutionTarget.CPU, ExecutionTarget.QUALCOMM_NPU))
        },
        val calls: MutableList<String> = mutableListOf()
    ) : QualcommNpuRuntime<FakeEnvironment> {
        override fun isDeviceSupported(): Boolean { calls += "isDeviceSupported"; return deviceSupported }
        override fun isLibraryReady(): Boolean { calls += "isLibraryReady"; return libraryReady }
        override fun createEnvironment(): FakeEnvironment { calls += "createEnvironment"; return environment() }
    }

    private var cpuCreations = 0
    private var npuRuntimeConstructions = 0

    private fun factory(
        cpu: () -> FakeEnvironment = { FakeEnvironment("cpu", setOf(ExecutionTarget.CPU)) },
        npu: () -> QualcommNpuRuntime<FakeEnvironment> = { FakeNpuRuntime() }
    ) = AcceleratorEnvironmentFactory(
        createCpuEnvironment = { cpuCreations++; cpu() },
        createQualcommNpuRuntime = { npuRuntimeConstructions++; npu() }
    )

    private fun setupFailure(block: () -> Unit): AcceleratorSetupException =
        assertThrows(AcceleratorSetupException::class.java) { block() }

    // ---- CPU path: independent of every Qualcomm component ----

    @Test fun cpuPathNeverConstructsQualcommRuntime() {
        val environment = factory(npu = { throw AssertionError("Qualcomm runtime must not be built for CPU") })
            .create(ExecutionTarget.CPU)

        assertEquals("cpu", environment.name)
        assertEquals(1, cpuCreations)
        assertEquals(0, npuRuntimeConstructions)
        assertEquals(0, environment.closed)
    }

    @Test fun cpuPathSucceedsOnUnsupportedDeviceWithoutDispatchLibrary() {
        // Regression: CPU preparation used to require provider.isLibraryReady().
        val npu = FakeNpuRuntime(deviceSupported = false, libraryReady = false)
        val environment = factory(npu = { npu }).create(ExecutionTarget.CPU)

        assertEquals("cpu", environment.name)
        assertTrue("Qualcomm checks ran for CPU: " + npu.calls, npu.calls.isEmpty())
    }

    @Test fun cpuPathSucceedsWhenQualcommNativeLibrariesCannotLoad() {
        val environment = factory(npu = { throw UnsatisfiedLinkError("libLiteRtDispatch_Qualcomm.so not found") })
            .create(ExecutionTarget.CPU)

        assertEquals("cpu", environment.name)
        assertEquals(0, npuRuntimeConstructions)
    }

    @Test fun cpuEnvironmentFailureIsReportedAsCpuWithoutTouchingQualcomm() {
        val cause = IllegalStateException("native CPU init failed")
        val error = setupFailure { factory(cpu = { throw cause }).create(ExecutionTarget.CPU) }

        assertEquals(ExecutionTarget.CPU, error.target)
        assertEquals(Failure.ENVIRONMENT_FAILED, error.failure)
        assertSame(cause, error.cause)
        assertTrue(error.message!!.contains("native CPU init failed"))
        assertFalse(error.message!!.contains("Qualcomm"))
        assertEquals(0, npuRuntimeConstructions)
    }

    @Test fun cpuEnvironmentWithoutCpuAcceleratorIsClosedAndRejected() {
        val environment = FakeEnvironment("cpu", emptySet())
        val error = setupFailure { factory(cpu = { environment }).create(ExecutionTarget.CPU) }

        assertEquals(ExecutionTarget.CPU, error.target)
        assertEquals(Failure.ACCELERATOR_NOT_REGISTERED, error.failure)
        assertEquals(1, environment.closed)
        assertEquals(0, npuRuntimeConstructions)
    }

    // ---- Qualcomm NPU path: explicit checks, no fallback ----

    @Test fun npuPathRunsChecksInOrderAndReturnsNpuEnvironment() {
        val npu = FakeNpuRuntime()
        val environment = factory(npu = { npu }).create(ExecutionTarget.QUALCOMM_NPU)

        assertEquals("npu", environment.name)
        assertEquals(listOf("isDeviceSupported", "isLibraryReady", "createEnvironment"), npu.calls)
        assertEquals(0, cpuCreations)
        assertEquals(0, environment.closed)
    }

    @Test fun npuOnUnsupportedDeviceFailsWithoutFallingBackToCpu() {
        val npu = FakeNpuRuntime(deviceSupported = false)
        val error = setupFailure { factory(npu = { npu }).create(ExecutionTarget.QUALCOMM_NPU) }

        assertEquals(ExecutionTarget.QUALCOMM_NPU, error.target)
        assertEquals(Failure.DEVICE_UNSUPPORTED, error.failure)
        assertTrue(error.message!!.contains("supported Qualcomm NPU"))
        assertEquals(listOf("isDeviceSupported"), npu.calls)
        assertEquals(0, cpuCreations)
    }

    @Test fun npuWithoutDispatchLibraryFailsWithoutCreatingAnyEnvironment() {
        val npu = FakeNpuRuntime(libraryReady = false)
        val error = setupFailure { factory(npu = { npu }).create(ExecutionTarget.QUALCOMM_NPU) }

        assertEquals(Failure.LIBRARY_NOT_READY, error.failure)
        assertTrue(error.message!!.contains("dispatch runtime is not available"))
        assertEquals(listOf("isDeviceSupported", "isLibraryReady"), npu.calls)
        assertEquals(0, cpuCreations)
    }

    @Test fun npuProviderConstructionLinkFailureIsReportedAsProviderUnavailable() {
        val cause = UnsatisfiedLinkError("libQnnHtp.so not found")
        val error = setupFailure { factory(npu = { throw cause }).create(ExecutionTarget.QUALCOMM_NPU) }

        assertEquals(ExecutionTarget.QUALCOMM_NPU, error.target)
        assertEquals(Failure.PROVIDER_UNAVAILABLE, error.failure)
        assertSame(cause, error.cause)
        assertTrue(error.message!!.contains("libQnnHtp.so"))
        assertEquals(0, cpuCreations)
    }

    @Test fun npuDeviceCheckExceptionIsReportedAsDeviceFailure() {
        val npu = object : QualcommNpuRuntime<FakeEnvironment> {
            override fun isDeviceSupported(): Boolean = throw SecurityException("no SoC info")
            override fun isLibraryReady(): Boolean = throw AssertionError("must not be reached")
            override fun createEnvironment(): FakeEnvironment = throw AssertionError("must not be reached")
        }
        val error = setupFailure { factory(npu = { npu }).create(ExecutionTarget.QUALCOMM_NPU) }

        assertEquals(Failure.DEVICE_UNSUPPORTED, error.failure)
        assertTrue(error.cause is SecurityException)
    }

    @Test fun npuEnvironmentCreationFailureIsReportedAsNpu() {
        val npu = FakeNpuRuntime(environment = { throw IllegalStateException("QNN HTP context failed") })
        val error = setupFailure { factory(npu = { npu }).create(ExecutionTarget.QUALCOMM_NPU) }

        assertEquals(ExecutionTarget.QUALCOMM_NPU, error.target)
        assertEquals(Failure.ENVIRONMENT_FAILED, error.failure)
        assertTrue(error.message!!.contains("QNN HTP context failed"))
        assertEquals(0, cpuCreations)
    }

    @Test fun npuEnvironmentOfferingOnlyCpuIsClosedInsteadOfUsedAsCpu() {
        // An environment that silently registered only CPU must not be returned for
        // an NPU model; that would misreport the accelerator in use.
        val cpuOnly = FakeEnvironment("npu-without-npu", setOf(ExecutionTarget.CPU))
        val npu = FakeNpuRuntime(environment = { cpuOnly })
        val error = setupFailure { factory(npu = { npu }).create(ExecutionTarget.QUALCOMM_NPU) }

        assertEquals(Failure.ACCELERATOR_NOT_REGISTERED, error.failure)
        assertTrue(error.message!!.contains("Available: CPU"))
        assertEquals(1, cpuOnly.closed)
        assertEquals(0, cpuCreations)
    }

    @Test fun npuEnvironmentWhoseAcceleratorQueryFailsIsClosed() {
        val broken = FakeEnvironment("npu", emptySet(), availableError = IllegalStateException("query failed"))
        val error = setupFailure {
            factory(npu = { FakeNpuRuntime(environment = { broken }) }).create(ExecutionTarget.QUALCOMM_NPU)
        }

        assertEquals(Failure.ACCELERATOR_NOT_REGISTERED, error.failure)
        assertEquals(1, broken.closed)
    }

    @Test fun eachNpuSetupBuildsAFreshRuntime() {
        val factory = factory()
        factory.create(ExecutionTarget.QUALCOMM_NPU)
        factory.create(ExecutionTarget.QUALCOMM_NPU)
        factory.create(ExecutionTarget.CPU)

        assertEquals(2, npuRuntimeConstructions)
        assertEquals(1, cpuCreations)
    }
}
