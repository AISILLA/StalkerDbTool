package com.stalker.dbtool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.FolderOpen
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
        if (isValidLzHuff(rawChunk)) {
            runCatching { return decompressLzHuff(rawChunk) }
        }
        val decryptedRu = decryptScrambler(rawChunk, initSeed = 0x131a9d3, mult = 20042299, add = 0x0518443b)
        if (isValidLzHuff(decryptedRu)) {
            runCatching { return decompressLzHuff(decryptedRu) }
        }
        val decryptedWw = decryptScrambler(rawChunk, initSeed = 0x01622731, mult = 25178309, add = 0x0198812d)
        if (isValidLzHuff(decryptedWw)) {
            runCatching { return decompressLzHuff(decryptedWw) }
        }
        return decompressLzHuff(rawChunk)
    }

    private fun isValidLzHuff(data: ByteArray): Boolean {
        if (data.size < 9) return false
        val magic = data[0].toInt() and 0xFF
        if (magic != 0xC8) return false
        val uncompSize = readIntBE(data, 1)
        val lzSize = readIntBE(data, 5)
        return uncompSize in 1..(128 * 1024 * 1024) && lzSize in 1..(128 * 1024 * 1024)
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
        require(src.size >= 9) { "Слишком короткий блок LZHUFF" }
        var srcPos = 0
        val method = src[srcPos++].toInt() and 0xFF
        require(method == 0xC8) { "Неизвестная сигнатура заголовка" }

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

        while (lzPos < lzSize) {
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
        if (lzSize == uncompSize) return lzOut
        return lzOut
    }

    fun decompressLzo1x(src: ByteArray, realSize: Int): ByteArray {
        val dst = ByteArray(realSize)
        var ip = 0
        var op = 0
        var t: Int

        fun copyMatch(mPosStart: Int, count: Int) {
            var m = mPosStart
            for (k in 0 until count) {
                dst[op++] = dst[m++]
            }
        }

        var gtFirstLiteralRun = false
        var gtMatchDone = false

        if ((src[ip].toInt() and 0xFF) > 17) {
            t = (src[ip++].toInt() and 0xFF) - 17
            if (t < 4) {
                while (t-- > 0) dst[op++] = src[ip++]
                t = src[ip++].toInt() and 0xFF
            } else {
                do { dst[op++] = src[ip++] } while (--t > 0)
                gtFirstLiteralRun = true
            }
        }

        outer@ while (true) {
            if (!gtFirstLiteralRun) {
                t = src[ip++].toInt() and 0xFF
                if (t >= 16) {
                } else {
                    if (t == 0) {
                        while (src[ip].toInt() == 0) {
                            t += 255
                            ip++
                        }
                        t += 15 + (src[ip++].toInt() and 0xFF)
                    }
                    t += 3
                    do { dst[op++] = src[ip++] } while (--t > 0)
                    gtFirstLiteralRun = true
                }
            }

            if (gtFirstLiteralRun) {
                gtFirstLiteralRun = false
                t = src[ip++].toInt() and 0xFF
                if (t < 16) {
                    var mPos = op - (1 + 0x0800)
                    mPos -= t ushr 2
                    mPos -= (src[ip++].toInt() and 0xFF) shl 2
                    copyMatch(mPos, 3)
                    gtMatchDone = true
                }
            }

            matchLoop@ while (true) {
                if (!gtMatchDone) {
                    when {
                        t >= 64 -> {
                            var mPos = op - 1
                            mPos -= (t ushr 2) and 7
                            mPos -= (src[ip++].toInt() and 0xFF) shl 3
                            t = (t ushr 5) - 1
                            copyMatch(mPos, t + 2)
                        }
                        t >= 32 -> {
                            t = t and 31
                            if (t == 0) {
                                while (src[ip].toInt() == 0) {
                                    t += 255
                                    ip++
                                }
                                t += 31 + (src[ip++].toInt() and 0xFF)
                            }
                            var mPos = op - 1
                            val v = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
                            ip += 2
                            mPos -= v ushr 2
                            copyMatch(mPos, t + 2)
                        }
                        t >= 16 -> {
                            var mPos = op
                            mPos -= (t and 8) shl 11
                            t = t and 7
                            if (t == 0) {
                                while (src[ip].toInt() == 0) {
                                    t += 255
                                    ip++
                                }
                                t += 7 + (src[ip++].toInt() and 0xFF)
                            }
                            val v = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
                            ip += 2
                            mPos -= v ushr 2
                            if (mPos == op) break@outer
                            mPos -= 0x4000
                            copyMatch(mPos, t + 2)
                        }
                        else -> {
                            var mPos = op - 1
                            mPos -= t ushr 2
                            mPos -= (src[ip++].toInt() and 0xFF) shl 2
                            copyMatch(mPos, 2)
                        }
                    }
                }
                gtMatchDone = false
                t = src[ip - 2].toInt() and 3
                if (t == 0) break@matchLoop
                do { dst[op++] = src[ip++] } while (--t > 0)
                t = src[ip++].toInt() and 0xFF
            }
        }
        return dst
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
            val bytes = readRawOrDecompressedEntry(raf, entry)
            return String(bytes, CP1251).replace("\r\n", "\n")
        }
    }

    fun saveTextEntryToFolder(entry: DbFileEntry, outputDir: File, newText: String): File {
        val outFile = File(outputDir, entry.relativePath)
        outFile.parentFile?.mkdirs()
        val normalizedText = newText.replace("\r\n", "\n").replace("\n", "\r\n")
        FileOutputStream(outFile).use { it.write(normalizedText.toByteArray(CP1251)) }
        return outFile
    }

    fun updateTextEntryInArchive(dbFile: File, targetEntry: DbFileEntry, newText: String): List<DbFileEntry> {
        val currentEntries = readArchiveIndex(dbFile)
        val normalizedText = newText.replace("\r\n", "\n").replace("\n", "\r\n")
        val newBytes = normalizedText.toByteArray(CP1251)

        val crc = CRC32()
        crc.update(newBytes)
        val newCrc = crc.value

        RandomAccessFile(dbFile, "rw").use { raf ->
            raf.seek(0)
            val firstChunkId = raf.readUint32LE() and 0x7FFFFFFFL
            val firstChunkSize = raf.readUint32LE()

            val newEntryOffset = 8L + firstChunkSize
            raf.seek(newEntryOffset)
            raf.write(newBytes)

            val updatedChunk0Size = (firstChunkSize + newBytes.size).toInt()
            val chunk1StartOffset = raf.filePointer

            val updatedEntries = currentEntries.map { entry ->
                if (entry.relativePath == targetEntry.relativePath) {
                    entry.copy(sizeReal = newBytes.size.toLong(), sizeCompressed = newBytes.size.toLong(), crc32 = newCrc, offset = newEntryOffset)
                } else entry
            }

            val headerStream = ByteArrayOutputStream()
            for (entry in updatedEntries) {
                val relPathWin = entry.relativePath.replace(File.separatorChar, '\\')
                val nameBytes = relPathWin.toByteArray(CP1251)
                val entrySize = 16 + nameBytes.size
                val entryBuf = ByteBuffer.allocate(2 + entrySize).order(ByteOrder.LITTLE_ENDIAN)
                entryBuf.putShort(entrySize.toShort())
                entryBuf.putInt(entry.sizeReal.toInt())
                entryBuf.putInt(entry.sizeCompressed.toInt())
                entryBuf.putInt(entry.crc32.toInt())
                entryBuf.put(nameBytes)
                entryBuf.putInt(entry.offset.toInt())
                headerStream.write(entryBuf.array())
            }

            val newHeaderBytes = headerStream.toByteArray()
            raf.seek(chunk1StartOffset)
            raf.writeIntLE(CHUNK_HEADER)
            raf.writeIntLE(newHeaderBytes.size)
            raf.write(newHeaderBytes)
            raf.setLength(raf.filePointer)

            raf.seek(4)
            raf.writeIntLE(updatedChunk0Size)
            return updatedEntries
        }
    }

    fun packDirectory(sourceDir: File, outputDbFile: File, onProgress: (Int, Int, String) -> Unit) {
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        require(files.isNotEmpty()) { "Папка пуста" }
        outputDbFile.parentFile?.mkdirs()
        val headerStream = ByteArrayOutputStream()

        RandomAccessFile(outputDbFile, "rw").use { raf ->
            raf.setLength(0)
            raf.writeIntLE(CHUNK_DATA)
            raf.writeIntLE(0)
            val dataChunkStart = raf.filePointer
            val buffer = ByteArray(64 * 1024)

            files.forEachIndexed { index, file ->
                val relPathWin = file.relativeTo(sourceDir).path.replace('/', '\\')
                onProgress(index + 1, files.size, relPathWin)
                val fileOffset = raf.filePointer
                val fileSize = file.length()
                val crc = CRC32()

                file.inputStream().use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        crc.update(buffer, 0, read)
                        raf.write(buffer, 0, read)
                    }
                }

                val nameBytes = relPathWin.toByteArray(CP1251)
                val entrySize = 16 + nameBytes.size
                val entryBuf = ByteBuffer.allocate(2 + entrySize).order(ByteOrder.LITTLE_ENDIAN)
                entryBuf.putShort(entrySize.toShort())
                entryBuf.putInt(fileSize.toInt())
                entryBuf.putInt(fileSize.toInt())
                entryBuf.putInt(crc.value.toInt())
                entryBuf.put(nameBytes)
                entryBuf.putInt(fileOffset.toInt())
                headerStream.write(entryBuf.array())
            }

            val dataChunkEnd = raf.filePointer
            val dataChunkSize = (dataChunkEnd - dataChunkStart).toInt()
            raf.seek(4)
            raf.writeIntLE(dataChunkSize)

            raf.seek(dataChunkEnd)
            val headerBytes = headerStream.toByteArray()
            raf.writeIntLE(CHUNK_HEADER)
            raf.writeIntLE(headerBytes.size)
            raf.write(headerBytes)
        }
    }

    private fun readRawOrDecompressedEntry(raf: RandomAccessFile, entry: DbFileEntry): ByteArray {
        if (entry.sizeReal == 0L) return ByteArray(0)
        raf.seek(entry.offset)
        val rawData = ByteArray(entry.sizeCompressed.toInt())
        raf.readFully(rawData)

        return if (entry.sizeReal == entry.sizeCompressed) {
            rawData
        } else {
            if ((rawData[0].toInt() and 0xFF) == 0xC8 && rawData.size >= 9) {
                XRayCompression.decompressLzHuff(rawData)
            } else {
                XRayCompression.decompressLzo1x(rawData, entry.sizeReal.toInt())
            }
        }
    }

    private fun RandomAccessFile.readUint32LE(): Long {
        val b1 = read() and 0xFF
        val b2 = read() and 0xFF
        val b3 = read() and 0xFF
        val b4 = read() and 0xFF
        return (b1.toLong()) or (b2.toLong() shl 8) or (b3.toLong() shl 16) or (b4.toLong() shl 24)
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StalkerArchiverMain()
                }
            }
        }
    }
}

