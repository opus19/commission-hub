package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object ProjectHandlers {

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
  <div class="repo-card h-100">
    <div class="d-flex align-items-center gap-2 min-w-0">
      <i class="bi bi-journal-bookmark repo-icon" aria-hidden="true"></i>
      <a class="repo-name stretched-link text-truncate" href="/projects/${p.id}">${e(p.name)}</a>
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

    private fun editProjectModal(ctx: Context, project: Project): String = modalForm(
        ctx, "editProject", "编辑项目", "/projects/${project.id}",
        field("项目名称", "name", project.name, required = true, id = "ep_name", extra = """maxlength="100" autocomplete="off"""") + """
<div class="danger-zone mt-4">
  <div class="min-w-0">
    <div class="danger-zone-title">删除项目</div>
    <div class="danger-zone-text">项目里的需求、补充信息、附件和版本会一起永久删除</div>
  </div>
  <button class="btn btn-sm btn-outline-danger flex-shrink-0" type="button" data-bs-toggle="modal" data-bs-target="#deleteProject">删除项目</button>
</div>""",
        "保存"
    )

    private fun deleteProjectModal(ctx: Context, project: Project): String {
        val name = e(project.name)
        return """
<div class="modal fade" id="deleteProject" tabindex="-1" aria-labelledby="deleteProjectLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <form class="modal-content" method="post" action="/projects/${project.id}/delete">
      ${csrfInput(ctx)}
      <div class="modal-header">
        <h5 class="modal-title" id="deleteProjectLabel">删除项目</h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>
      </div>
      <div class="modal-body">
        <div class="alert alert-danger notice-bar mb-3" role="alert"><span class="fw-bold">删除后无法恢复</span></div>
        <p class="small mb-3">项目 <strong class="text-break">$name</strong> 里的所有需求，以及它们的补充信息、附件和上传的版本都会被永久删除，客户也看不到这个项目了</p>
        <label class="form-label" for="dp_name">输入项目名称 <strong class="text-break">$name</strong> 确认删除</label>
        <input class="form-control" id="dp_name" type="text" name="confirm_name" autocomplete="off" spellcheck="false" data-confirm-name="$name">
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link link-secondary" data-bs-dismiss="modal">取消</button>
        <button type="submit" class="btn btn-danger" disabled>删除这个项目</button>
      </div>
    </form>
  </div>
</div>"""
    }

    private fun header(ctx: Context, user: User, project: Project): String {
        val buttons = buildString {
            append("""<a class="btn btn-primary btn-sm" href="/requirements/new?project=${project.id}"><i class="bi bi-plus-lg me-1"></i>新建需求</a>""")
            if (user.isAdmin) append("""<button class="btn btn-outline-secondary btn-sm" type="button" data-bs-toggle="modal" data-bs-target="#editProject"><i class="bi bi-pencil me-1"></i>编辑项目</button>""")
        }
        val modals = if (user.isAdmin) editProjectModal(ctx, project) + deleteProjectModal(ctx, project) else ""

        return """
<div class="mb-4">
  <h3 class="mb-2 text-break">${e(project.name)}</h3>
  <div class="small text-secondary">${createdTag(project.createdAt)}</div>
  <div class="d-flex flex-wrap gap-2 mt-3">$buttons</div>
</div>
$modals"""
    }

    fun detail(ctx: Context) {
        val user = ctx.user()
        val project = Access.project(user, ctx.idParam())
        val base = "/projects/${project.id}"
        val spec = ListParams.spec(base, "", ListParams.parse(ctx, user, project.id))
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
        val project = ProjectRepo.byId(ctx.idParam()) ?: throw NotFoundResponse("项目不存在")
        val name = ctx.formParam("name")?.trim().orEmpty()
        if (name.isEmpty()) {
            ctx.flashErr("项目名称不能为空")
        } else {
            ProjectRepo.update(project.id, name.take(100), admin.id)
            ctx.flashOk("项目已保存")
        }
        ctx.go("/projects/${project.id}")
    }

    fun delete(ctx: Context) {
        val admin = ctx.requireAdmin()
        val project = ProjectRepo.byId(ctx.idParam()) ?: throw NotFoundResponse("项目不存在")
        if (ctx.formParam("confirm_name")?.trim() != project.name.trim()) {
            ctx.flashErr("输入的项目名称不对，没有删除")
            ctx.go("/projects/${project.id}")
            return
        }
        ProjectRepo.delete(project, admin.id).forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk("项目「${project.name}」已删除")
        ctx.go("/projects")
    }
}
