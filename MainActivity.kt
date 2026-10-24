package com.stalker.dbtool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class XRayDbArchiver {

    companion object {
        private const val CHUNK_DATA = 0
        private const val CHUNK_HEADER = 1
        private const val CFS_COMPRESS_MARK = 0x80000000L
        private val CP1251: Charset = Charset.forName("windows-1251")
    }

    fun readArchiveIndex(
        dbFile: File,
        decompressHeaderCallback: ((ByteArray) -> ByteArray)? = null
    ): List<DbFileEntry> {
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
                        decompressHeaderCallback?.invoke(rawChunk)
                            ?: throw IllegalStateException(
                                "Заголовок архива сжат (LZHUFF/Scrambled). Для зашифрованных архивов подключите JNI."
                            )
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

                entries.add(
                    DbFileEntry(
                        relativePath = path,
                        sizeReal = sizeReal,
                        sizeCompressed = sizeComp,
                        crc32 = crc,
                        offset = offset
                    )
                )
            }
        }
        return entries
    }

    fun unpackArchive(
        dbFile: File,
        outputDir: File,
        lzoDecompressor: ((compressed: ByteArray, realSize: Int) -> ByteArray)? = null,
        headerDecompressor: ((ByteArray) -> ByteArray)? = null,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ) {
        val entries = readArchiveIndex(dbFile, headerDecompressor)
        extractSelectedEntries(dbFile, entries, outputDir, lzoDecompressor, onProgress)
    }

    fun extractSingleEntry(
        dbFile: File,
        entry: DbFileEntry,
        outputDir: File,
        lzoDecompressor: ((compressed: ByteArray, realSize: Int) -> ByteArray)? = null
    ): File {
        val outFile = File(outputDir, entry.relativePath)
        outFile.parentFile?.mkdirs()

        RandomAccessFile(dbFile, "r").use { raf ->
            val data = readRawOrDecompressedEntry(raf, entry, lzoDecompressor)
            FileOutputStream(outFile).use { fos ->
                fos.write(data)
            }
        }
        return outFile
    }

    fun extractSelectedEntries(
        dbFile: File,
        entries: List<DbFileEntry>,
        outputDir: File,
        lzoDecompressor: ((compressed: ByteArray, realSize: Int) -> ByteArray)? = null,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ) {
        val sortedEntries = entries.sortedBy { it.offset }

        RandomAccessFile(dbFile, "r").use { raf ->
            sortedEntries.forEachIndexed { index, entry ->
                onProgress(index + 1, sortedEntries.size, entry.relativePath)

                val outFile = File(outputDir, entry.relativePath)
                outFile.parentFile?.mkdirs()

                val data = readRawOrDecompressedEntry(raf, entry, lzoDecompressor)
                FileOutputStream(outFile).use { fos ->
                    fos.write(data)
                }
            }
        }
    }

    fun readEntryAsText(
        dbFile: File,
        entry: DbFileEntry,
        lzoDecompressor: ((compressed: ByteArray, realSize: Int) -> ByteArray)? = null
    ): String {
        RandomAccessFile(dbFile, "r").use { raf ->
            val bytes = readRawOrDecompressedEntry(raf, entry, lzoDecompressor)
            return String(bytes, CP1251).replace("\r\n", "\n")
        }
    }

    fun saveTextEntryToFolder(
        entry: DbFileEntry,
        outputDir: File,
        newText: String
    ): File {
        val outFile = File(outputDir, entry.relativePath)
        outFile.parentFile?.mkdirs()
        val normalizedText = newText.replace("\r\n", "\n").replace("\n", "\r\n")
        val bytes = normalizedText.toByteArray(CP1251)
        FileOutputStream(outFile).use { fos ->
            fos.write(bytes)
        }
        return outFile
    }

    fun updateTextEntryInArchive(
        dbFile: File,
        targetEntry: DbFileEntry,
        newText: String
    ): List<DbFileEntry> {
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

            require(firstChunkId == CHUNK_DATA.toLong()) {
                "Чанк данных (ID=0) должен находиться в начале архива"
            }

            val newEntryOffset = 8L + firstChunkSize
            raf.seek(newEntryOffset)
            raf.write(newBytes)

            val updatedChunk0Size = (firstChunkSize + newBytes.size).toInt()
            val chunk1StartOffset = raf.filePointer

            val updatedEntries = currentEntries.map { entry ->
                if (entry.relativePath == targetEntry.relativePath) {
                    entry.copy(
                        sizeReal = newBytes.size.toLong(),
                        sizeCompressed = newBytes.size.toLong(),
                        crc32 = newCrc,
                        offset = newEntryOffset
                    )
                } else {
                    entry
                }
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

    fun packDirectory(
        sourceDir: File,
        outputDbFile: File,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ) {
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        require(files.isNotEmpty()) { "Папка пуста или не существует: ${sourceDir.absolutePath}" }

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

    private fun readRawOrDecompressedEntry(
        raf: RandomAccessFile,
        entry: DbFileEntry,
        lzoDecompressor: ((compressed: ByteArray, realSize: Int) -> ByteArray)?
    ): ByteArray {
        raf.seek(entry.offset)
        val rawData = ByteArray(entry.sizeCompressed.toInt())
        raf.readFully(rawData)

        return if (entry.sizeReal == entry.sizeCompressed) {
            rawData
        } else {
            lzoDecompressor?.invoke(rawData, entry.sizeReal.toInt())
                ?: throw IllegalStateException(
                    "Файл ${entry.relativePath} сжат LZO1X. Нужен JNI-модуль."
                )
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
    var archivePath by remember { mutableStateOf("$defaultRoot/StalkerCoP/patches/xpatch_02.db") }
    var folderPath by remember { mutableStateOf("$defaultRoot/StalkerCoP/gamedata") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Проводник .db", "Распаковка / Сборка")

    val hasStoragePermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hasStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Нужен доступ к файлам (Android 11+)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    ) {
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
            OutlinedTextField(
                value = archivePath,
                onValueChange = { archivePath = it },
                label = { Text("Путь к .db / .xdb архиву") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = folderPath,
                onValueChange = { folderPath = it },
                label = { Text("Папка gamedata (для извлечения и упаковки)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> ArchiveExplorerScreen(
                archiver = archiver,
                archivePath = archivePath,
                outputDirPath = folderPath
            )
            1 -> FullUnpackPackTab(
                archiver = archiver,
                archivePath = archivePath,
                folderPath = folderPath
            )
        }
    }
}

@Composable
fun ArchiveExplorerScreen(
    archiver: XRayDbArchiver,
    archivePath: String,
    outputDirPath: String
) {
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
                .filter { entry ->
                    if (selectedExtFilter == "Все") true
                    else entry.relativePath.endsWith(selectedExtFilter, ignoreCase = true)
                }
                .filter { entry ->
                    if (searchQuery.isBlank()) true
                    else entry.relativePath.contains(searchQuery.trim(), ignoreCase = true)
                }
                .toList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    isBusy = true
                    selectedPaths = emptySet()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                archiver.readArchiveIndex(File(archivePath))
                            }
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
                                    archiver.extractSelectedEntries(
                                        dbFile = File(archivePath),
                                        entries = toExtract,
                                        outputDir = File(outputDirPath),
                                        onProgress = { cur, total, name ->
                                            statusMessage = "Извлечение ($cur/$total): $name"
                                        }
                                    )
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
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
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            extFilters.forEach { ext ->
                FilterChip(
                    selected = selectedExtFilter == ext,
                    onClick = { selectedExtFilter = ext },
                    label = { Text(ext) }
                )
            }

            if (filteredEntries.isNotEmpty()) {
                AssistChip(
                    onClick = {
                        val allFilteredPaths = filteredEntries.map { it.relativePath }.toSet()
                        selectedPaths = if (selectedPaths.containsAll(allFilteredPaths)) {
                            selectedPaths - allFilteredPaths
                        } else {
                            selectedPaths + allFilteredPaths
                        }
                    },
                    label = { Text("Выбрать найденные (${filteredEntries.size})") }
                )
            }
        }

        Text(
            text = "$statusMessage | Показано: ${filteredEntries.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = filteredEntries,
                key = { it.relativePath }
            ) { entry ->
                val isChecked = entry.relativePath in selectedPaths
                val isTextFile = entry.relativePath.endsWith(".ltx", true) ||
                        entry.relativePath.endsWith(".script", true) ||
                        entry.relativePath.endsWith(".xml", true) ||
                        entry.relativePath.endsWith(".ini", true)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isTextFile) {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            archiver.readEntryAsText(File(archivePath), entry)
                                        }
                                    }.onSuccess { text ->
                                        initialEditorText = text
                                        editingEntry = entry
                                    }.onFailure { e ->
                                        statusMessage = "Ошибка открытия: ${e.localizedMessage}"
                                    }
                                }
                            } else {
                                selectedPaths = if (isChecked) {
                                    selectedPaths - entry.relativePath
                                } else {
                                    selectedPaths + entry.relativePath
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedPaths = if (checked) {
                                    selectedPaths + entry.relativePath
                                } else {
                                    selectedPaths - entry.relativePath
                                }
                            }
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = entry.relativePath.substringAfterLast(File.separatorChar),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.relativePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    append(formatFileSize(entry.sizeReal))
                                    if (isTextFile) append(" • Нажмите для редактирования")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            archiver.extractSingleEntry(
                                                dbFile = File(archivePath),
                                                entry = entry,
                                                outputDir = File(outputDirPath)
                                            )
                                        }
                                    }.onSuccess { savedFile ->
                                        statusMessage = "Сохранен: ${savedFile.absolutePath}"
                                    }.onFailure { e ->
                                        statusMessage = "Ошибка: ${e.localizedMessage}"
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
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
                        withContext(Dispatchers.IO) {
                            archiver.saveTextEntryToFolder(
                                entry = entry,
                                outputDir = File(outputDirPath),
                                newText = updatedText
                            )
                        }
                    }.onSuccess { savedFile ->
                        statusMessage = "Конфиг сохранен в gamedata: ${savedFile.absolutePath}"
                        editingEntry = null
                    }.onFailure { e ->
                        statusMessage = "Ошибка сохранения в папку: ${e.localizedMessage}"
                    }
                }
            },
            onSaveToArchive = { updatedText ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            archiver.updateTextEntryInArchive(
                                dbFile = File(archivePath),
                                targetEntry = entry,
                                newText = updatedText
                            )
                        }
                    }.onSuccess { updatedList ->
                        allEntries = updatedList
                        statusMessage = "Файл ${entry.relativePath} успешно обновлен внутри .db архива!"
                        editingEntry = null
                    }.onFailure { e ->
                        statusMessage = "Ошибка записи в .db: ${e.localizedMessage}"
                    }
                }
            }
        )
    }
}

