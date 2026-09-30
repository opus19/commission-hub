package kim.opus.hub.view

import io.javalin.http.Context
import kim.opus.hub.model.*
import kim.opus.hub.web.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun e(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val sb = StringBuilder(value.length + 16)
    for (ch in value) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

fun qs(vararg pairs: Pair<String, Any?>): String {
    val parts = pairs.mapNotNull { (k, v) ->
        val s = v?.toString()
        if (s.isNullOrBlank()) null else enc(k) + "=" + enc(s)
    }
    return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
}

fun csrfInput(ctx: Context): String =
    """<input type="hidden" name="_csrf" value="${e(ctx.session().csrf)}">"""

fun timeTag(iso: String?, prefix: String = ""): String =
    """<time datetime="${e(iso)}" title="${e(formatStamp(iso))}">${e(prefix)}${e(formatRelative(iso))}</time>"""

fun createdTag(iso: String?): String =
    """<time datetime="${e(iso)}" title="${e(formatStamp(iso))}">创建于 ${e(formatCreated(iso))}</time>"""

fun statusBadge(s: ReqStatus): String =
    """<span class="state st-${s.code}"><i class="bi ${s.icon}" aria-hidden="true"></i>${e(s.label)}</span>"""

fun priorityBadge(p: Priority): String =
    """<span class="prio prio-${p.level}"><i class="bi ${p.icon}" aria-hidden="true"></i>${e(p.longLabel)}</span>"""

fun emptyState(text: String, icon: String = "bi-inbox"): String = """
<div class="text-center text-secondary py-5 empty-state">
  <i class="bi $icon d-block mb-2"></i>
  <div>${e(text)}</div>
</div>
"""

fun sideCard(title: String, body: String, flush: Boolean = false, action: String = "", footer: String = ""): String = """
<div class="card mb-4">
  <div class="card-header d-flex justify-content-between align-items-center text-nowrap"><span>${e(title)}</span>$action</div>
  ${if (flush) body else """<div class="card-body">$body</div>"""}
  ${if (footer.isNotEmpty()) """<div class="card-footer bg-transparent small">$footer</div>""" else ""}
</div>
"""

fun queryGroup(items: List<Pair<String, String>>, current: String?, href: (String) -> String, maxBtn: Int = 4): String {
    val useMore = items.size > maxBtn + 1
    val shown = if (useMore) items.take(maxBtn) else items
    val more = if (useMore) items.drop(maxBtn) else emptyList()
    val currentMore = more.firstOrNull { it.first == current }

    val desktop = buildString {
        append("""<div class="btn-group btn-group-sm md-show" role="group">""")
        shown.forEach { (key, label) ->
            val on = key == current
            append("""<a class="btn btn-outline-secondary text-nowrap${if (on) " active" else ""}" href="${e(href(key))}"${if (on) " aria-current=\"page\"" else ""}>$label</a>""")
        }
        if (more.isNotEmpty()) {
            append("""<div class="btn-group btn-group-sm" role="group">""")
            append("""<button type="button" class="btn ${if (currentMore != null) "btn-secondary" else "btn-outline-secondary"} dropdown-toggle text-nowrap" data-bs-toggle="dropdown" aria-expanded="false">${currentMore?.second ?: "更多"}</button>""")
            append("""<ul class="dropdown-menu dropdown-menu-end">""")
            more.forEach { (key, label) ->
                append("""<li><a class="dropdown-item${if (key == current) " active" else ""}" href="${e(href(key))}">$label</a></li>""")
            }
            append("</ul></div>")
        }
        append("</div>")
    }

    val currentLabel = items.firstOrNull { it.first == current }?.second ?: "筛选"
    val mobile = buildString {
        append("""<div class="dropdown md-hide">""")
        append("""<button class="btn btn-sm btn-outline-secondary dropdown-toggle" type="button" data-bs-toggle="dropdown" aria-expanded="false">$currentLabel</button>""")
        append("""<ul class="dropdown-menu">""")
        items.forEach { (key, label) ->
            append("""<li><a class="dropdown-item${if (key == current) " active" else ""}" href="${e(href(key))}">$label</a></li>""")
        }
        append("</ul></div>")
    }
    return desktop + mobile
}

fun pagination(page: Int, pages: Int, href: (Int) -> String): String {
    if (pages <= 1) return ""
    val nums = sortedSetOf(1, pages)
    for (i in (page - 2)..(page + 2)) if (i in 1..pages) nums.add(i)
    val sb = StringBuilder("""<nav class="mt-4 mb-2 d-flex justify-content-center" aria-label="分页"><ul class="pagination pagination-sm mb-0">""")

    fun item(label: String, target: Int?, active: Boolean = false, aria: String? = null) {
        val cls = buildString {
            append("page-item")
            if (active) append(" active")
            if (target == null) append(" disabled")
        }
        val ariaAttr = if (aria != null) """ aria-label="${e(aria)}"""" else ""
        if (target == null || active) {
            sb.append("""<li class="$cls"${if (active) " aria-current=\"page\"" else ""}><span class="page-link"$ariaAttr>$label</span></li>""")
        } else {
            sb.append("""<li class="$cls"><a class="page-link" href="${e(href(target))}"$ariaAttr>$label</a></li>""")
        }
    }

    item("""<i class="bi bi-chevron-left"></i>""", if (page > 1) page - 1 else null, aria = "上一页")
    var last = 0
    for (n in nums) {
        if (last != 0 && n - last > 1) item("…", null)
        item(n.toString(), n, n == page)
        last = n
    }
    item("""<i class="bi bi-chevron-right"></i>""", if (page < pages) page + 1 else null, aria = "下一页")
    sb.append("</ul></nav>")
    return sb.toString()
}

fun wantedBadge(r: Requirement, full: Boolean): String {
    val date = (if (full) Wanted.full(r.wantedAt) else Wanted.short(r.wantedAt)) ?: return ""
    val label = if (full) date else "期望交付 $date"
    if (!r.isOverdue) return """<span class="wanted"><i class="bi bi-calendar-event" aria-hidden="true"></i>${e(label)}</span>"""
    return """<span class="wanted is-overdue"><i class="bi bi-calendar-x" aria-hidden="true"></i>${e(label)}<span class="wanted-late">已超期</span></span>"""
}

fun reqItem(v: RequirementView): String {
    val r = v.requirement
    val meta = buildString {
        append("""<span class="d-inline-flex flex-wrap align-items-center gap-2">${statusBadge(r.statusEnum)}${priorityBadge(r.priorityEnum)}${wantedBadge(r, full = false)}</span>""")
        append("""<span>${timeTag(r.updatedAt, "更新于 ")}</span>""")
    }
    return """
<li class="list-group-item list-group-item-action py-3 px-2 border-start-0 border-end-0 position-relative" data-req="${r.id}">
  <h5 class="text-wrap text-break mb-2">
    <a class="link-dark stretched-link" href="/requirements/${r.id}">${e(r.title)}</a>
  </h5>
  <div class="small text-secondary d-flex flex-wrap align-items-center gap-3">$meta</div>
</li>
"""
}

fun reqList(items: List<RequirementView>): String =
    """<ul class="list-group rounded-0 req-list">${items.joinToString("") { reqItem(it) }}</ul>"""

fun field(
    label: String,
    name: String,
    value: String? = null,
    type: String = "text",
    required: Boolean = false,
    placeholder: String = "",
    hint: String? = null,
    id: String = "f_$name",
    extra: String = ""
): String = """
<div class="mb-3">
  <label class="form-label" for="${e(id)}">${e(label)}${if (required) """ <span class="text-danger">*</span>""" else ""}</label>
  <input class="form-control" id="${e(id)}" type="${e(type)}" name="${e(name)}" value="${e(value)}" placeholder="${e(placeholder)}"${if (required) " required" else ""} $extra>
  ${if (hint != null) """<div class="form-text">${e(hint)}</div>""" else ""}
</div>
"""

fun areaField(
    label: String,
    name: String,
    value: String? = null,
    rows: Int = 5,
    placeholder: String = "",
    hint: String? = null,
    required: Boolean = false,
    id: String = "f_$name"
): String = """
<div class="mb-3">
  <label class="form-label" for="${e(id)}">${e(label)}${if (required) """ <span class="text-danger">*</span>""" else ""}</label>
  <textarea class="form-control" id="${e(id)}" name="${e(name)}" rows="$rows" placeholder="${e(placeholder)}"${if (required) " required" else ""}>${e(value)}</textarea>
  ${if (hint != null) """<div class="form-text">${e(hint)}</div>""" else ""}
</div>
"""

fun selectField(
    label: String,
    name: String,
    options: List<Pair<String, String>>,
    selected: String?,
    hint: String? = null,
    id: String = "f_$name"
): String = """
<div class="mb-3">
  <label class="form-label" for="${e(id)}">${e(label)}</label>
  <select class="form-select" id="${e(id)}" name="${e(name)}">
    ${options.joinToString("") { (value, text) -> """<option value="${e(value)}"${if (value == selected) " selected" else ""}>${e(text)}</option>""" }}
  </select>
  ${if (hint != null) """<div class="form-text">${e(hint)}</div>""" else ""}
</div>
"""

private fun currentAttr(active: Boolean): String = if (active) " aria-current=\"page\"" else ""

private fun barItem(href: String, icon: String, text: String, active: Boolean): String =
    """<a class="sb-item sb-link${if (active) " active" else ""}" href="${e(href)}"${currentAttr(active)}><i class="bi $icon"></i><span>${e(text)}</span></a>"""

private val STYLES = listOf(
    "vendor/bootstrap/bootstrap.min.css",
    "vendor/bootstrap-icons/bootstrap-icons.min.css",
    "app.css"
).joinToString("\n") { """<link rel="stylesheet" href="${Assets.url(it)}">""" }

private fun headHtml(title: String): String = """<!DOCTYPE html>
<html lang="zh-CN" data-bs-theme="dark">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="color-scheme" content="dark">
<title>${e(title)}</title>
<link rel="icon" href="data:,">
$STYLES
</head>
<body>
"""

private val SCRIPTS = """
<script src="${Assets.url("vendor/bootstrap/bootstrap.bundle.min.js")}"></script>
<script src="${Assets.url("app.js")}"></script>
</body>
</html>"""

private fun statusBar(ctx: Context, user: User, active: String): String {
    val left = if (user.isAdmin)
        """<nav class="d-flex align-items-stretch" aria-label="管理">${barItem("/users", "bi-person-badge", "账号", active == "users")}</nav>"""
    else "<div></div>"
    val logout = """<form method="post" action="/logout" class="sb-logout">${csrfInput(ctx)}<button class="sb-icon" type="submit" aria-label="退出登录"><i class="bi bi-box-arrow-right" aria-hidden="true"></i></button></form>"""
    return """
<footer id="statusBar" class="status-bar" aria-label="状态栏">
  <div class="d-flex align-items-stretch justify-content-between h-100">$left$logout</div>
</footer>"""
}

private fun flashItem(kind: String, icon: String?, text: String, role: String, hideMs: Int?): String =
    """<div class="alert alert-$kind alert-dismissible fade show d-flex align-items-start flash" role="$role"${if (hideMs != null) """ data-autohide="$hideMs"""" else ""}>${if (icon == null) "" else """<i class="bi $icon me-2"></i>"""}<div class="flex-fill text-break">${e(text)}</div><button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="关闭"></button></div>"""

private fun flashHtml(ok: String?, err: String?, keep: Boolean): String {
    if (ok == null && err == null) return ""
    val items = buildString {
        if (ok != null) append(flashItem("success", "bi-check-circle-fill", ok, "status", if (keep) null else 3000))
        if (err != null) append(flashItem("danger", null, err, "alert", 6000))
    }
    return """<div class="flash-stack">$items</div>"""
}

fun page(ctx: Context, title: String, active: String, main: String, side: String? = null, back: Pair<String, String>? = null): String {
    val user = ctx.user()
    val session = ctx.session()
    val ok = session.flashOk
    val err = session.flashErr
    val keep = session.flashKeep
    session.flashOk = null
    session.flashErr = null
    session.flashKeep = false

    val backLink = if (back == null) "" else
        """<a class="back-link" href="${e(back.first)}" aria-label="返回上一级：${e(back.second)}"><i class="bi bi-arrow-left"></i><span>${e(back.second)}</span></a>"""

    val sideColumn = if (side == null) "" else """
  <div class="col page-right-side mt-4 mt-xl-0">
    $side
  </div>"""

    return headHtml(title) + flashHtml(ok, err, keep) + """
<div class="workbench">
  <main class="editor-area">
    <div class="main-mx-with">
      ${if (backLink.isEmpty()) "" else """<nav class="back-bar" aria-label="返回">$backLink</nav>"""}
      <div class="row ${if (backLink.isEmpty()) "pt-4" else "pt-2"} mb-5">
        <div class="col page-main">
          $main
        </div>$sideColumn
      </div>
    </div>
  </main>
</div>
""" + statusBar(ctx, user, active) + SCRIPTS
}

fun barePage(title: String, content: String): String = headHtml(title).replace("<body>", """<body class="bare">""") + """
<div class="workbench">
  <main class="editor-area">
    <div class="container" style="padding-top:5rem;padding-bottom:5rem">$content</div>
  </main>
</div>
""" + SCRIPTS
