package nativekotlin.e1k.test

import kotlinx.metadata.klib.KlibMetadataVersion
import kotlinx.metadata.klib.KlibModuleMetadata
import org.jetbrains.kotlin.cli.bc.ADD_CACHE
import org.jetbrains.kotlin.cli.bc.CACHED_LIBRARY
import org.jetbrains.kotlin.cli.bc.INCLUDE_ARG
import org.jetbrains.kotlin.cli.bc.K2NativeCompilerArguments
import org.jetbrains.kotlin.cli.bc.SHORT_MODULE_NAME_ARG
import org.jetbrains.kotlin.cli.bc.STATIC_FRAMEWORK_FLAG
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.cli.klib.exec as klibExec
import org.jetbrains.kotlin.cli.utilities.invokeInterop
import org.jetbrains.kotlin.cli.utilities.runLlvmClangToolWithTarget
import org.jetbrains.kotlin.cli.utilities.runLlvmTool
import org.jetbrains.kotlin.kotlinp.Printer
import org.jetbrains.kotlin.kotlinp.Settings
import org.jetbrains.kotlin.kotlinp.klib.DeclarationId
import org.jetbrains.kotlin.kotlinp.klib.ExternalSignatureComputer
import org.jetbrains.kotlin.kotlinp.klib.KlibKotlinp
import org.jetbrains.kotlin.library.abi.AbiCompoundName
import org.jetbrains.kotlin.library.abi.AbiQualifiedName
import org.jetbrains.kotlin.library.abi.AbiReadingFilter
import org.jetbrains.kotlin.library.abi.AbiRenderingSettings
import org.jetbrains.kotlin.library.abi.AbiSignatureVersion
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbi
import org.jetbrains.kotlin.library.abi.LibraryAbiRenderer
import org.jetbrains.kotlin.library.abi.parser.KlibDumpParser
import org.jetbrains.kotlin.library.components.metadata
import org.jetbrains.kotlin.library.loader.KlibLoader
import org.jetbrains.kotlin.library.metadataVersion

interface E1KPlatformOps {
    fun exists(path: String): Boolean
    fun isRegularFile(path: String): Boolean
    fun fileSize(path: String): Long
    fun mkdirs(path: String)
    fun writeText(path: String, text: String)
    fun readText(path: String): String
    @OptIn(ExperimentalLibraryAbiReader::class)
    fun readAbiInfoFromFile(klibPath: String, filters: List<AbiReadingFilter>): LibraryAbi
    @OptIn(ExperimentalLibraryAbiReader::class)
    fun parseAbiDumpFromFile(dumpPath: String): LibraryAbi
    fun extractParseErrorOffset(t: Throwable): Int?
}

private data class KlibRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

private fun runKlibCli(vararg args: String): KlibRunResult {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val code = klibExec(stdout, stderr, arrayOf(*args))
    return KlibRunResult(code, stdout.toString(), stderr.toString())
}

private fun printCaseResult(tag: String, res: KlibRunResult) {
    println("E1K_CASE:$tag:exit=${res.exitCode}")
    if (res.stdout.isNotEmpty()) {
        for (line in res.stdout.trimEnd('\n').lines()) {
            println("E1K_STDOUT:$tag:$line")
        }
    }
    if (res.stderr.isNotEmpty()) {
        for (line in res.stderr.trimEnd('\n').lines()) {
            println("E1K_STDERR:$tag:$line")
        }
    }
}

