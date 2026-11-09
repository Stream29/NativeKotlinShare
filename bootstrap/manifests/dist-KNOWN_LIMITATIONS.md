# Known Limitations & Scope Boundaries

- **Host/Target Scope**: Validated and packaged for `linux_x64` host and target only. Apple (`macos_*`, `ios_*`), Windows (`mingw_x64`), and Android targets are excluded in this milestone.
- **Compiler Plugins & Gradle**: Arbitrary JVM-hosted third-party compiler plugins (`ServiceLoader` JARs) and the Gradle build ecosystem are out of scope for the standalone Native CLI.
- **Scripting & REPL**: Kotlin Scripting (`-script`) and interactive REPL (`-Xrepl`) are disabled in `NativeKlibCliPipeline`.
- **Low-Memory Resource Profile**: Under a strict `1536 MiB` cgroup `MemoryHigh` ceiling (`1` CPU), large monolithic binaries and `stdlib-cache` use the 3-step bitcode emission (`-Xdisable-phases=...ObjectFiles,Linker`) + `clang++ -cc1 -emit-obj -O0` + linker invocation recipe.