@Composable
fun ConfigEditorDialog(
    entry: DbFileEntry,
    initialText: String,
    onDismiss: () -> Unit,
    onSaveToGamedata: (String) -> Unit,
    onSaveToArchive: (String) -> Unit
) {
    var textFieldValue by remember(entry) {
        mutableStateOf(TextFieldValue(text = initialText))
    }
    var findQuery by remember { mutableStateOf("") }
    val isModified = textFieldValue.text != initialText
    val quickSymbols = listOf("[", "]", "=", ";", "_", "\"", ":", "--", ".", ",")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildString {
                                append(entry.relativePath.substringAfterLast(File.separatorChar))
                                if (isModified) append(" *")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${entry.relativePath} (Windows-1251)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Закрыть")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        label = { Text("Найти в файле (секция / параметр)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            if (findQuery.isNotEmpty()) {
                                val startPos = (textFieldValue.selection.end).coerceAtLeast(0)
                                val text = textFieldValue.text
                                var foundIdx = text.indexOf(findQuery, startPos, ignoreCase = true)
                                if (foundIdx == -1 && startPos > 0) {
                                    foundIdx = text.indexOf(findQuery, 0, ignoreCase = true)
                                }
                                if (foundIdx != -1) {
                                    textFieldValue = textFieldValue.copy(
                                        selection = TextRange(foundIdx, foundIdx + findQuery.length)
                                    )
                                }
                            }
                        }
                    ) {
                        Text("Найти")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickSymbols.forEach { sym ->
                        AssistChip(
                            onClick = {
                                val curText = textFieldValue.text
                                val start = textFieldValue.selection.start.coerceIn(0, curText.length)
                                val end = textFieldValue.selection.end.coerceIn(0, curText.length)
                                val newText = curText.substring(0, start) + sym + curText.substring(end)
                                val newCursor = start + sym.length
                                textFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursor)
                                )
                            },
                            label = {
                                Text(
                                    text = sym,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSaveToGamedata(textFieldValue.text) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("В папку gamedata")
                    }

                    Button(
                        onClick = { onSaveToArchive(textFieldValue.text) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Вшить в .db")
                    }
                }
            }
        }
    }
}

