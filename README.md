# NativeKotlinShare — Running the Kotlin/Native Compiler (`konanc`) Purely on Kotlin/Native (`0` JVM)

Can the Kotlin/Native compiler (`konanc`, `cinterop`, `klib`) run as a **100% self-hosted native binary** without any JVM, JNI, or Java bytecode — and what happens to compilation speed, CPU usage, and memory footprint when you strip away the JVM?

This repository contains the complete source port ([`./kotlin`](./kotlin), based on upstream [`JetBrains/kotlin` @ `9180ce6a7a7d3627f88b424717583f28ddca052d`](./kotlin), `2.4.20-dev-7885` + LLVM `21.1`), prebuilt whole-program optimized native compiler distributions for **4 host platforms (`linux_x64`, `linux_arm64`, `macos_arm64`, `mingw_x64`)**, and reproducible benchmarks comparing the official JVM-hosted compiler against the self-hosted Native compiler.

---

## Part 1 — The Idea & Prebuilt Native Distributions

### Why Test a Purely Native `konanc`?

Official Kotlin/Native compiles Kotlin code into native executables, yet the compiler itself (`konanc`, `cinterop`, `klib`) runs on top of the JVM and bridges into native LLVM (`libllvmext`) and Clang (`libclangext`) libraries through JNI. In real-world native builds, that architecture introduces three recurring costs:

1. **JVM Startup & JIT Warmup on Short-Lived Tasks**: Tools like `cinterop`, `klib`, and incremental module builds pay hundreds of milliseconds of classloading, JIT compilation, and JNI marshaling before doing useful work.
2. **Thread Storm & Context-Switch Overhead in Parallel Multi-Module Builds**: Running `-j8` or `-j16` compiler invocations in parallel spawns dozens of JVM instances, each with its own JIT compiler threads (`C1`/`C2`) and GC threads competing for CPU cores.
3. **Heap & Metadata Footprint**: Every JVM compiler process carries HotSpot metaspace, code cache, and GC heap overhead even on small or medium modules.

Our goal was straightforward:
- **Keep 100% of upstream Kotlin's compiler algorithms intact** — the exact same IntelliJ PSI / LightTree parser, K2 FIR frontend, KLIB serializer/deserializer, Native IR lowerings, whole-program optimizer (`-opt`), and LLVM 21 code generator.
- **Replace only the JVM host layer** (`java.io`, `java.nio`, `java.util.zip`, reflection, `ServiceLoader`, and JNI bridges) with direct Kotlin/Native POSIX/Win32 syscalls, `zlib`, GMP, and `kotlinx.cinterop` bindings.
- **Compile `konanc` with itself using full whole-program optimization (`-opt` + LLVM `-O3` + CMS GC + `latin1Strings` + `cCallMode=directOrIndirect`, zero `-ea` overhead)** and Native-specific host/runtime optimizations (zero-copy `protoc`-style Protobuf messages, zero-copy `zlib` slice inflate + hardware `crc32`, unthrottled CLI CMS heap, pure-Kotlin `@ThreadLocal` locks, and ASCII `< 128` lookup tables) — then measure head-to-head against HotSpot JDK 21 across every compiler workload.

### Prebuilt Whole-Program Optimized Distributions ([`v2.4.21-native`](https://github.com/Stream29/NativeKotlinShare/releases/tag/v2.4.21-native))

All four archives below are built with **whole-program Kotlin/Native IR optimization (`-opt`, zero `-ea` assertion overhead), Concurrent Mark-and-Sweep GC (`-Xbinary=gc=cms`) with an unthrottled CLI heap policy, compact Latin-1 strings (`-Xbinary=latin1Strings=true`), direct C interop (`-Xbinary=cCallMode=directOrIndirect`), full K/N LLVM bitcode LTO passes, LLVM `-O3` codegen, and symbol stripping**, and have **zero JVM dependency**. On first run, `bin/konanc` (or `cinterop`, `klib`, `kotlinc-native`) automatically checks and initializes the official LLVM 21 / sysroot dependencies under `${KONAN_DATA_DIR:-~/.konan}/dependencies`.

