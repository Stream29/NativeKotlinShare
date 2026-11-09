@file:OptIn(
    org.jetbrains.kotlin.fir.SessionConfiguration::class,
)

package nativekotlin.f3e

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.cli.common.createPerformanceManagerFor
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.builtInsPlatform
import org.jetbrains.kotlin.library.components.inlinableFunctionsIr
import org.jetbrains.kotlin.library.components.ir
import org.jetbrains.kotlin.library.components.metadata
import org.jetbrains.kotlin.library.isHeader
import org.jetbrains.kotlin.library.loader.KlibLoader
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.library.nativeTargets
import org.jetbrains.kotlin.library.shortName
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.library.unresolvedDependencies
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.native.pipeline.NativeKlibCliPipeline
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import org.jetbrains.kotlin.serialization.deserialization.getName

private data class CollectedMessage(
    val severity: CompilerMessageSeverity,
    val message: String,
    val path: String?,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
)

private class RecordingMessageCollector : MessageCollector {
    val messages = mutableListOf<CollectedMessage>()
    private var hasErr = false

    override fun clear() {
        messages.clear()
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
        val rangeLoc = location as? CompilerMessageLocationWithRange
        messages += CollectedMessage(
            severity = severity,
            message = message,
            path = location?.path,
            line = location?.line ?: -1,
            column = location?.column ?: -1,
            endLine = rangeLoc?.lineEnd ?: (location?.line ?: -1),
            endColumn = rangeLoc?.columnEnd ?: (location?.column ?: -1),
        )
    }

    override fun hasErrors(): Boolean = hasErr
}

private fun normalizeRelativePath(rawPath: String?, fixturesDir: String): String {
    if (rawPath == null) return "<no-path>"
    val normalizedRaw = rawPath.replace('\\', '/')
    val normalizedBase = fixturesDir.replace('\\', '/').trimEnd('/') + "/"
    return if (normalizedRaw.startsWith(normalizedBase)) {
        normalizedRaw.removePrefix(normalizedBase)
    } else {
        normalizedRaw.substringAfterLast('/')
    }
}

private fun executeNativeKlibPipeline(
    configure: K2NativeCompilerArguments.() -> Unit,
): Pair<ExitCode, List<CollectedMessage>> {
    val args = K2NativeCompilerArguments().apply {
        nodefaultlibs = true
        noInline = false
        renderInternalDiagnosticNames = true
        partialLinkageLogLevel = "error"
        configure()
    }
    val collector = RecordingMessageCollector()
    val perfManager = createPerformanceManagerFor(NativePlatforms.unspecifiedNativePlatform)
    val pipeline = NativeKlibCliPipeline(perfManager, isNativeOneStage = false)
    val exitCode = pipeline.execute(args, Services.EMPTY, collector)
    return exitCode to collector.messages.toList()
}