private fun testKlibCliArgumentsAndErrors(
    sampleIrKlibPath: String,
    workTmpDir: String,
) {
    val usageEmpty = runKlibCli()
    check(usageEmpty.exitCode == 0) { "Expected exitCode 0 for empty args, got ${usageEmpty.exitCode}" }
    check(usageEmpty.stderr.startsWith("Usage: klib <command> <library path> [<options>]")) {
        "Unexpected usage stderr: ${usageEmpty.stderr}"
    }
    printCaseResult("cli:usage-empty", usageEmpty)

    val usageOneArg = runKlibCli("info")
    check(usageOneArg.exitCode == 0 && usageOneArg.stderr == usageEmpty.stderr) {
        "Expected identical usage output for 1-arg invocation"
    }
    println("E1K_CASE:cli:usage-one-arg:matchesEmpty=true")

    val unknownCmd = runKlibCli("no-such-command", sampleIrKlibPath)
    check(unknownCmd.exitCode == 1)
    printCaseResult("cli:unknown-command", unknownCmd)

    val missingLib = runKlibCli("info", "$workTmpDir/does-not-exist.klib")
    check(missingLib.exitCode == 1)
    printCaseResult("cli:missing-library", missingLib)

    val flagNoDash = runKlibCli("info", sampleIrKlibPath, "not-a-flag")
    check(flagNoDash.exitCode == 1)
    printCaseResult("cli:flag-no-dash", flagNoDash)

    val missingFlagVal = runKlibCli("dump-abi", sampleIrKlibPath, "-signature-version")
    check(missingFlagVal.exitCode == 1)
    printCaseResult("cli:missing-flag-value", missingFlagVal)

    val unknownOpt = runKlibCli("info", sampleIrKlibPath, "-unknown-option", "123")
    check(unknownOpt.exitCode == 1)
    printCaseResult("cli:unknown-option", unknownOpt)

    val inapplicableOpt = runKlibCli("info", sampleIrKlibPath, "-signature-version", "2")
    check(inapplicableOpt.exitCode == 1)
    printCaseResult("cli:inapplicable-option", inapplicableOpt)

    val inapplicableSigOnMetadata = runKlibCli("dump-metadata", sampleIrKlibPath, "-signature-version", "2")
    check(inapplicableSigOnMetadata.exitCode == 1)
    printCaseResult("cli:inapplicable-sig-on-metadata", inapplicableSigOnMetadata)

    val invalidSigVer = runKlibCli("dump-abi", sampleIrKlibPath, "-signature-version", "abc")
    check(invalidSigVer.exitCode == 1)
    printCaseResult("cli:invalid-sig-version", invalidSigVer)

    val unsupportedSigVer = runKlibCli("dump-abi", sampleIrKlibPath, "-signature-version", "99")
    check(unsupportedSigVer.exitCode == 1)
    printCaseResult("cli:unsupported-sig-version", unsupportedSigVer)

    val invalidMetadataMode = runKlibCli("dump-metadata", sampleIrKlibPath, "-dump-metadata-test-mode", "invalid-mode")
    check(invalidMetadataMode.exitCode == 1)
    printCaseResult("cli:invalid-metadata-mode", invalidMetadataMode)
}

