package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.ResultSet

object ProjectRepo {
    fun byId(id: Long): Project? = Db.read { it.row("select * from projects where id = ?", id, map = ::mapProject) }

    fun all(): List<Project> = Db.read { it.rows("select * from projects order by id", map = ::mapProject) }

    fun views(memberId: Long?): List<ProjectView> = Db.read { c ->
        val sql = buildString {
            append(
                """
                select p.*,
                    (select count(*) from requirements r where r.project_id = p.id and r.status = 'todo') as todo_count,
                    (select count(*) from requirements r where r.project_id = p.id and r.status = 'testing') as testing_count,
                    (select count(*) from requirements r where r.project_id = p.id and r.status = 'archived') as archived_count,
                    coalesce((select max(r.updated_at) from requirements r where r.project_id = p.id), p.created_at) as last_activity
                from projects p
                """.trimIndent()
            )
            if (memberId != null) append(" where exists (select 1 from project_members m where m.project_id = p.id and m.user_id = ?)")
            append(" order by p.id")
        }
        val mapper: (ResultSet) -> ProjectView = { rs ->
            ProjectView(mapProject(rs), rs.getLong("todo_count"), rs.getLong("testing_count"), rs.getLong("archived_count"), rs.getString("last_activity"))
        }
        if (memberId != null) c.rows(sql, memberId, map = mapper) else c.rows(sql, map = mapper)
    }

    fun create(name: String, actor: Long): Long = Db.tx { c ->
        val id = c.insert("insert into projects(name, status, created_at) values(?, 'active', ?)", name, nowIso())
        Audit.add(c, actor, "project", id, "created", name)
        id
    }

    fun update(id: Long, name: String, status: String, actor: Long) = Db.tx { c ->
        c.exec("update projects set name = ?, status = ? where id = ?", name, status, id)
        Audit.add(c, actor, "project", id, "updated", name)
    }

    fun setStatus(id: Long, status: ProjectStatus, actor: Long) = Db.tx { c ->
        c.exec("update projects set status = ? where id = ?", status.code, id)
        Audit.add(c, actor, "project", id, "status", status.label)
    }
}
