package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object AdminHandlers {

    private val usernamePattern = Regex("^[A-Za-z0-9._-]{3,32}$")

    private fun clientTarget(ctx: Context): User {
        val target = UserRepo.byId(ctx.idParam())
        if (target == null || target.isAdmin || !target.active) throw NotFoundResponse("账号不存在")
        return target
    }

    private fun projectChecks(projects: List<Project>, selected: Set<Long>, prefix: String): String {
        val list = if (projects.isEmpty()) """<div class="form-text mt-0">还没有项目，先去「项目」页面新建</div>"""
        else """<div class="project-check-list">${
            projects.joinToString("") { p ->
                val id = "${prefix}_${p.id}"
                """<div class="form-check"><input class="form-check-input" type="checkbox" name="projects" value="${p.id}" id="$id"${if (p.id in selected) " checked" else ""}><label class="form-check-label" for="$id">${e(p.name)}</label></div>"""
            }
        }</div>"""
        return """
<fieldset class="project-checks mb-1">
  <legend class="form-label mb-2">所属项目</legend>
  $list
  <div class="form-text">勾选的项目，这个客户就能看到里面的需求并参与验收</div>
</fieldset>"""
    }

    fun userList(ctx: Context) {
        ctx.requireAdmin()
        val clients = UserRepo.clients()
        val projects = ProjectRepo.all()
        val memberships = MemberRepo.projectsByUser()

        val rows = clients.joinToString("") { u ->
            val projectCell = memberships[u.id]?.takeIf { it.isNotEmpty() }?.joinToString("") { p ->
                """<a class="project-chip" href="/projects/${p.id}">${e(p.name)}</a>"""
            } ?: """<span class="text-warning-emphasis">未分配项目</span>"""
            """
<tr>
  <td>
    <div class="min-w-0">
      <div class="text-truncate">${e(u.displayName)}</div>
      <div class="small"><code>${e(u.username)}</code></div>
    </div>
  </td>
  <td><div class="d-flex flex-wrap gap-1">$projectCell</div></td>
  <td class="small text-secondary text-nowrap">${e(formatStamp(u.createdAt))}</td>
  <td class="text-nowrap">${accountActions(ctx, u)}</td>
</tr>"""
        }

        val table = if (clients.isEmpty()) emptyState("还没有客户账号，点上面的「新建客户账号」开始", "bi-people") else """
<div class="card">
  <div class="table-responsive">
    <table class="table table-hover align-middle mb-0">
      <thead><tr><th>账号</th><th>所属项目</th><th>创建时间</th><th>操作</th></tr></thead>
      <tbody>$rows</tbody>
    </table>
  </div>
</div>"""

        val createModal = modalForm(
            ctx, "newAccount", "新建客户账号", "/users",
            field("登录账号", "username", required = true, placeholder = "英文、数字、点、下划线", hint = "用来登录，创建后不能改", id = "na_user", extra = """pattern="[A-Za-z0-9._\-]{3,32}" autocomplete="off"""") +
                field("显示名", "display_name", required = true, placeholder = "例如 QQ 昵称", id = "na_name", extra = """maxlength="50"""") +
                field("初始密码", "password", hint = "留空会自动生成一个随机密码，创建后只显示一次", id = "na_pass", extra = """autocomplete="new-password" minlength="8"""") +
                projectChecks(projects, emptySet(), "na_p"),
            "创建账号"
        )

        val editModals = clients.joinToString("") { u ->
            modalForm(
                ctx, "editUser${u.id}", "编辑账号 · ${u.username}", "/users/${u.id}",
                field("显示名", "display_name", u.displayName, required = true, id = "eu${u.id}_name", extra = """maxlength="50"""") +
                    projectChecks(projects, memberships[u.id]?.map { it.id }?.toSet() ?: emptySet(), "eu${u.id}_p"),
                "保存"
            )
        }

        val main = """
<h3 class="mb-3">账号</h3>
<div class="d-flex flex-wrap align-items-center gap-2 mb-4">
  <button class="btn btn-primary btn-sm" type="button" data-bs-toggle="modal" data-bs-target="#newAccount"><i class="bi bi-person-plus me-1"></i>新建客户账号</button>
</div>
$table
$createModal
$editModals"""
        ctx.html(page(ctx, "账号", "users", main, back = "/projects" to "项目"))
    }

    private fun accountActions(ctx: Context, u: User): String {
        val name = e(u.username)
        return """
<div class="d-flex flex-nowrap align-items-center gap-2">
  <button class="btn btn-sm btn-outline-secondary" type="button" data-bs-toggle="modal" data-bs-target="#editUser${u.id}" aria-label="编辑账号：$name"><i class="bi bi-pencil me-1"></i>编辑</button>
  <form method="post" action="/users/${u.id}/reset" class="m-0" data-confirm="重置 $name 的密码？旧密码会立即失效">
    ${csrfInput(ctx)}
    <button class="btn btn-sm btn-outline-secondary" type="submit" aria-label="重置密码：$name"><i class="bi bi-key me-1"></i>重置密码</button>
  </form>
  <form method="post" action="/users/${u.id}/remove" class="m-0" data-confirm="移除 $name？对方会被立刻踢下线，不能再登录，发过的需求和补充信息会保留">
    ${csrfInput(ctx)}
    <button class="btn btn-sm btn-outline-danger" type="submit" aria-label="移除账号：$name"><i class="bi bi-person-x me-1"></i>移除</button>
  </form>
</div>"""
    }

    private fun validProjectIds(ctx: Context): List<Long> {
        val valid = ProjectRepo.all().map { it.id }.toSet()
        return ctx.formParams("projects").mapNotNull { it.toLongOrNull() }.filter { it in valid }.distinct()
    }

    fun userCreate(ctx: Context) {
        val me = ctx.requireAdmin()
        val username = ctx.formParam("username")?.trim().orEmpty()
        val displayName = ctx.formParam("display_name")?.trim().orEmpty()
        val supplied = ctx.formParam("password")?.trim()
        val projectIds = validProjectIds(ctx)

        when {
            !username.matches(usernamePattern) -> ctx.flashErr("账号只能用字母、数字、点、下划线和减号，长度 3 到 32 位")
            displayName.isEmpty() -> ctx.flashErr("显示名不能为空")
            UserRepo.byUsername(username) != null -> ctx.flashErr("账号「$username」已被占用")
            !supplied.isNullOrEmpty() && !Passwords.strongEnough(supplied) -> ctx.flashErr("初始密码至少 8 位")
            else -> {
                val password = if (supplied.isNullOrEmpty()) Tokens.generate(9) else supplied
                UserRepo.createClient(username, displayName.take(50), password, projectIds, me.id)
                val scope = if (projectIds.isEmpty()) "还没分配项目" else "${projectIds.size} 个项目"
                ctx.flashOkKeep("客户账号已创建（$scope），登录账号 $username，初始密码 $password（只显示这一次，请复制给对方）")
            }
        }
        ctx.go("/users")
    }

    fun userUpdate(ctx: Context) {
        val me = ctx.requireAdmin()
        val target = clientTarget(ctx)
        val displayName = ctx.formParam("display_name")?.trim().orEmpty()
        if (displayName.isEmpty()) {
            ctx.flashErr("显示名不能为空")
            ctx.go("/users")
            return
        }
        UserRepo.updateClient(target.id, displayName.take(50), validProjectIds(ctx), me.id)
        ctx.flashOk("已保存 ${target.username}")
        ctx.go("/users")
    }

    fun userResetPassword(ctx: Context) {
        val me = ctx.requireAdmin()
        val target = clientTarget(ctx)
        val password = Tokens.generate(9)
        UserRepo.setPassword(target.id, password, me.id)
        Sessions.endAllFor(target.id)
        ctx.flashOkKeep("已重置 ${target.username} 的密码，新密码 $password（只显示这一次）")
        ctx.go("/users")
    }

    fun userRemove(ctx: Context) {
        val me = ctx.requireAdmin()
        val target = clientTarget(ctx)
        UserRepo.remove(target, me.id)
        Sessions.endAllFor(target.id)
        ctx.flashOk("已移除 ${target.username}，对方已被强制下线")
        ctx.go("/users")
    }
}