@OptIn(ExperimentalLibraryAbiReader::class)
private fun testKlibCommandsAndAbiParser(
    sampleIrKlibPath: String,
    sampleNoInlineKlibPath: String,
    fixturesDir: String,
    workTmpDir: String,
    ops: E1KPlatformOps,
) {
    // 1. info on sample-ir.klib and sample-no-inline.klib
    val infoIr = runKlibCli("info", sampleIrKlibPath)
    check(infoIr.exitCode == 0) { "info on sample-ir failed: ${infoIr.stderr}" }
    printCaseResult("klib:info:sample-ir", infoIr)

    val infoNoInline = runKlibCli("info", sampleNoInlineKlibPath)
    check(infoNoInline.exitCode == 0) { "info on sample-no-inline failed: ${infoNoInline.stderr}" }
    printCaseResult("klib:info:sample-no-inline", infoNoInline)

    // 2. dump-abi (default, v2, and v1 error check)
    val dumpAbiDefault = runKlibCli("dump-abi", sampleIrKlibPath)
    check(dumpAbiDefault.exitCode == 0) { "dump-abi failed: ${dumpAbiDefault.stderr}" }
    printCaseResult("klib:dump-abi:default", dumpAbiDefault)

    val dumpAbiV2 = runKlibCli("dump-abi", sampleIrKlibPath, "-signature-version", "2")
    check(dumpAbiV2.exitCode == 0 && dumpAbiV2.stdout == dumpAbiDefault.stdout) {
        "Expected dump-abi -signature-version 2 to match default dump-abi"
    }
    println("E1K_CASE:klib:dump-abi:v2-matches-default=true")

    val dumpAbiV1 = runKlibCli("dump-abi", sampleIrKlibPath, "-signature-version", "1")
    check(dumpAbiV1.exitCode == 0) { "Expected dump-abi -signature-version 1 to succeed: ${dumpAbiV1.stderr}" }
    printCaseResult("klib:dump-abi:v1", dumpAbiV1)

    // 3. Direct LibraryAbiReader, AbiReadingFilter, LibraryAbiRenderer, and KlibDumpParser round-trip
    val abiWithFilters = ops.readAbiInfoFromFile(
        sampleIrKlibPath,
        listOf(
            AbiReadingFilter.Composite(
                listOf(
                    AbiReadingFilter.NonPublicMarkerAnnotations(
                        listOf(AbiQualifiedName(AbiCompoundName("e1k.sample.pkg"), AbiCompoundName("E1KInternalMarker")))
                    ),
                    AbiReadingFilter.ExcludedPackages(
                        listOf(AbiCompoundName("e1k.excluded.pkg"))
                    ),
                    AbiReadingFilter.ExcludedClasses(
                        listOf(AbiQualifiedName(AbiCompoundName("e1k.sample.pkg"), AbiCompoundName("NonExistentClass")))
                    ),
                )
            )
        ),
    )
    val renderedWithManifest = buildString {
        LibraryAbiRenderer.render(
            libraryAbi = abiWithFilters,
            output = this,
            settings = AbiRenderingSettings(
                renderedSignatureVersion = AbiSignatureVersion.resolveByVersionNumber(2),
                renderManifest = true,
                renderDeclarations = true,
                indentationString = "    ",
            ),
        )
    }
    for (line in renderedWithManifest.trimEnd('\n').lines()) {
        println("E1K_ABI_FILTERED:$line")
    }

    val renderedForRoundTrip = buildString {
        LibraryAbiRenderer.render(
            libraryAbi = abiWithFilters,
            output = this,
            settings = AbiRenderingSettings(
                renderedSignatureVersion = AbiSignatureVersion.resolveByVersionNumber(2),
                renderManifest = false,
                renderDeclarations = true,
                indentationString = "    ",
            ),
        )
    }
    val dumpFilePath = "$workTmpDir/sample-ir.abi.dump"
    ops.writeText(dumpFilePath, renderedForRoundTrip)
    val parsedFromString = KlibDumpParser(renderedForRoundTrip).parse()
    val parsedFromFile = ops.parseAbiDumpFromFile(dumpFilePath)
    val reRenderedFromString = buildString {
        LibraryAbiRenderer.render(
            libraryAbi = parsedFromString,
            output = this,
            settings = AbiRenderingSettings(
                renderedSignatureVersion = AbiSignatureVersion.resolveByVersionNumber(2),
                renderManifest = false,
                renderDeclarations = true,
                indentationString = "    ",
            ),
        )
    }
    val reRenderedFromFile = buildString {
        LibraryAbiRenderer.render(
            libraryAbi = parsedFromFile,
            output = this,
            settings = AbiRenderingSettings(
                renderedSignatureVersion = AbiSignatureVersion.resolveByVersionNumber(2),
                renderManifest = false,
                renderDeclarations = true,
                indentationString = "    ",
            ),
        )
    }
    val stripSigComments = { text: String ->
        text.lines()
            .filterNot { it.startsWith("// Library unique name:") }
            .map { it.substringBefore(" // ") }
    }
    check(reRenderedFromString == reRenderedFromFile && stripSigComments(reRenderedFromFile) == stripSigComments(renderedForRoundTrip)) {
        "KlibDumpParser round-trip mismatch!\nExpected:\n$renderedForRoundTrip\nActual:\n$reRenderedFromFile"
    }
    println(
        "E1K_CASE:abi:parser-roundtrip:" +
            "uniqueName=${parsedFromFile.uniqueName};" +
            "decls=${parsedFromFile.topLevelDeclarations.declarations.size};" +
            "exactMatch=true;lines=${reRenderedFromFile.lines().size}"
    )

    val badDumpResult = runCatching {
        KlibDumpParser("// Rendering settings:\n// - Signature version: 2\nthis is invalid syntax\n").parse()
    }
    val parseErrorOffset = badDumpResult.exceptionOrNull()?.let(ops::extractParseErrorOffset)
    println("E1K_CASE:abi:parser-error:failed=${badDumpResult.isFailure};offset=$parseErrorOffset")

    // 4. dump-metadata (default, compact-with-stable-order, ultracompact-with-stable-order)
    val metaDefault = runKlibCli("dump-metadata", sampleIrKlibPath)
    check(metaDefault.exitCode == 0) { "dump-metadata default failed: ${metaDefault.stderr}" }
    printCaseResult("klib:dump-metadata:default", metaDefault)

    val metaCompact = runKlibCli("dump-metadata", sampleIrKlibPath, "-dump-metadata-test-mode", "compact-with-stable-order")
    check(metaCompact.exitCode == 0) { "dump-metadata compact failed: ${metaCompact.stderr}" }
    printCaseResult("klib:dump-metadata:compact", metaCompact)

    val metaUltracompact = runKlibCli("dump-metadata", sampleIrKlibPath, "-dump-metadata-test-mode", "ultracompact-with-stable-order")
    check(metaUltracompact.exitCode == 0) { "dump-metadata ultracompact failed: ${metaUltracompact.stderr}" }
    printCaseResult("klib:dump-metadata:ultracompact", metaUltracompact)

    // 5. Direct KlibKotlinp + ExternalSignatureComputer test
    val loadedSampleKlib = KlibLoader { libraryPaths(sampleIrKlibPath) }.load().librariesStdlibFirst.single()
    val moduleMeta = KlibModuleMetadata.readLenient(
        object : KlibModuleMetadata.MetadataLibraryProvider {
            private val metadata = loadedSampleKlib.metadata
            override val moduleHeaderData get() = metadata.moduleHeaderData
            override val metadataVersion = KlibMetadataVersion(loadedSampleKlib.metadataVersion!!.toArray())
            override fun packageMetadata(fqName: String, partName: String) = metadata.getPackageFragment(fqName, partName)
            override fun packageMetadataParts(fqName: String) = metadata.getPackageFragmentNames(fqName)
        }
    )
    val externalSigComputer = ExternalSignatureComputer(moduleMeta) { declId: DeclarationId ->
        "ext-sig:${declId::class.simpleName}"
    }
    val kotlinpWithExtSigs = buildString {
        KlibKotlinp(
            settings = Settings(isVerbose = true, sortDeclarations = true),
            signatureComputer = externalSigComputer,
        ).renderModule(moduleMeta, Printer(this))
    }
    val sigCommentLines = kotlinpWithExtSigs.lines().filter { it.trim().startsWith("// signature: ") }
    println("E1K_CASE:kotlinp:external-signatures:count=${sigCommentLines.size}")
    for (line in sigCommentLines) {
        println("E1K_KOTLINP_SIG:${line.trim()}")
    }

    // 6. dump-metadata-signatures (default, v2, v1)
    val metaSigsDefault = runKlibCli("dump-metadata-signatures", sampleIrKlibPath)
    check(metaSigsDefault.exitCode == 0) { "dump-metadata-signatures default failed: ${metaSigsDefault.stderr}" }
    printCaseResult("klib:dump-metadata-signatures:default", metaSigsDefault)

    val metaSigsV2 = runKlibCli("dump-metadata-signatures", sampleIrKlibPath, "-signature-version", "2")
    check(metaSigsV2.exitCode == 0 && metaSigsV2.stdout == metaSigsDefault.stdout)
    println("E1K_CASE:klib:dump-metadata-signatures:v2-matches-default=true")

    val metaSigsV1 = runKlibCli("dump-metadata-signatures", sampleIrKlibPath, "-signature-version", "1")
    check(metaSigsV1.exitCode == 0) { "dump-metadata-signatures v1 failed: ${metaSigsV1.stderr}" }
    printCaseResult("klib:dump-metadata-signatures:v1", metaSigsV1)

    // 7. dump-ir-signatures (default, v2, only-top-level=true, v1)
    val irSigsDefault = runKlibCli("dump-ir-signatures", sampleIrKlibPath)
    check(irSigsDefault.exitCode == 0) { "dump-ir-signatures default failed: ${irSigsDefault.stderr}" }
    printCaseResult("klib:dump-ir-signatures:default", irSigsDefault)

    val irSigsV2 = runKlibCli("dump-ir-signatures", sampleIrKlibPath, "-signature-version", "2")
    check(irSigsV2.exitCode == 0 && irSigsV2.stdout == irSigsDefault.stdout)
    println("E1K_CASE:klib:dump-ir-signatures:v2-matches-default=true")

    val irSigsTopLevel = runKlibCli("dump-ir-signatures", sampleIrKlibPath, "-only-top-level-signatures", "true")
    check(irSigsTopLevel.exitCode == 0) { "dump-ir-signatures top-level failed: ${irSigsTopLevel.stderr}" }
    printCaseResult("klib:dump-ir-signatures:top-level", irSigsTopLevel)

    val irSigsV1 = runKlibCli("dump-ir-signatures", sampleIrKlibPath, "-signature-version", "1")
    check(irSigsV1.exitCode == 0) { "dump-ir-signatures v1 failed: ${irSigsV1.stderr}" }
    printCaseResult("klib:dump-ir-signatures:v1", irSigsV1)

    // 8. dump-ir (with -relative-path-base)
    val dumpIrRel = runKlibCli("dump-ir", sampleIrKlibPath, "-relative-path-base", fixturesDir)
    check(dumpIrRel.exitCode == 0) { "dump-ir failed: ${dumpIrRel.stderr}" }
    printCaseResult("klib:dump-ir:relative", dumpIrRel)

    // 9. dump-ir-inlinable-functions (on sample-ir.klib and sample-no-inline.klib)
    val dumpInlineIr = runKlibCli("dump-ir-inlinable-functions", sampleIrKlibPath)
    check(dumpInlineIr.exitCode == 0) { "dump-ir-inlinable-functions failed: ${dumpInlineIr.stderr}" }
    printCaseResult("klib:dump-ir-inlinable-functions:sample-ir", dumpInlineIr)

    val dumpNoInlineIr = runKlibCli("dump-ir-inlinable-functions", sampleNoInlineKlibPath)
    check(dumpNoInlineIr.exitCode == 0) { "dump-ir-inlinable-functions on no-inline failed: ${dumpNoInlineIr.stderr}" }
    printCaseResult("klib:dump-ir-inlinable-functions:sample-no-inline", dumpNoInlineIr)
}

