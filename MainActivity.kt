package com.stalker.dbtool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.CRC32

data class DbFileEntry(
    val relativePath: String,
    val sizeReal: Long,
    val sizeCompressed: Long,
    val crc32: Long,
    val offset: Long
)

object XRayCompression {
    private const val SBOX_SIZE = 256

    fun decodeHeaderChunk(rawChunk: ByteArray): ByteArray {
        // 1. Попытка распаковки как LZO1X (используется в большинстве .db/.xdb модов)
        for (candidateSize in listOf(2 * 1024 * 1024, 8 * 1024 * 1024, 16 * 1024 * 1024, 32 * 1024 * 1024)) {
            runCatching {
                val out = decompressLzo1xSafe(rawChunk, candidateSize)
                if (isValidIndexHeader(out)) return out
            }
        }

        // 2. Попытка распаковки как прямой LZHUFF
        if (isValidLzHuff(rawChunk)) {
            runCatching {
                val out = decompressLzHuff(rawChunk)
                if (isValidIndexHeader(out)) return out
            }
        }

        // 3. Дешифровка GSC (RU / CS / CoP)
        val decRu = decryptScrambler(rawChunk, initSeed = 0x131a9d3, mult = 20042299, add = 0x0518443b)
        runCatching {
            val out = if (isValidLzHuff(decRu)) decompressLzHuff(decRu) else decompressLzo1xSafe(decRu, 8 * 1024 * 1024)
            if (isValidIndexHeader(out)) return out
        }

        // 4. Дешифровка GSC (WW)
        val decWw = decryptScrambler(rawChunk, initSeed = 0x01622731, mult = 25178309, add = 0x0198812d)
        runCatching {
            val out = if (isValidLzHuff(decWw)) decompressLzHuff(decWw) else decompressLzo1xSafe(decWw, 8 * 1024 * 1024)
            if (isValidIndexHeader(out)) return out
        }

        // Если заголовок не был сжат, возвращаем исходный блок
        return rawChunk
    }

