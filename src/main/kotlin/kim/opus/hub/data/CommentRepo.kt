package kim.opus.hub.data

import kim.opus.hub.model.*

object CommentRepo {
    private const val SELECT = "select cm.*, coalesce(u.display_name, '已删除') as author_name from comments cm left join users u on u.id = cm.user_id"

    fun byId(id: Long): Comment? = Db.read { c ->
        c.row("$SELECT where cm.id = ?", id, map = ::mapComment)?.let { cm ->
            cm.copy(attachments = c.rows("select * from attachments where comment_id = ? order by id", id, map = ::mapAttachment))
        }
    }

    fun update(id: Long, requirementId: Long, body: String, oldBody: String, actor: Long) = Db.tx { c ->
        c.exec("update comments set body = ?, edited_at = ? where id = ?", body, nowIso(), id)
        ReqRepo.touch(c, requirementId)
        Audit.add(c, actor, "comment", id, "edited", oldBody.take(1000).ifEmpty { null })
    }

    fun delete(cm: Comment, actor: Long): List<String> = Db.tx { c ->
        val stored = c.rows("select stored_name from attachments where comment_id = ?", cm.id) { rs -> rs.getString(1) }
        c.exec("delete from attachments where comment_id = ?", cm.id)
        c.exec("delete from comments where id = ?", cm.id)
        ReqRepo.touch(c, cm.requirementId)
        Audit.add(c, actor, "comment", cm.id, "deleted", cm.body.take(1000).ifEmpty { null })
        stored
    }

    fun forRequirement(requirementId: Long): List<Comment> = Db.read { c ->
        val comments = c.rows("$SELECT where cm.requirement_id = ? order by cm.id", requirementId, map = ::mapComment)
        if (comments.isEmpty()) return@read comments
        val files = c.rows(
            """
            select a.* from attachments a join comments cm on cm.id = a.comment_id
            where cm.requirement_id = ?
            order by a.id
            """.trimIndent(),
            requirementId, map = ::mapAttachment
        ).groupBy { it.commentId }
        comments.map { it.copy(attachments = files[it.id] ?: emptyList()) }
    }

    fun create(requirementId: Long, userId: Long, body: String, files: List<NewFile>): Long = Db.tx { c ->
        val stamp = nowIso()
        val id = c.insert(
            "insert into comments(requirement_id, user_id, body, created_at) values(?, ?, ?, ?)",
            requirementId, userId, body, stamp
        )
        files.forEach { f ->
            c.insert(
                "insert into attachments(requirement_id, comment_id, user_id, original_name, stored_name, size_bytes, content_type, created_at) values(null, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, f.originalName, f.storedName, f.sizeBytes, f.contentType, stamp
            )
        }
        ReqRepo.touch(c, requirementId)
        Audit.add(c, userId, "requirement", requirementId, "commented", if (files.isEmpty()) null else "${files.size} 个附件")
        id
    }
}
