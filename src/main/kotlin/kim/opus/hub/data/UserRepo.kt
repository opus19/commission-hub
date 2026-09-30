package kim.opus.hub.data

import kim.opus.hub.model.*

object UserRepo {
    fun count(): Long = Db.read { it.count("select count(*) from users") }

    fun byId(id: Long): User? = Db.read { it.row("select * from users where id = ?", id, map = ::mapUser) }

    fun byUsername(username: String): User? = Db.read {
        it.row("select * from users where lower(username) = lower(?) and active = 1", username, map = ::mapUser)
    }

    fun passwordHash(id: Long): String? = Db.read {
        it.row("select password_hash from users where id = ?", id) { rs -> rs.getString(1) }
    }

    fun clients(): List<User> = Db.read {
        it.rows("select * from users where role = ? and active = 1 order by id", ROLE_CLIENT, map = ::mapUser)
    }

    fun createAdmin(username: String, displayName: String, password: String): Long = Db.tx { c ->
        val id = c.insert(
            "insert into users(username, display_name, password_hash, role, client_id, active, created_at) values(?, ?, ?, ?, null, 1, ?)",
            username, displayName, Passwords.hash(password), ROLE_ADMIN, nowIso()
        )
        Audit.add(c, null, "user", id, "created", "管理员 $username")
        id
    }

    fun createClient(username: String, displayName: String, password: String, projectIds: Collection<Long>, actor: Long): Long =
        Db.tx { c ->
            val id = c.insert(
                "insert into users(username, display_name, password_hash, role, client_id, active, created_at) values(?, ?, ?, ?, null, 1, ?)",
                username, displayName, Passwords.hash(password), ROLE_CLIENT, nowIso()
            )
            MemberRepo.replace(c, id, projectIds)
            Audit.add(c, actor, "user", id, "created", "账号 $username")
            id
        }

    fun updateClient(id: Long, displayName: String, projectIds: Collection<Long>, actor: Long) = Db.tx { c ->
        c.exec("update users set display_name = ? where id = ? and role = ?", displayName, id, ROLE_CLIENT)
        MemberRepo.replace(c, id, projectIds)
        Audit.add(c, actor, "user", id, "updated", displayName + "（${projectIds.size} 个项目）")
    }

    fun setPassword(id: Long, password: String, actor: Long?) = Db.tx { c ->
        c.exec("update users set password_hash = ? where id = ?", Passwords.hash(password), id)
        Audit.add(c, actor, "user", id, "password_changed")
    }

    fun remove(user: User, actor: Long) = Db.tx { c ->
        c.exec("update users set active = 0 where id = ? and role = ?", user.id, ROLE_CLIENT)
        c.exec("delete from project_members where user_id = ?", user.id)
        c.exec("delete from comment_folds where user_id = ?", user.id)
        c.exec("delete from item_folds where user_id = ?", user.id)
        Audit.add(c, actor, "user", user.id, "removed", "账号 " + user.username)
    }
}