@Suppress("DEPRECATION")
private fun testK2NativeCompilerArguments() {
    val args = K2NativeCompilerArguments().apply {
        produce = "library"
        outputName = "/tmp/out.klib"
        target = "linux_x64"
        listTargets = true
        checkDependencies = true
        exportedLibraries = arrayOf("exp1.klib", "exp2.klib")
        libraryToAddToCache = "/lib1"
        cacheDirectories = arrayOf("/cache1")
        cachedLibraries = arrayOf("/lib1=/cache1")
        moduleName = "org.example.mod"
        shortModuleName = "example_mod"
        staticFramework = true
        nodefaultlibs = true
        nostdlib = false
        nopack = true
        optimization = true
        debug = false
        partialLinkageMode = "ENABLE"
        partialLinkageLogLevel = "ERROR"
        temporaryFilesDir = "/tmp/konan-tmp"
        overrideKonanProperties = arrayOf("airplaneMode=true")
    }
    println(
        "E1K_CASE:args:K2NativeCompilerArguments:" +
            "flags=$STATIC_FRAMEWORK_FLAG|$INCLUDE_ARG|$CACHED_LIBRARY|$ADD_CACHE|$SHORT_MODULE_NAME_ARG;" +
            "produce=${args.produce};out=${args.outputName};target=${args.target};" +
            "listTargets=${args.listTargets};checkDeps=${args.checkDependencies};" +
            "exported=${args.exportedLibraries?.joinToString("|")};" +
            "addCache=${args.libraryToAddToCache};" +
            "caches=${args.cacheDirectories?.joinToString("|")};" +
            "cachedLibs=${args.cachedLibraries?.joinToString("|")};" +
            "mod=${args.moduleName};short=${args.shortModuleName};" +
            "staticFw=${args.staticFramework};noDef=${args.nodefaultlibs};" +
            "noStd=${args.nostdlib};nopack=${args.nopack};opt=${args.optimization};" +
            "plMode=${args.partialLinkageMode};plLevel=${args.partialLinkageLogLevel};" +
            "tmp=${args.temporaryFilesDir};" +
            "overrides=${args.overrideKonanProperties?.joinToString("|")}"
    )
}