@Composable
fun FullUnpackPackTab(
    archiver: XRayDbArchiver,
    archivePath: String,
    folderPath: String
) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Готов к полной распаковке или сборке архива") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    isWorking = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                archiver.unpackArchive(
                                    dbFile = File(archivePath),
                                    outputDir = File(folderPath),
                                    onProgress = { cur, total, name ->
                                        progress = cur.toFloat() / total.toFloat()
                                        statusText = "Распаковка ($cur/$total): $name"
                                    }
                                )
                            }
                        }.onSuccess {
                            statusText = "Полная распаковка успешно завершена!"
                        }.onFailure { e ->
                            statusText = "Ошибка: ${e.localizedMessage}"
                        }
                        isWorking = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isWorking
            ) {
                Text("Распаковать всё")
            }

            Button(
                onClick = {
                    isWorking = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                archiver.packDirectory(
                                    sourceDir = File(folderPath),
                                    outputDbFile = File(archivePath),
                                    onProgress = { cur, total, name ->
                                        progress = cur.toFloat() / total.toFloat()
                                        statusText = "Упаковка ($cur/$total): $name"
                                    }
                                )
                            }
                        }.onSuccess {
                            statusText = "Архив успешно собран: $archivePath"
                        }.onFailure { e ->
                            statusText = "Ошибка: ${e.localizedMessage}"
                        }
                        isWorking = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isWorking
            ) {
                Text("Запаковать папку")
            }
        }

        if (isWorking) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f МБ", mb)
}
