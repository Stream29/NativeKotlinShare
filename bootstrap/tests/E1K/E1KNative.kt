package nativekotlin.e1k.test

import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.library.abi.AbiReadingFilter
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbi
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import org.jetbrains.kotlin.library.abi.parser.KlibDumpParser
import org.jetbrains.kotlin.library.abi.parser.ParseException
import org.jetbrains.kotlin.resolve.konan.platform.NativeCliVisibilityConfiguration
import org.jetbrains.kotlin.resolve.konan.platform.installNativeDescriptorServices

private object NativePlatformOps : E1KPlatformOps {
    override fun exists(path: String): Boolean = File(path).exists

    override fun isRegularFile(path: String): Boolean = File(path).isFile

    override fun fileSize(path: String): Long = File(path).readBytes().size.toLong()

    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(text.encodeToByteArray())
    }

    override fun readText(path: String): String = File(path).readBytes().decodeToString()

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
        "Usage: e1k-test.kexe <fixturesDir> <libDir> <sampleIrKlibPath> <sampleNoInlineKlibPath> <workTmpDir> <konanHome> <konanDataDir> <llvmHome>"
    }
    installNativeDescriptorServices(NativeCliVisibilityConfiguration.standardNativeCli())
    runE1KSuite(
        fixturesDir = args[0],
        libDir = args[1],
        sampleIrKlibPath = args[2],
        sampleNoInlineKlibPath = args[3],
        workTmpDir = args[4],
        konanHome = args[5],
        konanDataDir = args[6],
        llvmHome = args[7],
        ops = NativePlatformOps,
    )
}
