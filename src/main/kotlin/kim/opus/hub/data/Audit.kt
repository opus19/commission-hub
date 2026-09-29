package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection

object Audit {
    fun add(c: Connection, userId: Long?, entity: String, entityId: Long, action: String, detail: String? = null) {
        c.exec(
            "insert into audit_log(user_id, entity, entity_id, action, detail, created_at) values(?, ?, ?, ?, ?, ?)",
            userId, entity, entityId, action, detail, nowIso()
        )
    }
}
