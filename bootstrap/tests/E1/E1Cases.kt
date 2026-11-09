/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package nativekotlin.e1.test

import org.jetbrains.kotlin.cli.bc.K2Native
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.utilities.daemonMain
import org.jetbrains.kotlin.cli.utilities.generatePlatformLibraries
import org.jetbrains.kotlin.cli.utilities.invokeInterop
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.konan.file.*

private fun Array<String>?.toNonNullList(): List<String> = this?.asList().orEmpty()

private fun String?.toNonNullList(): List<String> =
    this?.split(":", ",")?.filterNot(String::isEmpty).orEmpty()

private class RecordingMessageCollector : MessageCollector {
    val entries = mutableListOf<String>()
    private var hasErr = false

    override fun clear() {
        entries.clear()
        hasErr = false
    }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity.isError) {
            hasErr = true
        }
        val locPrefix = if (location != null) {
            val baseName = location.path.substringAfterLast('/')
            "$baseName:${location.line}:${location.column}: "
        } else {
            ""
        }
        val firstLine = message.lineSequence().firstOrNull()?.trim() ?: ""
        entries += "${severity.presentableName}: $locPrefix$firstLine"
    }

    override fun hasErrors(): Boolean = hasErr
}

private fun normalizeCliOutput(raw: String, fixturesDir: String, workDir: String): List<String> =
    raw.replace("\r\n", "\n")
        .replace(fixturesDir, "<FIXTURES>")
        .replace(workDir, "<WORK>")
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() }
        .toList()

private fun summarizeInteropKlib(klibDir: File): List<String> {
    val defaultDir = klibDir.child("default")
    val manifestFile = defaultDir.child("manifest")
    val linkdataModule = defaultDir.child("linkdata").child("module")
    val keepKeys = setOf("unique_name", "interop", "package", "depends", "ir_provider", "native_targets")
    val manifestProps = manifestFile.readBytes().decodeToString()
        .lineSequence()
        .map { it.trim() }
        .filter { line -> keepKeys.any { k -> line.startsWith("$k=") } }
        .sorted()
        .toList()
    return manifestProps + listOf("hasLinkdataModule=${linkdataModule.exists}")
}