private fun testInteropCompilerAndKlibInspection(
    fixturesDir: String,
    libDir: String,
    workTmpDir: String,
    konanHome: String,
    konanDataDir: String,
    llvmHome: String,
    ops: E1KPlatformOps,
) {
    val badFlavor = runCatching { invokeInterop("js", emptyArray(), runFromDaemon = false) }
    println("E1K_CASE:interop:bad-flavor:failed=${badFlavor.isFailure};msg=${badFlavor.exceptionOrNull()?.message}")

    val interopKlibPath = "$workTmpDir/e1k_interop.klib"
    val interopTmpDir = "$workTmpDir/interop-tmp"
    ops.mkdirs(interopTmpDir)

    val cinteropArgs = arrayOf(
        "-def", "$fixturesDir/e1k_sample.def",
        "-pkg", "e1k.sample",
        "-o", interopKlibPath,
        "-target", "linux_x64",
        "-compiler-option", "-I$fixturesDir",
        "-compiler-option", "-nostdinc",
        "-staticLibrary", "libe1k_sample.a",
        "-libraryPath", libDir,
        "-no-default-libs",
        "-Xshort-module-name", "e1k_sample_short",
        "-Xmodule-name", "org.sample.e1k.interop",
        "-Xforeign-exception-mode", "terminate",
        "-Xuser-setup-hint", "Link_libe1k_sample",
        "-Xtemporary-files-dir", interopTmpDir,
        "-Xkonan-home", konanHome,
        "-Xkonan-data-dir", konanDataDir,
        "-Xoverride-konan-properties", "llvmHome.linux_x64=$llvmHome;airplaneMode=true",
    )

    val compilerArgsFromInterop = invokeInterop("native", cinteropArgs, runFromDaemon = false)
    check(compilerArgsFromInterop == null) {
        "Expected invokeInterop in native metadata mode to return null, got ${compilerArgsFromInterop?.toList()}"
    }
    val buildDir = "$interopKlibPath-build"
    val manifestFile = "$buildDir/manifest.properties"
    val cstubsBc = "$buildDir/natives/cstubs.bc"
    check(ops.isRegularFile(interopKlibPath) && ops.fileSize(interopKlibPath) > 0L) {
        "Expected generated interop KLIB at $interopKlibPath"
    }
    check(ops.isRegularFile(manifestFile) && ops.fileSize(manifestFile) > 0L) {
        "Expected generated manifest.properties at $manifestFile"
    }
    check(ops.isRegularFile(cstubsBc) && ops.fileSize(cstubsBc) > 0L) {
        "Expected generated cstubs.bc at $cstubsBc"
    }
    println("E1K_CASE:interop:generated:klibExists=true;manifestExists=true;cstubsBcExists=true")

    val interopInfo = runKlibCli("info", interopKlibPath)
    check(interopInfo.exitCode == 0) { "info on interop KLIB failed: ${interopInfo.stderr}" }
    printCaseResult("klib:interop:info", interopInfo)

    val interopMetaCompact = runKlibCli("dump-metadata", interopKlibPath, "-dump-metadata-test-mode", "compact-with-stable-order")
    check(interopMetaCompact.exitCode == 0) { "dump-metadata compact on interop KLIB failed: ${interopMetaCompact.stderr}" }
    printCaseResult("klib:interop:dump-metadata:compact", interopMetaCompact)

    val interopMetaUltra = runKlibCli("dump-metadata", interopKlibPath, "-dump-metadata-test-mode", "ultracompact-with-stable-order")
    check(interopMetaUltra.exitCode == 0) { "dump-metadata ultracompact on interop KLIB failed: ${interopMetaUltra.stderr}" }
    printCaseResult("klib:interop:dump-metadata:ultracompact", interopMetaUltra)

    val interopMetaSigsV2 = runKlibCli("dump-metadata-signatures", interopKlibPath, "-signature-version", "2")
    check(interopMetaSigsV2.exitCode == 0) { "dump-metadata-signatures v2 on interop KLIB failed: ${interopMetaSigsV2.stderr}" }
    printCaseResult("klib:interop:dump-metadata-signatures:v2", interopMetaSigsV2)

    // Interop KLIB is IR-less: dump-ir, dump-ir-signatures, dump-ir-inlinable-functions, dump-abi must report IR-less library error
    for (irCmd in listOf("dump-ir", "dump-ir-signatures", "dump-ir-inlinable-functions", "dump-abi")) {
        val res = runKlibCli(irCmd, interopKlibPath)
        check(res.exitCode == 1) { "Expected $irCmd on IR-less interop KLIB to return exitCode 1" }
        printCaseResult("klib:interop:ir-less:$irCmd", res)
    }
}