    private fun isValidIndexHeader(b: ByteArray): Boolean {
        if (b.size < 18) return false
        val entrySize = ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8))
        return entrySize in 17..2048
    }

    private fun isValidLzHuff(data: ByteArray): Boolean {
        if (data.size < 9) return false
        return (data[0].toInt() and 0xFF) == 0xC8
    }

    private fun readIntBE(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
                ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or
                (b[offset + 3].toInt() and 0xFF)
    }

    private fun decryptScrambler(src: ByteArray, initSeed: Int, mult: Int, add: Int): ByteArray {
        val encSbox = IntArray(SBOX_SIZE) { it }
        val decSbox = IntArray(SBOX_SIZE)
        var seed = initSeed

        for (i in SBOX_SIZE - 1 downTo 1) {
            seed = seed * mult + add
            val idx = ((seed ushr 16) and 0x7FFF) % i
            val tmp = encSbox[i]
            encSbox[i] = encSbox[idx]
            encSbox[idx] = tmp
        }
        for (i in 0 until SBOX_SIZE) {
            decSbox[encSbox[i]] = i
        }

        val dst = ByteArray(src.size)
        var s = seed
        for (i in src.indices) {
            s = s * mult + add
            val key = (s ushr 24) and 0xFF
            dst[i] = (decSbox[src[i].toInt() and 0xFF] xor key).toByte()
        }
        return dst
    }

    fun decompressLzHuff(src: ByteArray): ByteArray {
        if (src.size < 9) return src
        var srcPos = 0
        val method = src[srcPos++].toInt() and 0xFF
        if (method != 0xC8) return src

        val uncompSize = readIntBE(src, srcPos)
        srcPos += 4
        val lzSize = readIntBE(src, srcPos)
        srcPos += 4

        val N_CHAR = 256 - 3 + 60 + 1
        val T = N_CHAR * 2 - 1
        val R = T - 1
        val MAX_FREQ = 0x8000

        val freq = IntArray(T + 1)
        val prnt = IntArray(T + N_CHAR)
        val son = IntArray(T)

        for (i in 0 until N_CHAR) {
            freq[i] = 1
            son[i] = i + T
            prnt[i + T] = i
        }
        var iIdx = 0
        var jIdx = N_CHAR
        while (jIdx <= R) {
            freq[jIdx] = freq[iIdx] + freq[iIdx + 1]
            son[jIdx] = iIdx
            prnt[iIdx] = jIdx
            prnt[iIdx + 1] = jIdx
            iIdx += 2
            jIdx++
        }
        freq[T] = 0xFFFF
        prnt[R] = 0

        var getbuf = 0
        var getlen = 0

        fun getBit(): Int {
            while (getlen <= 8) {
                val c = if (srcPos < src.size) (src[srcPos++].toInt() and 0xFF) else 0
                getbuf = getbuf or (c shl (8 - getlen))
                getlen += 8
            }
            val bit = (getbuf and 0x8000) ushr 15
            getbuf = (getbuf shl 1) and 0xFFFF
            getlen--
            return bit
        }

        fun getByte(): Int {
            while (getlen <= 8) {
                val c = if (srcPos < src.size) (src[srcPos++].toInt() and 0xFF) else 0
                getbuf = getbuf or (c shl (8 - getlen))
                getlen += 8
            }
            val byteVal = (getbuf and 0xFF00) ushr 8
            getbuf = (getbuf shl 8) and 0xFFFF
            getlen -= 8
            return byteVal
        }

        fun reconst() {
            var k = 0
            for (m in 0 until T) {
                if (son[m] >= T) {
                    freq[k] = (freq[m] + 1) / 2
                    son[k] = son[m]
                    k++
                }
            }
            var m = 0
            for (n in N_CHAR until T) {
                val k1 = m + 1
                val f = freq[m] + freq[k1]
                freq[n] = f
                var p = n - 1
                while (f < freq[p]) p--
                p++
                val copyLen = n - p
                System.arraycopy(freq, p, freq, p + 1, copyLen)
                freq[p] = f
                System.arraycopy(son, p, son, p + 1, copyLen)
                son[p] = m
                m += 2
            }
            for (m2 in 0 until T) {
                val s = son[m2]
                if (s >= T) prnt[s] = m2
                else {
                    prnt[s] = m2
                    prnt[s + 1] = m2
                }
            }
        }

        fun update(cParam: Int) {
            if (freq[R] == MAX_FREQ) reconst()
            var c = prnt[cParam + T]
            do {
                freq[c]++
                val k = freq[c]
                var l = c + 1
                if (k > freq[l]) {
                    while (k > freq[++l]) {}
                    l--
                    freq[c] = freq[l]
                    freq[l] = k
                    val i = son[c]
                    prnt[i] = l
                    if (i < T) prnt[i + 1] = l
                    val j = son[l]
                    son[l] = i
                    prnt[j] = c
                    if (j < T) prnt[j + 1] = c
                    son[c] = j
                    c = l
                }
                c = prnt[c]
            } while (c != 0)
        }

        val dCode = IntArray(256) { idx ->
            when {
                idx < 0x20 -> 0
                idx < 0x50 -> 1 + ((idx - 0x20) ushr 4)
                idx < 0x90 -> 4 + ((idx - 0x50) ushr 3)
                idx < 0xC0 -> 12 + ((idx - 0x90) ushr 2)
                idx < 0xF0 -> 24 + ((idx - 0xC0) ushr 1)
                else -> 48 + (idx - 0xF0)
            }
        }
        val dLen = IntArray(256) { idx ->
            when {
                idx < 0x20 -> 3
                idx < 0x50 -> 4
                idx < 0x90 -> 5
                idx < 0xC0 -> 6
                idx < 0xF0 -> 7
                else -> 8
            }
        }

        fun decodeChar(): Int {
            var c = son[R]
            while (c < T) {
                c += getBit()
                c = son[c]
            }
            c -= T
            update(c)
            return c
        }

        fun decodePosition(): Int {
            val i = getByte()
            val c = dCode[i] shl 6
            var j = dLen[i] - 2
            var rem = i
            while (j-- > 0) {
                rem = (rem shl 1) + getBit()
            }
            return c or (rem and 0x3F)
        }

        val N_DICT = 4096
        val F_DICT = 60
        val THRESHOLD = 2
        val textBuf = ByteArray(N_DICT + F_DICT - 1) { 0x20 }
        var rDict = N_DICT - F_DICT

        val lzOut = ByteArray(lzSize)
        var lzPos = 0

        while (lzPos < lzSize && srcPos <= src.size) {
            val c = decodeChar()
            if (c < 256) {
                val b = c.toByte()
                lzOut[lzPos++] = b
                textBuf[rDict++] = b
                rDict = rDict and (N_DICT - 1)
            } else {
                val matchPos = (rDict - decodePosition() - 1) and (N_DICT - 1)
                val matchLen = c - 255 + THRESHOLD
                for (k in 0 until matchLen) {
                    if (lzPos >= lzSize) break
                    val b = textBuf[(matchPos + k) and (N_DICT - 1)]
                    lzOut[lzPos++] = b
                    textBuf[rDict++] = b
                    rDict = rDict and (N_DICT - 1)
                }
            }
        }
        return lzOut
    }

    fun decompressLzo1xSafe(src: ByteArray, maxDestSize: Int): ByteArray {
        val out = ByteArray(maxDestSize)
        var ip = 0
        var op = 0
        var t = 0

        fun copyMatch(mPosStart: Int, count: Int) {
            var m = mPosStart
            for (k in 0 until count) {
                if (op < out.size && m >= 0 && m < op) {
                    out[op++] = out[m++]
                }
            }
        }

        var gtFirstLiteralRun = false
        var gtMatchDone = false

        if (ip < src.size && (src[ip].toInt() and 0xFF) > 17) {
            t = (src[ip++].toInt() and 0xFF) - 17
            if (t < 4) {
                while (t-- > 0 && ip < src.size && op < out.size) out[op++] = src[ip++]
                if (ip < src.size) t = src[ip++].toInt() and 0xFF
            } else {
                do {
                    if (ip < src.size && op < out.size) out[op++] = src[ip++]
                } while (--t > 0)
                gtFirstLiteralRun = true
            }
        }

        outer@ while (ip < src.size && op < out.size) {
            if (!gtFirstLiteralRun) {
                t = src[ip++].toInt() and 0xFF
                if (t < 16) {
                    if (t == 0) {
                        while (ip < src.size && src[ip].toInt() == 0) {
                            t += 255
                            ip++
                        }
                        if (ip < src.size) t += 15 + (src[ip++].toInt() and 0xFF)
                    }
                    t += 3
                    do {
                        if (ip < src.size && op < out.size) out[op++] = src[ip++]
                    } while (--t > 0)
                    gtFirstLiteralRun = true
                }
            }

            if (gtFirstLiteralRun) {
                gtFirstLiteralRun = false
                if (ip >= src.size) break
                t = src[ip++].toInt() and 0xFF
                if (t < 16) {
                    var mPos = op - (1 + 0x0800)
                    mPos -= t ushr 2
                    if (ip < src.size) mPos -= (src[ip++].toInt() and 0xFF) shl 2
                    copyMatch(mPos, 3)
                    gtMatchDone = true
                }
            }

            matchLoop@ while (ip < src.size && op < out.size) {
                if (!gtMatchDone) {
                    when {
                        t >= 64 -> {
                            var mPos = op - 1
                            mPos -= (t ushr 2) and 7
                            if (ip < src.size) mPos -= (src[ip++].toInt() and 0xFF) shl 3
                            t = (t ushr 5) - 1
                            copyMatch(mPos, t + 2)
                        }
                        t >= 32 -> {
                            t = t and 31
                            if (t == 0) {
                                while (ip < src.size && src[ip].toInt() == 0) {
                                    t += 255
                                    ip++
                                }
                                if (ip < src.size) t += 31 + (src[ip++].toInt() and 0xFF)
                            }
                            var mPos = op - 1
                            if (ip + 1 < src.size) {
                                val v = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
                                ip += 2
                                mPos -= v ushr 2
                            }
                            copyMatch(mPos, t + 2)
                        }
                        t >= 16 -> {
                            var mPos = op
                            mPos -= (t and 8) shl 11
                            t = t and 7
                            if (t == 0) {
                                while (ip < src.size && src[ip].toInt() == 0) {
                                    t += 255
                                    ip++
                                }
                                if (ip < src.size) t += 7 + (src[ip++].toInt() and 0xFF)
                            }
                            if (ip + 1 < src.size) {
                                val v = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
                                ip += 2
                                mPos -= v ushr 2
                            }
                            if (mPos == op) break@outer
                            mPos -= 0x4000
                            copyMatch(mPos, t + 2)
                        }
                        else -> {
                            var mPos = op - 1
                            mPos -= t ushr 2
                            if (ip < src.size) mPos -= (src[ip++].toInt() and 0xFF) shl 2
                            copyMatch(mPos, 2)
                        }
                    }
                }
                gtMatchDone = false
                if (ip - 2 >= 0) {
                    t = src[ip - 2].toInt() and 3
                    if (t == 0) break@matchLoop
                    do {
                        if (ip < src.size && op < out.size) out[op++] = src[ip++]
                    } while (--t > 0)
                }
                if (ip < src.size) t = src[ip++].toInt() and 0xFF
            }
        }
        return out.copyOf(op)
    }
}

