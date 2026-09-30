package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object VersionHandlers {

    private fun fileRow(f: VersionFile, closed: Boolean): String {
        val name = e(f.originalName)
        val inner = """<i class="bi ${fileIcon(f.originalName)} dl-file-icon" aria-hidden="true"></i><span class="dl-file-name">$name</span><span class="dl-file-size">${e(formatSize(f.sizeBytes))}</span>"""
        if (closed || f.purgedAt != null) return """<li><span class="dl-file is-gone" title="$name（已清理）">$inner</span></li>"""
        return """<li><a class="dl-file" href="/downloads/${f.id}" title="下载 $name">$inner</a></li>"""
    }

    private fun deleteForm(ctx: Context, v: ReqVersion, latest: Boolean): String {
        val what = if (latest) "最新版本" else formatWhen(v.createdAt) + " 上传的版本"
        return """<form method="post" action="/versions/${v.id}/delete" class="dl-del-form${if (latest) "" else " dl-fill"}" data-confirm="删除${e(what)}？里面的文件也会一起删掉">${csrfInput(ctx)}<button class="dl-del" type="submit" title="删除这个版本" aria-label="删除${e(what)}"><i class="bi bi-trash" aria-hidden="true"></i></button></form>"""
    }

    private fun block(ctx: Context, v: ReqVersion, latest: Boolean, canManage: Boolean, closed: Boolean): String {
        val del = if (canManage) deleteForm(ctx, v, latest) else ""
        val head = if (latest)
            """<span class="dl-latest">最新版本</span><span class="dl-time dl-fill">${timeTag(v.createdAt)}</span>$del"""
        else
            """<time class="dl-when" datetime="${e(v.createdAt)}" title="${e(formatStamp(v.createdAt))}">${e(formatWhen(v.createdAt))}</time>$del"""
        return """
<div class="dl-version">
  <div class="dl-head">$head</div>
  <ul class="dl-files">${v.files.joinToString("") { fileRow(it, closed) }}</ul>
</div>"""
    }

    private fun uploadModal(ctx: Context, r: Requirement): String {
        val mark = if (r.statusEnum != ReqStatus.TODO) "" else """
<div class="form-check mt-3">
  <input class="form-check-input" type="checkbox" name="mark_testing" value="1" id="uv_mark" checked>
  <label class="form-check-label" for="uv_mark">上传后改成「待测试」让客户验收</label>
</div>"""
        val body = """
<label class="visually-hidden" for="uv_files">文件</label>
<input class="form-control" id="uv_files" type="file" name="files" multiple required>
$mark"""
        return modalForm(ctx, "uploadVersion", "上传新版本", "/requirements/${r.id}/versions", body, "上传", multipart = true)
    }

    fun card(ctx: Context, user: User, view: RequirementView, versions: List<ReqVersion>): String {
        val canManage = user.isAdmin && !view.readOnly
        if (versions.isEmpty() && !canManage) return ""
        val closed = view.readOnly
        val action = if (!canManage) "" else
            """<button class="dl-add" type="button" data-island-modal="#uploadVersion"><i class="bi bi-upload" aria-hidden="true"></i>上传新版本</button>"""
        val body = if (versions.isEmpty()) """<p class="dl-empty">还没有上传版本</p>""" else buildString {
            append(block(ctx, versions[0], true, canManage, closed))
            val older = versions.drop(1)
            if (older.isNotEmpty()) {
                append("""<details class="dl-older"><summary><i class="bi bi-chevron-right dl-older-icon" aria-hidden="true"></i>历史版本<span class="count-muted">${older.size}</span></summary>""")
                older.forEach { append(block(ctx, it, false, canManage, closed)) }
                append("</details>")
            }
        }
        return """<div class="dl-card${if (closed) " is-closed" else ""}" id="downloads">""" + sideCard("下载", body, flush = true, action = action) + "</div>" +
            if (canManage) uploadModal(ctx, view.requirement) else ""
    }

    fun upload(ctx: Context) {
        val admin = ctx.requireAdmin()
        val view = Access.requirement(admin, ctx.idParam())
        Access.writable(view)
        val rid = view.requirement.id
        val uploads = ctx.uploadedFiles("files").filter { it.size() > 0 }
        if (uploads.isEmpty()) {
            ctx.flashErr("至少选一个文件")
            ctx.go("/requirements/$rid")
            return
        }
        val stored = Uploads.storeAll(uploads)
        val moved = try {
            VersionRepo.create(rid, stored, ctx.formParam("mark_testing") == "1", admin.id)
        } catch (ex: Exception) {
            stored.forEach { Uploads.deleteQuietly(it.storedName) }
            throw ex
        }
        ctx.flashOk(if (moved) "新版本已上传，需求改成了待测试" else "新版本已上传")
        ctx.go("/requirements/$rid")
    }

    fun delete(ctx: Context) {
        val admin = ctx.requireAdmin()
        val (version, view) = Access.version(admin, ctx.idParam())
        Access.writable(view)
        VersionRepo.delete(version, admin.id).forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk("版本已删除")
        ctx.go("/requirements/${view.requirement.id}")
    }

    fun download(ctx: Context) {
        val (file, view) = Access.versionFile(ctx.user(), ctx.idParam())
        if (file.purgedAt != null || view.readOnly) throw NotFoundResponse("需求归档后下载文件已清理，不能再下载")
        Uploads.send(ctx, file.storedName, file.originalName)
    }
}
