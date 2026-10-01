package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection

object BuildRepo {

    private const val LIVE = "exists (select 1 from build_files f where f.build_id = b.id and f.purged_at is null)"

    data class Purge(val files: Int, val freed: Long, val stored: List<String>) {
        operator fun plus(o: Purge) = Purge(files + o.files, freed + o.freed, stored + o.stored)

        companion object {
            val NONE = Purge(0, 0L, emptyList())
        }
    }

    data class Created(val seq: Int, val linked: Int, val moved: Int, val purge: Purge)

    data class Removed(val deleted: Boolean, val stored: List<String>)

    private fun complete(c: Connection, builds: List<Build>): List<Build> {
        if (builds.isEmpty()) return builds
        val ids = builds.joinToString(",") { it.id.toString() }
        val files = c.rows("select * from build_files where build_id in ($ids) order by id", map = ::mapBuildFile).groupBy { it.buildId }
        val links = c.rows("select build_id, requirement_id from build_requirements where build_id in ($ids) order by requirement_id") { rs ->
            rs.getLong(1) to rs.getLong(2)
        }.groupBy({ it.first }, { it.second })
        return builds.map { it.copy(files = files[it.id].orEmpty(), requirementIds = links[it.id].orEmpty()) }
    }

    fun forRequirement(requirementId: Long): List<Build> = Db.read { c ->
        complete(c, c.rows(
            "select b.* from builds b join build_requirements l on l.build_id = b.id where l.requirement_id = ? order by b.seq desc",
            requirementId, map = ::mapBuild
        ))
    }

    fun newestLive(projectId: Long): Build? = Db.read { c ->
        val b = c.row("select b.* from builds b where b.project_id = ? and $LIVE order by b.seq desc limit 1", projectId, map = ::mapBuild)
        if (b == null) null else complete(c, listOf(b)).first()
    }

    fun byId(id: Long): Build? = Db.read { c ->
        val b = c.row("select * from builds where id = ?", id, map = ::mapBuild)
        if (b == null) null else complete(c, listOf(b)).first()
    }

    fun fileById(id: Long): BuildFile? = Db.read { it.row("select * from build_files where id = ?", id, map = ::mapBuildFile) }

    fun nextSeq(projectId: Long): Int = Db.read { c ->
        c.count("select build_seq + 1 from projects where id = ?", projectId).toInt()
    }

    fun acceptedSeq(c: Connection, requirementId: Long, projectId: Long): Int? {
        if (c.count("select count(*) from build_requirements where requirement_id = ?", requirementId) == 0L) return null
        return c.row("select b.seq from builds b where b.project_id = ? and $LIVE order by b.seq desc limit 1", projectId) { it.getInt(1) }
    }

    fun create(projectId: Long, requirementId: Long, also: List<Long>, files: List<NewFile>, markTesting: Boolean, actor: Long): Created = Db.tx { c ->
        val stamp = nowIso()
        c.exec("update projects set build_seq = build_seq + 1 where id = ?", projectId)
        val seq = c.count("select build_seq from projects where id = ?", projectId).toInt()
        val id = c.insert(
            "insert into builds(project_id, seq, created_by, created_at) values(?, ?, ?, ?)",
            projectId, seq, actor, stamp
        )
        files.forEach { f ->
            c.insert(
                "insert into build_files(build_id, original_name, stored_name, size_bytes, content_type, created_at) values(?, ?, ?, ?, ?, ?)",
                id, f.originalName, f.storedName, f.sizeBytes, f.contentType, stamp
            )
        }
        val others = also.distinct().filter { rid ->
            rid != requirementId && c.count(
                "select count(*) from requirements where id = ? and project_id = ? and status != 'archived'",
                rid, projectId
            ) > 0L
        }
        var moved = 0
        (listOf(requirementId) + others).forEach { rid ->
            c.exec("insert into build_requirements(build_id, requirement_id) values(?, ?)", id, rid)
            val toTesting = markTesting && c.exec(
                "update requirements set status = 'testing', closed_at = null, updated_at = ? where id = ? and status = 'todo'",
                stamp, rid
            ) > 0
            if (toTesting) {
                moved++
                Audit.add(c, actor, "requirement", rid, "status", "待开发 → 待测试：上传构建 #$seq")
            } else {
                ReqRepo.touch(c, rid)
            }
            Audit.add(c, actor, "requirement", rid, "build", "上传构建 #$seq，${files.size} 个文件")
        }
        Created(seq, others.size + 1, moved, purgeStale(c, projectId, actor))
    }

    fun remove(build: Build, requirementId: Long, actor: Long): Removed = Db.tx { c ->
        c.exec("delete from build_requirements where build_id = ? and requirement_id = ?", build.id, requirementId)
        ReqRepo.touch(c, requirementId)
        if (c.count("select count(*) from build_requirements where build_id = ?", build.id) > 0L) {
            Audit.add(c, actor, "requirement", requirementId, "build_removed", "移除构建 #${build.seq}")
            return@tx Removed(false, purgeStale(c, build.projectId, actor).stored)
        }
        val stored = c.rows("select stored_name from build_files where build_id = ?", build.id) { it.getString(1) }
        c.exec("delete from build_files where build_id = ?", build.id)
        c.exec("delete from builds where id = ?", build.id)
        Audit.add(c, actor, "requirement", requirementId, "build_deleted", "删除构建 #${build.seq}")
        Removed(true, stored.distinct().filter { unused(c, it) } + purgeStale(c, build.projectId, actor).stored)
    }

    fun purgeStale(c: Connection, projectId: Long, actor: Long?): Purge {
        val top = c.count("select coalesce(max(seq), 0) from builds where project_id = ?", projectId)
        if (top == 0L) return Purge.NONE
        val stale = c.rows(
            """
            select b.id, b.seq from builds b
            where b.project_id = ? and b.seq < ? and $LIVE
              and not exists (
                select 1 from build_requirements l join requirements r on r.id = l.requirement_id
                where l.build_id = b.id and r.status != 'archived'
              )
            order by b.seq
            """.trimIndent(),
            projectId, top
        ) { rs -> rs.getLong(1) to rs.getInt(2) }
        if (stale.isEmpty()) return Purge.NONE
        val ids = stale.joinToString(",") { it.first.toString() }
        val live = c.rows("select stored_name, size_bytes from build_files where build_id in ($ids) and purged_at is null") { rs ->
            rs.getString(1) to rs.getLong(2)
        }
        c.exec("update build_files set purged_at = ? where build_id in ($ids) and purged_at is null", nowIso())
        val sizes = live.toMap()
        val stored = sizes.keys.filter { unused(c, it) }
        val freed = stored.sumOf { sizes.getValue(it) }
        Audit.add(c, actor, "project", projectId, "builds_purged", stale.joinToString(" ") { "#" + it.second } + "，${live.size} 个文件，释放 ${formatSize(freed)}")
        return Purge(live.size, freed, stored)
    }

    fun sweep(): Purge = Db.tx { c ->
        c.rows("select id from projects order by id") { it.getLong(1) }.fold(Purge.NONE) { acc, id -> acc + purgeStale(c, id, null) }
    }

    private fun unused(c: Connection, name: String): Boolean = c.count(
        "select (select count(*) from build_files where stored_name = ? and purged_at is null) + (select count(*) from attachments where stored_name = ?)",
        name, name
    ) == 0L
}
