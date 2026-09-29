package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection

object MemberRepo {
    fun isMember(projectId: Long, userId: Long): Boolean = Db.read {
        it.count("select count(*) from project_members where project_id = ? and user_id = ?", projectId, userId) > 0
    }

    fun projectIdsFor(userId: Long): List<Long> = Db.read {
        it.rows("select project_id from project_members where user_id = ? order by project_id", userId) { rs -> rs.getLong(1) }
    }

    fun projectsByUser(): Map<Long, List<Project>> = Db.read { c ->
        c.rows(
            """
            select m.user_id as member_id, p.*
            from project_members m join projects p on p.id = m.project_id
            order by p.name
            """.trimIndent()
        ) { rs -> rs.getLong("member_id") to mapProject(rs) }.groupBy({ it.first }, { it.second })
    }

    fun replace(c: Connection, userId: Long, projectIds: Collection<Long>) {
        c.exec("delete from project_members where user_id = ?", userId)
        val stamp = nowIso()
        projectIds.distinct().forEach { pid ->
            c.exec("insert or ignore into project_members(project_id, user_id, created_at) values(?, ?, ?)", pid, userId, stamp)
        }
    }
}
