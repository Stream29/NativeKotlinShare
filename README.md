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
- **Compile `konanc` with itself using full whole-program optimization (`-opt -ea` + LLVM `-O3`)** and measure where a pure Kotlin/Native compiler beats the JVM — and where HotSpot C2 JIT still holds an advantage.

### Prebuilt Whole-Program Optimized Distributions ([`v2.4.21-native`](https://github.com/Stream29/NativeKotlinShare/releases/tag/v2.4.21-native))

All four archives below are built with **whole-program Kotlin/Native IR optimization (`-opt -ea`), LLVM `-O3` codegen, and symbol stripping**, and have **zero JVM dependency**. On first run, `bin/konanc` (or `cinterop`, `klib`, `kotlinc-native`) automatically checks and initializes the official LLVM 21 / sysroot dependencies under `${KONAN_DATA_DIR:-~/.konan}/dependencies`.

| Host Platform | Release Archive | Archive Size | Archive SHA-256 | `konanc-cli` Binary Size & SHA-256 |
| :--- | :--- | :---: | :--- | :--- |
| **Linux x86_64** (`linux_x64`) | [`native-kotlin-dist-linux-x64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-x64.tar.gz) | `59.8 MiB` (`62,737,276 B`) | `a09b07c6a4cb95866d41c5aa0caa60f335ece5e0c9f3422a80a1d94bcf7969d5` | `94.5 MiB` (`99,105,392 B`)<br>`c00da3be262b0fe4a42ca1c8900dd33568bbbf34fa8efda2f6682f53be3284f1` |
| **Linux ARM64** (`linux_arm64`) | [`native-kotlin-dist-linux-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-arm64.tar.gz) | `117.1 MiB` (`122,764,806 B`) | `2eb5742d0a036c572bf37b649f8e5e2963ae013855c1739c77719e43ef8ccd2b` | `58.7 MiB` (`61,579,416 B`)<br>`b4b12c4e09063fccfc1acda57be2b710417ce41063d02581e6ea2b050a6a0f7a` |
| **macOS Apple Silicon** (`macos_arm64`) | [`native-kotlin-dist-macos-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-macos-arm64.tar.gz) | `115.0 MiB` (`120,537,133 B`) | `446af4e11bb4a733de6c8928ab1b3ff772fccf453fbb820bed022c7393d03740` | `131.8 MiB` (`138,167,024 B`)<br>`fbf06291134660aee35b3b1c0212172dc758c0215f3a4a8bed9250b281070955` |
| **Windows x64** (`mingw_x64`) | [`native-kotlin-dist-windows-x64.zip`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-windows-x64.zip) | `33.0 MiB` (`34,590,790 B`) | `84e40dbf4025cca16480a975b8fb30351607ce09325a456e7622c747c1eda582` | `75.7 MiB` (`79,360,512 B`)<br>`7b8fbcd158e3d2588232a96dcab6e976ea8fc1e319ea395429868ca70de839fa` |

---

## Part 2 — Benchmarks vs. JVM (with Real Build Commands)

### Benchmark Environment & Methodology

- **Hardware**: `12th Gen Intel Core i5-12500H` (4P + 8E cores / 16 threads, 16 GiB RAM, Ubuntu Linux `x86_64`).
- **Compilers Under Test**:
  - **Official JVM `konanc` (`stage0`)**: Upstream `:kotlin-native:dist` (`9180ce6a7a`) running on **Eclipse Temurin JDK 21.0.10+7** (`-Xmx4G` on 16-Thread Desktop; `-Xmx1536M -XX:ActiveProcessorCount=1` on Single-Core).
  - **Self-Hosted Native `konanc`**: `bin/konanc-cli.kexe` (`94.5 MiB`, built with whole-program `-opt -ea` + LLVM `-O3` + stripped symbols), running with `0` JVM processes (`env -i` clean environment).
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

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Notes |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `19.349s` | `37.555s` | `868.0 MiB` | `140,342` | C2 JIT warms up over 197 large backend files + parallel GC |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `51.840s` | `65.360s` | `1,379.8 MiB` | **`1,390` (`101x` fewer)** | **`3.05x` faster** than unoptimized `-O0` Native (`158.1s`) |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `30.863s` | `30.660s` | `676.7 MiB` | `115,273` | 197 files (`~30s`) is long enough to amortize C2 JIT |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `65.283s` | `64.880s` | `1,391.8 MiB` | **`13,407` (`8.6x` fewer)** | Pure AOT execution; K/N MM GC pause overhead on large IR graph |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `4.276s` | `9.740s` | `382.1 MiB` | `71,578` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`4.199s`** | **`5.230s`** | `530.7 MiB` | **`324`** | **Faster wall time, `1.86x` less CPU, `221x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `7.547s` | `7.490s` | `352.3 MiB` | `61,131` | Baseline (JIT compilation shares the single core) |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`5.336s`** | **`5.295s`** | `531.8 MiB` | **`1,673`** | **`1.41x` faster wall time, `1.41x` less CPU, `36.5x` fewer ctx switches** |

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

| Profile | Compiler | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Notes |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `8.157s` | `17.040s` | `1,073.2 MiB` | `120,028` | Links 55 cached compiler KLIBs + 2 entrypoint `.kt` files |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `26.317s` | `43.195s` | **`1,072.7 MiB`** | **`11,415` (`10.5x` fewer)** | Identical `1.07 GiB` peak RSS (`ld.lld` dominates memory) |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `13.674s` | `13.560s` | `1,072.3 MiB` | `75,911` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `42.697s` | `42.440s` | **`1,073.0 MiB`** | **`15,226` (`5.0x` fewer)** | Same peak RSS |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `1.789s` | `2.890s` | `247.7 MiB` | `19,122` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`1.579s`** | **`2.410s`** | **`209.1 MiB`** | **`199`** | **`1.13x` faster, `1.20x` less CPU, `-15.6%` RAM, `96x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `2.640s` | `2.610s` | `269.5 MiB` | `24,911` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`2.473s`** | **`2.430s`** | **`210.6 MiB`** | **`1,097`** | **`1.07x` faster, `-21.9%` RAM, `22.7x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `4.581s` | `9.770s` | `468.4 MiB` | `88,818` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `9.198s` | `14.510s` | **`298.9 MiB`** | **`1,302`** | **`36.2%` less RAM (`-169.5 MiB`), `68.2x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `7.683s` | `7.625s` | `405.2 MiB` | `65,096` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `14.526s` | `14.430s` | **`300.5 MiB`** | **`6,087`** | **`25.8%` less RAM (`-104.7 MiB`), `10.7x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `11.863s` | `17.460s` | `705.0 MiB` | `97,719` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `30.465s` | `45.230s` | **`417.5 MiB`** | **`2,152`** | **`40.8%` less RAM (`-287.5 MiB`), `45.4x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `15.598s` | `15.490s` | `532.4 MiB` | `67,243` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `45.389s` | `45.105s` | **`419.0 MiB`** | **`15,211`** | **`21.3%` less RAM (`-113.4 MiB`), `4.4x` fewer ctx switches** |

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
| **Desktop (16T)** | Temurin JDK 21 (`java -jar OptBench.jar`) | `0.243s` | `0.300s` | `61.8 MiB` | `608` | `7.83ms` / `34.18ms` / `44.01ms` / `86.02ms` |
| **Desktop (16T)** | Native `opt-bench.kexe` (`konanc -opt`) | **`0.192s` (`1.27x` faster)** | **`0.180s` (`1.67x` less)** | **`5.6 MiB` (`11.0x` less RAM)** | **`8` (`76x` fewer)** | **`7.11ms`** / `40.40ms` / `44.87ms` / `92.38ms` |
| **Single-Core (1T)** | Temurin JDK 21 (`java -jar OptBench.jar`) | `0.273s` | `0.260s` | `52.3 MiB` | `675` | `8.05ms` / `33.15ms` / `44.12ms` / `85.33ms` |
| **Single-Core (1T)** | Native `opt-bench.kexe` (`konanc -opt`) | **`0.189s` (`1.44x` faster)** | **`0.180s` (`1.44x` less)** | **`5.8 MiB` (`9.0x` less RAM)** | **`13` (`52x` fewer)** | **`7.55ms`** / `41.10ms` / `44.72ms` / `93.37ms` |

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
| **`-j1`** | Official JVM `konanc` | `22.239s` | `0.72 mod/s` | `32.53s` | `286.9 MiB` | `331,694` | Baseline |
| **`-j1`** | **Self-Hosted Native `konanc`** | **`13.946s`** | **`1.15 mod/s`** | **`20.59s`** | **`212.1 MiB`** | **`1,901`** | **`1.59x` faster wall, `1.58x` less CPU, `-26.1%` RAM, `174x` fewer ctx switches** |
| **`-j4`** | Official JVM `konanc` | `8.350s` | `1.92 mod/s` | `48.21s` | `290.9 MiB` | `379,280` | Baseline |
| **`-j4`** | **Self-Hosted Native `konanc`** | **`5.386s`** | **`2.97 mod/s`** | **`29.33s`** | **`211.9 MiB`** | **`3,094`** | **`1.55x` faster wall, `1.64x` less CPU, `-27.2%` RAM, `123x` fewer ctx switches** |
| **`-j8`** | Official JVM `konanc` | `5.871s` | `2.73 mod/s` | `63.24s` | `282.8 MiB` | `307,337` | Baseline |
| **`-j8`** | **Self-Hosted Native `konanc`** | **`3.325s`** | **`4.81 mod/s`** | **`37.21s`** | **`213.6 MiB`** | **`6,273`** | **`1.77x` faster wall, `1.70x` less CPU, `-24.5%` RAM, `49x` fewer ctx switches** |
| **`-j16`** | Official JVM `konanc` | `5.453s` | `2.93 mod/s` | `73.05s` | `286.2 MiB` | `481,794` | 16 JVMs spawn hundreds of C1/C2 JIT & GC threads |
| **`-j16`** | **Self-Hosted Native `konanc`** | **`3.369s`** | **`4.75 mod/s`** | **`39.14s`** | **`212.1 MiB`** | **`6,303`** | **`1.62x` faster wall, `1.87x` less CPU, `-25.9%` RAM, `76x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `cinterop` (JDK 21) | `0.958s` (`958 ms`) | `1.130s` | `160.1 MiB` | `22,955` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `cinterop`** | **`0.069s` (`69 ms`)** | **`0.050s`** | **`145.5 MiB`** | **`21`** | **`13.88x` faster wall, `22.60x` less CPU, `1,093x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `cinterop` (JDK 21) | `1.186s` (`1,186 ms`) | `0.850s` | `191.9 MiB` | `10,605` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `cinterop`** | **`0.055s` (`55 ms`)** | **`0.040s`** | **`145.6 MiB`** | **`45`** | **`21.56x` faster wall, `21.25x` less CPU, `-24.1%` RAM, `236x` fewer ctx switches** |

#### Case 4B — Inspecting KLIB ABI Signatures (`klib dump-abi`)

```bash
# Real Command
klib dump-abi build/e1c-cli-native.klib -signature-version 2
```

| Profile | Tool | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native Speedup |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `klib` (JDK 21) | `0.202s` (`202 ms`) | `0.330s` | `68.5 MiB` | `3,247` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `klib`** | **`0.027s` (`27 ms`)** | **`0.020s`** | `120.0 MiB` | **`25`** | **`7.48x` faster wall, `16.50x` less CPU, `130x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `klib` (JDK 21) | `0.294s` (`294 ms`) | `0.280s` | `66.0 MiB` | `4,030` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `klib`** | **`0.026s` (`26 ms`)** | **`0.010s`** | `121.2 MiB` | **`23`** | **`11.31x` faster wall, `28.00x` less CPU, `175x` fewer ctx switches** |

---

### Summary of Benchmark Takeaways

1. **Where Self-Hosted Native `konanc` Wins Immediately**:
   - **Developer & CI Tooling (`cinterop`, `klib`)**: **`7.5x` to `21.6x` faster** (`55–69 ms` vs `958–1,186 ms` for `cinterop`; `26–27 ms` vs `202–294 ms` for `klib dump-abi`), because direct `kotlinx.cinterop` calls into `libclang` and `zlib` eliminate JVM startup, classloading, and JNI marshaling.
   - **Small-to-Medium Module Builds (`d1-core.klib`, `e1c-cli-native.klib`)**: **`1.07x` to `1.41x` faster wall time** and up to **`1.86x` less total CPU time**, finishing before HotSpot's C1/C2 JIT can amortize its compilation cost.
   - **Parallel Multi-Module Project Builds (`-j1` to `-j16`)**: **`1.55x` to `1.77x` faster wall time**, **`1.58x` to `1.87x` less CPU core-seconds**, **`25%–27%` lower peak RSS per worker**, and **`49x` to `174x` fewer OS context switches**, because 16 native compiler processes do not spawn competing JIT and GC thread pools.
   - **Peak Memory on Application Binary Builds (`-g` and `-opt`)**: **`21%` to `41%` lower peak RSS** (`298.9 MiB` vs `468.4 MiB` on `-g`; `417.5 MiB` vs `705.0 MiB` on `-opt`).
2. **Where JVM HotSpot (`JDK 21`) Still Wins**:
   - **Long Single-Process Compilation (`b1k-backend-native.klib` with 197 large files) and Monolithic IR Linking (`konanc-cli.kexe`, `-opt` whole-program binary builds)**: Once a single compiler process runs for `10–30+ seconds` over a massive pointer-heavy AST/FIR/IR graph, HotSpot's profile-guided C2 JIT and parallel generational GC outperform Kotlin/Native's AOT codegen and stop-the-world mark-and-sweep memory manager by `2.0x–3.2x` wall time.
   - Notably, compiling `konanc-cli.kexe` itself with whole-program `-opt -ea` + LLVM `-O3` sped up Case 1A by **`3.05x`** (`158.1s -> 51.84s`) compared to an unoptimized (`-O0`) native compiler binary.

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

1. **Why Whole-Program `-opt` Required `-ea` (`SymbolTableSlice.kt` Side-Effect Inside `assert(...)`)**:
   - When we first compiled `konanc-cli.kexe` itself with `-opt` (without `-ea`), the resulting compiler worked for `.kt -> .klib` and `cinterop`, but failed during `LinkKlibsPhase` (`-p program`) with:
     `IrClassSymbolImpl is unbound. Signature: kotlin.internal/IntrinsicConstEvaluation|null[0]`.
   - Tracing `LinkKlibsPhase` revealed that upstream [`SymbolTableSlice.kt:52`](./kotlin/compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/util/SymbolTableSlice.kt) wrote a state-mutating expression inside `kotlin.assert(...)`:
     ```kotlin
     assert(unboundSymbols.add(new)) { "Symbol for ${new.signature} was already referenced" }
     ```
   - On the JVM, `kotlin.assert(condition)` always evaluates its argument before checking `_Assertions.ENABLED` unless JVM bytecode assertions are stripped. On Kotlin/Native, `kotlin.assert` is backed by `AssertionCompilerIntrinsic`, which **completely eliminates the `condition` expression (`unboundSymbols.add(new)`) from the IR when `-ea` is not enabled**! Building the compiler's bitcode with `-opt -ea` preserved `unboundSymbols.add(new)` while enabling full whole-program IR optimization.
2. **Parallelizing Whole-Program `-opt` Codegen (`llvm-split` + `ld.lld -r` + Symbol Localization)**:
   - Whole-program `-opt` on the 55-module compiler emits a single `145 MiB` LLVM bitcode module (`out.bc`). Compiling `out.bc` with a single-threaded `clang++ -O3` takes over 15 minutes.
   - Splitting `out.bc` into 16 partitions with `llvm-split -j=16` reduced `-O3` codegen time to **~80 seconds** across 16 cores. However, `llvm-split` promotes `internal` C++ helper symbols (from the merged Kotlin/Native `runtime.bc` / `llvm.bc`) to global visibility so partitions can call each other, which then collides (`duplicate symbol`) with the same C++ symbols in `libllvm_support.a` and LLVM static archives during final linking.
   - We solved this cleanly by pre-linking the 16 compiled partitions into a single relocatable object (`ld.lld -r -o konanc-kotlin.o`) so all cross-partition references resolve internally, and then running `llvm-objcopy --localize-symbols` on the promoted `_Z*` symbols before the final `--gc-sections --strip-all` link.

### 3. Three-Stage Bootstrap Fixed-Point (`stage2 == stage3`) & Artifact Consistency Verification

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
