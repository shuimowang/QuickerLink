package app.quickerlink.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.quickerlink.connection.QuickerToolboxProtocol
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class PreparedUpload(
    val file: File,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
)

data class ScreenPreview(
    val file: File,
    val name: String,
    val mime: String,
)

data class SavedDownload(
    val name: String,
    val location: String,
    val uri: Uri?,
)

internal class TransferFileTooLargeException :
    IllegalArgumentException("文件超过 64 MiB 上限")

class AndroidTransferStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cacheDirectory = File(appContext.cacheDir, "quicker-link-transfers")

    init {
        ensureCacheDirectory()
        cleanupExpiredCacheFiles()
    }

    fun prepareUpload(uri: Uri): PreparedUpload {
        require(uri.scheme == "content") { "只能发送通过系统选择的文件" }
        val name = sanitizeFileName(queryDisplayName(uri))
        val mime = normalizeMime(resolver.getType(uri), name)
        val part = newCacheFile("upload", ".part")
        val ready = newCacheFile("upload", ".ready")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(part).use { output ->
                    copyBounded(input, output, digest).also { output.fd.sync() }
                }
            } ?: throw IllegalArgumentException("无法读取所选文件")
            atomicMove(part, ready)
            return PreparedUpload(
                file = ready,
                name = name,
                mime = mime,
                size = size,
                sha256 = digest.digest().toLowerHex(),
            )
        } catch (error: Exception) {
            part.delete()
            ready.delete()
            throw error
        }
    }

    fun createIncomingPart(): File = newCacheFile("incoming", ".part")

    fun finalizeScreen(part: File, name: String, mime: String): ScreenPreview {
        require(part.parentFile == cacheDirectory && part.isFile) { "屏幕快照临时文件无效" }
        val safeName = sanitizeFileName(name)
        val normalizedMime = normalizeMime(mime, safeName)
        val extension = extensionFor(normalizedMime, safeName)
        val finalFile = newCacheFile("screen", extension)
        atomicMove(part, finalFile)
        return ScreenPreview(finalFile, safeName, normalizedMime)
    }

    fun saveToDownloads(source: File, requestedName: String, mime: String): SavedDownload {
        require(source.parentFile == cacheDirectory && source.isFile) { "待保存文件无效" }
        require(source.length() <= QuickerToolboxProtocol.MAX_FILE_BYTES) { "文件大小无效" }
        val safeName = sanitizeFileName(requestedName)
        val normalizedMime = normalizeMime(mime, safeName)
        return saveToMediaStore(source, safeName, normalizedMime)
    }

    fun delete(file: File?) {
        if (file?.parentFile == cacheDirectory) file.delete()
    }

    private fun saveToMediaStore(source: File, name: String, mime: String): SavedDownload {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val pendingName = ".${UUID.randomUUID()}-$name.part"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, pendingName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Quicker Link")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在下载目录创建文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> copyExact(input, output, source.length()) }
            } ?: throw IllegalStateException("无法写入下载目录")
            val completed = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            check(resolver.update(uri, completed, null, null) == 1) { "无法完成下载文件" }
            val savedName = queryDisplayNameOrNull(uri) ?: name
            return SavedDownload(savedName, "下载 / Quicker Link", uri)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return queryDisplayNameOrNull(uri)
            ?: "来自手机-${System.currentTimeMillis()}.bin"
    }

    private fun queryDisplayNameOrNull(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun newCacheFile(prefix: String, suffix: String): File {
        ensureCacheDirectory()
        return File(cacheDirectory, "$prefix-${UUID.randomUUID()}$suffix")
    }

    private fun ensureCacheDirectory() {
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "无法创建传输临时目录" }
    }

    private fun cleanupExpiredCacheFiles() {
        val threshold = Instant.now().minus(2, ChronoUnit.HOURS).toEpochMilli()
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < threshold) file.delete()
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
    ): Long {
        val buffer = ByteArray(QuickerToolboxProtocol.CHUNK_BYTES)
        var total = 0L
        var emptyReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (read == 0) {
                check(++emptyReads <= MAX_EMPTY_READS) { "读取文件时没有取得数据" }
                continue
            }
            emptyReads = 0
            total += read
            if (total > QuickerToolboxProtocol.MAX_FILE_BYTES) throw TransferFileTooLargeException()
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, expectedSize: Long) {
        val buffer = ByteArray(QuickerToolboxProtocol.CHUNK_BYTES)
        var total = 0L
        while (total < expectedSize) {
            val count = minOf(buffer.size.toLong(), expectedSize - total).toInt()
            val read = input.read(buffer, 0, count)
            check(read > 0) { "读取临时文件失败" }
            output.write(buffer, 0, read)
            total += read
        }
        check(input.read() == -1) { "临时文件大小已变化" }
        output.flush()
    }

    private fun sanitizeFileName(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val replaced = leaf.map { character ->
            if (character.isISOControl() || character in WINDOWS_INVALID_FILE_CHARS) '_' else character
        }.joinToString("").trim().trimEnd('.', ' ')
        val fallback = replaced.ifEmpty { "文件-${System.currentTimeMillis()}.bin" }
        val shortened = shortenFileName(fallback, MAX_FILE_NAME_LENGTH)
        val stem = shortened.substringBeforeLast('.', shortened)
        return if (stem.uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES) "_$shortened" else shortened
    }

    private fun shortenFileName(value: String, maximum: Int): String {
        if (value.length <= maximum) return value
        val dot = value.lastIndexOf('.').takeIf { it in 1 until value.lastIndex } ?: return value.take(maximum)
        val extension = value.substring(dot).take(16)
        return value.take(maximum - extension.length) + extension
    }

    private fun normalizeMime(raw: String?, name: String): String {
        val candidate = raw
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { value ->
                value.isNotEmpty() && value.length <= 127 && value.all { it.code in 33..126 && it != '"' && it != '\\' }
            }
        return candidate ?: mimeFromName(name)
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun extensionFor(mime: String, name: String): String {
        val existing = name.substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        if (existing != null) return ".${existing.lowercase(Locale.ROOT)}"
        return when (mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            else -> ".bin"
        }
    }

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { value ->
        "%02x".format(value)
    }

    private companion object {
        const val MAX_FILE_NAME_LENGTH = 120
        const val MAX_EMPTY_READS = 32
        val WINDOWS_INVALID_FILE_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { index ->
                add("COM$index")
                add("LPT$index")
            }
        }
    }
}
