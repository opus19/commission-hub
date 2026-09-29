package kim.opus.hub.view

import java.security.MessageDigest

object Assets {

    const val CACHE_CONTROL = "public, max-age=31536000, immutable"

    fun url(path: String): String {
        val bytes = Assets::class.java.getResourceAsStream("/public/$path")?.use { it.readBytes() }
            ?: error("缺少静态文件 /public/$path")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "/public/$path?v=" + hash.take(10)
    }
}
