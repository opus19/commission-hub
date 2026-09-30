package kim.opus.hub.view

import io.javalin.http.Context
import kim.opus.hub.model.*

fun tabLabel(key: String, count: Long): String =
    e(ReqTabs.label(key)) + """<span class="ms-1 count-muted">$count</span>"""

class ListSpec(
    val heading: String,
    val result: Page<RequirementView>,
    val counts: Map<String, Long>,
    val tab: String,
    val order: String,
    val orders: List<Pair<String, String>>,
    val tabHref: (String) -> String,
    val orderHref: (String) -> String,
    val pageHref: (Int) -> String,
    val emptyText: String
)

fun listSection(l: ListSpec): String {
    val tabs = ReqTabs.keys.map { it to tabLabel(it, l.counts[it] ?: 0L) }
    val orderMenu = """
<div class="dropdown">
  <button class="btn btn-sm btn-outline-secondary dropdown-toggle text-nowrap" type="button" data-bs-toggle="dropdown" aria-expanded="false"><i class="bi bi-sort-down me-1"></i>${e(ReqOrders.label(l.order))}</button>
  <ul class="dropdown-menu">
    <li><h6 class="dropdown-header">排序方式</h6></li>
    ${l.orders.joinToString("") { (k, t) -> """<li><a class="dropdown-item${if (k == l.order) " active" else ""}" href="${e(l.orderHref(k))}">${e(t)}</a></li>""" }}
  </ul>
</div>"""

    val list = if (l.result.items.isEmpty()) emptyState(l.emptyText) else reqList(l.result.items)

    val heading = if (l.heading.isBlank()) "" else
        """<h5 class="fs-5 text-nowrap mb-3">${e(l.heading)}<span class="text-secondary fw-normal fs-6 ms-2">${l.result.total} 条</span></h5>"""

    return """
$heading
<div class="d-flex flex-wrap align-items-center gap-2 mb-3">${queryGroup(tabs, l.tab, l.tabHref, 4)}$orderMenu</div>
<div data-list-body data-page="${l.result.page}">
$list
${pagination(l.result.page, l.result.pages, l.pageHref)}
</div>
"""
}

fun infoList(rows: List<Pair<String, String>>): String = """<ul class="list-group list-group-flush small">${
    rows.joinToString("") { (k, v) ->
        """<li class="list-group-item d-flex justify-content-between align-items-start gap-3"><span class="text-secondary text-nowrap">${e(k)}</span><span class="text-end text-break">$v</span></li>"""
    }
}</ul>"""

fun modalForm(
    ctx: Context,
    id: String,
    title: String,
    action: String,
    body: String,
    submit: String,
    submitClass: String = "btn-primary",
    multipart: Boolean = false,
    size: String = ""
): String = """
<div class="modal hub-island" id="${e(id)}" tabindex="-1" aria-labelledby="${e(id)}Label" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered $size">
    <form class="modal-content" method="post" action="${e(action)}"${if (multipart) """ enctype="multipart/form-data"""" else ""}>
      ${csrfInput(ctx)}
      <div class="modal-header">
        <h5 class="modal-title" id="${e(id)}Label">${e(title)}</h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>
      </div>
      <div class="modal-body">$body</div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link link-secondary" data-bs-dismiss="modal">取消</button>
        <button type="submit" class="btn $submitClass">${e(submit)}</button>
      </div>
    </form>
  </div>
</div>"""

fun attachmentHref(f: Attachment): String =
    if (imageType(f.originalName) != null) """href="/attachments/${f.id}/image" target="_blank" rel="noopener"""" else """href="/attachments/${f.id}""""

fun savedFileChip(f: Attachment): String {
    val name = e(f.originalName)
    return """<span class="file-chip" data-att-saved="${f.id}"><a class="file-chip-link" ${attachmentHref(f)} title="$name"><i class="bi ${fileIcon(f.originalName)}" aria-hidden="true"></i><span class="file-chip-name">$name</span><span class="file-chip-size">${e(formatSize(f.sizeBytes))}</span></a><button class="file-chip-del" type="button" data-att-drop title="移除" aria-label="移除附件：$name"><i class="bi bi-x-lg" aria-hidden="true"></i></button></span>"""
}

fun imageType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    else -> null
}

fun fileIcon(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> "bi-file-earmark-image"
        "zip", "jar", "rar", "7z", "gz", "tar", "mcpack", "mrpack" -> "bi-file-earmark-zip"
        "txt", "log", "md" -> "bi-file-earmark-text"
        "yml", "yaml", "json", "toml", "properties", "conf", "cfg" -> "bi-file-earmark-code"
        "mp4", "webm", "mov", "mkv", "avi" -> "bi-file-earmark-play"
        "pdf" -> "bi-file-earmark-pdf"
        "doc", "docx" -> "bi-file-earmark-word"
        "xls", "xlsx", "csv" -> "bi-file-earmark-spreadsheet"
        else -> "bi-file-earmark"
    }
}
