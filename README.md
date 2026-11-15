# NativeKotlinShare — Self-Hosted Kotlin/Native Compiler (`0` JVM, `v2.5.0-RC`)

Zero-JVM builds of `konanc`, `cinterop`, and `klib` rebased onto upstream [`JetBrains/kotlin` `v2.5.0-RC` (`4eeec3a1fb`)](./kotlin) + LLVM `21.1`, benchmarked head-to-head against **HotSpot JVM 25** and **GraalVM JDK 25 Native Image** on the exact same commit.

- **Source fork (`./kotlin`)**: `native-host` branch ([tag `v2.5.0-rc-native`](https://github.com/Stream29/kotlin/releases/tag/v2.5.0-rc-native), `6` commits on `4eeec3a1fb`); prior `v2.4.21-native` snapshot preserved via Git tag and [release](https://github.com/Stream29/NativeKotlinShare/releases/tag/v2.4.21-native).
- **Prebuilt zero-JVM distributions**: [Release `v2.5.0-rc-native`](https://github.com/Stream29/NativeKotlinShare/releases/tag/v2.5.0-rc-native) (`linux_x64`, `linux_arm64`, `macos_arm64`, `mingw_x64`).
- **GraalVM build scripts, metadata & raw benchmark JSON**: [`konanc-graal-v250-rc-repro.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.5.0-rc-native/konanc-graal-v250-rc-repro.tar.gz).

---

## 1. Prebuilt Distributions & Compiler Variants

### Zero-JVM `konanc` Distributions (`v2.5.0-rc-native`)

Built with Kotlin/Native `-opt` (no `-ea`), `-Xbinary=gc=cms` (batch CLI heap policy), `-Xbinary=latin1Strings=true`, `-Xbinary=cCallMode=directOrIndirect`, LLVM 21 `-O3`, and symbol stripping. On first invocation, `bin/konanc` / `cinterop` / `klib` populates `${KONAN_DATA_DIR:-~/.konan}/dependencies` from `download.jetbrains.com`.

| Host | Archive | Size | Archive SHA-256 | `konanc-cli` Size |
| :--- | :--- | :---: | :--- | :---: |
| `linux_x64` | [`native-kotlin-dist-linux-x64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.5.0-rc-native/native-kotlin-dist-linux-x64.tar.gz) | `61.6 MiB` | `6efbf30b2f064e979260cdcacb1275cd9cf1ac99affda20e0065da3fc1d54335` | `98.4 MiB` |
| `linux_arm64` | [`native-kotlin-dist-linux-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.5.0-rc-native/native-kotlin-dist-linux-arm64.tar.gz) | `106.8 MiB` | `09dd09a33b3b1dcbf094a7ab56511d49e934fb36ddc3010a81b7da8b232ece0f` | `33.6 MiB` |
| `macos_arm64` | [`native-kotlin-dist-macos-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.5.0-rc-native/native-kotlin-dist-macos-arm64.tar.gz) | `116.7 MiB` | `b05518652d300a0bccc958c4e4c8f532270f0e81b811b107fa7eef67bc51200b` | `117.0 MiB` |
| `mingw_x64` | [`native-kotlin-dist-windows-x64.zip`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.5.0-rc-native/native-kotlin-dist-windows-x64.zip) | `33.1 MiB` | `e55be7ece8be9cd5840cbee148cc1d40d411ceb87c1dd8a5f0045776b65d369e` | `57.8 MiB` |

### Official `kotlinc-native-image` (`2.5.0-RC`) vs. `konanc-graal`

JetBrains' `kotlin-compiler-graalvm-native-image-*-2.5.0-RC` ships **`bin/kotlinc-native-image` (`113.5 MiB`, `--gc=serial -Os`)**, which compiles `K2JVMCompiler` (`kotlinc` / JS / Wasm) and requires `-Djava.home=...`; it excludes `konanc`, `cinterop`, `klib`, `libllvmstubs.so`, and `libclangstubs.so`. Official `konanc` (`kotlin-native-prebuilt-*-2.5.0-RC`) still runs on HotSpot with `-XX:TieredStopAtLevel=1`.

To compare `konanc` across all three runtimes on `4eeec3a1fb`, we applied the exact GraalVM JDK 25 (`25.0.4+7.1`) flags from [`prepare/compiler-native-image/build.gradle.kts`](./kotlin/prepare/compiler-native-image/build.gradle.kts) to `kotlin-native-compiler-embeddable.jar` with `-agentlib:native-image-agent` trace metadata (`462 KiB`), producing two `konanc-graal` binaries alongside Case 0 calibration against official `kotlinc-native-image`:

| Variant (`v2.5.0-RC`, `linux_x64`) | Execution & Codegen | GC & LLVM / Clang Call Path | Footprint & OS Portability |
| :--- | :--- | :--- | :--- |
| **HotSpot 25 `konanc`** | `C1` (`TieredStopAtLevel=1`, shipping) & `C2` (`Level=4`) | HotSpot G1GC -> JNI (`libllvmstubs.so`) -> LLVM 21 `.so` | `82.6 MiB` JAR + `56.7 MiB` JNI `.so` + JDK 25 |
| **GraalVM `konanc-graal-serial`** | GraalVM 25 SubstrateVM (`-Os`, JetBrains flag profile) | SerialGC -> JNI (`libllvmstubs.so`) -> LLVM 21 `.so` | `119.1 MiB` + `56.7 MiB` JNI `.so` (cross-platform) |
| **GraalVM `konanc-graal-g1`** | Oracle GraalVM 25 (`-O2` + ML-PGO, peak throughput) | Generational G1GC -> JNI (`libllvmstubs.so`) -> LLVM 21 `.so` | `223.1 MiB` + `56.7 MiB` JNI `.so` (**Linux only** for G1) |
| **Kotlin/Native `konanc-cli.kexe`** | `konanc -opt` + LLVM 21 whole-program `-O3` | K/N CMS -> direct `cinterop` -> static LLVM 21 `.a` | **`98.4 MiB`** single binary (4 OSes, Apache-2.0) |

---

## 2. Three-Way Benchmarks (`v2.5.0-RC` @ `4eeec3a1fb`)

- **Host & Baseline**: Uncontended i5-12500H (`16T` unpinned / `taskset -c 0` `1T`), Ubuntu `26.04.1` `x86_64`, Oracle GraalVM / HotSpot `25.0.4+7.1` (`-XX:-UseJVMCICompiler` for HotSpot; `-Xmx4G` on `16T`, `-Xmx1536M -XX:ActiveProcessorCount=1` on `1T`). Medians via `/usr/bin/time -v`; HotSpot tested at `C1` (`-XX:TieredStopAtLevel=1`, shipping `run_konan` default) and `C2`.

### Case 0: Official `kotlinc-native-image` Calibration (`K2JVMCompiler` -> `.jar`)

| Workload (`K2JVMCompiler`) | HotSpot 25 `C1` | HotSpot 25 `C2` | Official `kotlinc-native-image` (`Serial -Os`) |
| :--- | :---: | :---: | :---: |
| Single file | `2.555s` (`4.54s` CPU, `283 MiB`) | `3.300s` (`11.62s` CPU, `397 MiB`) | **`0.260s`** (`0.25s` CPU, `185 MiB`) |
| 33 files (`~5,500 LOC`) | `6.656s` (`14.42s` CPU, `393 MiB`) | `10.369s` (`53.01s` CPU, `563 MiB`) | **`2.448s`** (`2.43s` CPU, `255 MiB`) |

### Compiler & Tooling Workloads: Wall Time (`s`) & Peak RSS (`MiB`)

- **Desktop (16 Threads)**:

| Workload | HotSpot 25 `C1` | Graal `Serial -Os` | Graal `G1 -O2` | Kotlin/Native `-opt -O3` |
| :--- | :---: | :---: | :---: | :---: |
| Compiler backend KLIB (`197` files, `~50k LOC`, `53` KLIBs) | `20.960s` (`883M`) | `13.477s` (`638M`) | **`6.477s`** (`988M`) | `14.014s` (`2,898M`) |
| Compiler CLI KLIB (`9` files, `55` KLIBs) | `5.013s` (`395M`) | `1.254s` (`267M`) | **`0.643s`** (`394M`) | `1.264s` (`534M`) |
| Compiler cached link (`56` KLIBs, `-g`) | `9.396s` (`1,445M`) | `5.774s` (`1,441M`) | **`3.712s`** (`1,442M`) | `5.125s` (`1,640M`) |
| Single-file library (`.kt -> .klib`) | `2.255s` (`238M`) | `0.180s` (`154M`) | **`0.110s`** (`198M`) | `0.138s` (`227M`) |
| Debug executable (`-g`, 3 `.kt` + C interop) | `5.156s` (`478M`) | `1.192s` (`260M`) | **`0.746s`** (`500M`) | `0.937s` (`451M`) |
| Release executable (`-opt`, whole-program LTO) | `13.114s` (`636M`) | `9.254s` (`436M`) | **`7.406s`** (`791M`) | `8.585s` (`796M`) |
| `cinterop` (`.def` -> `.klib` via `libclang`) | `1.179s` (`184M`) | `0.092s` (`149M`) | `0.087s` (`173M`) | **`0.069s`** (`135M`) |
| `klib dump-abi` | `0.283s` (`104M`) | **`0.015s`** (`44M`) | `0.016s` (`58M`) | `0.025s` (`112M`) |

- **Single-Core (`taskset -c 0`, 1 Thread)**:

| Workload | HotSpot 25 `C1` | Graal `Serial -Os` | Graal `G1 -O2` | Kotlin/Native `-opt -O3` |
| :--- | :---: | :---: | :---: | :---: |
| Compiler backend KLIB (`197` files) | `29.634s` (`789M`) | `13.164s` (`699M`) | **`8.221s`** (`2,093M`) | `18.172s` (`2,751M`) |
| Compiler CLI KLIB (`9` files, `55` KLIBs) | `8.115s` (`359M`) | `1.240s` (`267M`) | **`0.819s`** (`444M`) | `1.556s` (`537M`) |
| Compiler cached link (`56` KLIBs, `-g`) | `14.172s` (`1,437M`) | `6.301s` (`1,443M`) | **`4.822s`** (`1,443M`) | `6.495s` (`1,659M`) |
| Single-file library (`.kt -> .klib`) | `3.007s` (`256M`) | `0.186s` (`154M`) | **`0.121s`** (`205M`) | `0.144s` (`227M`) |
| Debug executable (`-g`, 3 `.kt` + C interop) | `7.807s` (`389M`) | `1.197s` (`260M`) | **`0.902s`** (`513M`) | `1.170s` (`452M`) |
| Release executable (`-opt`) | `15.924s` (`496M`) | `9.284s` (`409M`) | **`7.797s`** (`1,262M`) | `9.562s` (`813M`) |
| `cinterop` (`.def` -> `.klib` via `libclang`) | `1.525s` (`220M`) | `0.091s` (`149M`) | `0.093s` (`174M`) | **`0.053s`** (`136M`) |
| `klib dump-abi` | `0.417s` (`93M`) | **`0.014s`** (`44M`) | `0.016s` (`58M`) | `0.022s` (`111M`) |

### `-Xdump-perf` Phase Breakdown (`197`-File Backend Module, Desktop `16T`)

| Compiler Phase | HotSpot 25 `C1` | HotSpot 25 `C2` | Graal `Serial -Os` | Graal `G1 -O2` | Kotlin/Native `-opt -O3` |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `INIT` | `237 ms` | `290 ms` | `5 ms` | `4 ms` | **`1 ms`** |
| `ANALYZE` | `14,781 ms` | `18,810 ms` | `8,693 ms` | **`4,255 ms`** | `9,870 ms` |
| `TRANSLATION to IR` | `2,397 ms` | `3,821 ms` | `1,991 ms` | **`772 ms`** | `1,646 ms` |
| `IR PRE-LOWERING` | `1,327 ms` | `1,957 ms` | `894 ms` | **`445 ms`** | `1,116 ms` |
| `DYNAMIC PHASES` | `1,269 ms` | `1,895 ms` | `855 ms` | **`423 ms`** | `1,070 ms` |
| `IR SERIALIZATION` | `1,522 ms` | `2,131 ms` | `1,583 ms` | **`654 ms`** | `1,290 ms` |
| `KLIB WRITING` | `337 ms` | `424 ms` | `265 ms` | `279 ms` | **`58 ms`** |
| JIT / GC Pause Telemetry | `8,135ms` JIT + `429ms` GC | `149,961ms` JIT + `510ms` GC | `0ms` JIT + `4,467ms` GC (`81` GCs) | `0ms` JIT (no GC line) | not emitted by K/N |

### Multi-Module Parallel Build (`16` Independent Modules: `8 cinterop + 8 KLIBs`)

| Concurrency | Compiler | Batch Wall (`s`) | Total CPU (`s`) | Max / Sum Child RSS (`MiB`) |
| :---: | :--- | :---: | :---: | :---: |
| **`-j1`** | HotSpot 25 `C1` | `26.618s` | `40.440s` | `246` / `3,389` |
| **`-j1`** | Graal `Serial -Os` | `2.114s` | `1.920s` | **`154`** / **`2,423`** |
| **`-j1`** | Graal `G1 -O2` | **`1.522s`** | `1.550s` | `198` / `2,969` |
| **`-j1`** | Kotlin/Native `-opt -O3` | `1.532s` | **`1.370s`** | `227` / `2,898` |
| **`-j4`** | HotSpot 25 `C1` | `9.993s` | `59.350s` | `236` / `3,336` |
| **`-j4`** | Graal `Serial -Os` | `0.692s` | `2.520s` | **`154`** / **`2,400`** |
| **`-j4`** | Graal `G1 -O2` | `0.508s` | `2.040s` | `198` / `2,914` |
| **`-j4`** | **Kotlin/Native `-opt -O3`** | **`0.506s`** | **`1.730s`** | `227` / `2,895` |
| **`-j8`** | HotSpot 25 `C1` | `7.018s` | `77.880s` | `282` / `3,367` |
| **`-j8`** | Graal `Serial -Os` | `0.502s` | `3.270s` | **`154`** / **`2,381`** |
| **`-j8`** | Graal `G1 -O2` | `0.361s` | `2.610s` | `198` / `2,930` |
| **`-j8`** | **Kotlin/Native `-opt -O3`** | **`0.350s`** | **`2.240s`** | `228` / `2,886` |
| **`-j16`** | HotSpot 25 `C1` | `6.374s` | `87.260s` | `293` / `3,474` |
| **`-j16`** | Graal `Serial -Os` | `0.411s` | `4.220s` | **`154`** / **`2,412`** |
| **`-j16`** | Graal `G1 -O2` | **`0.290s`** | `3.430s` | `198` / `2,954` |
| **`-j16`** | Kotlin/Native `-opt -O3` | `0.328s` | **`2.960s`** | `227` / `2,883` |

### Compiled Application Output (`OptBench` Runtime)

Three-stage CPU benchmark (`AST` sealed visitor + `Scope` value-class resolution + `LongArray` kernel; deterministic checksum `5584724895034333103`):

| Runtime | Output Size | Wall (`16T` / `1T`) | CPU (`16T`) | Peak RSS (`MiB`) | Sub-Stages (`AST` / `Scope` / `Kernel` / `Total`) |
| :--- | :---: | :---: | :---: | :---: | :--- |
| HotSpot 25 `C2` (`OptBenchJvm.jar`) | `11.2 KiB` (+ JDK) | `0.259s` / `0.311s` | `0.340s` | `62.2` | `10.49ms` / **`37.11ms`** / `44.35ms` / **`92.22ms`** |
| GraalVM NI (`--gc=serial -Os`) | `4.94 MiB` | `0.377s` / `0.373s` | `0.370s` | `66.9` | `11.63ms` / `117.19ms` / `46.40ms` / `175.42ms` |
| GraalVM NI (`--gc=G1 -O2` ML-PGO) | `9.13 MiB` | `0.202s` / `0.204s` | **`0.190s`** | `13.4` | `10.49ms` / `43.13ms` / **`44.14ms`** / `97.70ms` |
| **Kotlin/Native (`konanc -opt`)** | **`580.7 KiB`** | **`0.199s` / `0.203s`** | **`0.190s`** | **`5.6`** | **`8.54ms`** / `43.75ms` / `44.55ms` / `97.14ms` |

### Key Takeaways

1. **AOT dominates HotSpot `C1` across every workload**: Even on JDK 25, JIT warmup and classloading make HotSpot `C1` `1.5x–20.9x` slower for `konanc` builds (`1.5x–16.3x` on `16T`, `1.6x–20.9x` on `1T`) and up to `28.8x` slower on `1T` `cinterop`; on `-j16` parallel builds, HotSpot `C1` burns `29.5x` more CPU core-seconds than Kotlin/Native.
2. **Kotlin/Native vs. GraalVM's official profile (`--gc=serial -Os`)**: Kotlin/Native wins **5 of 8** single-process workloads (`16T`), ties within `4%` on the 197-file backend and CLI KLIB modules (`16T`), wins all `-j1..-j16` parallel batches and `OptBench` runtime execution, and ships a standalone `98.4 MiB` binary (static LLVM 21) vs. `119.1 MiB` + `56.7 MiB` JNI `.so`.
3. **Where Oracle GraalVM `G1 -O2` leads vs. where Kotlin/Native leads**:
   - **Allocation-bound `ANALYZE`**: K2 `ANALYZE` dominates frontend runtime (`4.26s` vs `9.87s`); Oracle GraalVM `G1 -O2` benefits from closed-world ML-PGO, 32-bit compressed oops, and generational G1 TLAB bump-pointer allocation, isolating 64-bit object refs and non-generational CMS as K/N's highest-leverage compiler throughput targets. Graal G1 trades off a `223.1 MiB` binary (`2.27x` larger; `279.8 MiB` combined with `libllvmstubs.so`, `~2.84x` total footprint) and Linux-only G1 support.
   - **Native I/O, parallel CPU, and output artifacts**: K/N leads even against `Graal G1 -O2` on direct `libclang` `cinterop` (`-22%` RAM, faster wall time), zero-copy `KLIB WRITING`, total CPU across all `-j1..-j16` batches (and wall time at `-j4`/`-j8`), `OptBench` runtime RSS/binary size (`5.6 MiB` / `580.7 KiB` vs `13.4 MiB` / `9.13 MiB`), and uniform 4-OS Apache-2.0 distribution.

---

## 3. Port Architecture & Self-Bootstrap Verification (`./kotlin`)

Upstream compiler algorithms (IntelliJ PSI/LightTree, K2 FIR, KLIB serializer, Native IR lowerings, `-opt`, LLVM 21 bitcode/cache pipeline) remain intact; only the JVM host layer was replaced across a 6-commit stack on top of `4eeec3a1fb`:

| Layer | Commit | Scope | What Was Replaced / Adapted |
| :---: | :---: | :--- | :--- |
| **1** | `94bfb5896` | `core/*`, `compiler/config`, `compiler/util*`, `native/{unsafe-mem,utils,host-runtime}` | POSIX/Win32 VFS, zero-copy `zlib` KLIB container, Protobuf runtime, thread-local locks, GMP math, ICU NFC |
| **2** | `bfba59982` | `kotlin-native/Interop`, `native/cinterop.deserialization` | Direct `cinterop` bindings replacing JNI for LLVM 21 and `libclang` |
| **3** | `6223e159a` | `compiler/{psi,container,frontend.common*,cli/cli-base}` | Native lexer/parser and static DI service registration (no reflection or `ServiceLoader`) |
| **4** | `bb2fd8417` | `compiler/{fir,resolution.common,serialization}` | K2 FIR resolution/checkers, `fir2ir`, and KLIB metadata/IR serialization |
| **5** | `1f7addc41` | `compiler/ir/*`, `kotlin-native/backend.native` | IR inlining, Native lowerings, zero-`-ea` `-opt`, and LLVM 21 bitcode/cache pipeline |
| **6** | `638becbe8` | `compiler/cli/cli-native-klib`, `native/cli-native`, `kotlin-native/{klib,utilities}` | Table-driven CLI parser, multi-call `konanc`/`cinterop`/`klib` launcher, batch CLI CMS GC policy |

- **Three-stage self-bootstrap (`PATH=/nonexistent`, `0` JVM)**: `stage2` and `stage3` `konanc-cli.kexe` (`286,066,456 B`) match byte-for-byte (`SHA-256: 1450182b69e6aa12ca689eb0d8bf48058fcb17021cdf5e795c00a46b11089c34`), with identical `klib dump-abi -signature-version 2` outputs across `stage1..stage3`.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