@Composable
fun StalkerArchiverMain() {
    val context = LocalContext.current
    val archiver = remember { XRayDbArchiver() }

    val defaultRoot = Environment.getExternalStorageDirectory().absolutePath
    var archivePath by remember { mutableStateOf("$defaultRoot/Download/EH/configs.db") }
    var folderPath by remember { mutableStateOf("$defaultRoot/Download/gamedata") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Проводник .db", "Распаковка / Сборка")

    // Лаунчер для выбора файла .db архива
    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val path = getFilePathFromUri(context, it)
            if (path != null) archivePath = path
        }
    }

    // Лаунчер для выбора папки gamedata
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = getFilePathFromUri(context, it)
            if (path != null) folderPath = path
        }
    }

    val hasStoragePermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hasStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Нужен доступ к файлам (Android 11+)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    }) {
                        Text("Разрешить")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Поле выбора архива с кнопкой-папкой
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = archivePath,
                    onValueChange = { archivePath = it },
                    label = { Text("Путь к .db / .xdb архиву") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { archivePickerLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Выбрать архив")
                }
            }

            // Поле выбора папки gamedata с кнопкой-папкой
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = folderPath,
                    onValueChange = { folderPath = it },
                    label = { Text("Папка gamedata") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Выбрать папку")
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }

        when (selectedTab) {
            0 -> ArchiveExplorerScreen(archiver = archiver, archivePath = archivePath, outputDirPath = folderPath)
            1 -> FullUnpackPackTab(archiver = archiver, archivePath = archivePath, folderPath = folderPath)
        }
    }
}