private fun summarizeKlibMetadataAndIr(label: String, klibPath: String): List<String> {
    val out = mutableListOf<String>()
    val loadResult = KlibLoader {
        libraryPaths(klibPath)
    }.load()
    check(!loadResult.hasProblems) {
        "KlibLoader reported problems for $label ($klibPath): ${loadResult.problematicLibraries.map { it.libraryPath to it.problemCase }}"
    }
    val lib: KotlinLibrary = loadResult.librariesStdlibFirst.single()
    val deps = lib.unresolvedDependencies.map { it.path }.sorted().joinToString(",")
    val targets = lib.nativeTargets.sorted().joinToString(",")
    out += "KLIB_MANIFEST|$label|unique=${lib.uniqueName}|short=${lib.shortName ?: "-"}|header=${lib.isHeader}|platform=${lib.builtInsPlatform?.name ?: "-"}|targets=$targets|deps=$deps"

    val header = parseModuleHeader(lib.metadata.moduleHeaderData)
    val pkgNames = header.packageFragmentNameList.sorted()
    val emptyPkgs = header.emptyPackageList.sorted()
    out += "KLIB_HEADER|$label|pkgs=${pkgNames.joinToString(",")}|emptyPkgs=${emptyPkgs.joinToString(",")}"

    for (pkg in pkgNames) {
        val partNames = lib.metadata.getPackageFragmentNames(pkg).sorted()
        val allClasses = mutableListOf<String>()
        val allFunctions = mutableListOf<String>()
        val allProperties = mutableListOf<String>()
        val allTypeAliases = mutableListOf<String>()
        for (part in partNames) {
            val fragment = parsePackageFragment(lib.metadata.getPackageFragment(pkg, part))
            val nameResolver = NameResolverImpl(fragment.strings, fragment.qualifiedNames)
            for (cls in fragment.class_List) {
                val classFq = nameResolver.getClassId(cls.fqName).asSingleFqName().asString()
                allClasses += "$classFq[tp=${cls.typeParameterCount},sup=${cls.supertypeCount},ctor=${cls.constructorCount},fn=${cls.functionCount},prop=${cls.propertyCount},enum=${cls.enumEntryCount}]"
            }
            for (fn in fragment.getPackage().functionList) {
                val fnName = nameResolver.getName(fn.name).asString()
                allFunctions += "$fnName(tp=${fn.typeParameterCount},vp=${fn.valueParameterCount})"
            }
            for (prop in fragment.getPackage().propertyList) {
                allProperties += nameResolver.getName(prop.name).asString()
            }
            for (ta in fragment.getPackage().typeAliasList) {
                allTypeAliases += nameResolver.getName(ta.name).asString()
            }
        }
        allClasses.sort()
        allFunctions.sort()
        allProperties.sort()
        allTypeAliases.sort()
        out += "KLIB_PKG|$label|pkg=$pkg|parts=${partNames.size}|classes=${allClasses.joinToString(";")}|functions=${allFunctions.joinToString(";")}|properties=${allProperties.joinToString(";")}|typeAliases=${allTypeAliases.joinToString(";")}"
    }

    val mainIr = lib.ir
    val mainIrFiles = mainIr?.irFileCount ?: 0
    val fileStreamChecks = (0 until mainIrFiles).map { fileIdx ->
        val irComp = mainIr!!
        "f$fileIdx(decl=${irComp.declarations(fileIdx).isNotEmpty()},body=${irComp.bodies(fileIdx).isNotEmpty()},types=${irComp.types(fileIdx).isNotEmpty()},sigs=${irComp.signatures(fileIdx).isNotEmpty()},strs=${irComp.stringLiterals(fileIdx).isNotEmpty()})"
    }
    val inlinableIr = lib.inlinableFunctionsIr
    val inlinableIrFiles = inlinableIr?.irFileCount ?: 0
    out += "KLIB_IR|$label|hasMainIr=${mainIr != null}|mainIrFiles=$mainIrFiles|streams=${fileStreamChecks.joinToString(",")}|hasInlinableIr=${inlinableIr != null}|inlinableIrFiles=$inlinableIrFiles"
    return out
}