fun runE1Suite(
    fixturesDir: String,
    cLibDir: String,
    badBitcodePath: String,
    workDir: String,
    distDir: String,
    konanDataDir: String,
    llvmDir: String,
    execCli: (Array<String>) -> Pair<ExitCode, String>,
    dumpKlibAbi: (String) -> String,
    plainFullRenderer: MessageRenderer,
    withoutPathsRenderer: MessageRenderer,
    gradleRenderer: MessageRenderer,
    xcodeRenderer: MessageRenderer,
    xmlRenderer: MessageRenderer,
) {
    val workRoot = File(workDir).also {
        it.mkdirs()
    }

    println("=== SECTION 1: K2NativeCompilerArguments Parse, @argfile & copyOf ===")
    val argFile = workRoot.child("cli-args.txt")
    argFile.writeText(
        """
        -module-name "e1_arg_mod"
        -opt-in=kotlin.RequiresOptIn
        -Xbinary=memoryModel=experimental
        srcFromArgFile.kt
        """.trimIndent()
    )
    val rawArgs = listOf(
        "-target", "linux_x64",
        "-p", "library",
        "-o", "/tmp/e1-out.klib",
        "-l", "lib1.klib",
        "-l", "lib2.klib",
        "-friend-modules", "friend1.klib",
        "-g",
        "-ea",
        "-opt",
        "-no-default-libs",
        "-Xpartial-linkage-loglevel=error",
        "-XXLanguage:+NameBasedDestructuring",
        "-Xcontext-parameters",
        "@${argFile.absolutePath}",
        "srcDirect.kt",
    )
    val parsed = K2NativeCompilerArguments()
    parseCommandLineArguments(rawArgs, parsed)
    val copied = parsed.copyOf() as K2NativeCompilerArguments

    // Mutate original to prove deep copy isolation
    parsed.target = "macos_arm64"
    parsed.optIn!![0] = "mutated.OptIn"
    parsed.freeArgs = mutableListOf("mutated.kt")

    println("copied.target=${copied.target}")
    println("copied.produce=${copied.produce}")
    println("copied.outputName=${copied.outputName}")
    println("copied.libraries=${copied.libraries.toNonNullList()}")
    println("copied.friendModules=${copied.friendModules}")
    println("copied.debug=${copied.debug}")
    println("copied.enableAssertions=${copied.enableAssertions}")
    println("copied.optimization=${copied.optimization}")
    println("copied.nodefaultlibs=${copied.nodefaultlibs}")
    println("copied.partialLinkageLogLevel=${copied.partialLinkageLogLevel}")
    println("copied.contextParameters=${copied.contextParameters}")
    println("copied.moduleName=${copied.moduleName}")
    println("copied.optIn=${copied.optIn?.toList()}")
    println("copied.binaryOptions=${copied.binaryOptions?.toList()}")
    println("copied.internalArguments=${copied.internalArguments.map { it.stringRepresentation }}")
    println("copied.freeArgs=${copied.freeArgs}")

    val preprocessed = preprocessCommandLineArguments(
        listOf("-g", "@${argFile.absolutePath}", "-ea"),
        lazy { ArgumentParseErrors() },
    )
    println("preprocessed=$preprocessed")

    println("=== SECTION 2: K2Native Argument Validation Diagnostics ===")
    val validationCases = listOf(
        "unknown-flag" to arrayOf("-unknown-flag-xyz", "$fixturesDir/BaseLibA.kt"),
        "missing-value" to arrayOf("-output"),
        "bad-boolean" to arrayOf("-g=notabool", "$fixturesDir/BaseLibA.kt"),
        "bad-lang-feature" to arrayOf("-no-default-libs", "-XXLanguage:+NoSuchFeatureEver", "$fixturesDir/BaseLibA.kt"),
        "bool-lang-with-value" to arrayOf("-no-default-libs", "-XXLanguage:+InlineClasses=unexpected", "$fixturesDir/BaseLibA.kt"),
        "unknown-extra-flag-help" to arrayOf("-Xpartial-linkage-logleve=error", "-help"),
        "no-compilation-args" to arrayOf("-target", "linux_x64"),
    )
    for ((caseName, cliArgs) in validationCases) {
        val (code, rawOut) = execCli(cliArgs)
        val lines = normalizeCliOutput(rawOut, fixturesDir, workDir)
        val summaryLines = if (caseName == "unknown-extra-flag-help") {
            lines.filter {
                it.startsWith("warning:") ||
                    it.startsWith("Usage:") ||
                    it.contains("-output") ||
                    it.contains("-target")
            }
        } else {
            lines
        }
        println("case[$caseName].exitCode=$code")
        for (line in summaryLines) {
            println("case[$caseName].out: $line")
        }
    }

    println("=== SECTION 3: MessageRenderer Differential Suite ===")
    val renderers = listOf(
        "PLAIN_FULL" to plainFullRenderer,
        "WITHOUT_PATHS" to withoutPathsRenderer,
        "GRADLE" to gradleRenderer,
        "XCODE" to xcodeRenderer,
        "XML" to xmlRenderer,
    )
    val locWithLine = CompilerMessageLocation.create("/virtual/dir/Sample.kt", 14, 9, "    val bad = unknownCall()")
    val severities = listOf(
        CompilerMessageSeverity.ERROR,
        CompilerMessageSeverity.STRONG_WARNING,
        CompilerMessageSeverity.WARNING,
        CompilerMessageSeverity.INFO,
        CompilerMessageSeverity.LOGGING,
        CompilerMessageSeverity.OUTPUT,
    )
    for ((rName, renderer) in renderers) {
        println("renderer[$rName].name=${renderer.getName()}")
        val preamble = renderer.renderPreamble()
        if (preamble.isNotEmpty()) {
            println("renderer[$rName].preamble=$preamble")
        }
        for (sev in severities) {
            val withLoc = renderer.render(sev, "Sample diagnostic message", locWithLine).trimEnd()
            val noLoc = renderer.render(sev, "Global message <escaped & quoted>", null).trimEnd()
            println("renderer[$rName][$sev].withLoc=$withLoc")
            println("renderer[$rName][$sev].noLoc=$noLoc")
        }
        val conclusion = renderer.renderConclusion()
        if (conclusion.isNotEmpty()) {
            println("renderer[$rName].conclusion=$conclusion")
        }
    }

    println("=== SECTION 4: Stage 1 .kt -> .klib via K2Native CLI ===")
    val baseKlibPath = workRoot.child("e1_base.klib").absolutePath
    val appKlibPath = workRoot.child("e1_app.klib").absolutePath
    val commonKlibFlags = arrayOf(
        "-target", "linux_x64",
        "-produce", "library",
        "-no-default-libs",
        "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvmDir",
        "-Xkonan-data-dir=$konanDataDir",
        "-kotlin-home", distDir,
        "-ea",
        "-Xpartial-linkage-loglevel=error",
    )
    val (baseCode, baseOut) = execCli(
        commonKlibFlags + arrayOf(
            "-module-name", "e1_base",
            "-Xshort-module-name=e1_base",
            "-o", baseKlibPath,
            "$fixturesDir/BaseLibA.kt",
            "$fixturesDir/BaseLibB.kt",
        )
    )
    println("stage1.base.exitCode=$baseCode")
    for (line in normalizeCliOutput(baseOut, fixturesDir, workDir)) {
        println("stage1.base.out: $line")
    }
    check(baseCode == ExitCode.OK && File(baseKlibPath).exists) {
        "Failed to build e1_base.klib: exit=$baseCode out=$baseOut"
    }
    println("stage1.base.abi:")
    println(dumpKlibAbi(baseKlibPath))

    val (appCode, appOut) = execCli(
        commonKlibFlags + arrayOf(
            "-module-name", "e1_app",
            "-Xshort-module-name=e1_app",
            "-library", baseKlibPath,
            "-friend-modules", baseKlibPath,
            "-o", appKlibPath,
            "$fixturesDir/AppMain.kt",
        )
    )
    println("stage1.app.exitCode=$appCode")
    for (line in normalizeCliOutput(appOut, fixturesDir, workDir)) {
        println("stage1.app.out: $line")
    }
    check(appCode == ExitCode.OK && File(appKlibPath).exists) {
        "Failed to build e1_app.klib: exit=$appCode out=$appOut"
    }
    println("stage1.app.abi:")
    println(dumpKlibAbi(appKlibPath))

    println("=== SECTION 5: Frontend & Backend Error Diagnostics via K2Native CLI ===")
    val brokenKlibPath = workRoot.child("broken.klib").absolutePath
    val (brokenCode, brokenOut) = execCli(
        commonKlibFlags + arrayOf(
            "-Xrender-internal-diagnostic-names",
            "-o", brokenKlibPath,
            "$fixturesDir/BrokenSource.kt",
        )
    )
    println("frontend.broken.exitCode=$brokenCode")
    for (line in normalizeCliOutput(brokenOut, fixturesDir, workDir)) {
        println("frontend.broken.out: $line")
    }
    check(brokenCode == ExitCode.COMPILATION_ERROR) {
        "Expected COMPILATION_ERROR for BrokenSource.kt, got $brokenCode"
    }

    val badCollector = RecordingMessageCollector()
    val badArgs = K2NativeCompilerArguments()
    parseCommandLineArguments(
        listOf(
            "-target", "linux_x64",
            "-produce", "program",
            "-no-default-libs",
            "-kotlin-home", distDir,
            "-Xkonan-data-dir=$konanDataDir",
            "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvmDir",
            "-Xcompile-from-bitcode=$badBitcodePath",
            "-o", workRoot.child("bad-bitcode.kexe").absolutePath,
        ),
        badArgs,
    )
    val badBitcodeExit = K2Native().exec(badCollector, Services.EMPTY, badArgs)
    println("backend.badBitcode.exitCode=$badBitcodeExit")
    println("backend.badBitcode.hasErrors=${badCollector.hasErrors()}")
    check(badBitcodeExit == ExitCode.INTERNAL_ERROR) {
        "Expected INTERNAL_ERROR for bad.bc, got $badBitcodeExit"
    }

    println("=== SECTION 6: Two-Stage (.klib -> .kexe) & One-Stage (.kt -> .kexe) via K2Native CLI ===")
    val sysCacheDir = "$distDir/klib/cache/linux_x64-gSTATIC-system"
    val cacheFlags = if (File(sysCacheDir).exists) {
        arrayOf("-g", "-Xcache-directory=$sysCacheDir")
    } else {
        emptyArray()
    }
    val twoStageExe = workRoot.child("two-stage-app.kexe").absolutePath
    val (twoStageCode, twoStageOut) = execCli(
        arrayOf(
            "-target", "linux_x64",
            "-produce", "program",
            "-no-default-libs",
            "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvmDir",
            "-Xkonan-data-dir=$konanDataDir",
            "-kotlin-home", distDir,
            "-ea",
            "-Xpartial-linkage-loglevel=error",
            "-library", baseKlibPath,
            "-Xinclude=$appKlibPath",
            "-entry", "e1.fixture.app.main",
            "-o", twoStageExe,
        ) + cacheFlags
    )
    println("stage2.twoStage.exitCode=$twoStageCode")
    for (line in normalizeCliOutput(twoStageOut, fixturesDir, workDir)) {
        println("stage2.twoStage.out: $line")
    }
    check(twoStageCode == ExitCode.OK && File(twoStageExe).exists) {
        "Failed two-stage compilation: exit=$twoStageCode out=$twoStageOut"
    }

    val oneStageTmp = workRoot.child("one-stage-tmp").also { it.mkdirs() }
    val oneStageExe = workRoot.child("one-stage-app.kexe").absolutePath
    val (oneStageCode, oneStageOut) = execCli(
        arrayOf(
            "-target", "linux_x64",
            "-produce", "program",
            "-no-default-libs",
            "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvmDir",
            "-Xkonan-data-dir=$konanDataDir",
            "-kotlin-home", distDir,
            "-ea",
            "-Xpartial-linkage-loglevel=error",
            "-Xtemporary-files-dir=${oneStageTmp.absolutePath}",
            "-entry", "e1.fixture.app.main",
            "-o", oneStageExe,
            "$fixturesDir/BaseLibA.kt",
            "$fixturesDir/BaseLibB.kt",
            "$fixturesDir/AppMain.kt",
        ) + cacheFlags
    )
    println("oneStage.exitCode=$oneStageCode")
    for (line in normalizeCliOutput(oneStageOut, fixturesDir, workDir)) {
        println("oneStage.out: $line")
    }
    check(oneStageCode == ExitCode.OK && File(oneStageExe).exists) {
        "Failed one-stage compilation: exit=$oneStageCode out=$oneStageOut"
    }

    println("=== SECTION 7: Unified cli-runner Utilities (daemonMain, cinterop, defFileDependencies, generatePlatformLibraries) ===")
    val daemonKlibPath = workRoot.child("daemon_base.klib").absolutePath
    daemonMain(
        arrayOf(
            "konanc",
            "-target", "linux_x64",
            "-produce", "library",
            "-no-default-libs",
            "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvmDir",
            "-Xkonan-data-dir=$konanDataDir",
            "-kotlin-home", distDir,
            "-module-name", "e1_base",
            "-Xshort-module-name=e1_base",
            "-o", daemonKlibPath,
            "$fixturesDir/BaseLibA.kt",
            "$fixturesDir/BaseLibB.kt",
        )
    )
    println("daemonMain.konanc.exists=${File(daemonKlibPath).exists}")
    println("daemonMain.konanc.abiMatches=${dumpKlibAbi(daemonKlibPath) == dumpKlibAbi(baseKlibPath)}")

    val cinteropKlibDir = workRoot.child("e1_c_interop")
    val cinteropTmpDir = workRoot.child("cinterop-tmp").also { it.mkdirs() }
    val cinteropArgs = arrayOf(
        "-target", "linux_x64",
        "-def", "$fixturesDir/e1_c_sample.def",
        "-compiler-option", "-I$fixturesDir",
        "-libraryPath", cLibDir,
        "-no-default-libs",
        "-nopack",
        "-Xtemporary-files-dir", cinteropTmpDir.absolutePath,
        "-Xoverride-konan-properties", "airplaneMode=true;llvmHome.linux_x64=$llvmDir",
        "-Xkonan-data-dir", konanDataDir,
        "-o", cinteropKlibDir.absolutePath,
    )
    val konancInteropArgs = invokeInterop("native", cinteropArgs, runFromDaemon = false)
    konancInteropArgs?.let { K2Native.mainNoExit(it) }
    println("cliRunner.cinterop.exists=${cinteropKlibDir.exists}")
    println("cliRunner.cinterop.manifest=${summarizeInteropKlib(cinteropKlibDir)}")

    val miniDefDir = workRoot.child("mini-platform-def").also { it.mkdirs() }
    val miniOutDir = workRoot.child("mini-platform-out").also { it.mkdirs() }
    miniDefDir.child("mini_base.h").writeText(
        """
        #define MINI_BASE_CONST 42
        static inline int mini_base_double(int x) { return x * 2; }
        """.trimIndent() + "\n"
    )
    miniDefDir.child("mini_ext.h").writeText(
        """
        #include "mini_base.h"
        #define MINI_EXT_OFFSET 7
        static inline int mini_ext_calc(int x) { return x + MINI_EXT_OFFSET; }
        """.trimIndent() + "\n"
    )
    val miniBaseDef = miniDefDir.child("mini_base.def")
    val miniExtDef = miniDefDir.child("mini_ext.def")
    miniBaseDef.writeText(
        """
        package = platform.mini_base
        headers = mini_base.h
        compilerOpts = -I${miniDefDir.absolutePath}
        """.trimIndent() + "\n"
    )
    miniExtDef.writeText(
        """
        package = platform.mini_ext
        headers = mini_ext.h
        headerFilter = mini_ext.h
        compilerOpts = -I${miniDefDir.absolutePath}
        """.trimIndent() + "\n"
    )

    daemonMain(
        arrayOf(
            "defFileDependencies",
            "-target", "linux_x64",
            miniBaseDef.absolutePath,
            miniExtDef.absolutePath,
        )
    )
    val baseDependsLine = miniBaseDef.readBytes().decodeToString().lines().first { it.startsWith("depends =") }
    val extDependsLine = miniExtDef.readBytes().decodeToString().lines().first { it.startsWith("depends =") }
    println("cliRunner.defFileDependencies.base=$baseDependsLine")
    println("cliRunner.defFileDependencies.ext=$extDependsLine")

    generatePlatformLibraries(
        arrayOf(
            "-i", miniDefDir.absolutePath,
            "-o", miniOutDir.absolutePath,
            "-t", "linux_x64",
            "-S", "$distDir/klib/common/stdlib",
            "-Xkonan-data-dir", konanDataDir,
            "-Xoverride-konan-properties", "airplaneMode=true;llvmHome.linux_x64=$llvmDir",
        )
    )
    val genBase = miniOutDir.child("org.jetbrains.kotlin.native.platform.mini_base")
    val genExt = miniOutDir.child("org.jetbrains.kotlin.native.platform.mini_ext")
    println("cliRunner.generatePlatformLibraries.mini_base.exists=${genBase.exists}")
    println("cliRunner.generatePlatformLibraries.mini_ext.exists=${genExt.exists}")
    println("cliRunner.generatePlatformLibraries.mini_base.manifest=${summarizeInteropKlib(genBase)}")
    println("cliRunner.generatePlatformLibraries.mini_ext.manifest=${summarizeInteropKlib(genExt)}")

    val interopConsumerKt = workRoot.child("InteropConsumer.kt")
    interopConsumerKt.writeText(
        """
        @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        package e1.fixture.interop

        import e1.cinterop.sample.*
        import platform.mini_base.*
        import platform.mini_ext.*

        fun callAllInterop(x: Int): Int =
            e1_add_scaled(x, MINI_BASE_CONST, MINI_EXT_OFFSET) + mini_base_double(x) + mini_ext_calc(x)
        """.trimIndent()
    )
    val interopConsumerKlib = workRoot.child("e1_interop_consumer.klib").absolutePath
    val (icCode, icOut) = execCli(
        commonKlibFlags + arrayOf(
            "-module-name", "e1_interop_consumer",
            "-Xshort-module-name=e1_interop_consumer",
            "-library", cinteropKlibDir.absolutePath,
            "-library", genBase.absolutePath,
            "-library", genExt.absolutePath,
            "-o", interopConsumerKlib,
            interopConsumerKt.absolutePath,
        )
    )
    println("cliRunner.interopConsumer.exitCode=$icCode")
    check(icCode == ExitCode.OK && File(interopConsumerKlib).exists) {
        "Failed to compile InteropConsumer.kt: exit=$icCode out=$icOut"
    }
    println("cliRunner.interopConsumer.abi:")
    println(dumpKlibAbi(interopConsumerKlib))

    println("E1_CLI_SUITE_OK")
}
