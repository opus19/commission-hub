package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.Connection

object ItemRepo {
    class Saved(val ids: List<Long>, val dropped: List<String>)

    fun forRequirement(requirementId: Long): List<ReqItem> = Db.read {
        it.rows("select * from req_items where requirement_id = ? order by position, id", requirementId, map = ::mapItem)
    }

    fun byId(id: Long): ReqItem? = Db.read { it.row("select * from req_items where id = ?", id, map = ::mapItem) }

    fun folded(userId: Long, requirementId: Long): Boolean = Db.read {
        it.count("select count(*) from item_folds where user_id = ? and requirement_id = ?", userId, requirementId) > 0L
    }

    fun setFolded(userId: Long, requirementId: Long, folded: Boolean) {
        Db.read { c ->
            if (folded) c.exec("insert or ignore into item_folds(user_id, requirement_id) values(?, ?)", userId, requirementId)
            else c.exec("delete from item_folds where user_id = ? and requirement_id = ?", userId, requirementId)
        }
    }

    fun replace(c: Connection, requirementId: Long, items: List<ItemInput>): Saved {
        val existing = c.rows("select id from req_items where requirement_id = ?", requirementId) { rs -> rs.getLong(1) }.toSet()
        val kept = HashSet<Long>()
        val stamp = nowIso()
        val ids = items.mapIndexed { position, item ->
            val id = item.id
            if (id != null && id in existing && kept.add(id)) {
                c.exec("update req_items set body = ?, position = ? where id = ?", item.body, position, id)
                id
            } else {
                c.insert(
                    "insert into req_items(requirement_id, position, body, created_at) values(?, ?, ?, ?)",
                    requirementId, position, item.body, stamp
                )
            }
        }
        val dropped = (existing - kept).flatMap { id ->
            val files = c.rows("select stored_name from attachments where item_id = ?", id) { rs -> rs.getString(1) }
            c.exec("delete from attachments where item_id = ?", id)
            c.exec("delete from req_items where id = ?", id)
            files
        }
        return Saved(ids, dropped)
    }

    fun count(requirementId: Long): Long = Db.read { it.count("select count(*) from req_items where requirement_id = ?", requirementId) }

    class Added(val id: Long, val reopened: Boolean)

    fun append(requirementId: Long, body: String, files: List<NewFile>, actor: Long): Added = Db.tx { c ->
        val stamp = nowIso()
        val position = c.count("select coalesce(max(position), -1) + 1 from req_items where requirement_id = ?", requirementId).toInt()
        val id = c.insert(
            "insert into req_items(requirement_id, position, body, created_at) values(?, ?, ?, ?)",
            requirementId, position, body, stamp
        )
        AttachmentRepo.add(c, requirementId, id, actor, files, stamp)
        Audit.add(c, actor, "requirement", requirementId, "item_added", body.take(200))
        val reopened = c.exec(
            "update requirements set status = 'todo', closed_at = null, updated_at = ? where id = ? and status = 'testing'",
            stamp, requirementId
        ) > 0
        if (reopened) {
            c.exec("update req_items set done_at = null where requirement_id = ? and tested_at is null", requirementId)
            Audit.add(c, actor, "requirement", requirementId, "status", "待测试 → 待开发：客户加入清单项")
        } else {
            ReqRepo.touch(c, requirementId)
        }
        c.exec("delete from item_folds where user_id = ? and requirement_id = ?", actor, requirementId)
        Added(id, reopened)
    }

    fun mark(item: ReqItem, mark: ItemMark, value: Boolean, actor: Long) {
        if (mark.of(item) == value) return
        Db.tx { c ->
            val stamp = nowIso()
            when {
                mark == ItemMark.DONE && value -> c.exec("update req_items set done_at = ? where id = ?", stamp, item.id)
                mark == ItemMark.DONE -> c.exec("update req_items set done_at = null, tested_at = null where id = ?", item.id)
                value -> c.exec("update req_items set tested_at = ? where id = ?", stamp, item.id)
                else -> c.exec("update req_items set tested_at = null where id = ?", item.id)
            }
            ReqRepo.touch(c, item.requirementId)
            Audit.add(c, actor, "requirement", item.requirementId, "item_" + mark.path + if (value) "" else "_cleared", item.body.take(200))
        }
    }
}
