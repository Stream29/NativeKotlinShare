/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package nativekotlin.e1.test

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.jetbrains.kotlin.cli.bc.K2Native
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.library.abi.AbiRenderingSettings
import org.jetbrains.kotlin.library.abi.AbiSignatureVersion
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiRenderer

private fun execK2NativeJvm(args: Array<String>): Pair<ExitCode, String> {
    val baos = ByteArrayOutputStream()
    val ps = PrintStream(baos, true, StandardCharsets.UTF_8.name())
    val code = K2Native().exec(ps, *args)
    ps.flush()
    return code to baos.toString(StandardCharsets.UTF_8.name())
}

@OptIn(ExperimentalLibraryAbiReader::class)
private fun dumpKlibAbiJvm(klibPath: String): String {
    val abi = LibraryAbiReader.readAbiInfo(File(klibPath))
    val settings = AbiRenderingSettings(
        renderedSignatureVersion = AbiSignatureVersion.resolveByVersionNumber(2),
        renderManifest = false,
        renderDeclarations = true,
    )
    return LibraryAbiRenderer.render(abi, settings).trim()
}

fun main(args: Array<String>) {
    require(args.size == 7) {
        "Expected 7 args: <fixturesDir> <cLibDir> <bad.bc> <workDir> <distDir> <konanDataDir> <llvmDir>"
    }
    runE1Suite(
        fixturesDir = args[0],
        cLibDir = args[1],
        badBitcodePath = args[2],
        workDir = args[3],
        distDir = args[4],
        konanDataDir = args[5],
        llvmDir = args[6],
        execCli = ::execK2NativeJvm,
        dumpKlibAbi = ::dumpKlibAbiJvm,
        plainFullRenderer = MessageRenderer.PLAIN_FULL_PATHS,
        withoutPathsRenderer = MessageRenderer.WITHOUT_PATHS,
        gradleRenderer = MessageRenderer.GRADLE_STYLE,
        xcodeRenderer = MessageRenderer.XCODE_STYLE,
        xmlRenderer = MessageRenderer.XML,
    )
}
