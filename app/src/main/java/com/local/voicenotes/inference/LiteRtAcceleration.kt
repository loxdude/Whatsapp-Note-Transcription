package com.local.voicenotes.inference

import com.local.voicenotes.model.QualcommModels

/*
 * Accelerator selection and environment setup for the LiteRT Parakeet backend.
 *
 * This file intentionally has no Android or LiteRT imports: LiteRT's Environment
 * loads native code in its static initializer and the Qualcomm provider needs an
 * Android Context, so the selection/readiness logic lives here behind small
 * interfaces and the production adapter lives in LiteRtEnvironments.kt.
 */

/** The accelerator a prepared LiteRT session actually runs on. */
internal enum class ExecutionTarget(val label: String) {
    /** Portable LiteRT CPU path; must never require Qualcomm components. */
    CPU("CPU"),

    /** Qualcomm HTP via the LiteRT Qualcomm dispatch library and QNN runtime. */
    QUALCOMM_NPU("Qualcomm NPU");

    companion object {
        const val CPU_BACKEND = "litert-cpu"
        /** Parakeet Ultra with an i8 encoder: CPU (XNNPACK) only. */
        const val ULTRA_CPU_BACKEND = "litert-ultra-cpu"
        private const val QUALCOMM_NPU_BACKEND_PREFIX = "litert-qnn"

        /** Returns null for backends that are not local LiteRT (e.g. "mistral-api"). */
        fun forBackendOrNull(backend: String): ExecutionTarget? = when {
            backend == CPU_BACKEND || backend == ULTRA_CPU_BACKEND -> CPU
            backend.startsWith(QUALCOMM_NPU_BACKEND_PREFIX) -> QUALCOMM_NPU
            else -> null
        }

        /**
         * Unknown backends are rejected rather than defaulting to CPU, so an
         * AOT-compiled NPU export can never be silently routed to another accelerator.
         */
        fun forBackend(backend: String): ExecutionTarget =
            requireNotNull(forBackendOrNull(backend)) {
                "This model is not configured for local LiteRT inference."
            }
    }
}

/** A created LiteRT environment, reduced to what accelerator setup needs to verify. */
internal interface AcceleratorEnvironment : AutoCloseable {
    fun availableTargets(): Set<ExecutionTarget>
}

/** Qualcomm NPU provider seam; only ever constructed for [ExecutionTarget.QUALCOMM_NPU]. */
internal interface QualcommNpuRuntime<E : AcceleratorEnvironment> {
    fun isDeviceSupported(): Boolean
    fun isLibraryReady(): Boolean
    fun createEnvironment(): E
}

internal class AcceleratorSetupException(
    val target: ExecutionTarget,
    val failure: Failure,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause) {
    enum class Failure {
        PROVIDER_UNAVAILABLE,
        DEVICE_UNSUPPORTED,
        LIBRARY_NOT_READY,
        ENVIRONMENT_FAILED,
        ACCELERATOR_NOT_REGISTERED
    }
}

/**
 * Creates a verified environment for exactly the requested target.
 *
 * CPU: never constructs or queries the Qualcomm runtime.
 * Qualcomm NPU: checks provider construction, device support, dispatch library
 * readiness and accelerator registration, in that order, and throws on the first
 * failure. It never falls back to CPU; the caller has to choose a CPU model explicitly.
 */
internal class AcceleratorEnvironmentFactory<E : AcceleratorEnvironment>(
    private val createCpuEnvironment: () -> E,
    private val createQualcommNpuRuntime: () -> QualcommNpuRuntime<E>
) {
    fun create(target: ExecutionTarget): E = when (target) {
        ExecutionTarget.CPU -> createCpu()
        ExecutionTarget.QUALCOMM_NPU -> createQualcommNpu()
    }

    private fun createCpu(): E {
        val environment = step(ExecutionTarget.CPU, AcceleratorSetupException.Failure.ENVIRONMENT_FAILED,
            "LiteRT could not create the CPU environment.") { createCpuEnvironment() }
        return verified(environment, ExecutionTarget.CPU, "LiteRT did not register the CPU accelerator.")
    }

    private fun createQualcommNpu(): E {
        val target = ExecutionTarget.QUALCOMM_NPU
        val runtime = step(target, AcceleratorSetupException.Failure.PROVIDER_UNAVAILABLE,
            "The LiteRT Qualcomm NPU provider could not be created.") { createQualcommNpuRuntime() }
        val supported = step(target, AcceleratorSetupException.Failure.DEVICE_UNSUPPORTED,
            "The Qualcomm NPU device check failed.") { runtime.isDeviceSupported() }
        if (!supported) throw AcceleratorSetupException(target, AcceleratorSetupException.Failure.DEVICE_UNSUPPORTED,
            "LiteRT does not recognize this device as a supported Qualcomm NPU.")
        val ready = step(target, AcceleratorSetupException.Failure.LIBRARY_NOT_READY,
            "The Qualcomm dispatch runtime check failed.") { runtime.isLibraryReady() }
        if (!ready) throw AcceleratorSetupException(target, AcceleratorSetupException.Failure.LIBRARY_NOT_READY,
            "The LiteRT Qualcomm dispatch runtime is not available.")
        val environment = step(target, AcceleratorSetupException.Failure.ENVIRONMENT_FAILED,
            "LiteRT could not create the Qualcomm NPU environment.") { runtime.createEnvironment() }
        return verified(environment, target, "LiteRT could not register the Qualcomm NPU accelerator.")
    }

    private fun verified(environment: E, target: ExecutionTarget, message: String): E {
        val available = try {
            environment.availableTargets()
        } catch (error: Throwable) {
            runCatching { environment.close() }
            if (error !is Exception && error !is LinkageError) throw error
            throw AcceleratorSetupException(target, AcceleratorSetupException.Failure.ACCELERATOR_NOT_REGISTERED,
                withReason(message, error), error)
        }
        if (target !in available) {
            runCatching { environment.close() }
            throw AcceleratorSetupException(target, AcceleratorSetupException.Failure.ACCELERATOR_NOT_REGISTERED,
                "$message Available: ${available.joinToString { it.label }.ifEmpty { "none" }}.")
        }
        return environment
    }

    /** Wraps native-loading failures (e.g. UnsatisfiedLinkError) with target-specific context. */
    private inline fun <T> step(
        target: ExecutionTarget,
        failure: AcceleratorSetupException.Failure,
        message: String,
        block: () -> T
    ): T = try {
        block()
    } catch (error: AcceleratorSetupException) {
        throw error
    } catch (error: Exception) {
        throw AcceleratorSetupException(target, failure, withReason(message, error), error)
    } catch (error: LinkageError) {
        throw AcceleratorSetupException(target, failure, withReason(message, error), error)
    }
}

private fun withReason(message: String, error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() }?.let { "$message $it" } ?: message

internal object PreparationDiagnostics {
    private const val PORTABLE_HINT =
        "To run on CPU instead, import a portable parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite model"

    /** User-facing error that names the accelerator actually attempted. */
    fun message(
        target: ExecutionTarget,
        modelName: String,
        socManufacturer: String,
        socModel: String,
        error: Throwable
    ): String {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return when (target) {
            ExecutionTarget.CPU -> "CPU preparation failed for $modelName. $reason"
            ExecutionTarget.QUALCOMM_NPU -> {
                val note = QualcommModels.note(modelName, socModel)
                val hint = if ("portable" in note) "" else " $PORTABLE_HINT."
                "Qualcomm NPU preparation failed on $socManufacturer $socModel. $note. $reason$hint"
            }
        }
    }
}
