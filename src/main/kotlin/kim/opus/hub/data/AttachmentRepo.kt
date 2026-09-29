package kim.opus.hub.data

import kim.opus.hub.model.*

object AttachmentRepo {
    fun byId(id: Long): Attachment? = Db.read { it.row("select * from attachments where id = ?", id, map = ::mapAttachment) }

    fun delete(id: Long, actor: Long) = Db.tx { c ->
        c.exec("delete from attachments where id = ?", id)
        Audit.add(c, actor, "attachment", id, "deleted")
    }
}