| Host Platform | Release Archive | Archive Size | Archive SHA-256 | `konanc-cli` Binary Size & SHA-256 |
| :--- | :--- | :---: | :--- | :--- |
| **Linux x86_64** (`linux_x64`) | [`native-kotlin-dist-linux-x64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-x64.tar.gz) | `86.6 MiB` (`90,786,415 B`) | `deca6a257bdda7cbd0f7e33cb7d973ce8dbe3f1364f95f944fb359bd1f574b96` | `98.0 MiB` (`102,784,864 B`)<br>`bb81733f0db40fe23316854bb35d35be945c007929746cf37edae8c67465d566` |
| **Linux ARM64** (`linux_arm64`) | [`native-kotlin-dist-linux-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-arm64.tar.gz) | `112.8 MiB` (`118,238,496 B`) | `c327bbc1c180f0f90a130599d4619b71c756a35bfdcb2e6efc57559a410cbf54` | `33.2 MiB` (`34,765,112 B`)<br>`aa3da2e13038a39ed94ba2b8ec333a5d1a6e694b3888f1a203d4df91513dbf58` |
| **macOS Apple Silicon** (`macos_arm64`) | [`native-kotlin-dist-macos-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-macos-arm64.tar.gz) | `112.3 MiB` (`117,788,793 B`) | `81e7479f711cd9b437c13dbab8b019a8e2b32aeee64069598c59cf30d40abb66` | `116.7 MiB` (`122,318,656 B`)<br>`785ede6ff30df9bc64dc8843ff1075129f4d3396d7fbed6bec8990b461712487` |
| **Windows x64** (`mingw_x64`) | [`native-kotlin-dist-windows-x64.zip`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-windows-x64.zip) | `31.7 MiB` (`33,259,021 B`) | `feb56a74e99f3ba0f9aab349936811601646dc9fabf30596c340704b8c22281d` | `56.4 MiB` (`59,118,080 B`)<br>`ef8ef1dce23d1abc45756e2146ed8548a735f6088180611b56fcca83b840f26f` |

---

## Part 2 — Benchmarks vs. JVM (with Real Build Commands)

### Benchmark Environment & Methodology

- **Hardware**: `12th Gen Intel Core i5-12500H` (4P + 8E cores / 16 threads, 16 GiB RAM, Ubuntu Linux `x86_64`).
- **Compilers Under Test**:
  - **Official JVM `konanc` (`stage0`)**: Upstream `:kotlin-native:dist` (`9180ce6a7a`) running on **Eclipse Temurin JDK 21.0.12** (`-Xmx4G` on 16-Thread Desktop; `-Xmx1536M -XX:ActiveProcessorCount=1` on Single-Core).
  - **Self-Hosted Native `konanc`**: `bin/konanc-cli.kexe` (`98.0 MiB`, built with whole-program `-opt` + CMS GC + `latin1Strings` + `cCallMode=directOrIndirect` + full K/N LLVM bitcode passes + LLVM `-O3` + stripped symbols, zero `-ea`), running with `0` JVM processes (`env -i` clean environment).
- **Hardware Profiles**:
  - **Desktop (16 Threads)**: Unpinned full-machine execution.
  - **Single-Core (1 Thread)**: Pinned to a single physical core via `taskset -c 0` (with `-XX:ActiveProcessorCount=1` for JVM) to measure pure single-core instruction efficiency without background JIT/GC thread offloading.
- **Metrics Collected**: Wall-clock time (`s`), total CPU time (`user + sys` in `s`), peak resident set size (`Max RSS` in `MiB`), and OS context switches (`voluntary + involuntary` from `/usr/bin/time -v`).

---

### Case 1 (Headline Workload): Full Compiler Self-Bootstrap (Compiling `konanc` with Itself)

The heaviest workload in this repository is compiling and linking the Kotlin/Native compiler's own codebase (**`3,430` production source files organized into `55` compiler KLIB modules**). We measure three representative stages of the self-bootstrap:
- **Case 1A**: Compiling the largest compiler backend module (`b1k-backend-native.klib`, `197` Kotlin source files) against `51` upstream compiler KLIB dependencies.
- **Case 1B**: Compiling the compiler CLI entrypoint module (`e1c-cli-native.klib`, `9` Kotlin source files) against all `54` compiler KLIBs.
- **Case 1C**: Linking all `55` compiler KLIBs (`3,430` source files) plus the entrypoint into the self-hosted `konanc-cli.kexe` binary using prebuilt static caches (`-Xcache-directory=...`).

#### Case 1A — Compiling the Largest Compiler Backend Module (`b1k-backend-native.klib`, 197 `.kt` files)

```bash
# Real Build Command (identical flags for both JVM konanc and Native konanc-cli.kexe)
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -produce library \
  -module-name org.jetbrains.kotlin.native.b1k-backend-native \
  -XXLanguage:+NameBasedDestructuring \
  -Xnested-type-aliases \
  -Xcontext-parameters \
  -Xmulti-platform \
  -Xexpect-actual-classes \
  -Xallow-kotlin-package \
  -Xsuppress-version-warnings \
  -Xconsistent-data-class-copy-visibility \
  -opt-in=org.jetbrains.kotlin.K1Deprecation \
  -opt-in=org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi \
  -opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi \
  -opt-in=org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI \
  -opt-in=org.jetbrains.kotlin.ir.InternalSymbolFinderAPI \
  -opt-in=org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI \
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi \
  -opt-in=kotlinx.cinterop.BetaInteropApi \
  -opt-in=kotlin.experimental.ExperimentalNativeApi \
  $(cat b1k-51-dependency-klibs.args) \
  -friend-modules "$FRIEND_KLIBS_COLON_SEPARATED" \
  $(cat porting/B1K/native-sources.list) \
  -o build/b1k-backend-native.klib
```

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native vs. JVM |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `20.666s` | `40.290s` | `862.5 MiB` | `128,894` | Baseline (warmed C2 JIT + parallel GC over 197 files) |
| **Desktop (16T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`12.437s`** | **`16.280s`** | `2,863.8 MiB` | **`267`** | **`1.66x` faster wall, `2.47x` less CPU, `483x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `30.695s` | `30.495s` | `754.5 MiB` | `112,937` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`16.221s`** | **`16.105s`** | `2,787.1 MiB` | **`3,404`** | **`1.89x` faster wall, `1.89x` less CPU, `33.2x` fewer ctx switches** |

#### Case 1B — Compiling the Compiler CLI Entrypoint Module (`e1c-cli-native.klib`, 9 `.kt` files against 54 KLIBs)

```bash
# Real Build Command
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -produce library \
  -module-name org.jetbrains.kotlin.native.e1c-cli-native \
  -XXLanguage:+NameBasedDestructuring \
  -Xcontext-parameters \
  -opt-in=org.jetbrains.kotlin.K1Deprecation \
  -opt-in=org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi \
  -opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi \
  -opt-in=org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI \
  -opt-in=org.jetbrains.kotlin.ir.InternalSymbolFinderAPI \
  -opt-in=org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI \
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi \
  -opt-in=kotlin.ExperimentalUnsignedTypes \
  -opt-in=kotlin.experimental.ExperimentalNativeApi \
  $(cat e1c-54-compiler-klibs.args) \
  $(cat bootstrap/manifests/native-sources.list) \
  -o build/e1c-cli-native.klib
```

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native vs. JVM |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `4.551s` | `10.390s` | `404.8 MiB` | `85,980` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`1.054s`** | **`1.230s`** | `561.0 MiB` | **`43`** | **`4.32x` faster wall, `8.45x` less CPU, `2,000x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `7.715s` | `7.650s` | `351.9 MiB` | `60,842` | Baseline (JIT compilation shares the single core) |
| **Single-Core (1T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`1.261s`** | **`1.240s`** | `559.7 MiB` | **`269`** | **`6.12x` faster wall, `6.17x` less CPU, `226x` fewer ctx switches** |

#### Case 1C — Linking the Full Self-Hosted Compiler Executable (`konanc-cli.kexe`, 55 KLIBs / 3,430 files with Static Caches)

```bash
# Real Build Command
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -g \
  -Xpartial-linkage-loglevel=error \
  -XXLanguage:+NameBasedDestructuring \
  -Xcontext-parameters \
  -opt-in=kotlinx.cinterop.ExperimentalForeignApi \
  -opt-in=kotlin.ExperimentalUnsignedTypes \
  -opt-in=kotlin.experimental.ExperimentalNativeApi \
  $(cat all-55-compiler-klibs.args) \
  -Xcache-directory="$STAGE_CACHE_DIR" \
  bootstrap/tests/E1/E1Cases.kt \
  bootstrap/tests/E1/E1Native.kt \
  -entry nativekotlin.e1.test.main \
  -o build/konanc-cli-linked.kexe
```

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native vs. JVM |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `9.126s` | `19.040s` | `1,422.8 MiB` | `92,367` | Links 55 cached compiler KLIBs + 2 entrypoint `.kt` files |
| **Desktop (16T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`4.598s`** | **`6.870s`** | `1,503.1 MiB` | **`10,094`** | **`1.98x` faster wall, `2.77x` less CPU, `9.2x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `13.945s` | `13.845s` | `1,420.8 MiB` | `74,409` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`5.888s`** | **`5.840s`** | `1,499.2 MiB` | **`1,688`** | **`2.37x` faster wall, `2.37x` less CPU, `44.1x` fewer ctx switches** |

---

### Case 2: Realistic End-to-End Application Build & Runtime

This case models a typical Kotlin/Native application workflow:
- **Case 2A**: Compiling a core library (`D1CoreLib.kt -> d1-core.klib`).
- **Case 2B**: Building a debug executable (`-g`, `D1CoreLib.kt + D1Cases.kt + D1Native.kt -> d1-corpus.kexe` linked against `d1cbridge.klib` and `stdlib-cache`).
- **Case 2C**: Building a release executable with whole-program optimization (`-opt`, `OptBench.kt -> opt-bench.kexe`).
- **Case 2D**: Running the compiled `opt-bench.kexe` binary head-to-head against `java -jar OptBench.jar` on Temurin JDK 21 (`OptBench.kt` runs a 3-stage compiler-style workload: AST folding + lexical scope resolution + value-class matrix kernel).

#### Case 2A — Application Library Build (`D1CoreLib.kt -> d1-core.klib`)

```bash
# Real Build Command
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -produce library \
  bootstrap/tests/D1/D1CoreLib.kt \
  -o build/d1-core.klib
```

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native vs. JVM |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `1.883s` | `3.030s` | `249.2 MiB` | `18,515` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`0.132s`** | **`0.130s`** | **`205.7 MiB`** | **`13`** | **`14.27x` faster, `23.31x` less CPU, `-17.5%` RAM, `1,424x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `2.709s` | `2.680s` | `269.4 MiB` | `25,071` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`0.141s`** | **`0.130s`** | **`205.9 MiB`** | **`39`** | **`19.21x` faster, `20.62x` less CPU, `-23.6%` RAM, `643x` fewer ctx switches** |

#### Case 2B — Debug Application Executable Build (`-g`, `d1-corpus.kexe` with `stdlib-cache`)

```bash
# Real Build Command
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -g \
  -library build/d1cbridge.klib \
  -Xcache-directory="$STDLIB_CACHE_DIR" \
  bootstrap/tests/D1/D1CoreLib.kt \
  bootstrap/tests/D1/D1Cases.kt \
  bootstrap/tests/D1/D1Native.kt \
  -entry nativekotlin.d1.main \
  -o build/d1-corpus.kexe
```

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native vs. JVM |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `4.783s` | `10.100s` | `462.3 MiB` | `80,332` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`0.896s`** | **`1.020s`** | **`428.4 MiB`** | **`615`** | **`5.34x` faster wall, `9.90x` less CPU, `-7.3%` RAM, `131x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `7.880s` | `7.820s` | `405.1 MiB` | `65,234` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`1.030s`** | **`1.010s`** | `430.8 MiB` | **`525`** | **`7.65x` faster wall, `7.74x` less CPU, `124x` fewer ctx switches** |

#### Case 2C — Release Application Executable Build (`-opt`, `OptBench.kt -> opt-bench.kexe`)

```bash
# Real Build Command
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -opt \
  OptBench.kt \
  -o build/opt-bench.kexe
```

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native vs. JVM |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `12.550s` | `18.510s` | `651.3 MiB` | `92,942` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`8.195s`** | **`9.140s`** | `790.8 MiB` | **`720`** | **`1.53x` faster wall, `2.03x` less CPU, `129x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `15.875s` | `15.765s` | `530.1 MiB` | `66,301` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `konanc` (`-opt -O3`)** | **`9.236s`** | **`9.170s`** | `791.7 MiB` | **`1,580`** | **`1.72x` faster wall, `1.72x` less CPU, `42.0x` fewer ctx switches** |

#### Case 2D — Compiled Application Binary Runtime (`opt-bench.kexe` vs. JDK 21 `OptBench.jar`)

```bash
# Build & Run on JDK 21 vs. Native konanc -opt
java -cp "$KOTLIN_JVM_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler OptBench.kt -include-runtime -d build/OptBench.jar
java -Xmx4G -jar build/OptBench.jar
./build/opt-bench.kexe
```

Both binaries produce the exact same deterministic checksum (`CHECKSUM=5584724895034333103`):

| Profile | Runtime | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Sub-Stage Breakdown (`AST` / `Scope` / `Kernel` / `Total`) |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Temurin JDK 21 (`java -jar OptBench.jar`) | `0.249s` | `0.290s` | `55.1 MiB` | `487` | `9.62ms` / `37.14ms` / `45.53ms` / `92.04ms` |
| **Desktop (16T)** | **Native `opt-bench.kexe` (`konanc -opt`)** | **`0.200s` (`1.25x` faster)** | **`0.190s` (`1.53x` less)** | **`5.6 MiB` (`9.8x` less RAM)** | **`8` (`61x` fewer)** | **`8.63ms`** / `44.32ms` / **`45.06ms`** / `98.31ms` |
| **Single-Core (1T)** | Temurin JDK 21 (`java -jar OptBench.jar`) | `0.270s` | `0.260s` | `46.5 MiB` | `539` | `10.23ms` / `35.84ms` / `44.45ms` / `92.97ms` |
| **Single-Core (1T)** | **Native `opt-bench.kexe` (`konanc -opt`)** | **`0.203s` (`1.33x` faster)** | **`0.190s` (`1.37x` less)** | **`5.8 MiB` (`8.0x` less RAM)** | **`19` (`28x` fewer)** | **`8.91ms`** / `44.37ms` / `45.57ms` / `98.52ms` |

---

### Case 3: Realistic Multi-Module Parallel Project Build (`-j1, -j4, -j8, -j16`)

In real Gradle/Bazel/CI builds, a Kotlin/Native project compiles multiple independent C-interop bindings and Kotlin library modules concurrently. Here we build a **16-module native project** (**8 `cinterop` C libraries + 8 Kotlin libraries**) across `-j1`, `-j4`, `-j8`, and `-j16` parallel workers:

```bash
# Real Build Commands Executed Across 16 Modules (m01..m08 via cinterop, k01..k08 via konanc -produce library)
# 1. 8 C-Interop Modules (m01..m08):
cinterop \
  -target linux_x64 \
  -Xkonan-data-dir "$KONAN_DATA_DIR" \
  -def modules/c_m01.def \
  -compiler-option -Imodules/include \
  -o build/c_m01.klib

# 2. 8 Kotlin Library Modules (k01..k08):
konanc \
  -kotlin-home "$DIST" \
  -Xkonan-data-dir="$KONAN_DATA_DIR" \
  -target linux_x64 \
  -produce library \
  modules/Mod01.kt \
  -o build/k_m01.klib
```

| Concurrency | Compiler | Wall Time (`s`) | Throughput (`mod/s`) | Total CPU (`s`) | Peak Worker RSS (`MiB`) | Total Context Switches | Native Advantage |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **`-j1`** | Official JVM `konanc` | `22.993s` | `0.70 mod/s` | `33.81s` | `295.3 MiB` | `320,728` | Baseline |
| **`-j1`** | **Self-Hosted Native `konanc`** | **`1.583s`** | **`10.11 mod/s`** | **`1.44s`** | **`207.0 MiB`** | **`313`** | **`14.52x` faster wall, `23.48x` less CPU, `-29.9%` RAM, `1,025x` fewer ctx switches** |
| **`-j4`** | Official JVM `konanc` | `8.521s` | `1.88 mod/s` | `49.20s` | `290.6 MiB` | `355,001` | Baseline |
| **`-j4`** | **Self-Hosted Native `konanc`** | **`0.489s`** | **`32.72 mod/s`** | **`1.78s`** | **`207.0 MiB`** | **`1,033`** | **`17.43x` faster wall, `27.64x` less CPU, `-28.8%` RAM, `344x` fewer ctx switches** |
| **`-j8`** | Official JVM `konanc` | `6.062s` | `2.64 mod/s` | `65.95s` | `271.8 MiB` | `324,256` | Baseline |
| **`-j8`** | **Self-Hosted Native `konanc`** | **`0.364s`** | **`43.96 mod/s`** | **`2.43s`** | **`206.6 MiB`** | **`2,259`** | **`16.65x` faster wall, `27.14x` less CPU, `-24.0%` RAM, `144x` fewer ctx switches** |
| **`-j16`** | Official JVM `konanc` | `5.504s` | `2.91 mod/s` | `74.72s` | `255.7 MiB` | `436,901` | 16 JVMs spawn hundreds of C1/C2 JIT & GC threads |
| **`-j16`** | **Self-Hosted Native `konanc`** | **`0.322s`** | **`49.69 mod/s`** | **`3.12s`** | **`206.8 MiB`** | **`2,738`** | **`17.09x` faster wall, `23.95x` less CPU, `-19.1%` RAM, `160x` fewer ctx switches** |

---

### Case 4: Everyday Developer & CI Tooling (`cinterop` & `klib dump-abi`)

#### Case 4A — Generating C-Interop Bindings (`cinterop` on `d1cbridge.def`)

```bash
# Real Build Command
cinterop \
  -target linux_x64 \
  -Xkonan-data-dir "$KONAN_DATA_DIR" \
  -def bootstrap/tests/D1/c/d1cbridge.def \
  -compiler-option -Ibootstrap/tests/D1/c \
  -o build/d1cbridge.klib
```

| Profile | Tool | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native Speedup |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `cinterop` (JDK 21) | `0.969s` (`969 ms`) | `1.190s` | `161.2 MiB` | `21,722` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `cinterop`** | **`0.071s` (`71 ms`)** | **`0.060s`** | **`119.8 MiB`** | **`23`** | **`13.65x` faster wall, `19.83x` less CPU, `-25.7%` RAM, `944x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `cinterop` (JDK 21) | `1.209s` (`1,209 ms`) | `0.880s` | `191.4 MiB` | `10,635` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `cinterop`** | **`0.057s` (`57 ms`)** | **`0.040s`** | **`120.4 MiB`** | **`40`** | **`21.21x` faster wall, `22.00x` less CPU, `-37.1%` RAM, `266x` fewer ctx switches** |

#### Case 4B — Inspecting KLIB ABI Signatures (`klib dump-abi`)

```bash
# Real Command
klib dump-abi build/e1c-cli-native.klib -signature-version 2
```

| Profile | Tool | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native Speedup |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `klib` (JDK 21) | `0.204s` (`204 ms`) | `0.330s` | `68.3 MiB` | `3,035` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `klib`** | **`0.024s` (`24 ms`)** | **`0.010s`** | `93.0 MiB` | **`6`** | **`8.50x` faster wall, `33.00x` less CPU, `506x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `klib` (JDK 21) | `0.308s` (`308 ms`) | `0.290s` | `65.8 MiB` | `3,846` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `klib`** | **`0.021s` (`21 ms`)** | **`0.010s`** | `93.1 MiB` | **`11`** | **`14.67x` faster wall, `29.00x` less CPU, `350x` fewer ctx switches** |

---

### Summary of Benchmark Takeaways

1. **Self-Hosted Native `konanc` Wins Every Single Benchmark (`10 / 10` Cases, Both 16-Thread Desktop and Single-Core)**:
   - **Largest Single-Process Compiler Backend Build (`Case 1A`, `b1k-backend-native.klib`, 197 files / 50,355 LOC)**: **`1.66x` faster wall time on Desktop (`12.437s` vs `20.666s`, `2.47x` less CPU)** and **`1.89x` faster on Single-Core (`16.221s` vs `30.695s`)** — beating warmed HotSpot C2 JIT across every compiler phase (`INIT`, `ANALYZE`, `FIR2IR`, `IR PRE-LOWERING`, `IR SERIALIZATION`, and `KLIB WRITING`).
   - **Full Compiler Cached Link & Whole-Program `-opt` Release Builds (`Case 1C` & `Case 2C`)**: **`1.98x–2.37x` faster** linking the 55-module compiler (`4.598s` vs `9.126s` Desktop; `5.888s` vs `13.945s` Single-Core, `2.77x` less CPU), and **`1.53x–1.72x` faster** on full whole-program `-opt` release builds (`8.195s` vs `12.550s` Desktop; `9.236s` vs `15.875s` Single-Core, `2.03x` less CPU).
   - **Application Library & Debug Executable Builds (`Case 1B`, `Case 2A`, `Case 2B`)**: **`14.27x–19.21x` faster** on `d1-core.klib` (`132 ms` vs `1,883 ms`, `23.3x` less CPU), **`4.32x–6.12x` faster** on `e1c-cli-native.klib` (`1.054s` vs `4.551s` against 54 compiler KLIBs, `8.45x` less CPU), and **`5.34x–7.65x` faster** on full debug `.kexe` builds (`0.896s` vs `4.783s`, `9.90x` less CPU).
   - **Parallel Multi-Module Project Builds (`Case 3`, `-j1` to `-j16`)**: **`14.52x` to `17.43x` faster wall time** (**`49.69 mod/s` vs `2.91 mod/s` at `-j16` — `0.322s` vs `5.504s`!**), **`23.5x` to `27.6x` less total CPU core-seconds** (`3.12s` vs `74.72s` at `-j16`), **`19%–30%` lower peak RSS per worker**, and **`144x` to `1,025x` fewer OS context switches**.
   - **Developer & CI Tooling (`Case 4A` `cinterop`, `Case 4B` `klib dump-abi`)**: **`8.50x` to `21.21x` faster** (`57–71 ms` vs `969–1,209 ms` for `cinterop`; `21–24 ms` vs `204–308 ms` for `klib dump-abi`).
2. **Memory vs. Speed Trade-off on Large Single-Process Compiles**:
   - On short-lived and multi-module builds (`Cases 2A, 2B, 3, 4A`), Native `konanc` uses **`7%–37%` less peak RSS** than JVM (`120–207 MiB` vs `161–295 MiB`).
   - On heavy single-process builds (`Case 1A` 197-file backend and `Case 2C` `-opt`), configuring Kotlin/Native's CMS GC as an unthrottled batch CLI collector (`targetHeapUtilization = 0.35`, `pauseOnTargetHeapOverflow = false`) trades higher single-process RSS (`2.80 GiB` vs `863 MiB` on `Case 1A`) to eliminate repeated GC mark cycles during transient AST/FIR/IR graph construction — cutting `Case 1A` wall time from `52.343s` down to **`12.437s` (`4.21x` speedup)**.

---

## Part 3 — How We Ported `konanc` to Kotlin/Native

All source changes live in the [`./kotlin`](./kotlin) Git submodule on top of upstream commit `9180ce6a7a7d3627f88b424717583f28ddca052d` (`2.4.20-dev-7885`). You can inspect the 6-layer commit stack directly:

```bash
git clone --recurse-submodules https://github.com/Stream29/NativeKotlinShare.git
cd NativeKotlinShare/kotlin
git log --oneline 9180ce6a7a7d3627f88b424717583f28ddca052d..HEAD
```

### 1. The 6-Layer Commit Stack in `./kotlin`

| Layer | Commit | Scope | What We Changed |
| :---: | :---: | :--- | :--- |
| **1** | `409b35c06` | `core/*`, `compiler/config`, `compiler/util*`, `native/unsafe-mem`, `native/utils`, `kotlin-native/klib` | **Host Shims & Foundations**: Replaced `java.io`, `java.nio`, `java.util.zip`, `java.math.BigInteger`, and `java.text.Normalizer` with pure Kotlin/Native POSIX/Win32 file & VFS operations, in-memory `zlib` (`inflateInit2_` / `deflateInit2_`) ZIP KLIB containers, GMP-backed `BigInteger`, and C/Win32 Unicode NFC normalization. |
| **2** | `5ff5c12f0` | `kotlin-native/{llvmInterop,libllvmext,llvmDebugInfoC,libclangInterop,libclangext,Interop}` | **Native LLVM 21 & Clang Bindings**: Replaced JNI bridges with direct `kotlinx.cinterop` bindings for **965 LLVM C/C++ functions** (`llvm.klib` + `libllvmext.a` + `libdebugInfo.a`) and **378 `libclang` functions** (`clang.klib` + `libclangext.a`), enabling a 100% native `cinterop` indexer and stub generator. |
| **3** | `6181704ed` | `compiler/psi/*`, `compiler/container/*`, `compiler/frontend.common*` | **IntelliJ PSI, LightTree & DI Container**: Adapted the JFlex Kotlin lexer, LightTree/PSI parser, and IntelliJ `ComponentManager` / DI container to run on Kotlin/Native by replacing JVM bytecode reflection and `ServiceLoader` discovery with deterministic static service registration. |
| **4** | `69c0a3fe8` | `compiler/fir/*`, `compiler/resolution.common`, `compiler/serialization`, `compiler/cli/cli-native-klib` | **K2 FIR Frontend & KLIB Serializer**: Connected the full K2 FIR tree, Cone type system, symbol providers, call/type resolution, FIR checkers, `fir2ir`, and protobuf KLIB metadata/IR serializer (`3,430` total production files across `55` compiler KLIB modules). |
| **5** | `e2ffce2c0` | `compiler/ir/*`, `native/{frontend,base}`, `kotlin-native/backend.native` | **Native IR Backend, Whole-Program `-opt`, & Static Caches**: Ported IR deserialization, IR inlining, all Native IR lowerings, whole-program optimizations (`Devirtualization`, `EscapeAnalysis`, `UnboxInline`, `DCE`), LLVM bitcode emission, and `static_cache` generation. |
| **6** | `cc2e37cc2` | `compiler/cli/cli-base`, `native/cli-native`, `kotlin-native/{utilities,runtime,platformLibs}` | **Zero-JVM Multi-Call CLI & Cross-Host Recipes**: Replaced reflection-based CLI argument parsing with a table-driven parser and unified `konanc`, `kotlinc-native`, `cinterop`, and `klib` into a single multi-call native binary (`konanc-cli.kexe` / `konanc-cli.exe`). |

### 2. Two Key Technical Challenges Solved During the Port

1. **Eliminating Side-Effecting `assert(...)` Calls for Clean Zero-`-ea` Whole-Program `-opt`**:
   - When we first compiled `konanc-cli.kexe` itself with `-opt` (without `-ea`), the resulting compiler worked for `.kt -> .klib` and `cinterop`, but failed during `LinkKlibsPhase` (`-p program`) with `IrClassSymbolImpl is unbound. Signature: kotlin.internal/IntrinsicConstEvaluation|null[0]`.
   - Tracing `LinkKlibsPhase` and `PartialLinkageSupportForLinkerImpl` uncovered upstream state-mutating expressions placed directly inside `kotlin.assert(...)` in [`SymbolTableSlice.kt`](./kotlin/compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/util/SymbolTableSlice.kt) and [`PartiallyLinkedIrTreePatcher.kt`](./kotlin/compiler/ir/serialization.common/src/org/jetbrains/kotlin/backend/common/linkage/partial/PartiallyLinkedIrTreePatcher.kt):
     ```kotlin
     assert(unboundSymbols.add(new)) { "Symbol for ${new.signature} was already referenced" }
     assert(stack.removeFirst() === declarationContext)
     ```
   - On the JVM, `kotlin.assert(condition)` evaluates its inline argument unless bytecode assertions are stripped; on Kotlin/Native, `kotlin.assert` is backed by `AssertionCompilerIntrinsic`, which **completely eliminates the `condition` expression from the IR when `-ea` is disabled**! Extracting `val added = unboundSymbols.add(new)` and `val removedContext = stack.removeFirst()` before `assert(...)` (alongside restoring full `ObjCExport` selector/hash bridges) enabled clean zero-`-ea` whole-program `-opt` builds across all 4 host platforms without paying runtime assertion overhead across 55 compiler modules.
2. **Parallelizing Whole-Program `-opt` Codegen (`llvm-split` + `ld.lld -r` + Symbol Localization)**:
   - Whole-program `-opt` + Kotlin/Native's LLVM bitcode passes (`ModuleBitcodeOptimization`, `LTOBitcodeOptimization`, `RemoveRedundantSafepoints`) on the 55-module compiler emits a `96 MiB` optimized LLVM bitcode module (`out.bc` on `linux_x64`). Compiling `out.bc` with a single-threaded `clang++ -O3` takes over 10 minutes.
   - Splitting `out.bc` into 16 partitions with `llvm-split -j=16` reduced `-O3` codegen time to **~60 seconds** across 16 cores. However, `llvm-split` promotes `internal` C++ helper symbols (from the merged Kotlin/Native `runtime.bc` / `llvm.bc`) to global visibility so partitions can call each other, which then collides (`duplicate symbol`) with the same C++ symbols in `libllvm_support.a` and LLVM static archives during final linking.
   - We solved this cleanly by pre-linking the 16 compiled partitions into a single relocatable object (`ld.lld -r -o konanc-kotlin.o`) so all cross-partition references resolve internally, and then running `llvm-objcopy --localize-symbols` on the promoted `_Z*` symbols before the final `--gc-sections --strip-all` link.

### 3. Six Host, Runtime & CMS GC Optimizations That Put Native Ahead of HotSpot JDK 21

An initial straight port compiled with `-opt -O3` already beat JVM on short-lived builds (`cinterop`, `klib`, small modules) by `2.4x–19.9x`, but trailed warmed HotSpot JDK 21 on 197-file `Case 1A` (`52.343s` vs `19.912s`) and `-opt` release builds (`18.311s` vs `12.835s`). Profiling the self-hosted binary revealed six host-layer and runtime bottlenecks whose removal sped up `Case 1A` by an additional **`4.21x` (`52.343s -> 12.437s`)** and `Case 3 (-j16)` by **`5.26x` (`1.693s -> 0.322s`)**:

1. **Direct `protoc`-Style Generated Protobuf Messages + Zero-Copy `ByteString` / `CodedInputStream`**: Replaced dynamic map/list field bags in `GeneratedMessageLite` with direct typed fields, bitmask presence words, cached serialized sizes, and zero-copy `ByteString` slice views for KLIB metadata and IR serialization/deserialization.
2. **Concurrent Mark-and-Sweep GC (`-Xbinary=gc=cms`) + Unthrottled Batch CLI Heap Policy**: Enabled Kotlin/Native's CMS collector and tuned `GC` startup parameters for batch compiler processes (`minHeapBytes = 256 MiB`, `targetHeapBytes = 512 MiB`, `targetHeapUtilization = 0.35`, `heapTriggerCoefficient = 0.90`, `pauseOnTargetHeapOverflow = false`), eliminating stop-the-world GC thrashing during large FIR/IR phases.
3. **Pure-Kotlin `@ThreadLocal` Token + Fast `AtomicReference` Reentrant Locks**: Replaced `pthread_mutex_t` + native `Cleaner` allocations in `LockBasedStorageManager`, `SingleThreadValue`, and IntelliJ storage locks with zero-syscall pure-Kotlin `@ThreadLocal` thread tokens and `AtomicReference` CAS fast paths.
4. **ASCII `< 128` Lookup Tables & Fast Lexer Character Support**: Added branchless `128`-entry ASCII lookup tables for `Character` / `String` case conversion and lexer whitespace/identifier scanning (`LexerCharacterSupport.kt`), reserving ICU/Unicode table lookups strictly for non-ASCII code points.
5. **Zero-Copy KLIB ZIP Slice Inflate + Hardware `zlib.crc32`**: Streamed raw ZIP entry byte ranges directly from the mapped archive buffer into `zlib` (`inflateRawSliceNative`) without per-entry slice copies, computed checksums via hardware-accelerated `zlib.crc32`, and removed redundant per-file `fsync` calls during KLIB writing (`1,084,978 loc/s` in `KLIB WRITING`).
6. **Compact Latin-1 Strings & Direct C Interop (`-Xbinary=latin1Strings=true` + `-Xbinary=cCallMode=directOrIndirect`)**: Halved memory bandwidth for ASCII compiler identifiers/signatures and removed unnecessary C-interop state transitions across LLVM/Clang/zlib calls.

### 4. Three-Stage Bootstrap Fixed-Point (`stage2 == stage3`) & Artifact Consistency Verification

1. **Three-Stage Self-Bootstrap Fixed-Point (`stage0 -> stage1 -> stage2 -> stage3`)**:
   - **`stage0` (Official JVM `konanc`)** compiles the 55 compiler KLIBs and links **`stage1/konanc-cli.kexe`**.
   - Under `env -i PATH=/nonexistent` (zero JVM on `PATH`), **`stage1`** recompiles the compiler sources to produce **`stage2/konanc-cli.kexe`**, and **`stage2`** recompiles the same sources to produce **`stage3/konanc-cli.kexe`**.
   - **Result**: `stage2/konanc-cli.kexe` and `stage3/konanc-cli.kexe` are **byte-for-byte identical** (`SHA-256: cdf4849a9f9a2a4892348a8b27face322c89f78dbfc8f99ccbbc1886701d025e`), and the KLIB ABI across `stage1`, `stage2`, and `stage3` (`klib dump-abi -signature-version 2`) has **zero diff** (`SHA-256: 5366cdb5bc967940024f28345e57a8787f9398bcbe8de9ff9d98278296cd8860`).
2. **Artifact Consistency Against Official `:kotlin-native:dist` (`9180ce6a7a`)**:
   - Compiling the 7-section `D1` verification corpus with both official JVM `konanc` and self-hosted Native `konanc` produces:
     - **100% identical KLIB internal layout** (`29/29` entries), `default/manifest`, `klib dump-abi`, `klib dump-ir`, `klib dump-metadata`, and `klib dump-metadata-signatures`.
     - **Byte-for-byte identical SHA-256 checksums** across all 16 serialized IR files (`default/ir/*` and `default/ir_inlinable_functions/*`) and `default/linkdata/module`.
     - **100% identical `.kexe` dynamic symbol tables (`nm -D`) and runtime outputs**.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