private fun testLlvmClangUtilities(
    fixturesDir: String,
    workTmpDir: String,
    ops: E1KPlatformOps,
) {
    val cObjPath = "$workTmpDir/llvm_clang_c.o"
    val cxxObjPath = "$workTmpDir/llvm_clang_cxx.o"
    val archivePath = "$workTmpDir/libllvm_clang_test.a"

    runLlvmClangToolWithTarget(
        arrayOf(
            "clang",
            "linux_x64",
            "-I$fixturesDir",
            "-c",
            "$fixturesDir/e1k_sample.c",
            "-o",
            cObjPath,
        )
    )
    check(ops.isRegularFile(cObjPath) && ops.fileSize(cObjPath) > 0L) {
        "Expected clang object file at $cObjPath"
    }

    runLlvmClangToolWithTarget(
        arrayOf(
            "clang++",
            "linux_x64",
            "-I$fixturesDir",
            "-x",
            "c++",
            "-c",
            "$fixturesDir/e1k_sample.c",
            "-o",
            cxxObjPath,
        )
    )
    check(ops.isRegularFile(cxxObjPath) && ops.fileSize(cxxObjPath) > 0L) {
        "Expected clang++ object file at $cxxObjPath"
    }

    runLlvmTool(
        arrayOf(
            "llvm-ar",
            "rcs",
            archivePath,
            cObjPath,
            cxxObjPath,
        )
    )
    check(ops.isRegularFile(archivePath) && ops.fileSize(archivePath) > 0L) {
        "Expected llvm-ar archive at $archivePath"
    }

    val invalidClangTool = runCatching {
        runLlvmClangToolWithTarget(arrayOf("not-clang", "linux_x64"))
    }
    println(
        "E1K_CASE:llvm-clang:objectsAndArchiveCreated=true;" +
            "invalidToolFailed=${invalidClangTool.isFailure};" +
            "invalidToolMsg=${invalidClangTool.exceptionOrNull()?.message}"
    )
}

fun runE1KSuite(
    fixturesDir: String,
    libDir: String,
    sampleIrKlibPath: String,
    sampleNoInlineKlibPath: String,
    workTmpDir: String,
    konanHome: String,
    konanDataDir: String,
    llvmHome: String,
    ops: E1KPlatformOps,
) {
    testKlibCliArgumentsAndErrors(sampleIrKlibPath, workTmpDir)
    testKlibCommandsAndAbiParser(sampleIrKlibPath, sampleNoInlineKlibPath, fixturesDir, workTmpDir, ops)
    testK2NativeCompilerArguments()
    testInteropCompilerAndKlibInspection(fixturesDir, libDir, workTmpDir, konanHome, konanDataDir, llvmHome, ops)
    testLlvmClangUtilities(fixturesDir, workTmpDir, ops)
    println("E1K_SUITE_OK")
}
