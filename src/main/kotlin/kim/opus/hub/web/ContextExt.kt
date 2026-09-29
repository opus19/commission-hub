package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.NotFoundResponse
import io.javalin.http.UnauthorizedResponse
import kim.opus.hub.model.*

const val ATTR_USER = "hub.user"
const val ATTR_SESSION = "hub.session"
const val NAV_HEADER = "X-Hub-Nav"
const val NAV_LOCATION = "X-Hub-Location"

fun Context.userOrNull(): User? = attribute(ATTR_USER)

fun Context.user(): User = userOrNull() ?: throw UnauthorizedResponse("请先登录")

fun Context.session(): Session = attribute(ATTR_SESSION) ?: throw UnauthorizedResponse("会话已过期")

fun Context.requireAdmin(): User {
    val u = user()
    if (!u.isAdmin) throw ForbiddenResponse("没有权限执行这个操作")
    return u
}

fun Context.checkCsrf() {
    val supplied = formParam("_csrf")
    if (supplied.isNullOrBlank() || supplied != session().csrf) {
        throw ForbiddenResponse("表单已过期，请刷新页面后重试")
    }
}

fun Context.isNav(): Boolean = header(NAV_HEADER) == "1"

fun Context.go(path: String) {
    if (isNav()) {
        header(NAV_LOCATION, path)
        status(200)
    } else {
        redirect(path)
    }
}

fun safeNext(raw: String?): String? {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty() || v.length > 500) return null
    if (!v.startsWith("/") || v.startsWith("//") || v.startsWith("/\\")) return null
    if (v.any { it.code < 32 || it == '\\' }) return null
    val path = v.substringBefore('?').substringBefore('#')
    if (path == "/login" || path == "/logout" || path == "/healthz" || path.startsWith("/public/")) return null
    return v
}

fun Context.flashOk(message: String) {
    session().flashOk = message
}

fun Context.flashOkKeep(message: String) {
    val s = session()
    s.flashOk = message
    s.flashKeep = true
}

fun Context.flashErr(message: String) {
    session().flashErr = message
}

fun Context.idParam(name: String = "id"): Long =
    pathParam(name).toLongOrNull() ?: throw NotFoundResponse("参数不合法")
