@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package org.jetbrains.kotlin.native.d1.verify

import d1cbridge.D1Metric
import d1cbridge.d1_apply_transform
import d1cbridge.d1_combine_metrics_ptr
import d1cbridge.d1_compute_checksum
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.builtin.builtin_bswap16
import platform.builtin.builtin_bswap32
import platform.builtin.builtin_clz
import platform.builtin.builtin_ctz
import platform.builtin.builtin_popcount
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.opendir
import platform.posix.posix_errno
import platform.posix.posix_htons
import platform.posix.readdir
import platform.posix.set_posix_errno
import platform.posix.stat
import platform.posix.strlen
import platform.zlib.Z_OK
import platform.zlib.adler32
import platform.zlib.compress2
import platform.zlib.crc32
import platform.zlib.uncompress

private class NativeD1PlatformHooks : D1PlatformHooks {
    override fun readTextFile(path: String): String {
        val file = fopen(path, "rb") ?: error("Cannot open file: $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)
            if (size <= 0) return ""
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.convert(), file)
            }
            return bytes.decodeToString()
        } finally {
            fclose(file)
        }
    }

    override fun fileExistsAndNonEmpty(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return false
        st.st_size > 0L
    }

    override fun listFilesSorted(dirPath: String): List<String> {
        val dir = opendir(dirPath) ?: return emptyList()
        val names = mutableListOf<String>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                if (fileExistsAndNonEmpty("$dirPath/$name")) {
                    names += name
                }
            }
        } finally {
            closedir(dir)
        }
        return names.sorted()
    }

    override fun runPosixAndBuiltinInterop(): List<String> {
        set_posix_errno(42)
        val errnoSet = posix_errno()
        set_posix_errno(0)
        val errnoReset = posix_errno()
        val strLen = strlen("NativeKotlin-D1-Posix").toInt()
        val htonsVal = posix_htons(0x1234.toShort()).toInt() and 0xFFFF

        val bswap16Val = builtin_bswap16(0x1234.toShort()).toInt() and 0xFFFF
        val bswap32Val = builtin_bswap32(0x12345678)
        val popcountVal = builtin_popcount(0x0F0F0F0F)
        val clzVal = builtin_clz(0x00010000u)
        val ctzVal = builtin_ctz(0x00010000u)

        return listOf(
            "d1.interop.posix=errno_set:$errnoSet,errno_reset:$errnoReset,strlen:$strLen,htons:$htonsVal",
            "d1.interop.builtin=bswap16:$bswap16Val,bswap32:$bswap32Val,popcount:$popcountVal,clz:$clzVal,ctz:$ctzVal",
        )
    }

    override fun runZlibInterop(): List<String> = memScoped {
        val payload = "NativeKotlin-D1-Zlib-Payload-2026".encodeToByteArray()
        val crc = payload.usePinned { pinned ->
            crc32(0uL, pinned.addressOf(0).reinterpret<UByteVar>(), payload.size.toUInt())
        }
        val adler = payload.usePinned { pinned ->
            adler32(1uL, pinned.addressOf(0).reinterpret<UByteVar>(), payload.size.toUInt())
        }

        val rawText = "KotlinNativeD1DistributionRebuildZlibRoundTripCheck!KotlinNative"
        val rawBytes = rawText.encodeToByteArray()
        val compCap = 128uL
        val compBuf = allocArray<UByteVar>(compCap.toInt())
        val compLenVar = alloc<ULongVar>()
        compLenVar.value = compCap

        val compRc = rawBytes.usePinned { pinned ->
            compress2(
                compBuf,
                compLenVar.ptr,
                pinned.addressOf(0).reinterpret<UByteVar>(),
                rawBytes.size.toULong(),
                6,
            )
        }
        val compLen = compLenVar.value.toInt()

        val uncompBuf = ByteArray(rawBytes.size)
        val uncompLenVar = alloc<ULongVar>()
        uncompLenVar.value = rawBytes.size.toULong()
        val uncompRc = uncompBuf.usePinned { pinned ->
            uncompress(
                pinned.addressOf(0).reinterpret<UByteVar>(),
                uncompLenVar.ptr,
                compBuf,
                compLenVar.value,
            )
        }
        val uncompLen = uncompLenVar.value.toInt()
        val restoredText = uncompBuf.decodeToString(0, uncompLen)
        val compOk = (compRc == Z_OK) && (uncompRc == Z_OK) && (compLen in 1 until rawBytes.size)

        listOf(
            "d1.interop.zlib=crc32:$crc,adler32:$adler,comp_ok:$compOk,roundtrip:${restoredText == rawText},len:$uncompLen"
        )
    }

    override fun runCustomCBridgeInterop(): List<String> = memScoped {
        val bytes = "D1CustomCBridge".encodeToByteArray()
        val checksum = bytes.usePinned { pinned ->
            d1_compute_checksum(pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size, 7u)
        }
        val a = alloc<D1Metric>().apply {
            id = 3
            count = 12
            weight = 100L
        }
        val b = alloc<D1Metric>().apply {
            id = 7
            count = 8
            weight = 50L
        }
        val out = alloc<D1Metric>()
        d1_combine_metrics_ptr(a.ptr, b.ptr, out.ptr)

        val cb = staticCFunction { value: Int, factor: Int ->
            value * factor - 3
        }
        val cbResult = d1_apply_transform(10, 4, cb)
        val nullCbResult = d1_apply_transform(10, 4, null)

        listOf(
            "d1.interop.cbridge=checksum:$checksum,metric:${out.id}/${out.count}/${out.weight},cb:$cbResult,null_cb:$nullCbResult"
        )
    }

    override fun runMemoryAndWeakRefSection(): List<String> {
        class Node(val id: Int, var next: Node? = null)
        val strongTarget = Node(77)
        val weakKeep = WeakReference(strongTarget)
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
        GC.collect()
        val pinnedBytes = ByteArray(16) { (it * 3 + 1).toByte() }
        val pinnedChecksum = pinnedBytes.usePinned { pinned ->
            val basePtr = pinned.addressOf(0)
            var sum = 0
            for (i in 0 until 16) {
                sum += (basePtr[i].toInt() and 0xFF)
            }
            sum
        }
        return listOf(
            "d1.memory.gc=strong_id:${weakKeep.get()?.id},cycle_sum:$cycleSum,pinned_sum:$pinnedChecksum"
        )
    }

    override fun runConcurrencyAndAtomicsSection(): List<String> {
        val ai = AtomicInt(10)
        val casOk = ai.compareAndSet(10, 25)
        val casFail = ai.compareAndSet(10, 99)
        val afterAdd = ai.addAndGet(15)

        val al = AtomicLong(1000L)
        val longAfter = al.addAndGet(2345L)

        val ar = AtomicReference("init")
        val refSwapped = ar.compareAndSet("init", "updated")
        val refVal = ar.value

        val workerSum = AtomicInt(0)
        val w1 = Worker.start()
        val w2 = Worker.start()
        val f1 = w1.execute(TransferMode.SAFE, { workerSum }) { sumRef ->
            var local = 0
            for (i in 1..50) local += i
            sumRef.addAndGet(local)
            "w1:$local"
        }
        val f2 = w2.execute(TransferMode.SAFE, { workerSum }) { sumRef ->
            var local = 0
            for (i in 51..100) local += i
            sumRef.addAndGet(local)
            "w2:$local"
        }
        val r1 = f1.result
        val r2 = f2.result
        w1.requestTermination().result
        w2.requestTermination().result

        return listOf(
            "d1.concurrency.atomics=int:$casOk/$casFail/$afterAdd,long:$longAfter,ref:$refSwapped/$refVal",
            "d1.concurrency.workers=$r1,$r2,total:${workerSum.value}",
        )
    }
}

fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: d1-corpus-app.kexe <rebuilt-dist> <abi-dump-file>" }
    runAllD1Cases(
        rebuiltDistPath = args[0],
        abiDumpPath = args[1],
        hooks = NativeD1PlatformHooks(),
    )
}
