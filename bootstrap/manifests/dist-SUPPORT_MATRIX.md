# Native-Hosted Kotlin/Native Toolchain Support Matrix

## Supported Host & Target
- **Host OS / Arch**: `linux_x64` (`x86_64-unknown-linux-gnu`, glibc >= 2.19)
- **Target OS / Arch**: `linux_x64` (`x86_64-unknown-linux-gnu`)
- **Upstream Baseline**: `9180ce6a7a7d3627f88b424717583f28ddca052d` (`2.4.20-dev-7885`)

## Supported Entrypoints & Modes
- `konanc`:
  - `-produce library` (`.kt` -> `.klib`, including multi-fragment HMPP `-Xfragments` / `-Xfragment-refines` / `-Xfragment-sources`)
  - `-produce program` (two-stage `.klib` -> `.kexe` and one-stage `.kt` -> `.kexe`)
  - `-produce static_cache` (`-Xadd-cache` / `-Xcache-directory`)
  - `-produce bitcode` / `-Xcompile-from-bitcode` / `-Xread-dependencies-from` / `-Xwrite-dependencies-to`
- `cinterop`:
  - Native-hosted C interoperability indexer (`libclang`) and stub generator (`.def` + `.h` -> interop `.klib` with C bridges)
- `klib`:
  - `info`, `contents`, `signatures`, `dump-abi`, `dump-ir`, `dump-ir-signatures`, `dump-metadata`, `dump-metadata-signatures`
- `defFileDependencies` & `generatePlatformLibraries`:
  - Native-hosted `.def` dependency graph resolution and platform library generation (`posix`, `builtin`, `iconv`, `linux`, `zlib`)

## External Native/C/C++ Dependencies (Explicitly Declared, Zero JVM)
- `llvm-21-x86_64-linux-dev-116` (`clang`, `clang++`, `llvm-ar`, `llvm-as`, `llvm-link`, `llvm-bcanalyzer`, `ld.lld`, `libclang.so.21.1`)
- `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2` (target sysroot & CRT objects)
- Host system shared libraries: `libc.so.6`, `libstdc++.so.6`, `libgmp.so.10`, `libicuuc.so.78`, `libicui18n.so.78`, `libicudata.so.78`, `libz.so.1`
