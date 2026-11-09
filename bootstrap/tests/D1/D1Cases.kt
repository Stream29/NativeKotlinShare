package org.jetbrains.kotlin.native.d1.verify

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine
import org.jetbrains.kotlin.native.d1.core.D1DomainException
import org.jetbrains.kotlin.native.d1.core.D1Envelope
import org.jetbrains.kotlin.native.d1.core.D1IntProcessor
import org.jetbrains.kotlin.native.d1.core.D1Processor
import org.jetbrains.kotlin.native.d1.core.D1Tag
import org.jetbrains.kotlin.native.d1.core.D1ValidationException
import org.jetbrains.kotlin.native.d1.core.d1GuardInline

interface D1PlatformHooks {
    fun readTextFile(path: String): String
    fun fileExistsAndNonEmpty(path: String): Boolean
    fun listFilesSorted(dirPath: String): List<String>
    fun runPosixAndBuiltinInterop(): List<String>
    fun runZlibInterop(): List<String>
    fun runCustomCBridgeInterop(): List<String>
    fun runMemoryAndWeakRefSection(): List<String>
    fun runConcurrencyAndAtomicsSection(): List<String>
}

private val EXPECTED_RUNTIME_BC_MODULES = listOf(
    "adaptive_gcScheduler.bc",
    "aggressive_gcScheduler.bc",
    "common_alloc.bc",
    "common_gc.bc",
    "common_gcScheduler.bc",
    "compiler_interface.bc",
    "concurrent_ms_gc.bc",
    "custom_alloc.bc",
    "debug.bc",
    "exceptionsSupport.bc",
    "impl_externalCallsChecker.bc",
    "launcher.bc",
    "legacy_alloc.bc",
    "libbacktrace.bc",
    "manual_gcScheduler.bc",
    "mm.bc",
    "noop_crashHandler.bc",
    "noop_externalCallsChecker.bc",
    "noop_gc.bc",
    "objc.bc",
    "pmcs_gc.bc",
    "runtime.bc",
    "same_thread_ms_gc.bc",
    "source_info_libbacktrace.bc",
    "std_alloc.bc",
)

private val EXPECTED_PLATFORM_LIBS = listOf(
    "builtin",
    "iconv",
    "linux",
    "posix",
    "zlib",
)

private fun parsePropertiesMap(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val idx = line.indexOf('=')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        result[key] = value
    }
    return result
}

fun runAllD1Cases(
    rebuiltDistPath: String,
    abiDumpPath: String,
    hooks: D1PlatformHooks,
) {
    println("=== SECTION 1: REBUILT DISTRIBUTION AUDIT ===")
    verifyRebuiltDistribution(rebuiltDistPath, hooks)

    println("=== SECTION 2: CROSS-MODULE KLIB & ABI DUMP ===")
    verifyCrossModuleAbi(abiDumpPath, hooks)

    println("=== SECTION 3: CINTEROP & REBUILT PLATFORM LIBRARIES ===")
    for (line in hooks.runPosixAndBuiltinInterop()) {
        println(line)
    }
    for (line in hooks.runZlibInterop()) {
        println(line)
    }
    for (line in hooks.runCustomCBridgeInterop()) {
        println(line)
    }

    println("=== SECTION 4: EXCEPTIONS & STACK UNWINDING ===")
    verifyExceptionsAndUnwinding()

    println("=== SECTION 5: MEMORY MANAGEMENT, WEAKREF & GC ===")
    for (line in hooks.runMemoryAndWeakRefSection()) {
        println(line)
    }

    println("=== SECTION 6: CONCURRENCY, ATOMICS & WORKERS ===")
    for (line in hooks.runConcurrencyAndAtomicsSection()) {
        println(line)
    }

    println("=== SECTION 7: CROSS-MODULE GENERICS, VALUE CLASSES & COROUTINES ===")
    verifyGenericsValueClassesAndCoroutines()
}

