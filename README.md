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
- **Compile `konanc` with itself using full whole-program optimization (`-opt` + LLVM `-O3`, zero `-ea` overhead)** and measure where a pure Kotlin/Native compiler beats the JVM — and where HotSpot C2 JIT still holds an advantage.

### Prebuilt Whole-Program Optimized Distributions ([`v2.4.21-native`](https://github.com/Stream29/NativeKotlinShare/releases/tag/v2.4.21-native))

All four archives below are built with **whole-program Kotlin/Native IR optimization (`-opt`, zero `-ea` assertion overhead), full K/N LLVM bitcode LTO passes, LLVM `-O3` codegen, and symbol stripping**, and have **zero JVM dependency**. On first run, `bin/konanc` (or `cinterop`, `klib`, `kotlinc-native`) automatically checks and initializes the official LLVM 21 / sysroot dependencies under `${KONAN_DATA_DIR:-~/.konan}/dependencies`.

| Host Platform | Release Archive | Archive Size | Archive SHA-256 | `konanc-cli` Binary Size & SHA-256 |
| :--- | :--- | :---: | :--- | :--- |
| **Linux x86_64** (`linux_x64`) | [`native-kotlin-dist-linux-x64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-x64.tar.gz) | `61.8 MiB` (`64,812,125 B`) | `0f58c522eab6bc2ada11199fdd9e1ca3d7667418285f38aa5340cb1e686f7d87` | `102.2 MiB` (`107,216,232 B`)<br>`29e20eb3cbd2dc1b9d0adeec8165ba23c613aca8ff12d9c822191598ee285ee3` |
| **Linux ARM64** (`linux_arm64`) | [`native-kotlin-dist-linux-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-arm64.tar.gz) | `112.5 MiB` (`117,972,666 B`) | `9b082fe0c05128a8f48350fb65d5bc2bd312d6905ff5098f609f79b4b1f00f57` | `33.7 MiB` (`35,354,504 B`)<br>`423416a74db157077dffbf31845c1f38cc72d5da879e6ce8518dbe9724d7fb45` |
| **macOS Apple Silicon** (`macos_arm64`) | [`native-kotlin-dist-macos-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-macos-arm64.tar.gz) | `112.1 MiB` (`117,580,683 B`) | `d233a12b2c2d5b5f38a7b60787e4d7f86fdbff209ac45dc928e162dd6ce57126` | `117.3 MiB` (`123,007,504 B`)<br>`18b71eb266740a0fa896e84197f05849566d993a52648563134b3f9b07894543` |
| **Windows x64** (`mingw_x64`) | [`native-kotlin-dist-windows-x64.zip`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-windows-x64.zip) | `31.6 MiB` (`33,140,634 B`) | `825c28778d0d64266e9d4a3f24a03f387f00f067acd004b9fea8a47377e0434f` | `57.4 MiB` (`60,135,936 B`)<br>`48a263ea555fe1a6d649755a596f06de9b67f9f3d7ceec4b983ccf5c7b8f5fe7` |

---

## Part 2 — Benchmarks vs. JVM (with Real Build Commands)

### Benchmark Environment & Methodology

- **Hardware**: `12th Gen Intel Core i5-12500H` (4P + 8E cores / 16 threads, 16 GiB RAM, Ubuntu Linux `x86_64`).
- **Compilers Under Test**:
  - **Official JVM `konanc` (`stage0`)**: Upstream `:kotlin-native:dist` (`9180ce6a7a`) running on **Eclipse Temurin JDK 21.0.10+7** (`-Xmx4G` on 16-Thread Desktop; `-Xmx1536M -XX:ActiveProcessorCount=1` on Single-Core).
  - **Self-Hosted Native `konanc`**: `bin/konanc-cli.kexe` (`102.2 MiB`, built with whole-program `-opt` + full K/N LLVM bitcode passes + LLVM `-O3` + stripped symbols, zero `-ea`), running with `0` JVM processes (`env -i` clean environment).
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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `19.912s` | `37.970s` | `876.3 MiB` | `131,394` | C2 JIT warms up over 197 large backend files + parallel GC |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `52.343s` | `65.435s` | `1,358.4 MiB` | **`2,244` (`58.6x` fewer)** | **`3.02x` faster** than unoptimized `-O0` Native (`158.1s`) |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `29.449s` | `29.250s` | `749.7 MiB` | `113,778` | 197 files (`~29s`) is long enough to amortize C2 JIT |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `64.554s` | `64.160s` | `1,370.9 MiB` | **`13,013` (`8.7x` fewer)** | Pure AOT execution; K/N MM GC pause overhead on large IR graph |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `4.406s` | `10.080s` | `374.5 MiB` | `71,520` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`4.208s`** | **`5.200s`** | `509.4 MiB` | **`332`** | **`1.05x` faster wall, `1.94x` less CPU, `215x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `7.383s` | `7.320s` | `352.1 MiB` | `61,684` | Baseline (JIT compilation shares the single core) |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`5.258s`** | **`5.220s`** | `511.2 MiB` | **`1,634`** | **`1.40x` faster wall, `1.40x` less CPU, `37.7x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `8.751s` | `18.745s` | `1,164.0 MiB` | `102,555` | Links 55 cached compiler KLIBs + 2 entrypoint `.kt` files |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `12.768s` | **`16.960s`** | `1,166.1 MiB` | **`10,894` (`9.4x` fewer)** | **`1.11x` less total CPU than JVM**; identical `1.14 GiB` RSS (`ld.lld` dominates) |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `13.169s` | `13.070s` | `1,165.2 MiB` | `73,024` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `15.876s` | `15.770s` | `1,165.3 MiB` | **`5,037` (`14.5x` fewer)** | Within `1.21x` of JVM wall time; identical peak RSS |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `1.873s` | `3.030s` | `246.9 MiB` | `18,296` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`0.780s`** | **`0.900s`** | **`198.9 MiB`** | **`171`** | **`2.40x` faster, `3.37x` less CPU, `-19.4%` RAM, `107x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `2.599s` | `2.570s` | `269.3 MiB` | `24,728` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`0.865s`** | **`0.850s`** | **`199.0 MiB`** | **`445`** | **`3.00x` faster, `3.02x` less CPU, `-26.1%` RAM, `55.6x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `4.819s` | `10.300s` | `457.5 MiB` | `91,619` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`4.799s`** | **`5.680s`** | **`308.1 MiB`** | **`1,070`** | **Faster wall, `1.81x` less CPU, `32.7%` less RAM (`-149.4 MiB`), `85.6x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `7.577s` | `7.520s` | `404.6 MiB` | `64,385` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | **`5.541s`** | **`5.500s`** | **`315.9 MiB`** | **`2,395`** | **`1.37x` faster wall, `1.37x` less CPU, `21.9%` less RAM (`-88.7 MiB`), `26.9x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `konanc` (JDK 21) | `12.835s` | `19.060s` | `723.0 MiB` | `94,380` | Baseline |
| **Desktop (16T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `18.311s` | `20.730s` | **`424.6 MiB`** | **`1,819`** | **`41.3%` less RAM (`-298.4 MiB`), near-equal CPU, `51.9x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `konanc` (JDK 21) | `15.572s` | `15.465s` | `532.4 MiB` | `66,440` | Baseline |
| **Single-Core (1T)** | Self-Hosted Native `konanc` (`-opt -O3`) | `20.724s` | `20.590s` | **`417.1 MiB`** | **`5,810`** | **`21.7%` less RAM (`-115.3 MiB`), `11.4x` fewer ctx switches** |

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
| **Desktop (16T)** | Temurin JDK 21 (`java -jar OptBench.jar`) | `0.234s` | `0.270s` | `55.5 MiB` | `501` | `8.68ms` / `34.94ms` / `44.12ms` / `87.64ms` |
| **Desktop (16T)** | Native `opt-bench.kexe` (`konanc -opt`) | **`0.191s` (`1.23x` faster)** | **`0.180s` (`1.50x` less)** | **`5.6 MiB` (`9.9x` less RAM)** | **`8` (`63x` fewer)** | **`7.71ms`** / `41.14ms` / **`44.00ms`** / `92.85ms` |
| **Single-Core (1T)** | Temurin JDK 21 (`java -jar OptBench.jar`) | `0.252s` | `0.240s` | `46.5 MiB` | `590` | `8.48ms` / `33.62ms` / `44.34ms` / `86.03ms` |
| **Single-Core (1T)** | Native `opt-bench.kexe` (`konanc -opt`) | **`0.190s` (`1.33x` faster)** | **`0.180s` (`1.33x` less)** | **`5.8 MiB` (`8.0x` less RAM)** | **`21` (`28x` fewer)** | **`7.92ms`** / `41.53ms` / **`44.12ms`** / `93.58ms` |

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
| **`-j1`** | Official JVM `konanc` | `21.902s` | `0.73 mod/s` | `32.14s` | `296.3 MiB` | `329,429` | Baseline |
| **`-j1`** | **Self-Hosted Native `konanc`** | **`6.626s`** | **`2.41 mod/s`** | **`7.43s`** | **`200.7 MiB`** | **`1,584`** | **`3.31x` faster wall, `4.33x` less CPU, `-32.3%` RAM, `208x` fewer ctx switches** |
| **`-j4`** | Official JVM `konanc` | `8.241s` | `1.94 mod/s` | `47.38s` | `249.9 MiB` | `353,711` | Baseline |
| **`-j4`** | **Self-Hosted Native `konanc`** | **`2.423s`** | **`6.60 mod/s`** | **`10.90s`** | **`201.1 MiB`** | **`3,989`** | **`3.40x` faster wall, `4.35x` less CPU, `-19.5%` RAM, `88.7x` fewer ctx switches** |
| **`-j8`** | Official JVM `konanc` | `5.809s` | `2.75 mod/s` | `63.15s` | `288.3 MiB` | `346,554` | Baseline |
| **`-j8`** | **Self-Hosted Native `konanc`** | **`1.715s`** | **`9.33 mod/s`** | **`14.97s`** | **`200.8 MiB`** | **`3,077`** | **`3.39x` faster wall, `4.22x` less CPU, `-30.4%` RAM, `113x` fewer ctx switches** |
| **`-j16`** | Official JVM `konanc` | `5.225s` | `3.06 mod/s` | `70.43s` | `274.7 MiB` | `439,149` | 16 JVMs spawn hundreds of C1/C2 JIT & GC threads |
| **`-j16`** | **Self-Hosted Native `konanc`** | **`1.693s`** | **`9.45 mod/s`** | **`15.81s`** | **`199.7 MiB`** | **`3,815`** | **`3.09x` faster wall, `4.45x` less CPU, `-27.3%` RAM, `115x` fewer ctx switches** |

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
| **Desktop (16T)** | Official JVM `cinterop` (JDK 21) | `0.940s` (`940 ms`) | `1.110s` | `160.0 MiB` | `20,744` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `cinterop`** | **`0.071s` (`71 ms`)** | **`0.060s`** | **`123.8 MiB`** | **`25`** | **`13.24x` faster wall, `18.50x` less CPU, `-22.6%` RAM, `830x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `cinterop` (JDK 21) | `1.173s` (`1,173 ms`) | `0.840s` | `191.1 MiB` | `10,669` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `cinterop`** | **`0.059s` (`59 ms`)** | **`0.050s`** | **`124.5 MiB`** | **`39`** | **`19.88x` faster wall, `16.80x` less CPU, `-34.9%` RAM, `274x` fewer ctx switches** |

#### Case 4B — Inspecting KLIB ABI Signatures (`klib dump-abi`)

```bash
# Real Command
klib dump-abi build/e1c-cli-native.klib -signature-version 2
```

| Profile | Tool | Wall Time (`s`) | Total CPU (`s`) | Peak RSS (`MiB`) | Context Switches | Native Speedup |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Desktop (16T)** | Official JVM `klib` (JDK 21) | `0.213s` (`213 ms`) | `0.340s` | `68.2 MiB` | `3,540` | Baseline |
| **Desktop (16T)** | **Self-Hosted Native `klib`** | **`0.030s` (`30 ms`)** | **`0.020s`** | `94.6 MiB` | **`27`** | **`7.10x` faster wall, `17.00x` less CPU, `131x` fewer ctx switches** |
| **Single-Core (1T)** | Official JVM `klib` (JDK 21) | `0.279s` (`279 ms`) | `0.270s` | `65.7 MiB` | `4,148` | Baseline |
| **Single-Core (1T)** | **Self-Hosted Native `klib`** | **`0.029s` (`29 ms`)** | **`0.010s`** | `95.5 MiB` | **`27`** | **`9.62x` faster wall, `27.00x` less CPU, `154x` fewer ctx switches** |

---

### Summary of Benchmark Takeaways

1. **Where Self-Hosted Native `konanc` Wins Immediately**:
   - **Developer & CI Tooling (`cinterop`, `klib`)**: **`7.1x` to `19.9x` faster** (`59–71 ms` vs `940–1,173 ms` for `cinterop`; `29–30 ms` vs `213–279 ms` for `klib dump-abi`), because direct `kotlinx.cinterop` calls into `libclang` and `zlib` eliminate JVM startup, classloading, and JNI marshaling.
   - **Small-to-Medium Module & Debug Binary Builds (`d1-core.klib`, `e1c-cli-native.klib`, `d1-corpus.kexe -g`)**: **`2.40x` to `3.00x` faster wall time** (`3.02x–3.37x` less CPU) on `d1-core.klib`, **`1.05x` to `1.40x` faster** (`1.40x–1.94x` less CPU) on `e1c-cli-native.klib`, and **faster than JVM even on full debug `.kexe` builds (`4.799s` vs `4.819s` Desktop, `5.541s` vs `7.577s` / `1.37x` faster Single-Core, with `1.81x` less CPU and `32.7%` less RAM)**.
   - **Parallel Multi-Module Project Builds (`-j1` to `-j16`)**: **`3.09x` to `3.40x` faster wall time** (`9.45 mod/s` vs `3.06 mod/s` at `-j16`), **`4.22x` to `4.45x` less total CPU core-seconds** (`15.81s` vs `70.43s` at `-j16`), **`20%–32%` lower peak RSS per worker**, and **`89x` to `208x` fewer OS context switches**, because 16 native compiler processes do not spawn competing JIT and GC thread pools.
   - **Peak Memory & CPU Efficiency on Monolithic Builds**: **`41.3%` lower peak RSS** on `-opt` application builds (`424.6 MiB` vs `723.0 MiB`), and **`1.11x` less total CPU** than JVM when linking the 55-module compiler with static caches (`16.96s` vs `18.75s` CPU).
2. **Where JVM HotSpot (`JDK 21`) Still Wins**:
   - **Long Single-Process Compilation (`b1k-backend-native.klib` with 197 large files) and Uncached Whole-Program `-opt` Passes**: Once a single compiler process runs for `15–30+ seconds` over a massive pointer-dense AST/FIR/IR graph, HotSpot's multi-core background C2 profile-guided JIT and parallel generational GC outperform Kotlin/Native's single-threaded AOT codegen and stop-the-world mark-and-sweep memory manager (`19.91s` vs `52.34s` on 197-file `b1k`; `12.84s` vs `18.31s` on `-opt` release link).
   - Notably, compiling `konanc-cli.kexe` itself with clean zero-`-ea` whole-program `-opt` + K/N LLVM bitcode LTO passes + LLVM `-O3` sped up Case 1A by **`3.02x`** (`158.1s -> 52.34s`) over unoptimized (`-O0`) Native, and removing `-ea` assertion checks + enabling full K/N LLVM bitcode optimization sped up cached linking (`Case 1C`), debug `.kexe` builds (`Case 2B`), and 16-module parallel builds (`Case 3`) by an additional **`1.9x–2.7x`**.

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
