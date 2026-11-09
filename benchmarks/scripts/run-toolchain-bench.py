#!/usr/bin/env python3
"""
Reproducible W1-W9 Toolchain Benchmark:
Compares Official JVM Kotlin/Native Toolchain (stage0) vs Self-Hosted Native Toolchain (stage3)
under both Constrained Single-Core (`taskset -c 0`) and Default Multi-Core Desktop profiles.
"""
import json
import os
import shutil
import statistics
import subprocess
import time
from pathlib import Path

BASELINE_DIST = Path(os.environ.get("BASELINE_DIST", "/home/stream/ACodeSpace/local/NativeKotlin-baseline/kotlin/kotlin-native/dist"))
NATIVE_DIST = Path(os.environ.get("NATIVE_DIST", "/home/stream/ACodeSpace/local/NativeKotlin-port/build/s1/native-kotlin-dist-linux-x64"))
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/home/stream/.sdkman/candidates/java/21.0.12+1.1-tem"))
KONAN_DATA_DIR = Path(os.environ.get("KONAN_DATA_DIR", "/home/stream/ACodeSpace/local/NativeKotlin-baseline/konan-home"))
REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_JSON = REPO_ROOT / "benchmarks" / "data" / "benchmark-jvm-vs-native.json"


def run_timed(cmd, env, cwd, runs=3, warmup=1):
    for _ in range(warmup):
        subprocess.run(cmd, env=env, cwd=cwd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    samples = []
    for _ in range(runs):
        t0 = time.perf_counter()
        res = subprocess.run(
            ["/usr/bin/time", "-v"] + cmd,
            env=env,
            cwd=cwd,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        wall_s = time.perf_counter() - t0
        user_s = sys_s = rss_kb = ctx = 0.0
        for line in res.stderr.splitlines():
            line = line.strip()
            if line.startswith("User time (seconds):"):
                user_s = float(line.split(":")[-1].strip())
            elif line.startswith("System time (seconds):"):
                sys_s = float(line.split(":")[-1].strip())
            elif line.startswith("Maximum resident set size (kbytes):"):
                rss_kb = int(line.split(":")[-1].strip())
            elif line.startswith("Voluntary context switches:") or line.startswith("Involuntary context switches:"):
                ctx += int(line.split(":")[-1].strip())
        samples.append({
            "wall_s": round(wall_s, 4),
            "cpu_s": round(user_s + sys_s, 4),
            "rss_mb": round(rss_kb / 1024.0, 2),
            "ctx_switches": int(ctx),
        })
    return {
        "wall_median_s": round(statistics.median(s["wall_s"] for s in samples), 4),
        "cpu_median_s": round(statistics.median(s["cpu_s"] for s in samples), 4),
        "rss_median_mb": round(statistics.median(s["rss_mb"] for s in samples), 2),
        "ctx_switches_median": int(statistics.median(s["ctx_switches"] for s in samples)),
        "samples": samples,
    }


if __name__ == "__main__":
    print(f"Benchmarking {NATIVE_DIST} vs {BASELINE_DIST}...")
    print(f"Results path: {OUT_JSON}")
