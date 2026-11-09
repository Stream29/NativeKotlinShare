# Kotlin/Native Toolchain Performance Benchmark (`xiaoxin-ubuntu`, `linux_x64`)

- **Test Device**: `xiaoxin-ubuntu` (`12th Gen Intel Core i5-12500H`, 16 threads, 16 GiB RAM, Ubuntu 26.04.1 LTS)
- **Upstream Commit**: `9180ce6a7a7d3627f88b424717583f28ddca052d` (`2.4.20-dev-7885`)
- **Compared Toolchains**:
  - **Official JVM Toolchain (`stage0`)**: `kotlin-native-compiler-embeddable.jar` on Temurin JDK `21.0.12+1.1`
  - **Self-Hosted Native Toolchain (`stage3`)**: `native-kotlin-dist-linux-x64/bin/konanc-cli.kexe` (`185,383,000` bytes, `env -i PATH=/nonexistent`, **0 JVM**)
- **Raw Data**: [benchmark-jvm-vs-native.json](data/benchmark-jvm-vs-native.json)

---

## 1. Constrained Single-Core Profile (`taskset -c 0`, JVM `-Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=1`)

| # | Workload | JVM Wall (s) | Native Wall (s) | **Wall Speedup** | JVM CPU (s) | Native CPU (s) | **CPU Speedup** | JVM Peak RSS | Native Peak RSS | **Memory Change** | JVM Ctx Sw | Native Ctx Sw |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **W1** | `klib info stdlib` (CLI Cold Start) | `0.310s` | `0.230s` | **`1.35x` faster** | `0.29s` | `0.22s` | **`1.32x` less** | `72.3 MB` | `121.4 MB` | `+67.9%` (ELF mmap) | `123` | `9` |
| **W2** | `klib dump-abi` (`e1c-cli-native.klib`) | `0.334s` | `0.060s` | **`5.57x` faster** | `0.32s` | `0.05s` | **`6.40x` less** | `70.0 MB` | `109.9 MB` | `+57.0%` (ELF mmap) | `124` | `6` |
| **W3** | `cinterop` (`d1cbridge.def` + `libclang`) | `1.527s` | `0.124s` | **`12.31x` faster** | `1.19s` | `0.11s` | **`10.82x` less** | `219.5 MB` | `142.4 MB` | **`-35.1%` (`1.54x` smaller)** | `1,095` | `20` |
| **W4** | Frontend `.kt -> .klib` (3-File Multi-Platform Lib) | `4.561s` | `2.483s` | **`1.84x` faster** | `4.51s` | `2.46s` | **`1.83x` less** | `291.4 MB` | `217.2 MB` | **`-25.5%` (`1.34x` smaller)** | `1,445` | `1,303` |
| **W5** | Frontend `.kt -> .klib` (9 Compiler CLI Files + 55 KLIBs) | `13.805s` | `16.663s` | `0.83x` | `13.70s` | `16.55s` | `0.83x` | `418.9 MB` | `497.5 MB` | `+18.8%` | `4,284` | `9,375` |
| **W6** | Backend `static_cache` (`BaseLibA.klib`) | `4.395s` | `2.622s` | **`1.68x` faster** | `4.35s` | `2.59s` | **`1.68x` less** | `319.0 MB` | `184.2 MB` | **`-42.3%` (`1.73x` smaller)** | `8,595` | `1,618` |
| **W7** | Two-Stage `.klib -> .kexe` (`AppMain` + `stdlib-cache`) | `6.598s` | `4.902s` | **`1.35x` faster** | `6.54s` | `4.86s` | **`1.35x` less** | `371.5 MB` | `225.8 MB` | **`-39.2%` (`1.65x` smaller)** | `12,999` | `2,655` |
| **W8** | One-Stage 7-Section Corpus (`.kt -> d1-corpus.kexe`) | `12.187s` | `13.900s` | `0.88x` | `12.09s` | `13.80s` | `0.88x` | `483.9 MB` | `321.1 MB` | **`-33.6%` (`1.51x` smaller)** | `21,445` | `5,840` |

---

## 2. Default Multi-Core Desktop Profile (Unpinned 16 Threads, JVM `-Xmx4096m` G1GC vs Native Default)

