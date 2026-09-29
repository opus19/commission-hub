package kim.opus.hub.data

import at.favre.lib.crypto.bcrypt.BCrypt

object Passwords {
    private const val COST = 12

    fun hash(plain: String): String = BCrypt.withDefaults().hashToString(COST, plain.toCharArray())

    fun verify(plain: String, hash: String): Boolean =
        runCatching { BCrypt.verifyer().verify(plain.toCharArray(), hash).verified }.getOrDefault(false)

    fun strongEnough(plain: String): Boolean = plain.length >= 8
}
