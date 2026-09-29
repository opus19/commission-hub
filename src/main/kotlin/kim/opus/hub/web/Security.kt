package kim.opus.hub.web

import io.javalin.http.Context
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object Tokens {
    private val random = SecureRandom()

    fun generate(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}

class Session(val userId: Long, val csrf: String) {
    @Volatile
    var lastSeen: Long = System.currentTimeMillis()

    @Volatile
    var flashOk: String? = null

    @Volatile
    var flashErr: String? = null

    @Volatile
    var flashKeep: Boolean = false
}

object Sessions {
    const val COOKIE = "hub_sid"

    private val store = ConcurrentHashMap<String, Session>()
    private var ttlMillis: Long = 14L * 24 * 60 * 60 * 1000
    private var secure: Boolean = false

    fun configure(minutes: Long, secureCookie: Boolean) {
        ttlMillis = minutes * 60 * 1000
        secure = secureCookie
    }

    fun start(ctx: Context, userId: Long): Session {
        val token = Tokens.generate()
        val session = Session(userId, Tokens.generate(24))
        store[token] = session
        ctx.header("Set-Cookie", cookie(token, ttlMillis / 1000))
        return session
    }

    fun current(ctx: Context): Session? {
        val token = ctx.cookie(COOKIE) ?: return null
        val session = store[token] ?: return null
        if (System.currentTimeMillis() - session.lastSeen > ttlMillis) {
            store.remove(token)
            return null
        }
        session.lastSeen = System.currentTimeMillis()
        return session
    }

    fun end(ctx: Context) {
        ctx.cookie(COOKIE)?.let { store.remove(it) }
        ctx.header("Set-Cookie", cookie("", 0))
    }

    fun endAllFor(userId: Long) {
        store.entries.removeIf { it.value.userId == userId }
    }

    fun sweep() {
        val now = System.currentTimeMillis()
        store.entries.removeIf { now - it.value.lastSeen > ttlMillis }
    }

    private fun cookie(value: String, maxAgeSeconds: Long): String = buildString {
        append(COOKIE).append('=').append(value)
        append("; Path=/; HttpOnly; SameSite=Lax")
        append("; Max-Age=").append(maxAgeSeconds)
        if (secure) append("; Secure")
    }
}

object LoginGuard {
    private class Attempt(var count: Int, var until: Long)

    private val attempts = ConcurrentHashMap<String, Attempt>()
    private const val MAX = 8
    private const val WINDOW_MILLIS = 15 * 60 * 1000L

    fun blocked(key: String): Boolean {
        val a = attempts[key] ?: return false
        if (System.currentTimeMillis() > a.until) {
            attempts.remove(key)
            return false
        }
        return a.count >= MAX
    }

    fun fail(key: String) {
        val now = System.currentTimeMillis()
        attempts.compute(key) { _, existing ->
            if (existing == null || now > existing.until) Attempt(1, now + WINDOW_MILLIS)
            else existing.also { it.count++ }
        }
    }

    fun success(key: String) {
        attempts.remove(key)
    }
}
