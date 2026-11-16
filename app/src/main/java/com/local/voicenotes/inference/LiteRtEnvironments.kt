package com.local.voicenotes.inference

import android.content.Context
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.NpuCompatibilityChecker
import com.local.voicenotes.model.QualcommModels

/** Production adapter from LiteRT's [Environment] to [AcceleratorEnvironment]. */
internal class LiteRtEnvironment(val environment: Environment) : AcceleratorEnvironment {
    override fun availableTargets(): Set<ExecutionTarget> =
        environment.getAvailableAccelerators().mapNotNullTo(mutableSetOf()) { it.toExecutionTarget() }

    override fun close() = environment.close()
}

internal fun ExecutionTarget.toLiteRtAccelerator(): Accelerator = when (this) {
    ExecutionTarget.CPU -> Accelerator.CPU
    ExecutionTarget.QUALCOMM_NPU -> Accelerator.NPU
}

private fun Accelerator.toExecutionTarget(): ExecutionTarget? = when (this) {
    Accelerator.CPU -> ExecutionTarget.CPU
    // This app only constructs the Qualcomm provider, so a registered NPU is Qualcomm's.
    Accelerator.NPU -> ExecutionTarget.QUALCOMM_NPU
    else -> null
}

/** Built only for NPU models; constructing it is what touches the Qualcomm dispatch setup. */
internal class BuiltinQualcommNpuRuntime(context: Context) : QualcommNpuRuntime<LiteRtEnvironment> {
    // LiteRT 2.1.6's default allowlist starts at SM8550, although QNN ships
    // HTP v69 libraries. Only extend it for the two known Gen 1 chips.
    private val provider = BuiltinNpuAcceleratorProvider(context, object : NpuCompatibilityChecker {
        override fun isDeviceSupported(): Boolean =
            NpuCompatibilityChecker.Qualcomm.isDeviceSupported() ||
                QualcommModels.supportsLegacyDevice(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
    })

    override fun isDeviceSupported(): Boolean = provider.isDeviceSupported()
    override fun isLibraryReady(): Boolean = provider.isLibraryReady()
    override fun createEnvironment(): LiteRtEnvironment = LiteRtEnvironment(Environment.create(provider))
}

internal fun liteRtEnvironmentFactory(context: Context): AcceleratorEnvironmentFactory<LiteRtEnvironment> {
    val appContext = context.applicationContext ?: context
    return AcceleratorEnvironmentFactory(
        createCpuEnvironment = { LiteRtEnvironment(Environment.create()) },
        createQualcommNpuRuntime = { BuiltinQualcommNpuRuntime(appContext) }
    )
}
