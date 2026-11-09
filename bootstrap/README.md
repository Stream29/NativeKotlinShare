# 三阶段自举与独立分发复现指南 (`stage1 -> stage2 -> stage3`)

本目录包含将 [`../kotlin`](../kotlin) 子模块中的 Native 宿主化编译器源码进行 `stage1 -> stage2 -> stage3` 三阶段自举、全量回归校验并打包为独立 `native-kotlin-dist-linux-x64` 分发包的完整脚本与哈希清单。

---

## 1. 目录结构

- [`build-stage1-to-stage3.sh`](build-stage1-to-stage3.sh)：三阶段自举、不动点验证、E1/D1 全量回归与独立分发包组装入口脚本。
- [`manifests/`](manifests/)：
  - [`production.sha256`](manifests/production.sha256)：[`../kotlin`](../kotlin) 子模块中 `3,430` 个生产源文件的精确 SHA256 校验清单。
  - [`fixed-point-report.md`](manifests/fixed-point-report.md)：`stage1`、`stage2`、`stage3` 二进制哈希、KLIB ABI 签名与不动点验证报告。
  - [`bootstrap-stages-report.tsv`](manifests/bootstrap-stages-report.tsv)：三阶段产物大小、符号数与 SHA256 制表数据。
  - [`dist-manifest.sha256`](manifests/dist-manifest.sha256)：最终打包的 `native-kotlin-dist-linux-x64`（共 `697` 个文件）的 SHA256 清单。
  - [`dist-SUPPORT_MATRIX.md`](manifests/dist-SUPPORT_MATRIX.md) / [`dist-KNOWN_LIMITATIONS.md`](manifests/dist-KNOWN_LIMITATIONS.md) / [`dist-REPRODUCIBLE_BUILD.md`](manifests/dist-REPRODUCIBLE_BUILD.md)：分发包内附带的支持矩阵、已知边界与可复现构建说明。
- [`tests/`](tests/)：
  - [`tests/E1/`](tests/E1/)：8-Section 编译器全链路测试集（CLI、KLIB、CInterop、Friend Modules、Static Cache、诊断输出）。
  - [`tests/D1/`](tests/D1/)：7-Section 标准库与运行时语料测试集（集合、协程、反射、原子操作、C 桥接、POSIX、zlib）。

---

## 2. 校验 `../kotlin` 子模块的 3,430 个生产源文件

在仓库根目录（`NativeKotlinShare/`）执行：

```bash
sha256sum --quiet -c bootstrap/manifests/production.sha256 && echo "ALL 3,430 PRODUCTION SOURCES VERIFIED"
```

---

## 3. 执行三阶段自举与无 JVM 回归测试

准备好基础依赖目录（包含 LLVM `21.1.6` `llvm-21-x86_64-linux-dev-116` 与 `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2` sysroot）及 `stage1`/`D1` 构建产物后，运行：

```bash
WORK_ROOT="$(pwd)" \
BASELINE_ROOT="/path/to/NativeKotlin-baseline" \
DEPS_ROOT="/path/to/NativeKotlin-port" \
JAVA_HOME="/path/to/jdk-21" \
OUTPUT_DIR="/tmp/s1-bootstrap-out" \
  bash bootstrap/build-stage1-to-stage3.sh all
```

脚本将在 `env -i PATH=/nonexistent` 下自动完成：
1. `stage1 -> stage2` 自编译与静态缓存链接；
2. `stage2 -> stage3` 二次自编译与 `stage2 == stage3` 二进制哈希核对；
3. `E1` 与 `D1` 测试集在 `stage2`、`stage3` 与 JVM Oracle 之间的零差异比对；
4. 输出完整的 `native-kotlin-dist-linux-x64/` 独立原生工具链目录。