| # | Workload | JVM Wall (s) | Native Wall (s) | **Wall Speedup** | JVM Total CPU (s) | Native Total CPU (s) | **CPU Efficiency** | JVM Peak RSS | Native Peak RSS | **Memory Saving** | JVM Ctx Sw | Native Ctx Sw |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **W1** | `klib info stdlib` (CLI Cold Start) | `0.184s` | `0.196s` | `0.94x` | `0.49s` | `0.20s` | **`2.45x` less CPU** | `87.7 MB` | `121.1 MB` | `+38.1%` | `2,068` | `18` |
| **W2** | `klib dump-abi` (`e1c-cli-native.klib`) | `0.188s` | `0.055s` | **`3.42x` faster** | `0.45s` | `0.05s` | **`9.00x` less CPU** | `75.2 MB` | `109.4 MB` | `+45.5%` | `2,072` | `17` |
| **W3** | `cinterop` (`d1cbridge.def` + `libclang`) | `0.944s` | `0.127s` | **`7.43x` faster** | `1.61s` | `0.13s` | **`12.38x` less CPU** | `208.7 MB` | `141.6 MB` | **`-32.2%` (`1.47x` smaller)** | `5,834` | `38` |
| **W4** | Frontend `.kt -> .klib` (3-File Multi-Platform Lib) | `2.354s` | `1.727s` | **`1.36x` faster** | `8.48s` | `2.59s` | **`3.27x` less CPU** | `359.8 MB` | `219.9 MB` | **`-38.9%` (`1.64x` smaller)** | `20,498` | `298` |
| **W5** | Frontend `.kt -> .klib` (9 Compiler CLI Files + 55 KLIBs) | `6.336s` | `10.727s` | `0.59x` | `34.40s` | `16.09s` | **`2.14x` less CPU** | `520.1 MB` | `495.8 MB` | **`-4.7%` (`1.05x` smaller)** | `57,077` | `1,630` |
| **W6** | Backend `static_cache` (`BaseLibA.klib`) | `2.329s` | `1.598s` | **`1.46x` faster** | `9.92s` | `2.43s` | **`4.08x` less CPU** | `443.2 MB` | `183.0 MB` | **`-58.7%` (`2.42x` smaller)** | `24,550` | `318` |
| **W7** | Two-Stage `.klib -> .kexe` (`AppMain` + `stdlib-cache`) | `3.280s` | `3.190s` | **`1.03x` faster** | `12.43s` | `5.03s` | **`2.47x` less CPU** | `447.9 MB` | `225.5 MB` | **`-49.7%` (`1.99x` smaller)** | `41,583` | `1,009` |
| **W8** | One-Stage 7-Section Corpus (`.kt -> d1-corpus.kexe`) | `5.992s` | `8.814s` | `0.68x` | `26.21s` | `14.04s` | **`1.87x` less CPU** | `611.9 MB` | `325.1 MB` | **`-46.9%` (`1.88x` smaller)** | `82,981` | `1,338` |

---

## 3. Produced Binary Footprint & Runtime Performance (`W9`: `d1-corpus.kexe`)

| Metric | Built by Official JVM (`stage0`) | Built by Self-Hosted Native (`stage3`) | Comparison |
| :--- | :---: | :---: | :---: |
| **Executable Size** | `22,301,048` bytes (`21.27 MiB`) | `22,299,000` bytes (`21.27 MiB`) | `-2,048` bytes (`-0.01%`) |
| **Runtime Wall Time (Median of 5)** | `0.010s` (`10.3 ms`) | `0.013s` (`13.4 ms`) | Identical ~`10 ms` class |
| **Runtime Peak RSS** | `19.0 MB` | `18.9 MB` | Identical (`-0.1 MB`) |
| **Minor / Major Page Faults** | `2,871` / `0` | `2,870` / `0` | Identical |
| **7-Section Functional & ABI Diff** | Baseline Oracle | `0` diff | **100% Semantic & ABI Match** |

---

## 4. Optimization Level Scaling (`L0` `-O0` -> `L5` `konanc -opt` Full Whole-Program IR + LTO + `-O3`)

- **Raw Data**: [benchmark-opt-levels.json](data/benchmark-opt-levels.json)
- **Workload (`OptBench.kt`)**:
  1. **AST / FIR-like Polymorphic Visitor** (`65,535` nodes $\times$ `80` passes — stresses `BuildDFG`, `Devirtualization`, and inlining);
  2. **Scope Resolution & Value Class Pipeline** (`8,000,000` frames — stresses `EscapeAnalysis` stack allocation and `UnboxInline`);
  3. **Tight Bitwise / Hash Kernel** (`4,096` elements $\times$ `6,000` rounds — stresses `RemoveRedundantSafepoints`, register allocation, and LLVM `-O3` vectorization).
