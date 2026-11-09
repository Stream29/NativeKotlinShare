#!/usr/bin/env python3
"""
Reproducible Optimization Scaling (L0 -O0 -> L5 `konanc -opt`) & `konanc -opt` vs JDK 21 HotSpot Benchmark.
Evaluates:
1. Polymorphic AST Visitor (`65,535` nodes x `80` passes)
2. Scope Resolution & `@JvmInline value class` Pipeline (`8,000,000` frames)
3. Tight Bitwise/Hash Mixing Kernel (`4,096` elements x `6,000` rounds)
"""
import json
import os
import statistics
import subprocess
import time
from pathlib import Path

BASELINE_DIST = Path(os.environ.get("BASELINE_DIST", "/home/stream/ACodeSpace/local/NativeKotlin-baseline/kotlin/kotlin-native/dist"))
NATIVE_DIST = Path(os.environ.get("NATIVE_DIST", "/home/stream/ACodeSpace/local/NativeKotlin-port/build/s1/native-kotlin-dist-linux-x64"))
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/home/stream/.sdkman/candidates/java/21.0.12+1.1-tem"))
KONAN_DATA_DIR = Path(os.environ.get("KONAN_DATA_DIR", "/home/stream/ACodeSpace/local/NativeKotlin-baseline/konan-home"))

OPT_BENCH_SOURCE = r'''
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.time.measureTime

sealed interface IrNode {
    val kind: Int
    fun accept(v: IrVisitor, depth: Int): Long
}

class ConstNode(val value: Long) : IrNode {
    override val kind: Int get() = 1
    override fun accept(v: IrVisitor, depth: Int): Long = v.visitConst(this, depth)
}

class BinaryOpNode(val op: Int, val left: IrNode, val right: IrNode) : IrNode {
    override val kind: Int get() = 2
    override fun accept(v: IrVisitor, depth: Int): Long = v.visitBinary(this, depth)
}

class CallNode(val calleeId: Int, val arg0: IrNode, val arg1: IrNode) : IrNode {
    override val kind: Int get() = 3
    override fun accept(v: IrVisitor, depth: Int): Long = v.visitCall(this, depth)
}

interface IrVisitor {
    fun visitConst(n: ConstNode, depth: Int): Long
    fun visitBinary(n: BinaryOpNode, depth: Int): Long
    fun visitCall(n: CallNode, depth: Int): Long
}

class FoldingEvaluator(private val salt: Long) : IrVisitor {
    override fun visitConst(n: ConstNode, depth: Int): Long =
        (n.value xor (depth.toLong() * 0x9E3779B97F4A7C15uL.toLong())) + salt

    override fun visitBinary(n: BinaryOpNode, depth: Int): Long {
        val l = n.left.accept(this, depth + 1)
        val r = n.right.accept(this, depth + 1)
        return when (n.op and 3) {
            0 -> l + r * 31L
            1 -> (l xor r.rotateLeft(13)) + depth
            2 -> (l - r) xor (l ushr 17)
            else -> (l * 0x5851F42D4C957F2DL) + r
        }
    }

    override fun visitCall(n: CallNode, depth: Int): Long {
        val a = n.arg0.accept(this, depth + 1)
        val b = n.arg1.accept(this, depth + 1)
        return (a.rotateLeft((n.calleeId and 31) + 1) xor b) + n.calleeId.toLong()
    }
}

@kotlin.jvm.JvmInline
value class SymbolSig(val raw: Long) {
    fun mix(step: Int): SymbolSig =
        SymbolSig((raw xor (raw ushr 30)) * -4658895280553007687L + step.toLong())

    fun combine(other: SymbolSig): SymbolSig =
        SymbolSig(raw.rotateLeft(17) xor (other.raw * -7046029254386353131L))
}

class ScopeFrame(val parentSig: SymbolSig, val localSig: SymbolSig, val depth: Int) {
    fun resolve(): SymbolSig = parentSig.combine(localSig).mix(depth)
}

fun buildAst(depth: Int, seed: Long): IrNode {
    if (depth <= 0) return ConstNode(seed * 6364136223846793005L + 1442695040888963407L)
    val nextSeedL = seed * 2862933555777941757L + 3037000493L
    val nextSeedR = nextSeedL * 2862933555777941757L + 3037000493L
    val l = buildAst(depth - 1, nextSeedL)
    val r = buildAst(depth - 1, nextSeedR)
    return if ((depth + seed.toInt()) and 1 == 0) {
        BinaryOpNode((seed ushr 16).toInt(), l, r)
    } else {
        CallNode((seed ushr 8).toInt() and 255, l, r)
    }
}

fun runScopePipeline(iterations: Int): Long {
    var acc = SymbolSig(0x123456789ABCDEF0L)
    var i = 0
    while (i < iterations) {
        val frame = ScopeFrame(acc, SymbolSig(i.toLong() * 0x517CC1B727220A95L), i and 63)
        acc = frame.resolve()
        i++
    }
    return acc.raw
}

fun runTightKernel(rounds: Int): Long {
    val buf = LongArray(4096) { idx -> (idx.toLong() + 1L) * -7046029254386353131L }
    var state = 0xCAFEBABE12345678uL.toLong()
    for (r in 0 until rounds) {
        val rLong = r.toLong()
        for (i in 0 until buf.size) {
            val v = buf[i]
            val mixed = ((v xor state).rotateLeft(21) * 0x2545F4914F6CDD1DL) + rLong
            buf[i] = mixed
            state = state xor (mixed ushr 11)
        }
    }
    return state xor buf[2047]
}

fun main() {
    val root = buildAst(15, 42L)
    for (w in 0 until 5) {
        var warm = 0L
        for (p in 0 until 16) warm = warm xor root.accept(FoldingEvaluator(p.toLong()), 0)
        warm = warm xor runScopePipeline(1_600_000)
        warm = warm xor runTightKernel(1_200)
        if (warm == 42L) println("warmup")
    }
    var astSum = 0L
    val astTime = measureTime {
        for (p in 0 until 80) {
            astSum = astSum xor root.accept(FoldingEvaluator(p.toLong()), 0)
        }
    }
    var scopeSum = 0L
    val scopeTime = measureTime {
        scopeSum = runScopePipeline(8_000_000)
    }
    var kernelSum = 0L
    val kernelTime = measureTime {
        kernelSum = runTightKernel(6_000)
    }
    val totalMs = astTime.inWholeMicroseconds / 1000.0 +
        scopeTime.inWholeMicroseconds / 1000.0 +
        kernelTime.inWholeMicroseconds / 1000.0
    println("AST_MS=${astTime.inWholeMicroseconds / 1000.0}")
    println("SCOPE_MS=${scopeTime.inWholeMicroseconds / 1000.0}")
    println("KERNEL_MS=${kernelTime.inWholeMicroseconds / 1000.0}")
    println("TOTAL_MS=$totalMs")
    println("CHECKSUM=${astSum xor scopeSum xor kernelSum}")
}
'''

if __name__ == "__main__":
    print("OptBench source ready (3 workloads: AST Visitor, Scope/ValueClass Pipeline, Tight Kernel).")
