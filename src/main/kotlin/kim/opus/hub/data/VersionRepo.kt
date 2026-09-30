package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection
import java.util.concurrent.locks.ReentrantLock

object VersionRepo {
    private val seqLock = ReentrantLock()

    fun forRequirement(requirementId: Long): List<ReqVersion> = Db.read { c ->
        val versions = c.rows("select * from req_versions where requirement_id = ? order by seq desc", requirementId, map = ::mapVersion)
        if (versions.isEmpty()) return@read versions
        val files = c.rows(
            "select f.* from req_version_files f join req_versions v on v.id = f.version_id where v.requirement_id = ? order by f.id",
            requirementId, map = ::mapVersionFile
        ).groupBy { it.versionId }
        versions.map { it.copy(files = files[it.id] ?: emptyList()) }
    }

    fun byId(id: Long): ReqVersion? = Db.read { it.row("select * from req_versions where id = ?", id, map = ::mapVersion) }

    fun fileById(id: Long): VersionFile? = Db.read { it.row("select * from req_version_files where id = ?", id, map = ::mapVersionFile) }

    private fun nextSeq(c: Connection, requirementId: Long): Int =
        c.count("select coalesce(max(seq), 0) + 1 from req_versions where requirement_id = ?", requirementId).toInt()

    fun create(requirementId: Long, files: List<NewFile>, markTesting: Boolean, actor: Long): Boolean {
        seqLock.lock()
        try {
            return Db.tx { c ->
                val stamp = nowIso()
                val seq = nextSeq(c, requirementId)
                val id = c.insert(
                    "insert into req_versions(requirement_id, seq, created_by, created_at) values(?, ?, ?, ?)",
                    requirementId, seq, actor, stamp
                )
                files.forEach { f ->
                    c.insert(
                        "insert into req_version_files(version_id, original_name, stored_name, size_bytes, content_type, created_at) values(?, ?, ?, ?, ?, ?)",
                        id, f.originalName, f.storedName, f.sizeBytes, f.contentType, stamp
                    )
                }
                val moved = markTesting && c.exec(
                    "update requirements set status = 'testing', closed_at = null, updated_at = ? where id = ? and status = 'todo'",
                    stamp, requirementId
                ) > 0
                if (moved) Audit.add(c, actor, "requirement", requirementId, "status", "待开发 → 待测试：上传新版本")
                else ReqRepo.touch(c, requirementId)
                Audit.add(c, actor, "requirement", requirementId, "version", "上传新版本，${files.size} 个文件")
                moved
            }
        } finally {
            seqLock.unlock()
        }
    }

    data class Purge(val files: Int, val freed: Long, val stored: List<String>) {
        operator fun plus(o: Purge) = Purge(files + o.files, freed + o.freed, stored + o.stored)

        companion object {
            val NONE = Purge(0, 0L, emptyList())
        }
    }

    fun purge(c: Connection, requirementId: Long, actor: Long?): Purge {
        val live = c.rows(
            "select f.stored_name, f.size_bytes from req_version_files f join req_versions v on v.id = f.version_id where v.requirement_id = ? and f.purged_at is null",
            requirementId
        ) { rs -> rs.getString(1) to rs.getLong(2) }
        if (live.isEmpty()) return Purge.NONE
        c.exec(
            "update req_version_files set purged_at = ? where purged_at is null and version_id in (select id from req_versions where requirement_id = ?)",
            nowIso(), requirementId
        )
        val sizes = live.toMap()
        val stored = sizes.keys.filter { name ->
            c.count(
                "select (select count(*) from req_version_files where stored_name = ? and purged_at is null) + (select count(*) from attachments where stored_name = ?)",
                name, name
            ) == 0L
        }
        val freed = stored.sumOf { sizes.getValue(it) }
        Audit.add(c, actor, "requirement", requirementId, "versions_purged", "${live.size} 个文件，释放 ${formatSize(freed)}")
        return Purge(live.size, freed, stored)
    }

    fun purgeArchived(): Purge = Db.tx { c ->
        val ids = c.rows(
            "select distinct v.requirement_id from req_version_files f join req_versions v on v.id = f.version_id join requirements r on r.id = v.requirement_id where r.status = 'archived' and f.purged_at is null order by v.requirement_id"
        ) { rs -> rs.getLong(1) }
        ids.fold(Purge.NONE) { acc, id -> acc + purge(c, id, null) }
    }

    fun delete(version: ReqVersion, actor: Long): List<String> = Db.tx { c ->
        val stored = c.rows("select stored_name from req_version_files where version_id = ?", version.id) { rs -> rs.getString(1) }
        c.exec("delete from req_version_files where version_id = ?", version.id)
        c.exec("delete from req_versions where id = ?", version.id)
        ReqRepo.touch(c, version.requirementId)
        Audit.add(c, actor, "requirement", version.requirementId, "version_deleted", formatStamp(version.createdAt) + " 上传的版本")
        stored.distinct().filter { name -> c.count("select count(*) from req_version_files where stored_name = ?", name) == 0L }
    }
}
