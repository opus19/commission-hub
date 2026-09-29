package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object ProjectHandlers {

    private fun statusChip(p: Project, extra: String = ""): String =
        """<span class="repo-badge pj-${p.statusEnum.code}$extra">${e(p.statusEnum.label)}</span>"""

    fun list(ctx: Context) {
        val user = ctx.user()
        val all = ProjectRepo.views(if (user.isAdmin) null else user.id)

        val toolbar = if (!user.isAdmin) "" else
            """<div class="d-flex mb-4"><button class="btn btn-primary btn-sm" type="button" data-bs-toggle="modal" data-bs-target="#newProject"><i class="bi bi-plus-lg me-1"></i>新建项目</button></div>"""

        val grid = when {
            all.isNotEmpty() -> """<div class="row row-cols-1 row-cols-md-2 g-3">${all.joinToString("") { projectCard(it) }}</div>"""
            user.isAdmin -> emptyState("还没有项目，点上面的「新建项目」开始", "bi-folder2-open")
            else -> emptyState("你还没有被分配到任何项目，请联系开发者", "bi-folder2-open")
        }

        val main = """
$toolbar
$grid
${if (user.isAdmin) newProjectModal(ctx) else ""}
"""
        ctx.html(page(ctx, "项目", "projects", main))
    }

    private fun countItem(status: ReqStatus, count: Long): String =
        """<span class="repo-count"><span class="repo-dot st-${status.code}" aria-hidden="true"></span>$count ${e(status.label)}</span>"""

    private fun projectCard(pv: ProjectView): String {
        val p = pv.project
        val meta = buildString {
            if (pv.todoCount + pv.testingCount + pv.archivedCount == 0L) {
                append("""<span>暂无需求</span>""")
            } else {
                if (pv.todoCount > 0) append(countItem(ReqStatus.TODO, pv.todoCount))
                if (pv.testingCount > 0) append(countItem(ReqStatus.TESTING, pv.testingCount))
                if (pv.archivedCount > 0) append(countItem(ReqStatus.ARCHIVED, pv.archivedCount))
            }
            append("""<span>${timeTag(pv.lastActivity, "更新于 ")}</span>""")
        }
        return """
<div class="col">
  <div class="repo-card h-100${if (p.isArchived) " is-archived" else ""}">
    <div class="d-flex align-items-center gap-2 min-w-0">
      <i class="bi ${if (p.isArchived) "bi-archive" else "bi-journal-bookmark"} repo-icon" aria-hidden="true"></i>
      <a class="repo-name stretched-link text-truncate" href="/projects/${p.id}">${e(p.name)}</a>
      ${statusChip(p, " ms-auto")}
    </div>
    <div class="repo-meta">$meta</div>
  </div>
</div>"""
    }

    private fun newProjectModal(ctx: Context): String = modalForm(
        ctx, "newProject", "新建项目", "/projects",
        field("项目名称", "name", required = true, id = "np_name", extra = """maxlength="100" autocomplete="off""""),
        "创建项目"
    )

    private fun header(ctx: Context, user: User, project: Project): String {
        val base = "/projects/${project.id}"
        val archived = project.isArchived

        val buttons = buildString {
            if (!archived) append("""<a class="btn btn-primary btn-sm" href="/requirements/new?project=${project.id}"><i class="bi bi-plus-lg me-1"></i>新建需求</a>""")
            if (user.isAdmin && !archived) append("""<button class="btn btn-outline-secondary btn-sm" type="button" data-bs-toggle="modal" data-bs-target="#editProject"><i class="bi bi-pencil me-1"></i>编辑项目</button>""")
            if (user.isAdmin && archived) append("""<form method="post" action="$base/unarchive" class="m-0">${csrfInput(ctx)}<button class="btn btn-outline-secondary btn-sm" type="submit"><i class="bi bi-box-arrow-up me-1"></i>取消归档</button></form>""")
        }
        val actions = if (buttons.isEmpty()) "" else """<div class="d-flex flex-wrap gap-2 mt-3">$buttons</div>"""

        val banner = if (!archived) "" else
            """<div class="alert alert-warning notice-bar mb-4" role="status"><span class="fw-bold">项目已归档，不能再新建或修改需求</span></div>"""

        val editModal = if (!user.isAdmin || archived) "" else modalForm(
            ctx, "editProject", "编辑项目", base,
            field("项目名称", "name", project.name, required = true, id = "ep_name", extra = """maxlength="100" autocomplete="off"""") +
                selectField("状态", "status", ProjectStatus.entries.map { it.code to it.label }, project.status, hint = "归档后项目和里面的需求都变成只读", id = "ep_status"),
            "保存"
        )

        return """
$banner
<div class="mb-4">
  <div class="d-flex align-items-center gap-2 mb-2">
    <h3 class="mb-0 text-break">${e(project.name)}</h3>
    ${statusChip(project)}
  </div>
  <div class="small text-secondary">${createdTag(project.createdAt)}</div>
  $actions
</div>
$editModal"""
    }

    fun detail(ctx: Context) {
        val user = ctx.user()
        val project = Access.project(user, ctx.idParam())
        val base = "/projects/${project.id}"
        val q = ListParams.parse(ctx, user, project.id)
        val spec = ListParams.spec(base, "", q, ReqTabs.emptyText(q.tab))
        val main = header(ctx, user, project) + listSection(spec)
        ctx.html(page(ctx, project.name, "projects", main, back = "/projects" to "项目"))
    }

    fun create(ctx: Context) {
        val admin = ctx.requireAdmin()
        val name = ctx.formParam("name")?.trim().orEmpty()
        if (name.isEmpty()) {
            ctx.flashErr("项目名称不能为空")
            ctx.go("/projects")
            return
        }
        val id = ProjectRepo.create(name.take(100), admin.id)
        ctx.flashOk("项目已创建，到底部「账号」里给客户分配这个项目")
        ctx.go("/projects/$id")
    }

    fun update(ctx: Context) {
        val admin = ctx.requireAdmin()
        val id = ctx.idParam()
        val project = ProjectRepo.byId(id) ?: throw NotFoundResponse("项目不存在")
        val name = ctx.formParam("name")?.trim().orEmpty()
        when {
            project.isArchived -> ctx.flashErr("项目已归档，先取消归档再修改")
            name.isEmpty() -> ctx.flashErr("项目名称不能为空")
            else -> {
                ProjectRepo.update(id, name.take(100), ProjectStatus.of(ctx.formParam("status")).code, admin.id)
                ctx.flashOk("项目已保存")
            }
        }
        ctx.go("/projects/$id")
    }

    fun unarchive(ctx: Context) {
        val admin = ctx.requireAdmin()
        val id = ctx.idParam()
        val project = ProjectRepo.byId(id) ?: throw NotFoundResponse("项目不存在")
        if (project.isArchived) {
            ProjectRepo.setStatus(id, ProjectStatus.ACTIVE, admin.id)
            ctx.flashOk("已取消归档，项目回到「进行中」")
        }
        ctx.go("/projects/$id")
    }
}
