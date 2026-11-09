package nativekotlin.e1k.test

import java.io.File
import java.text.ParseException
import org.jetbrains.kotlin.library.abi.AbiReadingFilter
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbi
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import org.jetbrains.kotlin.library.abi.parser.KlibDumpParser

private object JvmPlatformOps : E1KPlatformOps {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun isRegularFile(path: String): Boolean = File(path).isFile

    override fun fileSize(path: String): Long = File(path).length()

    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun readText(path: String): String = File(path).readText()

    @OptIn(ExperimentalLibraryAbiReader::class)
    override fun readAbiInfoFromFile(klibPath: String, filters: List<AbiReadingFilter>): LibraryAbi =
        LibraryAbiReader.readAbiInfo(File(klibPath), filters)

    @OptIn(ExperimentalLibraryAbiReader::class)
    override fun parseAbiDumpFromFile(dumpPath: String): LibraryAbi =
        KlibDumpParser(File(dumpPath)).parse()

    override fun extractParseErrorOffset(t: Throwable): Int? =
        (t as? ParseException)?.errorOffset
}

fun main(args: Array<String>) {
    require(args.size == 8) {
        "Usage: E1KJvmKt <fixturesDir> <libDir> <sampleIrKlibPath> <sampleNoInlineKlibPath> <workTmpDir> <konanHome> <konanDataDir> <llvmHome>"
    }
    runE1KSuite(
        fixturesDir = args[0],
        libDir = args[1],
        sampleIrKlibPath = args[2],
        sampleNoInlineKlibPath = args[3],
        workTmpDir = args[4],
        konanHome = args[5],
        konanDataDir = args[6],
        llvmHome = args[7],
        ops = JvmPlatformOps,
    )
}
