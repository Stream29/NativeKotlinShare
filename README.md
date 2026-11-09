# NativeKotlinShare — Self-Hosted Kotlin/Native Compiler (`0` JVM)

将官方 Kotlin/Native 编译器（`konanc`、`cinterop`、`klib`）从 JVM 宿主完整移植并自举至 **4 大原生宿主平台（`linux_x64`、`linux_arm64`、`macos_arm64`、`mingw_x64` / Windows x64）**，在不依赖任何 JVM、Java 字节码或 JNI 的纯净环境（`env -i PATH=/nonexistent`）下独立运行，并完成 `stage0 -> stage1 -> stage2 -> stage3` 三阶段自举与逐字节不动点验证（`stage2 == stage3`）。

---

## 预编译四平台发行包下载（[`v2.4.21-native`](https://github.com/Stream29/NativeKotlinShare/releases/tag/v2.4.21-native)）

所有预编译包均保持与官方 `kotlin-native-prebuilt` 一致的开箱即用体验：首次执行 `bin/konanc`（或 `kotlinc-native`、`cinterop`、`klib`）时自动检查并初始化 `${KONAN_DATA_DIR:-~/.konan}/dependencies` 中的官方 LLVM / sysroot 依赖，全程 **0 JVM 依赖**。

| 宿主平台 | 发行包名称 | 体积 | SHA256 校验和 |
| :--- | :--- | :---: | :--- |
| **Linux x86_64** (`linux_x64`) | [`native-kotlin-dist-linux-x64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-x64.tar.gz) | `75 MB` | `688d8c4141332eae3a2cbdf009df2b10fb5934da4240db5c5c516738c5ce0cda` |
| **Linux ARM64** (`linux_arm64`) | [`native-kotlin-dist-linux-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-linux-arm64.tar.gz) | `131 MB` | `31134e9d289e30035551865e25eb5b3d5bf22eb198e7602ee765a0e0b93ede01` |
| **macOS Apple Silicon** (`macos_arm64`) | [`native-kotlin-dist-macos-arm64.tar.gz`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-macos-arm64.tar.gz) | `122 MB` | `787efc36888fa4f0c96e89eb08d63429d099fc3a0eec9649c08ce6ab95480f75` |
| **Windows x64** (`mingw_x64`) | [`native-kotlin-dist-windows-x64.zip`](https://github.com/Stream29/NativeKotlinShare/releases/download/v2.4.21-native/native-kotlin-dist-windows-x64.zip) | `40 MB` | `93ad96da927fc7160dfbf02cbcd63bf8e0ea77cbe356726c50a877e7f581a032` |

---

## 核心成果速览

- **基线版本**：上游 [`JetBrains/kotlin` @ `9180ce6a7a7d3627f88b424717583f28ddca052d`](./kotlin)（`2.4.20-dev-7885`），配套 LLVM `21.1`。
- **与官方 `:kotlin-native:dist` 产物 100% 语义/IR 对齐**：
  - 使用官方 JVM `konanc` 与自举 Native `konanc` 编译同一份源码：KLIB 内部目录树（`29/29` 项）、`default/manifest`、`klib dump-abi`、`klib dump-ir`、`klib dump-metadata`、`klib dump-metadata-signatures` **100% 零差异**；全部 16 个 IR 二进制文件（`default/ir/*` 与 `default/ir_inlinable_functions/*`）及 `default/linkdata/module` 达到 **SHA256 逐字节完全一致**；生成的 `.kexe` 运行输出与动态符号表（`nm -D`）**100% 一致**。
- **零算法重写、最小化宿主适配**：
  - 完整保留上游编译器核心算法、K2 FIR 前端、PSI/LightTree 解析器、KLIB 序列化/反序列化、Native IR Lowering、全程序优化（`Devirtualization`、`EscapeAnalysis`、`UnboxInline`、`DCE`）以及 LLVM 21 Bitcode 代码生成流水线。
  - 仅用纯 Kotlin/Native（POSIX / `zlib` / 原生内存分配器 / `libllvmext` / `libclangext`）替换底层 JVM I/O、ZIP、反射、ServiceLoader 与 JNI 桥接。
- **三阶段自举与二进制不动点（`stage2 == stage3`）**：
  - 全量生产源码闭包共 **`3,430` 个源文件**，组装为 **`55` 个编译器 KLIB 模块**。
  - 在 `env -i PATH=/nonexistent` 隔离环境下完成 `stage1 -> stage2 -> stage3` 自编译：
    - `stage2/konanc-cli.kexe` 与 `stage3/konanc-cli.kexe`（`185,383,000` 字节，导出符号 `474,762` 个）达到**二进制逐字节完全一致**（SHA256: `cdf4849a9f9a2a4892348a8b27face322c89f78dbfc8f99ccbbc1886701d025e`）。
    - `stage1`、`stage2`、`stage3` 的 KLIB ABI（`klib dump-abi -signature-version 2`）**100% 零差异**（SHA256: `5366cdb5bc967940024f28345e57a8787f9398bcbe8de9ff9d98278296cd8860`）。
- **内存与多核性能显著领先 JVM（`Intel Core i5-12500H`，16 线程）**：
  - **受限环境构建**：整个编译器的自举与链接可在 **`1.3 GiB` 峰值内存（`0 B` Swap）**、约 **4 分 52 秒**内完成。
  - **多核并发模块编译（`-j16` 并发构建 16 个模块）**：自托管 Native 工具链仅耗时 **`2.85s`**，相比官方 JDK 21 工具链（`9.27s`）提速 **`3.25x`**，累计 CPU 核时消耗减少 **`4.26x`**（`32.84s` vs `139.88s`），上下文切换减少 **`109x`**。
  - **全程序优化（`konanc -opt`）对比 JDK 21 HotSpot**：
    - 相比 `-O0` 调试构建，开启 `konanc -opt`（全程序跨模块内联 + 去虚化 + 逃逸分析栈分配 + Value Class 拆箱 + 全局 DCE + LTO + LLVM `-O3`）后运行速度提升 **`16.86x`**，产物体积从 `21.10 MiB` 缩减至 **`293.7 KiB`（缩减 `98.6%`）**。
    - 与预热后的 JDK 21 HotSpot（`-Xmx4G` G1GC）正面对决：单线程下端到端快 **`2.12x`**、内存占用小 **`8.12x`**（`7.6 MB` vs `61.7 MB`）；在 **16 线程多核满载**下，因彻底消除了 GC 堆分配争用与后台 JIT 抢核，端到端快 **`2.85x`**（`113.3 ms` vs `322.4 ms`），进程内纯并行计算快 **`2.48x`**（`107.09 ms` vs `266.07 ms`）。

---

## 仓库目录结构

```text
NativeKotlinShare/
├── README.md                          # 项目总览与快速导航
├── LICENSE                            # Apache-2.0 开源许可证
├── kotlin/                            # [Git Submodule] 基于 9180ce6a7a 的 Kotlin 源码树及 6 层 Native 宿主化 Commit 栈
├── blog/                              # 系列深度技术文章
│   ├── 01-why-and-architecture.md     # 第 1 篇：为什么要脱掉 JVM？从 0 JVM 依赖看 Kotlin/Native 编译器的自举架构
│   ├── 02-host-shims-and-frontend.md  # 第 2 篇：跨越 JVM 鸿沟：在 Native 上运行 IntelliJ PSI、K2 FIR 与 LLVM/Clang 绑定
│   ├── 03-three-stage-bootstrap.md    # 第 3 篇：55 个 KLIB 与三阶段自举（stage0 -> stage1 -> stage2 == stage3 不动点）
│   └── 04-performance-opt-and-multicore.md # 第 4 篇：全程序优化（konanc -opt）与多核并发下 Native vs JDK 21 实测对决
├── benchmarks/                        # 完整性能基准测试报告、原始 JSON 数据与复现脚本
│   ├── README.md                      # 6 大维度性能评测总报告
│   ├── data/                          # 单核/多核/-opt 原始评测 JSON 数据
│   └── scripts/                       # 自动化基准测试复现脚本
└── bootstrap/                         # 三阶段自举构建脚本、3,430 文件哈希清单与端到端回归测试集
    ├── README.md                      # 自举构建指南与低内存构建配方说明
    ├── build-stage1-to-stage3.sh      # 零 JVM 三阶段自举与独立分发打包脚本
    ├── manifests/                     # 源码闭包、不动点报告与分发文件 SHA256 清单
    └── tests/                         # E1（8-section）与 D1（7-section）端到端语义/ABI 校验集
```

---

## 系列技术文章导航

1. **[为什么要脱掉 JVM？从 0 JVM 依赖看 Kotlin/Native 编译器的自举架构](blog/01-why-and-architecture.md)**
   - 剖析官方 Kotlin/Native 编译器“产物是 Native、编译器自身却捆绑在 JVM/JNI 上”的历史包袱，以及构建纯 Native 工具链的 55-KLIB 分层拓扑。
2. **[跨越 JVM 鸿沟：如何在 Kotlin/Native 上运行 IntelliJ PSI、K2 FIR 与 LLVM/Clang 绑定](blog/02-host-shims-and-frontend.md)**
   - 详解 `H0/H1` POSIX+zlib 宿主适配层、IntelliJ VFS/PSI 的去反射化改造、以及 965 个 LLVM 绑定与 378 个 `libclang` 绑定的纯 Native 接通。
3. **[55 个 KLIB 与三阶段自举：`stage0 -> stage1 -> stage2 == stage3` 逐字节不动点](blog/03-three-stage-bootstrap.md)**
   - 拆解如何利用 `static_cache` 与三步式 Bitcode 链接配方在 `1.3 GiB` 内存峰值下完成自举，并在 `env -i` 环境中达成二进制不动点。
4. **[性能实测：全程序优化（`konanc -opt`）与多核并发下 Native vs JDK 21 HotSpot 的全面对决](blog/04-performance-opt-and-multicore.md)**
   - 通过 6 级优化梯度（`L0 -> L5`）、1~16 线程并行扩展性与 `-j1..-j16` 多模块并发构建实测，揭示全程序逃逸分析、拆箱内联与零 GC/JIT 争用带来的性能质变。

---

## 内层 `./kotlin` 子模块 6 层 Commit 栈说明

克隆本仓库及内层 [`./kotlin`](./kotlin) 子模块：

```bash
git clone --recurse-submodules https://github.com/Stream29/NativeKotlinShare.git
cd NativeKotlinShare/kotlin
git log --oneline 9180ce6a7a7d3627f88b424717583f28ddca052d..HEAD
```

在 [`./kotlin`](./kotlin) 子模块中，所有改动均基于官方固定上游 Commit `9180ce6a7a7d3627f88b424717583f28ddca052d`（`2.4.20-dev-7885`），按架构层次自底向上分为 **6 个独立且语义内聚的 Commit**：

| 层级 | Commit 主题 | 核心路径 | 职责概述 |
| :---: | :--- | :--- | :--- |
| **1** | `feat(native-host): add POSIX/Win32/zlib host shims, unsafe-mem, and KLIB/config foundations` | `core/*`, `compiler/config`, `compiler/util*`, `native/unsafe-mem`, `native/utils`, `kotlin-native/klib` | 替换 `java.io`/`java.nio`/`java.util.zip` 与 JVM 反射，提供跨平台（Linux/macOS/Windows）文件系统、ZIP 容器、原生内存分配器与 KLIB 基础层 |
| **2** | `feat(native-host): port libllvmext, llvmDebugInfoC, and libclangext bindings to Kotlin/Native` | `kotlin-native/{llvmInterop,libllvmext,llvmDebugInfoC,libclangInterop,libclangext,Interop}` | 原生化 965 个 LLVM C/C++ 绑定与 378 个 `libclang` 绑定，接通纯 Native `cinterop` Indexer 与 StubGenerator |
| **3** | `feat(native-host): adapt IntelliJ PSI, LightTree lexer/parser, container, and VFS for Native host` | `compiler/psi/*`, `compiler/container/*`, `compiler/frontend.common*` | 适配 JFlex 词法分析器、Kotlin 语法解析器、LightTree、PSI AST 与 DI 容器，消除 IntelliJ 动态服务反射 |
| **4** | `feat(native-host): port K2 FIR frontend, resolution, and KLIB serializer to Native` | `compiler/fir/*`, `compiler/resolution.common`, `compiler/serialization`, `compiler/cli/cli-native-klib` | 接通完整 K2 FIR 树、Cone 类型系统、符号解析、Checkers 诊断、FIR2IR 与 KLIB 序列化 |
| **5** | `feat(native-host): port Kotlin/Native IR inline, lowerings, and LLVM bitcode/cache backend` | `compiler/ir/*`, `native/{frontend,base}`, `kotlin-native/backend.native` | 接通 Native IR 反序列化、内联、全程序优化（`Devirt`/`EscapeAnalysis`/`UnboxInline`/`DCE`）、LLVM Bitcode 发射与 `static_cache` 后端 |
| **6** | `feat(native-host): add zero-JVM konanc/cinterop/klib Native CLI entrypoints and runtime recipes` | `compiler/cli/cli-base`, `native/cli-native`, `kotlin-native/{utilities,runtime,platformLibs}` | 提供表驱动参数解析与 `konanc-cli.kexe` 多合一 CLI 入口，达成 `stage2 == stage3` 不动点自举 |

---

## 快速体验与复现入口

- **查看完整性能评测报告与图表**：[benchmarks/README.md](benchmarks/README.md)
- **复现三阶段自举与 `stage2 == stage3` 校验**：[bootstrap/README.md](bootstrap/README.md)
