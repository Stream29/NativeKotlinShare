# 第 2 篇：跨越 JVM 鸿沟：如何在 Kotlin/Native 上运行 IntelliJ PSI、K2 FIR 与 LLVM/Clang 绑定

将百万行规模的 Kotlin 编译器从 JVM 迁移到 Kotlin/Native，最大的拦路虎不是 Kotlin 语法本身，而是编译器源码树中无处不在的 **JDK 基础库调用、IntelliJ 平台动态反射机制以及 C/C++ JNI 扩展**。

对应 [`./kotlin` 仓库](../kotlin) 的前 4 层 Commit，我们通过以下四项核心改造实现了零算法损失的平滑迁移。

---

## 1. 用 POSIX + `zlib` 原位替代 `java.io` / `java.nio` / `java.util.zip`（Commit 1）

Kotlin 编译器对 KLIB（本质上是特定目录布局或 ZIP 归档）的读写重度依赖 `java.nio.file.FileSystem`（尤其是 `jar:file:...` ZIP 文件系统挂载）以及 `java.util.Properties`。

在 Kotlin/Native 宿主上：
1. **POSIX 文件系统层（`KonanFile` 与 `java.io.File` 兼容桥接）**：
   - 使用 `platform.posix` 的 `stat`、`opendir`/`readdir`、`realpath`、`mkdir`、`rename`、`unlink` 构建宿主无关的路径与目录操作，保证符号链接解析与原子重命名（`rename`）与 Linux 内核行为完全一致。
2. **基于 `platform.zlib` 的原生 ZIP 容器引擎**：
   - KLIB 既可以展开为目录，也可以打包为 `.klib`（ZIP 格式）。我们直接在内存与文件流之上实现了完整的 ZIP Central Directory 解析、CRC32 校验与 `deflate`/`inflate` 压缩流，无需挂载任何虚拟 NIO 文件系统即可透明读写 KLIB 内的 `default/manifest`、`default/linkdata/` 与 `default/ir/`。
3. **编译期固定 Unicode 表（`compiler-names`）**：
   - 标识符校验与名称规范化原本依赖 `java.lang.Character.getType(codePoint)`。我们将 JVM Unicode 字符分类表提取为紧凑的静态查找表，与 JVM Oracle 做了 `65,807` 组边界用例差分验证，实现 `0` 误差。

---

## 2. 将 965 个 LLVM 绑定与 378 个 `libclang` 绑定去 JNI 化（Commit 2）

官方 Kotlin/Native 源码树中虽然包含 `llvmInterop` 与 `libclangInterop` 的 `.def` 定义文件，但在日常构建中还混杂了 JNI 辅助调用（如 `cli-runner` 中的 JNI `setEnv`、`renameAtomic` 等）。

- **LLVM 21 完整扩展绑定（`L0`）**：
  - 原样导入 `llvmInterop`、`libllvmext`、`llvmDebugInfoC` 三个上游模块，核对全部 **965 个 C/C++ 函数声明与符号导出**。
  - 在 Native 进程内直接创建 LLVM `Module`、构建函数 CFG、生成 DWARF 调试信息并写出 `.bc` Bitcode 文件。
- **`libclang` 与 `cinterop` 全链路原生化（`C0A` + `C1I` + `C1S`）**：
  - 导入 `libclangInterop` 与 `libclangext` 的 **378 个函数绑定**。
  - 在 Native 宿主上接通完整的 `Indexer`（通过 `clang_visitChildren` 原生函数指针回调遍历 C AST、宏常量、结构体布局与位域）与 `StubGenerator`（生成 Kotlin CValues/CPointer 桥接源码并序列化为 `.klib`）。

---

## 3. 给 IntelliJ PSI 与命令行解析器做“去反射化手术”（Commit 3 & 6）

Kotlin 编译器前端沿用了 IntelliJ IDEA 的底层基础设施（`com.intellij.openapi.*`、`com.intellij.psi.*`），同时命令行参数解析（`parseCommandLineArguments`）在 JVM 上是通过运行时反射扫描 `@Argument` 注解字段实现的。而在 Kotlin/Native 中，不支持任意类的运行时字段反射读写与动态字节码生成。

我们采取了**“编译期/表驱动替代运行时反射”**的方案：

1. **IntelliJ `ApplicationEnvironment` 与 `Project` 静态服务注册**：
   - 将原本通过 XML + `Class.forName` 动态实例化的核心服务（如 `PsiBuilderFactory`、`VirtualFileSystem`、`FileDocumentManager`）改为确定性的显式注册表。
   - 原样保留 JFlex 生成的 `_JetLexer.kt`、语法分析器 `KotlinParsing.kt`、以及 K2 专用的 `LightTree` 构建器，使源码解析产生的语法树节点位置、Token 序列和语法错误诊断与 JVM 版 100% 一致。
2. **表驱动命令行参数解析器（`K2NativeArgumentParserSupport.kt`）**：
   - 为 `CommonCompilerArguments`、`K2NativeCompilerArguments`、`CInteropArguments` 提供确定性的属性描述符表，完整支持全部标准参数（`-produce`、`-opt`、`-g`、`-l`、`-Xinclude`、`-Xstatic-framework`、`-Xdisable-phases` 等）的解析、默认值填充与错误诊断。

---

## 4. 原样运行 K2 FIR 前端与 FIR2IR 流水线（Commit 4）

当底层的 VFS、KLIB 读取器和 PSI/LightTree 就位后，最核心的 **K2 FIR（Frontend IR）** 层反而展现出了极高的平台无关性：

- 无论是 `compiler/fir/tree`、`cones`（类型系统）、`providers`、`resolve`（符号解析与类型推导）、`checkers`（静态检查器），还是 `fir-serialization` 与 `fir2ir`，其内部逻辑几乎完全由纯 Kotlin 数据结构与算法构成。
- 我们在不改动任何类型推导规则与诊断算法的前提下，将 **2,733 个 FIR 相关源文件**完整编译进 Native KLIB，并通过了多模块、多平台（`-Xmulti-platform` / `expect-actual`）、内联值类（`@JvmInline value class`）与复杂泛型约束的差分测试。

下一篇我们将探讨如何把这 55 个 KLIB 模块串成一条可在 **1.3 GiB 内存限制**下运行的构建流水线，并完成 `stage0 -> stage1 -> stage2 -> stage3` 的三阶段自举。