- **Semantic Equivalence**: All 6 tiers produce the exact same checksum (`CHECKSUM=8818135076601557240`).

| Tier | Optimization Configuration | Compile Wall (s) | Compile Peak RSS | Binary Size | AST Visitor (`ms`) | Scope / ValueClass (`ms`) | Tight Kernel (`ms`) | **Total Runtime (`ms`)** | **Runtime RSS** | **Total Speedup vs `L0`** |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`L0`** | `-g` + `-Xdisable-phases=...` + `clang++ -O0` (Low-Mem zram Recipe) | `3.52s` | `231.5 MB` | `21.10 MiB` (`22,123,008` B) | `121.32 ms` | `828.67 ms` | `109.97 ms` | **`1,059.96 ms`** | `16.6 MB` | `1.00x` (Baseline) |
| **`L1`** | `-g` Default (Standard Debug Lowering + LLVM `-O0`, cached `stdlib`) | `3.35s` | `229.9 MB` | `21.09 MiB` (`22,115,024` B) | `121.81 ms` | `841.78 ms` | `117.68 ms` | **`1,081.27 ms`** | `16.5 MB` | `0.98x` |
| **`L2`** | `-g` IR Opt + `clang++ -O1` (App-only LLVM `-O1`, cached `-g` `stdlib`) | `3.44s` | `233.6 MB` | `21.10 MiB` (`22,122,232` B) | `75.47 ms` (`1.61x`) | `688.72 ms` (`1.20x`) | `95.78 ms` (`1.15x`) | **`859.96 ms`** | `16.6 MB` | **`1.23x` faster** |
| **`L3`** | `-g` IR Opt + `clang++ -O2` + Vectorization (Cached `-g` `stdlib`) | `3.48s` | `233.2 MB` | `21.10 MiB` (`22,122,232` B) | `72.99 ms` (`1.66x`) | `702.77 ms` (`1.18x`) | `89.84 ms` (`1.22x`) | **`865.61 ms`** | `16.6 MB` | **`1.22x` faster** |
| **`L4`** | `-g` IR Opt + `clang++ -O3` + Vectorization (Cached `-g` `stdlib`) | `3.76s` | `233.7 MB` | `21.10 MiB` (`22,122,248` B) | `74.37 ms` (`1.63x`) | `694.40 ms` (`1.19x`) | `89.61 ms` (`1.23x`) | **`858.38 ms`** | `16.6 MB` | **`1.23x` faster** |
| **`L5`** | **`konanc -opt` (Full Release: Whole-Program Devirt + EscapeAnalysis + UnboxInline + DCE + LTO + LLVM `-O3`)** | `33.17s` | `444.4 MB` | **`0.29 MiB` (`300,792` B, `-98.6%`)** | **`11.95 ms` (`10.15x`)** | **`27.98 ms` (`29.62x`)** | **`22.93 ms` (`4.80x`)** | **`62.86 ms`** | **`7.6 MB` (`-54.2%`)** | **`16.86x` faster** |

---

## 5. Direct Head-to-Head: Full Whole-Program Optimization (`konanc -opt`) vs JVM (JDK 21 HotSpot)

- **Raw Data**: [benchmark-opt-vs-jvm.json](data/benchmark-opt-vs-jvm.json)

### 5.1 Workload Execution (`OptBench.kt`: Native `konanc -opt` Binary vs JDK 21 HotSpot JVM)

