#!/usr/bin/env python3
"""
Reproducible Multi-Core Benchmark (`1, 2, 4, 8, 16` threads runtime scaling & `-j1 .. -j16` parallel compilation):
1. Multi-Core Runtime Scaling: `konanc -opt` (`pthread_create` / `pthread_join`) vs JDK 21 HotSpot (`java.lang.Thread`)
   across 16 independent compiler-style work units (640 AST passes + 64,000,000 ScopeFrame resolutions + 48,000 hash kernel rounds).
2. Multi-Core Parallel Compilation Throughput: Self-Hosted Native Toolchain (`stage3`) vs Official JVM Toolchain (`stage0`)
   building a 16-module batch (8 `cinterop` jobs + 8 `konanc -produce library` jobs) at `-j1, -j4, -j8, -j16`.
"""
import os
from pathlib import Path

BASELINE_DIST = Path(os.environ.get("BASELINE_DIST", "/home/stream/ACodeSpace/local/NativeKotlin-baseline/kotlin/kotlin-native/dist"))
NATIVE_DIST = Path(os.environ.get("NATIVE_DIST", "/home/stream/ACodeSpace/local/NativeKotlin-port/build/s1/native-kotlin-dist-linux-x64"))
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/home/stream/.sdkman/candidates/java/21.0.12+1.1-tem"))
KONAN_DATA_DIR = Path(os.environ.get("KONAN_DATA_DIR", "/home/stream/ACodeSpace/local/NativeKotlin-baseline/konan-home"))
REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_JSON = REPO_ROOT / "benchmarks" / "data" / "benchmark-multicore.json"

if __name__ == "__main__":
    print(f"Multi-core benchmark suite configured. Output: {OUT_JSON}")