private fun verifyRebuiltDistribution(rebuiltDistPath: String, hooks: D1PlatformHooks) {
    val nativeRtDir = "$rebuiltDistPath/konan/targets/linux_x64/native"
    val presentBc = hooks.listFilesSorted(nativeRtDir).filter { it.endsWith(".bc") }
    val allNonEmpty = EXPECTED_RUNTIME_BC_MODULES.all { hooks.fileExistsAndNonEmpty("$nativeRtDir/$it") }
    val rtFpExists = hooks.fileExistsAndNonEmpty("$rebuiltDistPath/konan/targets/linux_x64/runtime.fingerprint")
    val compFpExists = hooks.fileExistsAndNonEmpty("$rebuiltDistPath/konan/compiler.fingerprint")
    println("d1.dist.runtime.bc_count=${presentBc.size}")
    println("d1.dist.runtime.matches_expected=${presentBc == EXPECTED_RUNTIME_BC_MODULES}")
    println("d1.dist.runtime.all_non_empty=$allNonEmpty")
    println("d1.dist.fingerprints.present=${rtFpExists && compFpExists}")

    val stdlibDir = "$rebuiltDistPath/klib/common/stdlib/default"
    val stdlibManifest = parsePropertiesMap(hooks.readTextFile("$stdlibDir/manifest"))
    val stdlibIrOk = hooks.fileExistsAndNonEmpty("$stdlibDir/ir/bodies.knb") &&
        hooks.fileExistsAndNonEmpty("$stdlibDir/ir/irDeclarations.knd") &&
        hooks.fileExistsAndNonEmpty("$stdlibDir/ir_inlinable_functions/bodies.knb") &&
        hooks.fileExistsAndNonEmpty("$stdlibDir/linkdata/module")
    println("d1.dist.stdlib.unique_name=${stdlibManifest["unique_name"]}")
    println("d1.dist.stdlib.builtins_platform=${stdlibManifest["builtins_platform"]}")
    println("d1.dist.stdlib.abi_version=${stdlibManifest["abi_version"]}")
    println("d1.dist.stdlib.ir_signature_versions=${stdlibManifest["ir_signature_versions"]}")
    println("d1.dist.stdlib.ir_and_linkdata_ok=$stdlibIrOk")
    println("d1.dist.stdlib.kotlin_version=${KotlinVersion.CURRENT.major}.${KotlinVersion.CURRENT.minor},at_least_2_4=${KotlinVersion.CURRENT.isAtLeast(2, 4, 20)}")

    val cacheDir = "$rebuiltDistPath/klib/cache/linux_x64-gSTATIC-system/stdlib-cache"
    val cacheMeta = parsePropertiesMap(hooks.readTextFile("$cacheDir/metadata.properties"))
    val cacheArchiveOk = hooks.fileExistsAndNonEmpty("$cacheDir/bin/libstdlib-cache.a")
    val cacheBitcodeDepsOk = hooks.fileExistsAndNonEmpty("$cacheDir/bin/bitcode_deps")
    val cacheIrFilesOk = hooks.fileExistsAndNonEmpty("$cacheDir/ir/inline_bodies") &&
        hooks.fileExistsAndNonEmpty("$cacheDir/ir/class_fields") &&
        hooks.fileExistsAndNonEmpty("$cacheDir/ir/eager_init") &&
        hooks.fileExistsAndNonEmpty("$cacheDir/ir/trivial_getters")
    println("d1.dist.stdlib_cache.archive_ok=$cacheArchiveOk")
    println("d1.dist.stdlib_cache.bitcode_deps_ok=$cacheBitcodeDepsOk")
    println("d1.dist.stdlib_cache.ir_summary_ok=$cacheIrFilesOk")
    println("d1.dist.stdlib_cache.meta_target=${cacheMeta["target"]}")
    println("d1.dist.stdlib_cache.has_runtime_fp=${!cacheMeta["runtimeFingerprint"].isNullOrEmpty()}")

    val platformBase = "$rebuiltDistPath/klib/platform/linux_x64"
    for (lib in EXPECTED_PLATFORM_LIBS) {
        val klibDir = "$platformBase/org.jetbrains.kotlin.native.platform.$lib/default"
        val mf = parsePropertiesMap(hooks.readTextFile("$klibDir/manifest"))
        val linkdataOk = hooks.fileExistsAndNonEmpty("$klibDir/linkdata/module")
        val bitcodeOk = hooks.fileExistsAndNonEmpty("$klibDir/targets/linux_x64/native/cstubs.bc")
        println(
            "d1.dist.platform.$lib=" +
                "unique=${mf["unique_name"]}," +
                "pkg=${mf["package"]}," +
                "short=${mf["short_name"]}," +
                "interop=${mf["interop"]}," +
                "provider=${mf["ir_provider"]}," +
                "depends=${mf["depends"] ?: "none"}," +
                "linkdata=$linkdataOk," +
                "cstubs=$bitcodeOk"
        )
    }
}