class XRayDbArchiver {
    companion object {
        private const val CHUNK_DATA = 0
        private const val CHUNK_HEADER = 1
        private const val CFS_COMPRESS_MARK = 0x80000000L
        private val CP1251: Charset = Charset.forName("windows-1251")
    }

    fun readArchiveIndex(dbFile: File): List<DbFileEntry> {
        val entries = mutableListOf<DbFileEntry>()
        RandomAccessFile(dbFile, "r").use { raf ->
            val fileLength = raf.length()
            var headerBytes: ByteArray? = null

            while (raf.filePointer + 8 <= fileLength) {
                val chunkIdRaw = raf.readUint32LE()
                val chunkSize = raf.readUint32LE()
                val isCompressed = (chunkIdRaw and CFS_COMPRESS_MARK) != 0L
                val chunkId = (chunkIdRaw and 0x7FFFFFFFL).toInt()

                if (chunkId == CHUNK_HEADER) {
                    val rawChunk = ByteArray(chunkSize.toInt())
                    raf.readFully(rawChunk)
                    headerBytes = if (isCompressed) {
                        XRayCompression.decodeHeaderChunk(rawChunk)
                    } else {
                        rawChunk
                    }
                    break
                } else {
                    raf.seek(raf.filePointer + chunkSize)
                }
            }

            val hdr = headerBytes ?: throw IllegalArgumentException("Чанк оглавления (ID=1) не найден")
            val buffer = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)

            while (buffer.remaining() >= 2) {
                val entrySize = buffer.short.toInt() and 0xFFFF
                if (entrySize < 16 || buffer.remaining() < entrySize) break

                val sizeReal = buffer.int.toLong() and 0xFFFFFFFFL
                val sizeComp = buffer.int.toLong() and 0xFFFFFFFFL
                val crc = buffer.int.toLong() and 0xFFFFFFFFL

                val nameLength = entrySize - 16
                val nameBytes = ByteArray(nameLength)
                buffer.get(nameBytes)
                val path = String(nameBytes, CP1251).replace('\\', File.separatorChar)
                val offset = buffer.int.toLong() and 0xFFFFFFFFL

                entries.add(DbFileEntry(path, sizeReal, sizeComp, crc, offset))
            }
        }
        return entries
    }

    fun unpackArchive(dbFile: File, outputDir: File, onProgress: (Int, Int, String) -> Unit) {
        val entries = readArchiveIndex(dbFile)
        extractSelectedEntries(dbFile, entries, outputDir, onProgress)
    }

    fun extractSingleEntry(dbFile: File, entry: DbFileEntry, outputDir: File): File {
        val outFile = File(outputDir, entry.relativePath)
        outFile.parentFile?.mkdirs()
        RandomAccessFile(dbFile, "r").use { raf ->
            val data = readRawOrDecompressedEntry(raf, entry)
            FileOutputStream(outFile).use { it.write(data) }
        }
        return outFile
    }

    fun extractSelectedEntries(dbFile: File, entries: List<DbFileEntry>, outputDir: File, onProgress: (Int, Int, String) -> Unit) {
        val sortedEntries = entries.sortedBy { it.offset }
        RandomAccessFile(dbFile, "r").use { raf ->
            sortedEntries.forEachIndexed { index, entry ->
                onProgress(index + 1, sortedEntries.size, entry.relativePath)
                val outFile = File(outputDir, entry.relativePath)
                outFile.parentFile?.mkdirs()
                val data = readRawOrDecompressedEntry(raf, entry)
                FileOutputStream(outFile).use { it.write(data) }
            }
        }
    }

    fun readEntryAsText(dbFile: File, entry: DbFileEntry): String {
        RandomAccessFile(dbFile, "r").use { raf ->
            val bytes = readRawOrDecompressedEntry(raf, 
