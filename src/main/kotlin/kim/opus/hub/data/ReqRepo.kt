package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock

object ReqRepo {
    private val seqLock = ReentrantLock()

    private const val VIEW_SELECT = """
        select r.*, p.name as project_name, p.status as project_status,
            (select count(*) from attachments a where a.requirement_id = r.id)
              + (select count(*) from attachments a join comments cm on cm.id = a.comment_id where cm.requirement_id = r.id) as attachment_count,
            (select count(*) from comments cm where cm.requirement_id = r.id) as comment_count
        from requirements r
        join projects p on p.id = r.project_id
    """

    private const val COUNT_SELECT = "select count(*) from requirements r"

    private fun mapView(rs: ResultSet) = RequirementView(
        requirement = mapRequirement(rs),
        attachmentCount = rs.getLong("attachment_count"),
        commentCount = rs.getLong("comment_count"),
        projectName = rs.getString("project_name"),
        projectStatus = rs.getString("project_status")
    )

    private fun whereFor(q: ReqQuery, args: MutableList<Any?>): String {
        val parts = ArrayList<String>()
        if (q.memberId != null) {
            parts.add("r.project_id in (select project_id from project_members where user_id = ?)")
            args.add(q.memberId)
        }
        if (q.projectId != null) {
            parts.add("r.project_id = ?")
            args.add(q.projectId)
        }
        parts.add("(" + ReqTabs.clause(q.tab) + ")")
        return " where " + parts.joinToString(" and ")
    }

    fun search(q: ReqQuery): Page<RequirementView> = Db.read { c ->
        val countArgs = ArrayList<Any?>()
        val total = c.count(COUNT_SELECT + whereFor(q, countArgs), *countArgs.toTypedArray())

        val size = q.pageSize.coerceIn(1, 100)
        val maxPage = if (total <= 0) 1 else ((total + size - 1) / size).toInt()
        val page = q.page.coerceIn(1, maxPage)

        val args = ArrayList<Any?>()
        val sql = VIEW_SELECT + whereFor(q, args) + " order by " + ReqOrders.sql(q.order) + ", r.id desc limit ? offset ?"
        args.add(size)
        args.add((page - 1) * size)
        val items = c.rows(sql, *args.toTypedArray(), map = ::mapView)
        Page(items, total, page, size)
    }

    fun tabCounts(q: ReqQuery): Map<String, Long> = Db.read { c ->
        ReqTabs.keys.associateWith { key ->
            val args = ArrayList<Any?>()
            c.count(COUNT_SELECT + whereFor(q.copy(tab = key), args), *args.toTypedArray())
        }
    }

    fun view(id: Long): RequirementView? = Db.read { c ->
        c.row("$VIEW_SELECT where r.id = ?", id, map = ::mapView)
    }

    fun create(
        projectId: Long,
        title: String,
        body: String?,
        status: ReqStatus,
        priority: Int,
        wantedAt: String?,
        actor: Long
    ): Long {
        seqLock.lock()
        try {
            return Db.tx { c ->
                val seq = c.count("select coalesce(max(seq), 0) + 1 from requirements where project_id = ?", projectId)
                val stamp = nowIso()
                val id = c.insert(
                    """
                    insert into requirements(project_id, seq, title, body, status, priority, on_hold, created_by, created_at, updated_at, wanted_at)
                    values(?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                    """.trimIndent(),
                    projectId, seq, title, body, status.code, priority, actor, stamp, stamp, wantedAt
                )
                Audit.add(c, actor, "requirement", id, "created", title)
                id
            }
        } finally {
            seqLock.unlock()
        }
    }

    fun update(id: Long, title: String, body: String?, priority: Int, wantedAt: String?, actor: Long) = Db.tx { c ->
        c.exec(
            "update requirements set title = ?, body = ?, priority = ?, wanted_at = ?, updated_at = ? where id = ?",
            title, body, priority, wantedAt, nowIso(), id
        )
        Audit.add(c, actor, "requirement", id, "edited", title)
    }

    fun setStatus(id: Long, from: ReqStatus, to: ReqStatus, actor: Long, note: String?) = Db.tx { c ->
        val closedAt = if (to == ReqStatus.ARCHIVED) nowIso() else null
        c.exec(
            "update requirements set status = ?, closed_at = ?, updated_at = ? where id = ?",
            to.code, closedAt, nowIso(), id
        )
        val detail = buildString {
            append(from.label)
            append(" → ")
            append(to.label)
            if (!note.isNullOrBlank()) {
                append("：")
                append(note.trim())
            }
        }
        Audit.add(c, actor, "requirement", id, "status", detail)
    }

    fun touch(c: Connection, id: Long) {
        c.exec("update requirements set updated_at = ? where id = ?", nowIso(), id)
    }
}