private fun verifyCrossModuleAbi(abiDumpPath: String, hooks: D1PlatformHooks) {
    val abiText = hooks.readTextFile(abiDumpPath).replace("\r\n", "\n").trim()
    val lines = abiText.lines()
    println("d1.abi.line_count=${lines.size}")
    println("d1.abi.has_tag=${abiText.contains("final value class org.jetbrains.kotlin.native.d1.core/D1Tag")}")
    println("d1.abi.has_processor=${abiText.contains("abstract interface org.jetbrains.kotlin.native.d1.core/D1Processor")}")
    println("d1.abi.has_envelope=${abiText.contains("final class org.jetbrains.kotlin.native.d1.core/D1Envelope")}")
    println("d1.abi.has_domain_ex=${abiText.contains("open class org.jetbrains.kotlin.native.d1.core/D1DomainException")}")
    println("d1.abi.has_validation_ex=${abiText.contains("final class org.jetbrains.kotlin.native.d1.core/D1ValidationException")}")
    println("d1.abi.has_guard_inline=${abiText.contains("final inline fun <#A1: kotlin/Any?> org.jetbrains.kotlin.native.d1.core/d1GuardInline")}")
}

private fun verifyExceptionsAndUnwinding() {
    val trace = mutableListOf<String>()
    val processor: D1Processor<Int> = D1IntProcessor("scale", 6)

    val okResult = d1GuardInline("positive") {
        processor.process(7, D1Tag("  Alpha "))
    }
    trace += "ok=${okResult.getOrThrow()}"

    val failResult = d1GuardInline("negative") {
        processor.process(-3, D1Tag("Beta"))
    }
    val ex = failResult.exceptionOrNull() as? D1DomainException
    val cause = ex?.cause as? D1ValidationException
    trace += "err.code=${ex?.code},err.msg=${ex?.message},cause.field=${cause?.field},cause.code=${cause?.code}"

    var finallyCount = 0
    val nestedOutcome = try {
        try {
            finallyCount += 1
            throw D1DomainException(503, "inner-failure", IllegalStateException("root-cause"))
        } finally {
            finallyCount += 10
        }
    } catch (e: D1DomainException) {
        finallyCount += 100
        "caught:${e.code}:${e.message}:${e.cause?.message}"
    } finally {
        finallyCount += 1000
    }
    trace += "nested=$nestedOutcome,finally=$finallyCount"

    for (item in trace) {
        println("d1.exceptions.$item")
    }
}

private class D1ContinuationBox : Continuation<String> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var completedValue: String? = null
    var completedError: Throwable? = null
    var pendingResume: (() -> Unit)? = null

    override fun resumeWith(result: Result<String>) {
        completedValue = result.getOrNull()
        completedError = result.exceptionOrNull()
    }
}

private suspend fun d1SuspendStep(stepName: String, delta: Int, box: D1ContinuationBox): Int =
    suspendCoroutineUninterceptedOrReturn { cont ->
        box.pendingResume = {
            cont.resumeWith(Result.success(stepName.length + delta))
        }
        COROUTINE_SUSPENDED
    }

private fun verifyGenericsValueClassesAndCoroutines() {
    val env = D1Envelope(id = 19, tag = D1Tag("  Rebuilt-Stdlib  "), payload = listOf(2, 4, 6, 8))
    val mapped = env.mapPayload { nums -> nums.sum() * env.tag.normalized().length }
    println("d1.generics.envelope=id:${mapped.id},tag:${mapped.tag.normalized()},payload:${mapped.payload}")

    val seqSummary = generateSequence(1) { it + 2 }
        .take(8)
        .filter { it % 3 != 0 }
        .map { it * it }
        .toList()
    println("d1.stdlib.sequence=$seqSummary")

    val grouped = buildMap<String, Int> {
        for (i in 1..6) {
            val key = if (i % 2 == 0) "even" else "odd"
            put(key, (get(key) ?: 0) + i * 10)
        }
    }
    println("d1.stdlib.map=odd:${grouped["odd"]},even:${grouped["even"]}")

    val box = D1ContinuationBox()
    val block: suspend () -> String = {
        val a = d1SuspendStep("stageA", 10, box)
        val b = d1SuspendStep("stageBC", 20, box)
        "coroutine-ok:${a + b}"
    }
    block.startCoroutine(box)
    var steps = 0
    while (box.completedValue == null && box.completedError == null) {
        val resume = box.pendingResume ?: error("Coroutine suspended without resume callback")
        box.pendingResume = null
        steps += 1
        resume()
    }
    println("d1.coroutines.result=${box.completedValue},steps=$steps")
}