fun runAllF3ECases(
    kotlinHomePath: String,
    fixturesDir: String,
    outKlibDir: String,
): List<String> {
    val lines = mutableListOf<String>()

    // Case 1: Multi-file base.klib compilation via LightTree (useFirLT = true)
    // Covers generics, interfaces, class delegation, inline + reified functions, custom exceptions, and coroutines.
    val baseKlibPath = "$outKlibDir/base.klib"
    val [baseExit, baseMsgs] = executeNativeKlibPipeline {
        kotlinHome = kotlinHomePath
        target = "linux_x64"
        produce = "library"
        moduleName = "f3e_base"
        shortModuleName = "f3e_base"
        outputName = baseKlibPath
        useFirLT = true
        freeArgs = listOf(
            "$fixturesDir/base/Core.kt",
            "$fixturesDir/base/Async.kt",
        )
    }
    val baseErrors = baseMsgs.filter { it.severity.isError }
    check(baseExit == ExitCode.OK && baseErrors.isEmpty()) {
        "Case 1 (base.klib) failed with exit=$baseExit, errors=$baseErrors"
    }
    lines += "CASE1_BASE_KLIB|exit=${baseExit.name}|errorCount=${baseErrors.size}"
    val baseLtSummary = summarizeKlibMetadataAndIr("base_lt", baseKlibPath)
    lines += baseLtSummary

    // Case 2: Multi-file cross-module app.klib compilation depending on base.klib
    // Exercises KLIB symbol deserialization, cross-module inline/reified IR inlining before KLIB serialization,
    // delegation, exception handling, and coroutine suspension across module boundaries.
    val appKlibPath = "$outKlibDir/app.klib"
    val [appExit, appMsgs] = executeNativeKlibPipeline {
        kotlinHome = kotlinHomePath
        target = "linux_x64"
        produce = "library"
        moduleName = "f3e_app"
        shortModuleName = "f3e_app"
        outputName = appKlibPath
        setLibraries(this, baseKlibPath)
        useFirLT = true
        freeArgs = listOf(
            "$fixturesDir/app/Models.kt",
            "$fixturesDir/app/Main.kt",
        )
    }
    val appErrors = appMsgs.filter { it.severity.isError }
    check(appExit == ExitCode.OK && appErrors.isEmpty()) {
        "Case 2 (app.klib) failed with exit=$appExit, errors=$appErrors"
    }
    lines += "CASE2_APP_KLIB|exit=${appExit.name}|errorCount=${appErrors.size}"
    lines += summarizeKlibMetadataAndIr("app_lt", appKlibPath)

    // Case 3A: PSI Frontend mode (useFirLT = false) on the same multi-file base module
    // Verifies firFrontendWithPsi + buildResolveAndCheckFirFromKtFiles produces identical KLIB metadata & IR structure.
    val basePsiKlibPath = "$outKlibDir/base_psi.klib"
    val [basePsiExit, basePsiMsgs] = executeNativeKlibPipeline {
        kotlinHome = kotlinHomePath
        target = "linux_x64"
        produce = "library"
        moduleName = "f3e_base"
        shortModuleName = "f3e_base"
        outputName = basePsiKlibPath
        useFirLT = false
        freeArgs = listOf(
            "$fixturesDir/base/Core.kt",
            "$fixturesDir/base/Async.kt",
        )
    }
    val basePsiErrors = basePsiMsgs.filter { it.severity.isError }
    check(basePsiExit == ExitCode.OK && basePsiErrors.isEmpty()) {
        "Case 3A (base_psi.klib) failed with exit=$basePsiExit, errors=$basePsiErrors"
    }
    val basePsiSummary = summarizeKlibMetadataAndIr("base_psi", basePsiKlibPath)
    val normalizedLt = baseLtSummary.map { it.replace("|base_lt|", "|base_cmp|") }
    val normalizedPsi = basePsiSummary.map { it.replace("|base_psi|", "|base_cmp|") }
    check(normalizedLt == normalizedPsi) {
        "LightTree vs PSI KLIB summary mismatch:\nLT=$normalizedLt\nPSI=$normalizedPsi"
    }
    lines += "CASE3A_PSI_KLIB|exit=${basePsiExit.name}|errorCount=${basePsiErrors.size}|ltEqualsPsi=${normalizedLt == normalizedPsi}"
    lines += basePsiSummary

    // Case 3B: Metadata-only KLIB mode (metadataKlib = true) and Header KLIB mode (headerKlibPath)
    val baseMetaKlibPath = "$outKlibDir/base_meta.klib"
    val [metaExit, metaMsgs] = executeNativeKlibPipeline {
        kotlinHome = kotlinHomePath
        target = "linux_x64"
        produce = "library"
        metadataKlib = true
        moduleName = "f3e_base_meta"
        shortModuleName = "f3e_base_meta"
        outputName = baseMetaKlibPath
        useFirLT = true
        freeArgs = listOf(
            "$fixturesDir/base/Core.kt",
            "$fixturesDir/base/Async.kt",
        )
    }
    val metaErrors = metaMsgs.filter { it.severity.isError }
    check(metaExit == ExitCode.OK && metaErrors.isEmpty()) {
        "Case 3B (base_meta.klib) failed with exit=$metaExit, errors=$metaErrors"
    }
    lines += "CASE3B_META_KLIB|exit=${metaExit.name}|errorCount=${metaErrors.size}"
    lines += summarizeKlibMetadataAndIr("base_meta", baseMetaKlibPath)

    val appHeaderKlibPath = "$outKlibDir/app_header.klib"
    val [headerExit, headerMsgs] = executeNativeKlibPipeline {
        kotlinHome = kotlinHomePath
        target = "linux_x64"
        produce = "library"
        headerKlibPath = appHeaderKlibPath
        moduleName = "f3e_app"
        shortModuleName = "f3e_app"
        outputName = "$outKlibDir/app_with_friend.klib"
        setLibraries(this, baseKlibPath)
        friendModules = baseKlibPath
        useFirLT = true
        freeArgs = listOf(
            "$fixturesDir/app/Models.kt",
            "$fixturesDir/app/Main.kt",
        )
    }
    val headerErrors = headerMsgs.filter { it.severity.isError }
    check(headerExit == ExitCode.OK && headerErrors.isEmpty()) {
        "Case 3C (app_header.klib) failed with exit=$headerExit, errors=$headerErrors"
    }
    lines += "CASE3C_HEADER_KLIB|exit=${headerExit.name}|errorCount=${headerErrors.size}"
    lines += summarizeKlibMetadataAndIr("app_header", appHeaderKlibPath)

    // Case 4: Invalid .kt programs rejected with exact diagnostic codes and line:column positions
    // Verified in both LightTree (useFirLT = true) and PSI (useFirLT = false) modes.
    for ([modeLabel, useLt] in listOf("LT" to true, "PSI" to false)) {
        val [diagExit, diagMsgs] = executeNativeKlibPipeline {
            kotlinHome = kotlinHomePath
            target = "linux_x64"
            produce = "library"
            moduleName = "f3e_diag_$modeLabel"
            outputName = "$outKlibDir/should_not_exist_$modeLabel.klib"
            setLibraries(this, baseKlibPath)
            useFirLT = useLt
            renderInternalDiagnosticNames = true
            freeArgs = listOf(
                "$fixturesDir/diag/ErrorsA.kt",
                "$fixturesDir/diag/ErrorsB.kt",
                "$fixturesDir/diag/SyntaxBroken.kt",
            )
        }
        check(diagExit == ExitCode.COMPILATION_ERROR) {
            "Expected COMPILATION_ERROR for invalid sources in mode $modeLabel, got $diagExit"
        }
        val relevant = diagMsgs.filter {
            it.severity.isError ||
                it.severity == CompilerMessageSeverity.WARNING ||
                it.severity == CompilerMessageSeverity.STRONG_WARNING
        }
        check(relevant.isNotEmpty()) {
            "Expected non-empty diagnostics in mode $modeLabel"
        }
        lines += "CASE4_DIAG_SUMMARY|mode=$modeLabel|exit=${diagExit.name}|count=${relevant.size}"
        for (msg in relevant) {
            val relPath = normalizeRelativePath(msg.path, fixturesDir)
            val cleanMsg = msg.message.replace("\r\n", "\n").replace('\n', ' ').trim()
            lines += "DIAG|$modeLabel|$relPath|${msg.line}:${msg.column}-${msg.endLine}:${msg.endColumn}|${msg.severity.name}|$cleanMsg"
        }
    }

    // Case 5: Direct compiler/fir/entrypoint DependencyListForCliModule API
    val depList = DependencyListForCliModule.build(Name.special("<f3e-cli-module>")) {
        dependencies(listOf(baseKlibPath, appKlibPath))
        friendDependencies(listOf(baseKlibPath))
        dependsOnDependencies(listOf(baseMetaKlibPath))
    }
    val regNames = depList.regularDependencies.map { it.name.asString() }
    val friendNames = depList.friendDependencies.map { it.name.asString() }
    val depOnNames = depList.dependsOnDependencies.map { it.name.asString() }
    val allModNames = depList.moduleDataProvider.allModuleData.map { it.name.asString() }
    lines += "CASE5_ENTRYPOINT_DEPS|reg=${regNames.joinToString(",")}|friends=${friendNames.joinToString(",")}|dependsOn=${depOnNames.joinToString(",")}|providerAll=${allModNames.joinToString(",")}"

    return lines
}
