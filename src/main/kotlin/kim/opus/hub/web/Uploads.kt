package kim.opus.hub.web

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.javalin.http.UploadedFile
import kim.opus.hub.model.*
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.YearMonth
import java.util.UUID

object Uploads {
    private lateinit var root: Path
    private var maxBytes: Long = 64L * 1024 * 1024

    fun configure(uploadDir: Path, maxUploadBytes: Long) {
        root = uploadDir
        maxBytes = maxUploadBytes
        Files.createDirectories(root)
    }

    val limitBytes: Long get() = maxBytes

    fun safeName(raw: String?): String {
        val base = (raw ?: "file").replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.filter { it.code >= 32 && it !in "\"<>|?*:" }.take(180)
        return cleaned.ifBlank { "file" }
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1).lowercase().filter { it.isLetterOrDigit() }
        return if (ext.isEmpty() || ext.length > 12) "" else ".$ext"
    }

    fun store(file: UploadedFile): Triple<String, String, Long> {
        val original = safeName(file.filename())
        if (file.size() <= 0) throw BadRequestResponse("文件「$original」是空的")
        if (file.size() > maxBytes) {
            throw BadRequestResponse("文件「$original」超过上限 ${formatSize(maxBytes)}")
        }
        val bucket = YearMonth.now().toString()
        val dir = root.resolve(bucket)
        Files.createDirectories(dir)
        val stored = UUID.randomUUID().toString().replace("-", "") + extensionOf(original)
        val target = dir.resolve(stored)
        file.content().use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val size = Files.size(target)
        if (size > maxBytes) {
            Files.deleteIfExists(target)
            throw BadRequestResponse("文件「$original」超过上限 ${formatSize(maxBytes)}")
        }
        return Triple(original, "$bucket/$stored", size)
    }

    fun resolve(storedName: String): Path {
        val normalized = root.resolve(storedName).normalize()
        if (!normalized.startsWith(root)) throw BadRequestResponse("路径不合法")
        return normalized
    }

    fun deleteQuietly(storedName: String) {
        runCatching { Files.deleteIfExists(resolve(storedName)) }
    }

    fun storeAll(files: List<UploadedFile>): List<NewFile> {
        val stored = ArrayList<NewFile>()
        try {
            files.forEach { f ->
                val (original, name, size) = store(f)
                stored.add(NewFile(original, name, size, f.contentType()))
            }
        } catch (ex: Exception) {
            stored.forEach { deleteQuietly(it.storedName) }
            throw ex
        }
        return stored
    }

    private fun encodedName(originalName: String): String =
        URLEncoder.encode(originalName, "UTF-8").replace("+", "%20")

    fun send(ctx: Context, storedName: String, originalName: String) {
        val path = resolve(storedName)
        if (!Files.exists(path)) throw NotFoundResponse("文件已不在磁盘上")
        ctx.header("Content-Disposition", "attachment; filename*=UTF-8''${encodedName(originalName)}")
        ctx.header("X-Content-Type-Options", "nosniff")
        ctx.header("Cache-Control", "private, no-store")
        ctx.contentType("application/octet-stream")
        ctx.result(Files.newInputStream(path))
    }

    fun sendImage(ctx: Context, storedName: String, originalName: String, type: String) {
        val path = resolve(storedName)
        if (!Files.exists(path)) throw NotFoundResponse("文件已不在磁盘上")
        ctx.header("Content-Disposition", "inline; filename*=UTF-8''${encodedName(originalName)}")
        ctx.header("X-Content-Type-Options", "nosniff")
        ctx.header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
        ctx.header("Cache-Control", "private, max-age=86400")
        ctx.contentType(type)
        ctx.result(Files.newInputStream(path))
    }
}
