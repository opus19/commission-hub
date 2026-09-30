package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection

object AttachmentRepo {
    fun byId(id: Long): Attachment? = Db.read { it.row("select * from attachments where id = ?", id, map = ::mapAttachment) }

    fun delete(id: Long, actor: Long) = Db.tx { c ->
        c.exec("delete from attachments where id = ?", id)
        Audit.add(c, actor, "attachment", id, "deleted")
    }

    fun forRequirement(requirementId: Long): List<Attachment> = Db.read {
        it.rows("select * from attachments where requirement_id = ? and comment_id is null order by id", requirementId, map = ::mapAttachment)
    }

    fun add(c: Connection, requirementId: Long, itemId: Long?, userId: Long, files: List<NewFile>, stamp: String) {
        files.forEach { f ->
            c.insert(
                "insert into attachments(requirement_id, comment_id, item_id, user_id, original_name, stored_name, size_bytes, content_type, created_at) values(?, null, ?, ?, ?, ?, ?, ?, ?)",
                requirementId, itemId, userId, f.originalName, f.storedName, f.sizeBytes, f.contentType, stamp
            )
        }
    }

    fun remove(c: Connection, requirementId: Long, ids: Set<Long>, actor: Long): List<String> = ids.mapNotNull { id ->
        val found = c.row(
            "select stored_name, original_name from attachments where id = ? and requirement_id = ? and comment_id is null",
            id, requirementId
        ) { rs -> rs.getString(1) to rs.getString(2) } ?: return@mapNotNull null
        c.exec("delete from attachments where id = ?", id)
        Audit.add(c, actor, "attachment", id, "deleted", found.second)
        found.first
    }
}