// Вспомогательная функция для конвертации Uri в стандартный путь на телефоне
fun getFilePathFromUri(context: android.content.Context, uri: Uri): String? {
    val path = uri.path ?: return null
    if (path.startsWith("/external_files/")) {
        return Environment.getExternalStorageDirectory().absolutePath + path.removePrefix("/external_files")
    }
    if (path.contains("/document/primary:")) {
        val subPath = path.substringAfter("primary:")
        return Environment.getExternalStorageDirectory().absolutePath + "/" + subPath.replace("%2F", "/")
    }
    if (path.startsWith("/")) return path
    return Environment.getExternalStorageDirectory().absolutePath + "/Download/" + uri.lastPathSegment
}

@Composable
fun ArchiveExplorerScreen(archiver: XRayDbArchiver, archivePath: String, outputDirPath: String) {
    val scope = rememberCoroutineScope()
    var allEntries by remember { mutableStateOf<List<DbFileEntry>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedExtFilter by remember { mutableStateOf("Все") }
    val extFilters = listOf("Все", ".ltx", ".script", ".xml", ".dds", ".ogg", ".ogf")

    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Нажмите «Загрузить список», чтобы прочитать архив") }

    var editingEntry by remember { mutableStateOf<DbFileEntry?>(null) }
    var initialEditorText by remember { mutableStateOf("") }

    val filteredEntries by remember(allEntries, searchQuery, selectedExtFilter) {
        derivedStateOf {
            allEntries.asSequence()
                .filter { if (selectedExtFilter == "Все") true else it.relativePath.endsWith(selectedExtFilter, ignoreCase = true) }
                .filter { if (searchQuery.isBlank()) true else it.relativePath.contains(searchQuery.trim(), ignoreCase = true) }
                .toList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    isBusy = true
                    selectedPaths = emptySet()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { archiver.readArchiveIndex(File(archivePath)) }
                        }.onSuccess { list ->
                            allEntries = list
                            statusMessage = "Файлов в архиве: ${list.size}"
                        }.onFailure { e ->
                            statusMessage = "Ошибка: ${e.localizedMessage}"
                        }
                        isBusy = false
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Загрузить список")
            }

            if (selectedPaths.isNotEmpty()) {
                Button(
                    onClick = {
                        isBusy = true
                        val toExtract = allEntries.filter { it.relativePath in selectedPaths }
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    archiver.extractSelectedEntries(File(archivePath), toExtract, File(outputDirPath)) { cur, total, name ->
                                        statusMessage = "Извлечение ($cur/$total): $name"
                                    }
                                }
                            }.onSuccess {
                                statusMessage = "Извлечено файлов: ${toExtract.size}"
                                selectedPaths = emptySet()
                            }.onFailure { e ->
                                statusMessage = "Ошибка: ${e.localizedMessage}"
                            }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Извлечь (${selectedPaths.size})")
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Поиск (actor.ltx, w_ak74, bind_stalker)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            extFilters.forEach { ext ->
                FilterChip(selected = selectedExtFilter == ext, onClick = { selectedExtFilter = ext }, label = { Text(ext) })
            }
        }

        Text(text = "$statusMessage | Показано: ${filteredEntries.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items = filteredEntries, key = { it.relativePath }) { entry ->
                val isChecked = entry.relativePath in selectedPaths
                val isTextFile = entry.relativePath.endsWith(".ltx", true) || entry.relativePath.endsWith(".script", true) || entry.relativePath.endsWith(".xml", true) || entry.relativePath.endsWith(".ini", true)

                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (isTextFile) {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { archiver.readEntryAsText(File(archivePath), entry) }
                                }.onSuccess { text ->
                                    initialEditorText = text
                                    editingEntry = entry
                                }.onFailure { statusMessage = "Ошибка: ${it.localizedMessage}" }
                            }
                        } else {
                            selectedPaths = if (isChecked) selectedPaths - entry.relativePath else selectedPaths + entry.relativePath
                        }
                    }
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isChecked, onCheckedChange = { checked ->
                            selectedPaths = if (checked) selectedPaths + entry.relativePath else selectedPaths - entry.relativePath
                        })
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(text = entry.relativePath.substringAfterLast(File.separatorChar), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(text = entry.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(text = formatFileSize(entry.sizeReal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { archiver.extractSingleEntry(File(archivePath), entry, File(outputDirPath)) }
                                }.onSuccess { statusMessage = "Сохранен: ${it.absolutePath}" }
                            }
                        }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("Извлечь", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    editingEntry?.let { entry ->
        ConfigEditorDialog(
            entry = entry,
            initialText = initialEditorText,
            onDismiss = { editingEntry = null },
            onSaveToGamedata = { updatedText ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { archiver.saveTextEntryToFolder(entry, File(outputDirPath), updatedText) }
                    }.onSuccess {
                        statusMessage = "Сохранено в gamedata"
                        editingEntry = null
                    }
                }
            },
            onSaveToArchive = { updatedText ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { archiver.updateTextEntryInArchive(File(archivePath), entry, updatedText) }
                    }.onSuccess { updatedList ->
                        allEntries = updatedList
                        statusMessage = "Обновлено внутри .db архива!"
                        editingEntry = null
                    }
                }
            }
        )
    }
}

