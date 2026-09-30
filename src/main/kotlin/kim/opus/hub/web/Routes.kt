package kim.opus.hub.web

import io.javalin.config.JavalinConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpResponseException
import kim.opus.hub.data.*
import kim.opus.hub.view.*

object Routes {

    fun register(config: JavalinConfig) {
        val r = config.routes

        fun post(path: String, handler: (Context) -> Unit) {
            r.post(path) { ctx ->
                ctx.checkCsrf()
                handler(ctx)
            }
        }

        r.before { ctx ->
            ctx.header("X-Frame-Options", "DENY")
            ctx.header("X-Content-Type-Options", "nosniff")
            ctx.header("Referrer-Policy", "same-origin")
            if (!ctx.path().startsWith("/public/")) ctx.header("Cache-Control", "no-store")
            attachUser(ctx)
        }

        r.before { ctx -> guard(ctx) }

        r.get("/healthz") { ctx -> ctx.result("ok") }
        r.get("/login") { ctx -> AuthHandlers.loginPage(ctx) }
        r.post("/login") { ctx -> AuthHandlers.login(ctx) }
        post("/logout") { ctx -> AuthHandlers.logout(ctx) }

        r.get("/") { ctx -> ProjectHandlers.list(ctx) }

        r.get("/users") { ctx -> AdminHandlers.userList(ctx) }
        post("/users") { ctx -> AdminHandlers.userCreate(ctx) }
        post("/users/{id}") { ctx -> AdminHandlers.userUpdate(ctx) }
        post("/users/{id}/reset") { ctx -> AdminHandlers.userResetPassword(ctx) }
        post("/users/{id}/remove") { ctx -> AdminHandlers.userRemove(ctx) }

        r.get("/projects") { ctx -> ProjectHandlers.list(ctx) }
        post("/projects") { ctx -> ProjectHandlers.create(ctx) }
        r.get("/projects/{id}") { ctx -> ProjectHandlers.detail(ctx) }
        post("/projects/{id}") { ctx -> ProjectHandlers.update(ctx) }
        post("/projects/{id}/delete") { ctx -> ProjectHandlers.delete(ctx) }
        r.get("/projects/{id}/releases") { ctx -> ctx.go("/projects/" + ctx.idParam()) }

        r.get("/requirements/new") { ctx -> RequirementHandlers.newPage(ctx) }
        post("/requirements") { ctx -> RequirementHandlers.create(ctx) }
        r.get("/requirements/{id}") { ctx -> RequirementHandlers.detail(ctx) }
        r.get("/requirements/{id}/edit") { ctx -> RequirementHandlers.editPage(ctx) }
        post("/requirements/{id}") { ctx -> RequirementHandlers.update(ctx) }
        post("/requirements/{id}/status") { ctx -> RequirementHandlers.changeStatus(ctx) }
        post("/requirements/{id}/comments") { ctx -> CommentHandlers.create(ctx) }
        post("/requirements/{id}/versions") { ctx -> VersionHandlers.upload(ctx) }
        post("/requirements/{id}/items") { ctx -> ItemHandlers.add(ctx) }
        post("/requirements/{id}/items/fold") { ctx -> ItemHandlers.fold(ctx) }
        post("/comments/{id}") { ctx -> CommentHandlers.update(ctx) }
        post("/comments/{id}/delete") { ctx -> CommentHandlers.delete(ctx) }
        post("/comments/{id}/fold") { ctx -> CommentHandlers.fold(ctx) }
        post("/items/{id}/done") { ctx -> ItemHandlers.done(ctx) }
        post("/items/{id}/tested") { ctx -> ItemHandlers.tested(ctx) }

        post("/versions/{id}/delete") { ctx -> VersionHandlers.delete(ctx) }
        r.get("/downloads/{id}") { ctx -> VersionHandlers.download(ctx) }

        r.get("/attachments/{id}") { ctx -> CommentHandlers.download(ctx) }
        r.get("/attachments/{id}/image") { ctx -> CommentHandlers.image(ctx) }
        post("/attachments/{id}/delete") { ctx -> CommentHandlers.deleteAttachment(ctx) }

        r.exception(HttpResponseException::class.java) { e, ctx ->
            ctx.status(e.status)
            ctx.html(errorPage(ctx, e.status, e.message ?: "请求出错了"))
        }
        r.exception(Exception::class.java) { e, ctx ->
            ctx.status(500)
            ctx.html(errorPage(ctx, 500, "服务器出错了：" + (e.message ?: e.javaClass.simpleName)))
        }
        r.error(404) { ctx ->
            if (ctx.result() == null) ctx.html(errorPage(ctx, 404, "页面不存在"))
        }
    }

    private fun attachUser(ctx: Context) {
        val session = Sessions.current(ctx) ?: return
        val user = UserRepo.byId(session.userId)
        if (user == null || !user.active) {
            Sessions.end(ctx)
            return
        }
        ctx.attribute(ATTR_USER, user)
        ctx.attribute(ATTR_SESSION, session)
    }

    private fun guard(ctx: Context) {
        val path = ctx.path()
        if (path == "/login" || path == "/healthz" || path.startsWith("/public/")) return
        if (ctx.userOrNull() == null) {
            if (ctx.method() == HandlerType.GET) {
                val query = ctx.queryString()?.let { "?$it" }.orEmpty()
                AuthHandlers.renderLogin(ctx, null, null, if (path == "/") null else safeNext(path + query))
            } else {
                ctx.go("/login")
            }
            ctx.skipRemainingHandlers()
        }
    }

    private fun errorPage(ctx: Context, status: Int, message: String): String {
        val face = when (status) {
            404 -> "(=‘x‘=)"
            403 -> "(=‘^‘=)"
            else -> "(=‘o‘=)"
        }
        val body = """
<div class="d-flex flex-column align-items-center py-5">
  <div class="mb-4 text-secondary error-face">$face</div>
  <h4 class="text-center mb-2">$status</h4>
  <div class="text-center text-secondary mb-4">${e(message)}</div>
  <a class="btn btn-link" href="/projects">回到项目列表</a>
</div>"""
        return if (ctx.userOrNull() != null && ctx.attribute<Session>(ATTR_SESSION) != null) page(ctx, "出错了", "", body)
        else barePage("出错了", body)
    }
}
