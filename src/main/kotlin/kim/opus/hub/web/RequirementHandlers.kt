package kim.opus.hub.web

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object RequirementHandlers {

    private val steps = listOf(ReqStatus.TODO to "待开发", ReqStatus.TESTING to "待测试")

    private class ReqForm(
        val title: String,
        val priority: Int,
        val wanted: String,
        val items: List<ItemHandlers.Row>,
        val removed: Set<Long>
    ) {
        val hasFiles: Boolean get() = items.any { it.files.isNotEmpty() }
    }

    private fun readForm(ctx: Context): ReqForm = ReqForm(
        title = ctx.formParam("title")?.trim()?.take(200).orEmpty(),
        priority = Priority.of(ctx.formParam("priority")?.toIntOrNull() ?: Priority.NORMAL.level).level,
        wanted = ctx.formParam("wanted_at")?.trim()?.take(40).orEmpty(),
        items = ItemHandlers.readForm(ctx),
        removed = ctx.formParams("att_remove").mapNotNull { it.trim().toLongOrNull() }.toSet()
    )

    private fun blankForm(): ReqForm = ReqForm("", Priority.NORMAL.level, "", emptyList(), emptySet())

    private fun formOf(r: Requirement): ReqForm = ReqForm(
        r.title,
        r.priority,
        Wanted.full(r.wantedAt).orEmpty(),
        ItemRepo.forRequirement(r.id).map { ItemHandlers.Row(it.id, it.body, emptyList()) },
        emptySet()
    )

    private fun check(f: ReqForm, saved: List<Attachment>): Pair<String?, Map<String, String>> {
        val errors = LinkedHashMap<String, String>()
        if (f.title.isEmpty()) errors["title"] = "标题不能为空"
        val (wanted, wantedErr) = Wanted.readInput(f.wanted)
        if (wantedErr != null) errors["wanted_at"] = wantedErr
        val withFiles = saved.filter { it.id !in f.removed }.mapNotNull { it.itemId }.toSet()
        if (f.items.any { it.body.isEmpty() && (it.files.isNotEmpty() || (it.id != null && it.id in withFiles)) }) {
            errors["items"] = ItemHandlers.NEED_TEXT
        }
        if (errors.isNotEmpty() && f.hasFiles) errors["files"] = "刚才选的附件还没上传，请重新选一次"
        return wanted to errors
    }

    private fun rowsToSave(f: ReqForm): List<ItemHandlers.Row> = f.items.filter { it.body.isNotEmpty() }.take(ItemHandlers.MAX_ITEMS)

    private fun store(f: ReqForm, rows: List<ItemHandlers.Row>): FileChanges {
        val done = ArrayList<NewFile>()
        try {
            val items = rows.map { row -> Uploads.storeAll(row.files).also { done.addAll(it) } }
            return FileChanges(items, f.removed)
        } catch (ex: Exception) {
            done.forEach { Uploads.deleteQuietly(it.storedName) }
            throw ex
        }
    }

    private fun readOnlyReason(view: RequirementView): String? =
        if (view.readOnly) "这条需求已归档，现在是只读的" else null

    private fun form(ctx: Context, action: String, submit: String, cancelHref: String, f: ReqForm, projectId: Long?, errors: Map<String, String>, saved: List<Attachment>): String {
        val projectField = if (projectId == null) "" else """<input type="hidden" name="project_id" value="$projectId">"""
        val live = saved.filter { it.id !in f.removed }
        val byItem = live.filter { it.itemId != null }.groupBy { it.itemId!! }
        val rows = f.items.mapIndexedNotNull { i, row ->
            val files = row.id?.let { byItem[it] }.orEmpty()
            if (row.body.isEmpty() && row.files.isEmpty() && files.isEmpty()) null
            else ItemHandlers.EditRow(row.id?.toString() ?: "r$i", row.body, files, row.body.isEmpty())
        }
        val removedFields = saved.filter { it.id in f.removed }.joinToString("") { """<input type="hidden" name="att_remove" value="${it.id}">""" }
        val first = errors.keys.firstOrNull()
        fun state(key: String, id: String): String = buildString {
            if (key in errors) append(""" aria-invalid="true" aria-describedby="${id}_err"""")
            if (key == first) append(" autofocus")
        }
        fun cls(key: String): String = if (key in errors) "form-control is-invalid" else "form-control"
        fun feedback(key: String, id: String): String =
            errors[key]?.let { """<div class="invalid-feedback" id="${id}_err">${e(it)}</div>""" } ?: ""
        return """
<form method="post" action="${e(action)}" enctype="multipart/form-data" novalidate>
  ${csrfInput(ctx)}
  $projectField
  $removedFields
  <div class="req-form-fields">
  <div class="mb-3">
    <label class="form-label" for="f_title">标题 <span class="text-danger">*</span></label>
    <input class="${cls("title")}" id="f_title" type="text" name="title" value="${e(f.title)}" placeholder="一句话说清要做什么" maxlength="200" autocomplete="off"${state("title", "f_title")}>
    ${feedback("title", "f_title")}
  </div>
  ${ItemHandlers.editor(rows, errors["items"], if (first == "items") rows.indexOfFirst { it.invalid } else -1, errors["files"])}
  <div class="row g-3">
    <div class="col-sm-6">${selectField("优先级", "priority", Priority.entries.map { it.level.toString() to it.label }, f.priority.toString())}</div>
    <div class="col-sm-6">
      <div class="mb-3">
        <label class="form-label" for="f_wanted_at">期望交付时间</label>
        <input class="${cls("wanted_at")}" id="f_wanted_at" type="text" name="wanted_at" value="${e(f.wanted)}" placeholder="${Wanted.PLACEHOLDER}" maxlength="40" autocomplete="off" spellcheck="false"${state("wanted_at", "f_wanted_at")}>
        ${feedback("wanted_at", "f_wanted_at")}
      </div>
    </div>
  </div>
  </div>
  <div class="req-form-actions d-flex align-items-center mt-2">
    <button class="btn btn-primary me-2" type="submit">${e(submit)}</button>
    <a class="btn btn-link link-secondary" href="${e(cancelHref)}" data-island-cancel>取消</a>
  </div>
</form>"""
    }

    private fun islandBody(title: String, form: String): String =
        """<h3 class="mb-4">${e(title)}</h3><div data-island-body data-island-title="${e(title)}">$form</div>"""

    private fun renderNew(ctx: Context, project: Project, f: ReqForm, errors: Map<String, String>) {
        val main = islandBody("新建需求", form(ctx, "/requirements", "创建需求", "/projects/${project.id}", f, project.id, errors, emptyList()))
        ctx.html(page(ctx, "新建需求", "projects", main, back = "/projects/${project.id}" to project.name))
    }

    private fun renderEdit(ctx: Context, r: Requirement, f: ReqForm, errors: Map<String, String>, saved: List<Attachment>) {
        val main = islandBody("编辑需求", form(ctx, "/requirements/${r.id}", "保存修改", "/requirements/${r.id}", f, null, errors, saved))
        ctx.html(page(ctx, "编辑需求", "projects", main, back = "/requirements/${r.id}" to r.title))
    }

    fun newPage(ctx: Context) {
        val user = ctx.user()
        val projectId = ctx.queryParam("project")?.toLongOrNull()
        if (projectId == null) {
            ctx.go("/projects")
            return
        }
        val project = Access.project(user, projectId)
        renderNew(ctx, project, blankForm(), emptyMap())
    }

    fun create(ctx: Context) {
        val user = ctx.user()
        val projectId = ctx.formParam("project_id")?.toLongOrNull() ?: throw NotFoundResponse("项目不存在")
        val project = Access.project(user, projectId)
        val f = readForm(ctx)
        val (wanted, errors) = check(f, emptyList())
        if (errors.isNotEmpty()) {
            ctx.status(400)
            renderNew(ctx, project, f, errors)
            return
        }
        val rows = rowsToSave(f)
        val files = store(f, rows)
        val id = try {
            ReqRepo.create(
                projectId = project.id,
                title = f.title,
                status = ReqStatus.TODO,
                priority = f.priority,
                wantedAt = wanted,
                items = rows.map { ItemInput(it.id, it.body) },
                files = files,
                actor = user.id
            )
        } catch (ex: Exception) {
            files.stored.forEach { Uploads.deleteQuietly(it.storedName) }
            throw ex
        }
        ctx.flashOk("需求已创建")
        ctx.go("/requirements/$id")
    }

    fun editPage(ctx: Context) {
        val user = ctx.user()
        val view = Access.requirement(user, ctx.idParam())
        val r = view.requirement
        val reason = readOnlyReason(view)
        if (reason != null) {
            ctx.flashErr(reason)
            ctx.go("/requirements/${r.id}")
            return
        }
        renderEdit(ctx, r, formOf(r), emptyMap(), AttachmentRepo.forItems(r.id))
    }

    fun update(ctx: Context) {
        val user = ctx.user()
        val view = Access.requirement(user, ctx.idParam())
        Access.writable(view)
        val r = view.requirement
        val f = readForm(ctx)
        val saved = AttachmentRepo.forItems(r.id)
        val (wanted, errors) = check(f, saved)
        if (errors.isNotEmpty()) {
            ctx.status(400)
            renderEdit(ctx, r, f, errors, saved)
            return
        }
        val rows = rowsToSave(f)
        val files = store(f, rows)
        val gone = try {
            ReqRepo.update(
                id = r.id,
                title = f.title,
                priority = f.priority,
                wantedAt = wanted,
                items = rows.map { ItemInput(it.id, it.body) },
                files = files,
                actor = user.id
            )
        } catch (ex: Exception) {
            files.stored.forEach { Uploads.deleteQuietly(it.storedName) }
            throw ex
        }
        gone.forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk("需求已保存")
        ctx.go("/requirements/${r.id}")
    }

    private fun acceptModal(ctx: Context, r: Requirement): String {
        val title = e(r.title)
        return """
<div class="modal hub-island" id="acceptReq" tabindex="-1" aria-labelledby="acceptReqLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <form class="modal-content" method="post" action="/requirements/${r.id}/status">
      ${csrfInput(ctx)}
      <input type="hidden" name="to" value="${ReqStatus.ARCHIVED.code}">
      <div class="modal-header">
        <h5 class="modal-title" id="acceptReqLabel">输入需求标题 <strong class="text-break">$title</strong> 确认验收</h5>
      </div>
      <div class="modal-body">
        <input class="form-control" id="ar_title" type="text" name="confirm_name" autocomplete="off" spellcheck="false" data-confirm-name="$title" aria-labelledby="acceptReqLabel">
      </div>
      <div class="modal-footer">
        <button type="submit" class="btn btn-success" disabled>验收通过</button>
      </div>
    </form>
  </div>
</div>"""
    }

    private fun clientActionBox(ctx: Context, r: Requirement, targets: List<ReqStatus>): String {
        if (ReqStatus.ARCHIVED !in targets) return ""
        return """
<div class="alert alert-warning notice-bar accept-bar mb-4" role="status">
  <span class="fw-bold">这条需求等你验收</span>
  <div class="accept-actions">
    <button class="btn btn-success btn-sm" type="button" data-island-modal="#acceptReq"><i class="bi bi-check-circle me-1"></i>验收通过</button>
  </div>
</div>""" + acceptModal(ctx, r)
    }

    private fun readOnlyBanner(view: RequirementView): String {
        if (!view.readOnly) return ""
        val r = view.requirement
        val text = when {
            r.closedAt != null -> "归档于 ${formatStamp(r.closedAt)}，不能再编辑或添加补充信息"
            else -> "已归档，不能再编辑或添加补充信息"
        }
        return """<div class="alert alert-warning notice-bar mb-4" role="status"><span class="fw-bold">${e(text)}</span></div>"""
    }

    fun detail(ctx: Context) {
        val user = ctx.user()
        val view = Access.requirement(user, ctx.idParam())
        val r = view.requirement
        val comments = CommentRepo.forRequirement(r.id)
        val builds = BuildRepo.forRequirement(r.id)
        val top = if (builds.isEmpty() || view.readOnly) null else BuildRepo.newestLive(r.projectId)
        val siblings = if (user.isAdmin && !view.readOnly) ReqRepo.openSiblings(r.projectId, r.id) else emptyList()
        val items = ItemRepo.forRequirement(r.id)
        val itemFiles = AttachmentRepo.forItems(r.id).filter { it.itemId != null }.groupBy { it.itemId!! }
        val targets = Transitions.allowed(user, view)

        val edit = if (view.readOnly) "" else
            """<a class="link-secondary req-edit" href="/requirements/${r.id}/edit" aria-label="编辑需求" data-island><i class="bi bi-pencil" aria-hidden="true"></i>编辑</a>"""

        val question = """
<div>
  ${readOnlyBanner(view)}
  <div class="d-flex align-items-baseline gap-3 border-bottom pb-3 mb-4">
    <h1 class="h3 mb-0 flex-grow-1 min-w-0 text-wrap text-break">${e(r.title)}</h1>
    $edit
  </div>
  ${if (!user.isAdmin) clientActionBox(ctx, r, targets) else ""}
  ${ItemHandlers.section(ctx, user, view, items, itemFiles)}
</div>"""

        val main = question + CommentHandlers.section(ctx, user, view, comments)

        val info = buildList {
            add("状态" to """<span data-live="status">${statusBadge(r.statusEnum)}</span>""")
            add("优先级" to priorityBadge(r.priorityEnum))
            add("期望交付" to wantedBadge(r, full = true).ifEmpty { """<span class="text-secondary">未填</span>""" })
            add("创建" to e(formatStamp(r.createdAt)))
            add("最后更新" to e(formatStamp(r.updatedAt)))
            if (r.statusEnum == ReqStatus.ARCHIVED && r.closedAt != null) add("归档时间" to e(formatStamp(r.closedAt)))
            if (r.statusEnum == ReqStatus.ARCHIVED && r.acceptedBuild != null) add("验收构建" to "#${r.acceptedBuild}")
        }

        val side = buildString {
            if (user.isAdmin) append(adminStatusCard(ctx, view))
            append(BuildHandlers.card(ctx, user, view, builds, top, siblings))
            append(sideCard("需求信息", infoList(info), flush = true))
        }

        ctx.html(page(ctx, r.title, "projects", main, side, back = "/projects/${r.projectId}" to view.projectName))
    }

    private fun adminStatusCard(ctx: Context, view: RequirementView): String {
        val r = view.requirement
        val current = r.statusEnum
        if (current == ReqStatus.ARCHIVED) return ""
        val buttons = steps.joinToString("") { (s, text) ->
            if (s == current) {
                """<span class="status-step-cell"><span class="status-step is-current" aria-current="step">${e(text)}</span></span>"""
            } else {
                """<form method="post" action="/requirements/${r.id}/status" class="status-step-cell">${csrfInput(ctx)}<input type="hidden" name="to" value="${s.code}"><button class="status-step" type="submit">${e(text)}</button></form>"""
            }
        }
        val body = """<div class="status-steps" role="group" aria-label="推进状态" data-seg="status">$buttons</div>"""
        return sideCard("推进状态", body)
    }

    fun changeStatus(ctx: Context) {
        val user = ctx.user()
        val view = Access.requirement(user, ctx.idParam())
        val current = view.requirement.statusEnum
        val code = ctx.formParam("to")
        val target = ReqStatus.entries.firstOrNull { it.code == code } ?: throw BadRequestResponse("状态不合法")
        Transitions.check(user, view, target)
        val r = view.requirement
        if (target == ReqStatus.ARCHIVED && ctx.formParam("confirm_name")?.trim() != r.title.trim()) {
            ctx.flashErr("输入的需求标题不对，没有验收")
            ctx.go("/requirements/${r.id}")
            return
        }
        val purge = ReqRepo.setStatus(r.id, current, target, user.id, null)
        purge.stored.forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk(if (target == ReqStatus.ARCHIVED) "验收通过，需求已归档" else "状态已改为「${target.label}」")
        ctx.go("/requirements/${r.id}")
    }
}