@Composable
fun ConfigEditorDialog(entry: DbFileEntry, initialText: String, onDismiss: () -> Unit, onSaveToGamedata: (String) -> Unit, onSaveToArchive: (String) -> Unit) {
    var textFieldValue by remember(entry) { mutableStateOf(TextFieldValue(text = initialText)) }
    var findQuery by remember { mutableStateOf("") }
    val isModified = textFieldValue.text != initialText
    val quickSymbols = listOf("[", "]", "=", ";", "_", "\"", ":", "--", ".", ",")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(8.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = entry.relativePath.substringAfterLast(File.separatorChar) + if (isModified) " *" else "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = findQuery, onValueChange = { findQuery = it }, label = { Text("Найти в файле") }, singleLine = true, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = {
                        if (findQuery.isNotEmpty()) {
                            val startPos = (textFieldValue.selection.end).coerceAtLeast(0)
                            val text = textFieldValue.text
                            var foundIdx = text.indexOf(findQuery, startPos, ignoreCase = true)
                            if (foundIdx == -1 && startPos > 0) foundIdx = text.indexOf(findQuery, 0, ignoreCase = true)
                            if (foundIdx != -1) textFieldValue = textFieldValue.copy(selection = TextRange(foundIdx, foundIdx + findQuery.length))
                        }
                    }) { Text("Найти") }
                }

                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    quickSymbols.forEach { sym ->
                        AssistChip(onClick = {
                            val cur = textFieldValue.text
                            val start = textFieldValue.selection.start.coerceIn(0, cur.length)
                            val end = textFieldValue.selection.end.coerceIn(0, cur.length)
                            textFieldValue = TextFieldValue(text = cur.substring(0, start) + sym + cur.substring(end), selection = TextRange(start + sym.length))
                        }, label = { Text(sym, fontFamily = FontFamily.Monospace) })
                    }
                }

                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSaveToGamedata(textFieldValue.text) }, modifier = Modifier.weight(1f)) { Text("В gamedata") }
                    Button(onClick = { onSaveToArchive(textFieldValue.text) }, modifier = Modifier.weight(1f)) { Text("Вшить в .db") }
                }
            }
        }
    }
}

