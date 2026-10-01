package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object BuildHandlers {

    private fun fileRow(f: BuildFile, open: Boolean, ask: String?): String {
        val name = e(f.originalName)
        val inner = """<i class="bi ${fileIcon(f.originalName)} dl-file-icon" aria-hidden="true"></i><span class="dl-file-name">$name</span><span class="dl-file-size">${e(formatSize(f.sizeBytes))}</span>"""
        if (f.purgedAt != null) return """<li><span class="dl-file is-gone" title="$name（已清理）">$inner</span></li>"""
        if (!open) return """<li><span class="dl-file is-off" title="$name">$inner</span></li>"""
        val confirm = if (ask == null) "" else """ data-confirm="${e(ask)}""""
        return """<li><a class="dl-file" href="/downloads/${f.id}" title="下载 $name"$confirm>$inner</a></li>"""
    }

    private fun removeForm(ctx: Context, r: Requirement, b: Build): String {
        val only = b.requirementIds.size <= 1
        val ask = if (only) "删除 #${b.seq}？文件会一起删掉" else "从这个需求移除 #${b.seq}？"
        val label = if (only) "删除 #${b.seq}" else "从这个需求移除 #${b.seq}"
        return """<form method="post" action="/requirements/${r.id}/builds/${b.id}/remove" class="dl-del-form" data-confirm="${e(ask)}">${csrfInput(ctx)}<button class="dl-del" type="submit" title="$label" aria-label="$label"><i class="bi bi-trash" aria-hidden="true"></i></button></form>"""
    }

    private fun block(head: String, files: String): String = """
<div class="dl-version">
  <div class="dl-head">$head</div>
  <ul class="dl-files">$files</ul>
</div>"""

    private fun stamp(iso: String): String =
        """<time class="dl-time dl-fill" datetime="${e(iso)}" title="${e(formatStamp(iso))}">${e(formatWhen(iso))}</time>"""

    private fun num(b: Build): String = """<span class="dl-num">#${b.seq}</span>"""

    private fun older(list: List<Build>, render: (Build) -> String): String {
        if (list.isEmpty()) return ""
        return """<details class="dl-older"><summary><i class="bi bi-chevron-right dl-older-icon" aria-hidden="true"></i>历史构建<span class="count-muted">${list.size}</span></summary>""" +
            list.joinToString("") { render(it) } + "</details>"
    }

    private fun openBody(ctx: Context, r: Requirement, mine: List<Build>, top: Build, canManage: Boolean): String {
        val del = if (canManage && mine.any { it.id == top.id }) removeForm(ctx, r, top) else ""
        val head = """${num(top)}<span class="dl-latest">最新</span><span class="dl-time dl-fill">${timeTag(top.createdAt)}</span>$del"""
        val history = older(mine.filter { it.id != top.id }) { b ->
            val bDel = if (canManage) removeForm(ctx, r, b) else ""
            val ask = "#${b.seq} 比最新的 #${top.seq} 旧，确定下载？"
            block(num(b) + stamp(b.createdAt) + bDel, b.files.joinToString("") { fileRow(it, true, ask) })
        }
        return block(head, top.files.joinToString("") { fileRow(it, true, null) }) + history
    }

    private fun closedBody(mine: List<Build>): String {
        val first = mine.first()
        return block(num(first) + stamp(first.createdAt), first.files.joinToString("") { fileRow(it, false, null) }) +
            older(mine.drop(1)) { b -> block(num(b) + stamp(b.createdAt), b.files.joinToString("") { fileRow(it, false, null) }) }
    }

    private fun uploadModal(ctx: Context, r: Requirement, siblings: List<Requirement>): String {
        val also = if (siblings.isEmpty()) "" else """
<fieldset class="mt-3">
  <legend class="form-label mb-2">同时修了</legend>
  <div class="build-also">${siblings.joinToString("") { s ->
            """<div class="form-check"><input class="form-check-input" type="checkbox" name="also" value="${s.id}" id="ub_also_${s.id}"><label class="form-check-label" for="ub_also_${s.id}">${e(s.title)}<span class="build-also-state">${e(s.statusEnum.label)}</span></label></div>"""
        }}</div>
</fieldset>"""
        val todo = r.statusEnum == ReqStatus.TODO || siblings.any { it.statusEnum == ReqStatus.TODO }
        val mark = if (!todo) "" else """
<div class="form-check mt-3">
  <input class="form-check-input" type="checkbox" name="mark_testing" value="1" id="ub_mark" checked>
  <label class="form-check-label" for="ub_mark">上传后改成「待测试」</label>
</div>"""
        val body = """
<label class="visually-hidden" for="ub_files">文件</label>
<input class="form-control" id="ub_files" type="file" name="files" multiple required>
$also$mark"""
        return modalForm(ctx, "uploadBuild", "上传构建 #${BuildRepo.nextSeq(r.projectId)}", "/requirements/${r.id}/builds", body, "上传", multipart = true)
    }

    fun card(ctx: Context, user: User, view: RequirementView, mine: List<Build>, top: Build?, siblings: List<Requirement>): String {
        val r = view.requirement
        val canManage = user.isAdmin && !view.readOnly
        if (mine.isEmpty() && !canManage) return ""
        val action = if (!canManage) "" else
            """<button class="dl-add" type="button" data-island-modal="#uploadBuild"><i class="bi bi-upload" aria-hidden="true"></i>上传构建</button>"""
        val body = when {
            mine.isEmpty() -> """<p class="dl-empty">还没有构建</p>"""
            view.readOnly -> closedBody(mine)
            else -> openBody(ctx, r, mine, top ?: mine.first(), canManage)
        }
        return """<div class="dl-card${if (view.readOnly) " is-closed" else ""}" id="downloads">""" + sideCard("下载", body, flush = true, action = action) + "</div>" +
            if (canManage) uploadModal(ctx, r, siblings) else ""
    }

    fun upload(ctx: Context) {
        val admin = ctx.requireAdmin()
        val view = Access.requirement(admin, ctx.idParam())
        Access.writable(view)
        val r = view.requirement
        val uploads = ctx.uploadedFiles("files").filter { it.size() > 0 }
        if (uploads.isEmpty()) {
            ctx.flashErr("至少选一个文件")
            ctx.go("/requirements/${r.id}")
            return
        }
        val also = ctx.formParams("also").mapNotNull { it.trim().toLongOrNull() }
        val stored = Uploads.storeAll(uploads)
        val made = try {
            BuildRepo.create(r.projectId, r.id, also, stored, ctx.formParam("mark_testing") == "1", admin.id)
        } catch (ex: Exception) {
            stored.forEach { Uploads.deleteQuietly(it.storedName) }
            throw ex
        }
        made.purge.stored.forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk(buildString {
            append("已上传 #${made.seq}")
            if (made.linked > 1) append("，共 ${made.linked} 条需求")
            if (made.moved > 0) append(if (made.linked > 1) "，${made.moved} 条改成了待测试" else "，改成了待测试")
        })
        ctx.go("/requirements/${r.id}")
    }

    fun remove(ctx: Context) {
        val admin = ctx.requireAdmin()
        val view = Access.requirement(admin, ctx.idParam())
        Access.writable(view)
        val build = Access.build(admin, ctx.idParam("build"))
        val rid = view.requirement.id
        if (rid !in build.requirementIds) throw NotFoundResponse("构建不存在")
        val done = BuildRepo.remove(build, rid, admin.id)
        done.stored.forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk(if (done.deleted) "已删除 #${build.seq}" else "已移除 #${build.seq}")
        ctx.go("/requirements/$rid")
    }

    fun download(ctx: Context) {
        val user = ctx.user()
        val (file, _) = Access.buildFile(user, ctx.idParam())
        if (file.purgedAt != null) throw NotFoundResponse("文件已清理")
        Uploads.send(ctx, file.storedName, file.originalName)
    }
}
