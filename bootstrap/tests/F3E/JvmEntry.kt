package nativekotlin.f3e

internal fun setLibraries(args: org.jetbrains.kotlin.cli.common.arguments.K2NativeCompilerArguments, vararg paths: String) {
    args.libraries = arrayOf(*paths)
}

fun main(args: Array<String>) {
    require(args.size == 3) {
        "Expected 3 arguments: <kotlin-home> <fixtures-dir> <out-klib-dir>, got ${args.size}"
    }
    for (line in runAllF3ECases(args[0], args[1], args[2])) {
        println(line)
    }
}