| Metric | JDK 21 HotSpot (Default `-Xmx4G` G1GC) | Native `konanc -opt` (Default) | **Native `-opt` Advantage (Desktop)** | JDK 21 HotSpot (`1 CPU` SerialGC) | Native `konanc -opt` (`1 CPU`) | **Native `-opt` Advantage (`1 CPU`)** |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Process Wall Time (Median of 5)** | `130.6 ms` | **`61.7 ms`** (min `59.5 ms`) | **`2.12x` faster** | `165.0 ms` | **`63.3 ms`** | **`2.61x` faster** |
| **Total CPU Time (`user + sys`)** | `210.0 ms` | **`50.0 ms`** | **`4.20x` less CPU** | `150.0 ms` | **`50.0 ms`** | **`3.00x` less CPU** |
| **In-Process Pure Compute Total (`TOTAL_MS`)** | `80.82 ms` (min `79.47 ms`) | **`57.61 ms`** (min `55.08 ms`) | **`1.40x ~ 1.44x` faster** | `98.47 ms` (min `92.54 ms`) | **`59.34 ms`** (min `57.99 ms`) | **`1.66x` faster** |
| — **1. Polymorphic AST Visitor (`AST_MS`)** | `12.52 ms` (min `11.31 ms`) | **`9.72 ms`** (min `7.48 ms`) | **`1.29x ~ 1.51x` faster** | `27.90 ms` | **`8.04 ms`** | **`3.47x` faster** |
| — **2. Scope & ValueClass Pipeline (`SCOPE_MS`)** | `42.44 ms` (min `41.90 ms`) | **`25.50 ms`** (min `25.25 ms`) | **`1.66x` faster** | `43.34 ms` | **`25.88 ms`** | **`1.67x` faster** |
| — **3. Tight Bitwise/Hash Loop (`KERNEL_MS`)** | `25.87 ms` (min `25.29 ms`) | **`22.40 ms`** (min `22.24 ms`) | **`1.15x` faster** | `27.25 ms` | **`25.48 ms`** | **`1.07x` faster** |
| **Peak Memory (`RSS`)** | `61.7 MB` | **`7.6 MB`** | **`8.12x` smaller (`-87.7%`)** | `52.4 MB` | **`7.8 MB`** | **`6.72x` smaller (`-85.1%`)** |
| **Minor Page Faults** | `11,288` | **`1,017`** | **`11.1x` fewer** | `8,703` | **`1,080`** | **`8.06x` fewer** |
| **Context Switches** | `601` | **`6`** | **`100.2x` fewer** | `675` | **`13`** | **`51.9x` fewer** |

### 5.2 Compiler `-opt` Build Resource Comparison (Official JVM `konanc -opt` vs Self-Hosted Native `konanc -opt`)

| Metric | Official JVM `konanc -opt` (`stage0`) | Self-Hosted Native `konanc -opt` (`stage3`) | Comparison |
| :--- | :---: | :---: | :--- |
| **Produced Binary Size** | `300,792` bytes (`293.7 KiB`) | `300,792` bytes (`293.7 KiB`) | **Exact byte-size match** |
| **Desktop Peak Memory (`RSS`)** | `837.1 MB` | **`444.5 MB`** | **Native uses `46.9%` less RAM (`1.88x` smaller)** |
| **Single-Core Peak Memory (`RSS`)** | `606.2 MB` | **`446.2 MB`** | **Native uses `26.4%` less RAM (`1.36x` smaller)** |
| **Desktop Total CPU Time (`user + sys`)** | `53.88s` | **`48.84s`** | **Native uses `9.4%` less total CPU time** |
| **Desktop Context Switches** | `90,902` | **`2,245`** | **Native has `40.5x` fewer context switches** |
| **Desktop Compile Wall Time** | `13.28s` | `33.23s` | JVM uses multi-core JIT/GC threads (`53.88s` CPU in `13.28s` wall); `stage3` binary itself was compiled at `-O0` |

---

## 6. Multi-Core Scaling Benchmark (`1, 2, 4, 8, 16` Threads & `-j1 .. -j16` Parallel Compilation)

- **Raw Data**: [benchmark-multicore.json](data/benchmark-multicore.json)
- **Workload (`MultiCoreNative.kt` vs `MultiCoreJvm.kt`)**:
  - `16` independent compiler-style work units (`640` AST visitor passes on `65,535`-node trees + `64,000,000` `ScopeFrame` resolutions + `48,000` tight hash kernel rounds) partitioned across `T` worker threads (`pthread_create` on Native `konanc -opt` vs `java.lang.Thread` on JDK 21 HotSpot `-Xmx4096m` G1GC).
  - Both produce identical checksums across all thread counts (`CHECKSUM=-3175046192169356254`).

### 6.1 Multi-Core Parallel Runtime Scaling (`1T -> 2T -> 4T -> 8T -> 16T` on `i5-12500H`)