@Composable
fun FullUnpackPackTab(archiver: XRayDbArchiver, archivePath: String, folderPath: String) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Готов к полной распаковке или сборке архива") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                isWorking = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            archiver.unpackArchive(File(archivePath), File(folderPath)) { cur, total, name ->
                                progress = cur.toFloat() / total.toFloat()
                                statusText = "Распаковка ($cur/$total): $name"
                            }
                        }
                    }.onSuccess { statusText = "Успешно распаковано!" }.onFailure { statusText = "Ошибка: ${it.localizedMessage}" }
                    isWorking = false
                }
            }, modifier = Modifier.weight(1f), enabled = !isWorking) { Text("Распаковать всё") }

            Button(onClick = {
                isWorking = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            archiver.packDirectory(File(folderPath), File(archivePath)) { cur, total, name ->
                                progress = cur.toFloat() / total.toFloat()
                                statusText = "Упаковка ($cur/$total): $name"
                            }
                        }
                    }.onSuccess { statusText = "Архив успешно собран!" }.onFailure { statusText = "Ошибка: ${it.localizedMessage}" }
                    isWorking = false
                }
            }, modifier = Modifier.weight(1f), enabled = !isWorking) { Text("Запаковать папку") }
        }

        if (isWorking) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
    return String.format(Locale.US, "%.2f МБ", kb / 1024.0)
}
