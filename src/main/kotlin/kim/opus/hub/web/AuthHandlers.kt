package kim.opus.hub.web

import io.javalin.http.Context
import kim.opus.hub.data.*
import kim.opus.hub.view.*

object AuthHandlers {

    fun loginPage(ctx: Context) {
        if (ctx.userOrNull() != null) {
            ProjectHandlers.list(ctx)
            return
        }
        renderLogin(ctx, null, null, safeNext(ctx.queryParam("next")))
    }

    fun renderLogin(ctx: Context, error: String?, username: String?, next: String?) {
        val nextField = if (next == null) "" else """<input type="hidden" name="next" value="${e(next)}">"""
        val body = """
<div class="col-md-6 col-lg-4 mx-auto" style="max-width:360px">
  ${if (error != null) """<div class="alert alert-danger" role="alert">${e(error)}</div>""" else ""}
  <form method="post" action="/login">
    $nextField
    <div class="mb-3">
      <label class="form-label" for="l_user">账号</label>
      <input class="form-control" id="l_user" type="text" name="username" value="${e(username)}" required autofocus autocomplete="username">
    </div>
    <div class="mb-3">
      <label class="form-label" for="l_pass">密码</label>
      <input class="form-control" id="l_pass" type="password" name="password" required autocomplete="current-password">
    </div>
    <div class="d-grid">
      <button class="btn btn-primary" type="submit">登录</button>
    </div>
  </form>
</div>
"""
        ctx.html(barePage("登录", body))
    }

    fun login(ctx: Context) {
        val username = ctx.formParam("username")?.trim().orEmpty()
        val password = ctx.formParam("password").orEmpty()
        val next = safeNext(ctx.formParam("next"))
        val key = ctx.ip() + "|" + username.lowercase()

        if (username.isEmpty() || password.isEmpty()) {
            ctx.status(400)
            renderLogin(ctx, "请输入账号和密码", username, next)
            return
        }
        if (LoginGuard.blocked(key)) {
            ctx.status(429)
            renderLogin(ctx, "尝试次数过多，请 15 分钟后再试", username, next)
            return
        }

        val user = UserRepo.byUsername(username)
        val hash = user?.let { UserRepo.passwordHash(it.id) }

        if (user == null || !user.active || hash == null || !Passwords.verify(password, hash)) {
            LoginGuard.fail(key)
            ctx.status(401)
            renderLogin(ctx, "账号或密码不对", username, next)
            return
        }

        LoginGuard.success(key)
        Sessions.start(ctx, user.id)
        ctx.go(next ?: "/projects")
    }

    fun logout(ctx: Context) {
        Sessions.end(ctx)
        ctx.go("/login")
    }
}