| Threads (`T`) | JVM Wall (`ms`) | Native `-opt` Wall (`ms`) | **Wall Speedup** | JVM In-Proc (`ms`) | Native `-opt` In-Proc (`ms`) | **In-Proc Speedup** | JVM Self-Scaling | Native `-opt` Self-Scaling | JVM CPU (`s` / `%`) | Native CPU (`s` / `%`) | JVM Peak RSS | Native Peak RSS | **Memory Advantage** |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`1T`** | `541.0 ms` | **`495.4 ms`** | **`1.09x` faster** | `478.93 ms` | `491.34 ms` | `0.97x` (tie) | `1.00x` | `1.00x` | `0.62s` (`117%`) | **`0.49s` (`100%`)** | `75.0 MB` | **`14.9 MB`** | **`5.03x` smaller** |
| **`2T`** | `332.2 ms` | **`266.9 ms`** | **`1.24x` faster** | `284.89 ms` | **`262.36 ms`** | **`1.09x` faster** | `1.68x` | **`1.87x`** | `0.71s` (`215%`) | **`0.52s` (`200%`)** | `76.1 MB` | **`15.3 MB`** | **`4.97x` smaller** |
| **`4T`** | `304.3 ms` | **`175.0 ms`** | **`1.74x` faster** | `252.79 ms` | **`169.37 ms`** | **`1.49x` faster** | `1.89x` (plateau) | **`2.90x`** | `1.16s` (`384%`) | **`0.62s` (`361%`)** | `80.5 MB` | **`23.1 MB`** | **`3.48x` smaller** |
| **`8T`** | `315.5 ms` | **`147.2 ms`** | **`2.14x` faster** | `269.26 ms` | **`141.40 ms`** | **`1.90x` faster** | `1.78x` (regression) | **`3.47x`** | `2.13s` (`676%`) | **`0.79s` (`559%`)** | `90.4 MB` | **`32.9 MB`** | **`2.75x` smaller** |
| **`16T`** | `322.4 ms` | **`113.3 ms`** | **`2.85x` faster** | `266.07 ms` | **`107.09 ms`** | **`2.48x` faster** | `1.80x` (bottlenecked) | **`4.59x`** | `3.69s` (`1150%`) | **`1.15s` (`1036%`)** | `104.7 MB` | **`40.0 MB`** | **`2.62x` smaller** |

### 6.2 Multi-Core Parallel Compilation Throughput (`16` Build Modules: `8` `cinterop` + `8` `.kt -> .klib` at `-j1 .. -j16`)

| Parallel Jobs (`-j`) | JVM `stage0` Batch Wall (`s`) | Native `stage3` Batch Wall (`s`) | **Batch Wall Speedup** | JVM Throughput (`mod/s`) | Native Throughput (`mod/s`) | JVM Total CPU (`s` / `%`) | Native Total CPU (`s` / `%`) | **CPU Core-Time Saving** | JVM Max Child RSS | Native Max Child RSS | JVM Context Switches | Native Context Switches |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`-j1`** | `22.82s` | **`11.22s`** | **`2.03x` faster** | `0.70 mod/s` | **`1.43 mod/s`** | `68.73s` (`300%`) | **`16.12s` (`143%`)** | **`4.26x` less CPU** | `381.7 MB` | **`200.9 MB`** | `275,107` | **`1,783` (`154x` fewer)** |
| **`-j4`** | `10.73s` | **`4.41s`** | **`2.43x` faster** | `1.49 mod/s` | **`3.63 mod/s`** | `107.82s` (`1005%`) | **`24.20s` (`548%`)** | **`4.46x` less CPU** | `361.5 MB` | **`202.0 MB`** | `298,425` | **`2,289` (`130x` fewer)** |
| **`-j8`** | `9.07s` | **`2.92s`** | **`3.11x` faster** | `1.76 mod/s` | **`5.48 mod/s`** | `124.84s` (`1376%`) | **`31.26s` (`1070%`)** | **`3.99x` less CPU** | `337.6 MB` | **`200.7 MB`** | `347,525` | **`3,054` (`114x` fewer)** |
| **`-j16`** | `9.27s` | **`2.85s`** | **`3.25x` faster** | `1.73 mod/s` | **`5.61 mod/s`** | `139.88s` (`1508%`) | **`32.84s` (`1151%`)** | **`4.26x` less CPU** | `348.9 MB` | **`199.8 MB`** | `408,998` | **`3,750` (`109x` fewer)** |



