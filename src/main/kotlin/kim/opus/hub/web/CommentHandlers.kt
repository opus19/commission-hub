package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object CommentHandlers {

    private const val RECENT_MSG = 5

    fun section(ctx: Context, user: User, view: RequirementView, comments: List<Comment>): String {
        val r = view.requirement
        val folded = CommentRepo.foldedIds(user.id, r.id)
        val visible = comments.filter { it.body.isNotBlank() || it.attachments.isNotEmpty() }
        val hiddenCount = if (visible.size > RECENT_MSG + 1) visible.size - RECENT_MSG else 0
        val older = visible.take(hiddenCount)
        val recent = visible.drop(hiddenCount)
        val thread = if (visible.isEmpty()) """<p class="thread-empty">还没有补充信息</p>""" else buildString {
            if (older.isNotEmpty()) {
                append("""<details class="thread-more"><summary><span class="thread-more-closed">展开更早的 ${older.size} 条</span><span class="thread-more-open">收起更早的</span></summary><div class="thread-list">""")
                older.forEach { append(commentItem(ctx, user, view, it, it.id in folded)) }
                append("</div></details>")
            }
            append("""<div class="thread-list">""")
            recent.forEach { append(commentItem(ctx, user, view, it, it.id in folded)) }
            append("</div>")
        }
        val tail = if (view.readOnly) "" else composer(ctx, r)
        return """
<section class="thread mt-5" id="conversation" aria-labelledby="conversationTitle" data-csrf="${e(ctx.session().csrf)}">
  <h5 class="thread-title" id="conversationTitle">补充信息<span class="text-secondary fw-normal fs-6 ms-2">${visible.size} 条</span></h5>
  $thread
</section>
$tail"""
    }

    private fun commentItem(ctx: Context, user: User, view: RequirementView, cm: Comment, folded: Boolean): String {
        val canDelete = user.isAdmin && !view.readOnly
        val canEdit = cm.userId == user.id && !view.readOnly
        val (pictures, others) = cm.attachments.partition { imageType(it.originalName) != null }
        val gallery = if (pictures.isEmpty()) "" else
            """<div class="tl-images">${pictures.joinToString("") { imageItem(ctx, canDelete, it) }}</div>"""
        val files = if (others.isEmpty()) "" else
            """<div class="tl-files">${others.joinToString("") { fileChip(ctx, canDelete, it) }}</div>"""
        val foldText = if (folded) "展开" else "收起"
        val fold = """<button class="tl-tool tl-fold" type="button" data-comment-fold="/comments/${cm.id}/fold" aria-expanded="${!folded}" title="$foldText" aria-label="${foldText}这条补充信息"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>"""
        val deleteNote = if (cm.attachments.isEmpty()) "删除这条补充信息？" else "删除这条补充信息？里面的 ${cm.attachments.size} 个附件也会一起删掉"
        val tools = if (!canEdit) "" else """
    <span class="tl-tools">
      <button class="tl-tool" type="button" data-comment-edit title="编辑" aria-label="编辑这条补充信息"><i class="bi bi-pencil" aria-hidden="true"></i></button>
      <form method="post" action="/comments/${cm.id}/delete" class="m-0 d-inline-flex" data-confirm="${e(deleteNote)}">${csrfInput(ctx)}<button class="tl-tool tl-tool-danger" type="submit" title="删除" aria-label="删除这条补充信息"><i class="bi bi-trash" aria-hidden="true"></i></button></form>
    </span>"""
        val rows = (cm.body.count { it == '\n' } + 2).coerceIn(3, 12)
        val form = if (!canEdit) "" else """
  <form class="tl-edit-form" method="post" action="/comments/${cm.id}" hidden>
    ${csrfInput(ctx)}
    <label class="visually-hidden" for="ce_${cm.id}">编辑补充信息</label>
    <textarea class="form-control composer-input" id="ce_${cm.id}" name="body" rows="$rows" maxlength="20000"${if (cm.attachments.isEmpty()) " required" else ""}>${e(cm.body)}</textarea>
    <div class="tl-edit-bar">
      <button class="btn btn-link link-secondary btn-sm" type="button" data-comment-cancel>取消</button>
      <button class="btn btn-primary btn-sm" type="submit">保存</button>
    </div>
  </form>"""
        return """
<article class="tl-comment${if (folded) " is-folded" else ""}" id="c${cm.id}">
  <header class="tl-comment-head">
    $fold
    <span class="fw-medium text-body">${e(cm.authorName)}</span>
    <span class="text-secondary">${timeTag(cm.createdAt)}</span>
    $tools
  </header>
  ${if (cm.body.isNotBlank()) """<div class="tl-comment-body fmt last-p text-break">${Markdown.render(cm.body)}</div>""" else ""}
  $form
  $gallery
  $files
</article>"""
    }

    fun imageItem(ctx: Context, canDelete: Boolean, f: Attachment): String {
        val name = e(f.originalName)
        return """<figure class="tl-image"><a class="tl-image-link" href="/attachments/${f.id}/image" target="_blank" rel="noopener" title="查看原图"><img src="/attachments/${f.id}/image" alt="$name" loading="lazy"></a><figcaption class="tl-image-cap">${fileChip(ctx, canDelete, f)}</figcaption></figure>"""
    }

    fun fileChip(ctx: Context, canDelete: Boolean, f: Attachment): String {
        val name = e(f.originalName)
        val del = if (!canDelete) "" else """<form method="post" action="/attachments/${f.id}/delete" class="file-chip-form" data-confirm="删除附件「$name」？">${csrfInput(ctx)}<button class="file-chip-del" type="submit" title="删除" aria-label="删除附件：$name"><i class="bi bi-x-lg"></i></button></form>"""
        return """<span class="file-chip"><a class="file-chip-link" href="/attachments/${f.id}" title="$name"><i class="bi ${fileIcon(f.originalName)}"></i><span class="file-chip-name">$name</span><span class="file-chip-size">${e(formatSize(f.sizeBytes))}</span></a>$del</span>"""
    }

    private fun composer(ctx: Context, r: Requirement): String = """
<section class="reply" id="reply" aria-label="添加补充信息">
  <form class="composer" id="composer" method="post" action="/requirements/${r.id}/comments" enctype="multipart/form-data" data-composer>
    ${csrfInput(ctx)}
    <label class="visually-hidden" for="c_body">补充信息内容</label>
    <textarea class="form-control composer-input" id="c_body" name="body" rows="4" maxlength="20000"></textarea>
    <div class="composer-files" id="c_file_names" hidden></div>
    <div class="composer-error" id="c_error" role="alert" hidden>写点什么，或者至少选一个附件</div>
    <div class="composer-bar">
      <input class="composer-file-input" id="c_files" type="file" name="files" multiple data-file-list="#c_file_names">
      <label class="composer-attach" for="c_files"><i class="bi bi-paperclip me-1"></i>添加附件</label>
      <button class="btn btn-primary btn-sm ms-auto" type="submit"><i class="bi bi-send me-1"></i>发送</button>
    </div>
  </form>
</section>"""

    fun create(ctx: Context) {
        val user = ctx.user()
        val view = Access.requirement(user, ctx.idParam())
        Access.writable(view)
        val rid = view.requirement.id
        val body = ctx.formParam("body")?.replace("\r\n", "\n")?.trim()?.take(20000).orEmpty()
        val uploads = ctx.uploadedFiles("files").filter { it.size() > 0 }
        if (body.isEmpty() && uploads.isEmpty()) {
            ctx.flashErr("写点什么，或者至少选一个附件")
            ctx.go("/requirements/$rid")
            return
        }
        val stored = Uploads.storeAll(uploads)
        val commentId = try {
            CommentRepo.create(rid, user.id, body, stored)
        } catch (ex: Exception) {
            stored.forEach { Uploads.deleteQuietly(it.storedName) }
            throw ex
        }
        ctx.go("/requirements/$rid#c$commentId")
    }

    fun update(ctx: Context) {
        val user = ctx.user()
        val (cm, view) = Access.comment(user, ctx.idParam())
        Access.writable(view)
        if (cm.userId != user.id) throw ForbiddenResponse("只能编辑自己发的补充信息")
        val rid = view.requirement.id
        val body = ctx.formParam("body")?.replace("\r\n", "\n")?.trim()?.take(20000).orEmpty()
        if (body.isEmpty() && cm.attachments.isEmpty()) {
            ctx.flashErr("内容不能为空")
            ctx.go("/requirements/$rid#c${cm.id}")
            return
        }
        if (body != cm.body) CommentRepo.update(cm.id, rid, body, cm.body, user.id)
        ctx.go("/requirements/$rid#c${cm.id}")
    }

    fun delete(ctx: Context) {
        val user = ctx.user()
        val (cm, view) = Access.comment(user, ctx.idParam())
        Access.writable(view)
        if (cm.userId != user.id) throw ForbiddenResponse("只能删除自己发的补充信息")
        CommentRepo.delete(cm, user.id).forEach { Uploads.deleteQuietly(it) }
        ctx.flashOk("补充信息已删除")
        ctx.go("/requirements/${view.requirement.id}")
    }

    fun fold(ctx: Context) {
        val user = ctx.user()
        val (cm, _) = Access.comment(user, ctx.idParam())
        CommentRepo.setFolded(user.id, cm.id, ctx.formParam("folded") == "1")
        ctx.status(204)
    }

    fun image(ctx: Context) {
        val (file, _) = Access.attachment(ctx.user(), ctx.idParam())
        val type = imageType(file.originalName) ?: throw NotFoundResponse("这个附件不是图片")
        Uploads.sendImage(ctx, file.storedName, file.originalName, type)
    }

    fun download(ctx: Context) {
        val user = ctx.user()
        val (file, _) = Access.attachment(user, ctx.idParam())
        Uploads.send(ctx, file.storedName, file.originalName)
    }

    fun deleteAttachment(ctx: Context) {
        val admin = ctx.requireAdmin()
        val (file, view) = Access.attachment(admin, ctx.idParam())
        Access.writable(view)
        AttachmentRepo.delete(file.id, admin.id)
        Uploads.deleteQuietly(file.storedName)
        ctx.flashOk("附件已删除")
        ctx.go("/requirements/${view.requirement.id}#conversation")
    }
}
