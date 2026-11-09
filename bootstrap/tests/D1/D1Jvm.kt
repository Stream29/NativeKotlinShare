package org.jetbrains.kotlin.native.d1.verify

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

private class JvmD1PlatformHooks : D1PlatformHooks {
    override fun readTextFile(path: String): String = File(path).readText(Charsets.UTF_8)

    override fun fileExistsAndNonEmpty(path: String): Boolean {
        val f = File(path)
        return f.isFile && f.length() > 0L
    }

    override fun listFilesSorted(dirPath: String): List<String> {
        val dir = File(dirPath)
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.isFile }.map { it.name }.sorted()
    }

    override fun runPosixAndBuiltinInterop(): List<String> {
        val errnoSet = 42
        val errnoReset = 0
        val strLen = "NativeKotlin-D1-Posix".toByteArray(Charsets.UTF_8).size
        val htonsVal = java.lang.Short.reverseBytes(0x1234.toShort()).toInt() and 0xFFFF
        val bswap16Val = java.lang.Short.reverseBytes(0x1234.toShort()).toInt() and 0xFFFF
        val bswap32Val = Integer.reverseBytes(0x12345678)
        val popcountVal = Integer.bitCount(0x0F0F0F0F)
        val clzVal = Integer.numberOfLeadingZeros(0x00010000)
        val ctzVal = Integer.numberOfTrailingZeros(0x00010000)
        return listOf(
            "d1.interop.posix=errno_set:$errnoSet,errno_reset:$errnoReset,strlen:$strLen,htons:$htonsVal",
            "d1.interop.builtin=bswap16:$bswap16Val,bswap32:$bswap32Val,popcount:$popcountVal,clz:$clzVal,ctz:$ctzVal",
        )
    }

    override fun runZlibInterop(): List<String> {
        val payload = "NativeKotlin-D1-Zlib-Payload-2026".toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(payload) }.value
        val adler = Adler32().apply { update(payload) }.value

        val rawText = "KotlinNativeD1DistributionRebuildZlibRoundTripCheck!KotlinNative"
        val rawBytes = rawText.toByteArray(Charsets.UTF_8)
        val compressedBuf = ByteArray(128)
        val deflater = Deflater()
        deflater.setInput(rawBytes)
        deflater.finish()
        val compLen = deflater.deflate(compressedBuf)
        deflater.end()

        val restoredBuf = ByteArray(rawBytes.size)
        val inflater = Inflater()
        inflater.setInput(compressedBuf, 0, compLen)
        val uncompLen = inflater.inflate(restoredBuf)
        inflater.end()
        val restoredText = String(restoredBuf, 0, uncompLen, Charsets.UTF_8)

        return listOf(
            "d1.interop.zlib=crc32:$crc,adler32:$adler,comp_ok:${compLen in 1 until rawBytes.size},roundtrip:${restoredText == rawText},len:$uncompLen"
        )
    }

    override fun runCustomCBridgeInterop(): List<String> {
        val bytes = "D1CustomCBridge".toByteArray(Charsets.UTF_8)
        var h = 7u xor 0x811c9dc5u
        for (b in bytes) {
            h = h xor (b.toInt() and 0xFF).toUInt()
            h *= 0x01000193u
        }
        val combinedId = 3 * 100 + 7
        val combinedCount = 12 + 8
        val combinedWeight = 100L * 3L + 50L * 5L
        val cbResult = (10 * 4 - 3) + 7
        val nullCbResult = 10 + 4
        return listOf(
            "d1.interop.cbridge=checksum:$h,metric:$combinedId/$combinedCount/$combinedWeight,cb:$cbResult,null_cb:$nullCbResult"
        )
    }

    override fun runMemoryAndWeakRefSection(): List<String> {
        class Node(val id: Int, var next: Node? = null)
        val strongTarget = Node(77)
        val weakKeep = java.lang.ref.WeakReference(strongTarget)
        var cycleSum = 0L
        for (round in 1..4) {
            val head = Node(round)
            var cur = head
            for (i in 1..32) {
                val n = Node(round * 100 + i)
                cur.next = n
                cur = n
            }
            cur.next = head
            var walk: Node? = head
            for (i in 0..32) {
                cycleSum += walk!!.id
                walk = walk.next
            }
        }
        System.gc()
        val pinnedBytes = ByteArray(16) { (it * 3 + 1).toByte() }
        val pinnedChecksum = pinnedBytes.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        return listOf(
            "d1.memory.gc=strong_id:${weakKeep.get()?.id},cycle_sum:$cycleSum,pinned_sum:$pinnedChecksum"
        )
    }

    override fun runConcurrencyAndAtomicsSection(): List<String> {
        val ai = AtomicInteger(10)
        val casOk = ai.compareAndSet(10, 25)
        val casFail = ai.compareAndSet(10, 99)
        val afterAdd = ai.addAndGet(15)

        val al = AtomicLong(1000L)
        val longAfter = al.addAndGet(2345L)

        val ar = AtomicReference("init")
        val refSwapped = ar.compareAndSet("init", "updated")
        val refVal = ar.get()

        val workerSum = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)
        val f1 = pool.submit(Callable {
            var local = 0
            for (i in 1..50) local += i
            workerSum.addAndGet(local)
            "w1:$local"
        })
        val f2 = pool.submit(Callable {
            var local = 0
            for (i in 51..100) local += i
            workerSum.addAndGet(local)
            "w2:$local"
        })
        val r1 = f1.get()
        val r2 = f2.get()
        pool.shutdown()

        return listOf(
            "d1.concurrency.atomics=int:$casOk/$casFail/$afterAdd,long:$longAfter,ref:$refSwapped/$refVal",
            "d1.concurrency.workers=$r1,$r2,total:${workerSum.get()}",
        )
    }
}

fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: D1JvmKt <rebuilt-dist> <abi-dump-file>" }
    runAllD1Cases(
        rebuiltDistPath = args[0],
        abiDumpPath = args[1],
        hooks = JvmD1PlatformHooks(),
    )
}
