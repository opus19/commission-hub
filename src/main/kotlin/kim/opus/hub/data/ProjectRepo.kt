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
        val id = c.insert("insert into projects(name, created_at) values(?, ?)", name, nowIso())
        Audit.add(c, actor, "project", id, "created", name)
        id
    }

    fun update(id: Long, name: String, actor: Long) = Db.tx { c ->
        c.exec("update projects set name = ? where id = ?", name, id)
        Audit.add(c, actor, "project", id, "updated", name)
    }

    fun delete(project: Project, actor: Long): List<String> = Db.tx { c ->
        val id = project.id
        val reqs = "select id from requirements where project_id = ?"
        val comments = "select id from comments where requirement_id in ($reqs)"
        val versions = "select id from req_versions where requirement_id in ($reqs)"
        val releases = "select id from releases where project_id = ?"
        val stored = c.rows(
            """
            select stored_name from attachments where requirement_id in ($reqs) or comment_id in ($comments)
            union select stored_name from req_version_files where version_id in ($versions)
            union select stored_name from release_assets where release_id in ($releases)
            """.trimIndent(),
            id, id, id, id
        ) { rs -> rs.getString(1) }
        c.exec("delete from attachments where requirement_id in ($reqs) or comment_id in ($comments)", id, id)
        c.exec("delete from comments where requirement_id in ($reqs)", id)
        c.exec("delete from req_version_files where version_id in ($versions)", id)
        c.exec("delete from req_versions where requirement_id in ($reqs)", id)
        c.exec("delete from release_requirements where requirement_id in ($reqs) or release_id in ($releases)", id, id)
        c.exec("delete from release_assets where release_id in ($releases)", id)
        c.exec("delete from releases where project_id = ?", id)
        c.exec("delete from requirement_labels where requirement_id in ($reqs)", id)
        c.exec("delete from requirements where project_id = ?", id)
        c.exec("delete from project_members where project_id = ?", id)
        c.exec("delete from projects where id = ?", id)
        Audit.add(c, actor, "project", id, "deleted", project.name)
        stored.filter { name ->
            c.count(
                "select (select count(*) from attachments where stored_name = ?) + (select count(*) from req_version_files where stored_name = ?) + (select count(*) from release_assets where stored_name = ?)",
                name, name, name
            ) == 0L
        }
    }
}
