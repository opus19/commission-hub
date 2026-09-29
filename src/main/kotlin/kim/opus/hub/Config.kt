package kim.opus.hub

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

class Config(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val maxUploadBytes: Long,
    val sessionMinutes: Long,
    val secureCookie: Boolean,
    val adminDisplayName: String
) {
    val dbPath: Path get() = dataDir.resolve("hub.db")
    val uploadDir: Path get() = dataDir.resolve("uploads")

    companion object {
        fun load(): Config {
            val props = Properties()
            val file = Path.of(System.getProperty("hub.config") ?: "config.properties").toAbsolutePath().normalize()
            if (Files.notExists(file)) {
                writeDefault(file)
            }
            if (Files.exists(file)) {
                props.load(StringReader(readText(file)))
            }

            fun raw(key: String): String? {
                val env = System.getenv("HUB_" + key.uppercase().replace('.', '_'))
                if (!env.isNullOrBlank()) return env.trim()
                val p = props.getProperty(key)
                return if (p.isNullOrBlank()) null else p.trim()
            }

            fun str(key: String, def: String) = raw(key) ?: def
            fun int(key: String, def: Int) = raw(key)?.toIntOrNull() ?: def
            fun long(key: String, def: Long) = raw(key)?.toLongOrNull() ?: def
            fun bool(key: String, def: Boolean) = raw(key)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: def

            return Config(
                host = str("host", "0.0.0.0"),
                port = int("port", 13140),
                dataDir = Path.of(str("data.dir", "data")).toAbsolutePath().normalize(),
                maxUploadBytes = long("upload.max.mb", 64) * 1024 * 1024,
                sessionMinutes = long("session.minutes", 60 * 24 * 14),
                secureCookie = bool("cookie.secure", false),
                adminDisplayName = str("admin.display.name", "管理员").take(50)
            )
        }

        private val DEFAULT_TEMPLATE = """
            host=0.0.0.0
            port=13140

            data.dir=data

            upload.max.mb=64

            session.minutes=20160

            cookie.secure=false

            admin.display.name=十二新作
        """.trimIndent() + "\n"

        private fun writeDefault(file: Path) {
            runCatching {
                file.parent?.let { Files.createDirectories(it) }
                Files.writeString(file, DEFAULT_TEMPLATE, StandardOpenOption.CREATE_NEW)
            }.onSuccess {
                println("已生成默认配置文件: " + file)
            }.onFailure {
                println("无法生成配置文件 " + file + ": " + it.message)
            }
        }

        private fun readText(file: Path): String {
            val bytes = Files.readAllBytes(file)
            val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
            return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString() }
                .getOrElse { String(body, Charset.forName(System.getProperty("native.encoding") ?: "UTF-8")) }
        }
    }
}
