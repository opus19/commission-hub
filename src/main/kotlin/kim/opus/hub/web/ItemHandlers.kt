package kim.opus.hub.web

import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.UploadedFile
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object ItemHandlers {

    const val MAX_ITEMS = 200
    const val MAX_LENGTH = 500
    const val NEED_TEXT = "有附件的清单项要写上内容"

    private val REF = Regex("[A-Za-z0-9]{1,24}")

    class Row(val id: Long?, val body: String, val files: List<UploadedFile>)

    class EditRow(val ref: String, val body: String, val saved: List<Attachment>, val invalid: Boolean)

    fun stageMark(view: RequirementView): ItemMark =
        if (view.requirement.statusEnum == ReqStatus.TESTING) ItemMark.TESTED else ItemMark.DONE

    private fun stageOpen(user: User, view: RequirementView, mark: ItemMark): Boolean = when {
        view.readOnly -> false
        mark == ItemMark.DONE -> user.isAdmin && view.requirement.statusEnum == ReqStatus.TODO
        else -> !user.isAdmin && view.requirement.statusEnum == ReqStatus.TESTING
    }

    private fun locked(mark: ItemMark, item: ReqItem): Boolean = mark == ItemMark.TESTED && !item.done

    fun canMark(user: User, view: RequirementView, mark: ItemMark, item: ReqItem): Boolean =
        stageOpen(user, view, mark) && !locked(mark, item)

    fun readForm(ctx: Context): List<Row> {
        val refs = ctx.formParams("item_ref")
        val used = HashSet<String>()
        return ctx.formParams("item_text").mapIndexedNotNull { i, raw ->
            val ref = refs.getOrNull(i)?.trim().orEmpty()
            val own = REF.matches(ref) && used.add(ref)
            val files = if (own) ctx.uploadedFiles("item_files_$ref").filter { it.size() > 0 } else emptyList()
            val body = raw.replace('\r', ' ').replace('\n', ' ').trim().take(MAX_LENGTH)
            val id = if (own) ref.toLongOrNull() else null
            if (body.isEmpty() && files.isEmpty() && id == null) null else Row(id, body, files)
        }
    }

    fun section(ctx: Context, user: User, view: RequirementView, items: List<ReqItem>, files: Map<Long, List<Attachment>>, spaced: Boolean): String {
        if (items.isEmpty()) return ""
        val mark = stageMark(view)
        val open = stageOpen(user, view, mark)
        val rows = items.joinToString("") { item ->
            val on = mark.of(item)
            val waiting = locked(mark, item)
            val editable = open && !waiting
            val id = "ri${item.id}"
            val checked = if (on) " checked" else ""
            val input = if (editable)
                """<input class="form-check-input" type="checkbox" id="$id"$checked data-item-toggle="/items/${item.id}/${mark.path}" data-saved="${if (on) "1" else "0"}">"""
            else
                """<input class="form-check-input" type="checkbox" id="$id"$checked disabled>"""
            val cls = buildString {
                append("req-item")
                if (on) append(" is-checked")
                if (editable) append(" is-editable")
                if (waiting) append(" is-locked")
            }
            """<li class="$cls">$input<div class="req-item-body"><label class="req-item-text" for="$id"><span class="req-item-label">${e(item.body)}</span></label>${menu(files[item.id].orEmpty())}</div></li>"""
        }
        val rid = view.requirement.id
        val folded = ItemRepo.folded(user.id, rid)
        val listId = "ri-list-$rid"
        val head = """<button class="req-list-head" type="button" data-items-fold="/requirements/$rid/items/fold" aria-expanded="${!folded}" aria-controls="$listId" title="${if (folded) "展开" else "收起"}"><i class="bi bi-chevron-right" aria-hidden="true"></i><span>需求清单</span></button>"""
        val cls = "req-list" + (if (folded) " is-folded" else "") + (if (spaced) " mt-3" else "")
        return """<div class="$cls" id="checklist" data-csrf="${e(ctx.session().csrf)}">$head<ul class="req-items" id="$listId" aria-label="需求清单">$rows</ul></div>"""
    }

    fun fold(ctx: Context) {
        val user = ctx.user()
        val view = Access.requirement(user, ctx.idParam())
        ItemRepo.setFolded(user.id, view.requirement.id, ctx.formParam("folded") == "1")
        ctx.status(204)
    }

    private fun menu(files: List<Attachment>): String {
        if (files.isEmpty()) return ""
        val links = files.joinToString("") { f ->
            val name = e(f.originalName)
            """<li><a class="dropdown-item req-att" ${attachmentHref(f)} title="$name"><i class="bi ${fileIcon(f.originalName)}" aria-hidden="true"></i><span class="req-att-name">$name</span><span class="req-att-size">${e(formatSize(f.sizeBytes))}</span></a></li>"""
        }
        return """<span class="dropdown req-item-more"><button class="req-item-dots" type="button" data-bs-toggle="dropdown" aria-expanded="false" title="附件" aria-label="查看附件，共 ${files.size} 个"><i class="bi bi-three-dots" aria-hidden="true"></i></button><ul class="dropdown-menu dropdown-menu-end req-item-menu">$links</ul></span>"""
    }

    private fun editRow(row: EditRow?, focus: Boolean): String {
        val ref = row?.ref.orEmpty()
        val fileId = if (ref.isEmpty()) "" else "if_$ref"
        val fileName = if (ref.isEmpty()) "" else "item_files_$ref"
        val chips = row?.saved.orEmpty().joinToString("") { savedFileChip(it) }
        val invalid = row?.invalid == true
        val cls = if (invalid) "form-control form-control-sm is-invalid" else "form-control form-control-sm"
        val aria = (if (invalid) """ aria-invalid="true" aria-describedby="f_items_err"""" else "") + if (focus) " autofocus" else ""
        return """<div class="req-edit-item" data-item-row data-att-scope><div class="req-edit-line"><input type="hidden" name="item_ref" value="$ref"><input class="$cls" type="text" name="item_text" value="${e(row?.body)}" maxlength="$MAX_LENGTH" autocomplete="off" aria-label="清单项"$aria><input class="composer-file-input" type="file" id="$fileId"${if (fileName.isEmpty()) "" else """ name="$fileName""""} data-att-name="$fileName" multiple data-att-input data-max-bytes="${Uploads.limitBytes}" aria-label="给这一项添加附件"><label class="req-edit-tool" for="$fileId" data-att-label title="添加附件"><i class="bi bi-paperclip" aria-hidden="true"></i></label><button class="req-edit-del" type="button" data-item-del title="删掉这一项" aria-label="删掉这一项"><i class="bi bi-x-lg" aria-hidden="true"></i></button></div><div class="att-list" data-att-list${if (chips.isEmpty()) " hidden" else ""}>$chips</div></div>"""
    }

    fun editor(rows: List<EditRow>, error: String?, focusAt: Int): String = """
<div class="mb-3" data-items-editor>
  <div class="req-edit-items" data-item-list role="group" aria-label="需求清单">${rows.mapIndexed { i, row -> editRow(row, i == focusAt) }.joinToString("")}</div>
  <div class="invalid-feedback${if (error != null) " d-block" else ""}" id="f_items_err" data-items-error>${e(error ?: NEED_TEXT)}</div>
  <button class="req-edit-add" type="button" data-item-add><i class="bi bi-plus-lg" aria-hidden="true"></i>添加一项</button>
  <template data-item-template>${editRow(null, false)}</template>
</div>"""

    fun done(ctx: Context) = toggle(ctx, ItemMark.DONE)

    fun tested(ctx: Context) = toggle(ctx, ItemMark.TESTED)

    private fun toggle(ctx: Context, mark: ItemMark) {
        val user = ctx.user()
        val (item, view) = Access.item(user, ctx.idParam())
        if (!canMark(user, view, mark, item)) throw ForbiddenResponse("现在不能改这一项")
        ItemRepo.mark(item, mark, ctx.formParam("value") == "1", user.id)
        ctx.status(204)
    }
}
