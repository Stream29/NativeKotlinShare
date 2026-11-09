/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package nativekotlin.e1.test

import com.intellij.openapi.extensions.PluginId
import org.jetbrains.kotlin.cli.bc.K2Native
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.GRADLE_STYLE
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.XCODE_STYLE
import org.jetbrains.kotlin.cli.common.messages.XML
import org.jetbrains.kotlin.cli.common.messages.normalizeCliMessageRenderer
import org.jetbrains.kotlin.cli.utilities.main as cliUtilitiesMain
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.library.abi.AbiRenderingSettings
import org.jetbrains.kotlin.library.abi.AbiSignatureVersion
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiRenderer
import org.jetbrains.kotlin.psi.impl.KotlinPsiBootstrap

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
private fun configureLowMemoryGc() {
    kotlin.native.runtime.GC.targetHeapUtilization = 0.80
    kotlin.native.runtime.GC.heapTriggerCoefficient = 0.88
    kotlin.native.runtime.GC.minHeapBytes = 16L * 1024 * 1024
    kotlin.native.runtime.GC.targetHeapBytes = 32L * 1024 * 1024
    kotlin.native.runtime.GC.pauseOnTargetHeapOverflow = true
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
private fun execK2NativeNative(args: Array<String>): Pair<ExitCode, String> {
    val buf = StringBuilder()
    val code = K2Native().exec(buf, *args)
    kotlin.native.runtime.GC.collect()
    return code to buf.toString()
}

@OptIn(ExperimentalLibraryAbiReader::class)
private fun dumpKlibAbiNative(klibPath: String): String {
    val abi = LibraryAbiReader.readAbiInfo(File(klibPath))
    val settings = AbiRenderingSettings(
        renderedSignatureVersion = AbiSignatureVersion.resolveByVersionNumber(2),
        renderManifest = false,
        renderDeclarations = true,
    )
    return LibraryAbiRenderer.render(abi, settings).trim()
}

fun main(args: Array<String>) {
    configureLowMemoryGc()
    if (args.isNotEmpty() && args[0] == "--e1-suite") {
        val suiteArgs = args.drop(1)
        require(suiteArgs.size == 7) {
            "Expected 7 suite args after --e1-suite: <fixturesDir> <cLibDir> <bad.bc> <workDir> <distDir> <konanDataDir> <llvmDir>"
        }
        val runtime = KotlinPsiBootstrap.install(PluginId.getId("nativekotlin.e1.test"))
        try {
            runE1Suite(
                fixturesDir = suiteArgs[0],
                cLibDir = suiteArgs[1],
                badBitcodePath = suiteArgs[2],
                workDir = suiteArgs[3],
                distDir = suiteArgs[4],
                konanDataDir = suiteArgs[5],
                llvmDir = suiteArgs[6],
                execCli = ::execK2NativeNative,
                dumpKlibAbi = ::dumpKlibAbiNative,
                plainFullRenderer = normalizeCliMessageRenderer(MessageRenderer.PLAIN_FULL_PATHS),
                withoutPathsRenderer = normalizeCliMessageRenderer(MessageRenderer.WITHOUT_PATHS),
                gradleRenderer = MessageRenderer.GRADLE_STYLE,
                xcodeRenderer = MessageRenderer.XCODE_STYLE,
                xmlRenderer = MessageRenderer.XML,
            )
        } finally {
            runtime.dispose()
        }
    } else {
        cliUtilitiesMain(args)
    }
}
