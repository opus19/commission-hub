package kim.opus.hub.data

import java.sql.Connection
import java.time.Instant

object Schema {
    private val migrations: List<Pair<Int, String>> = listOf(
        1 to """
        create table clients (
            id integer primary key autoincrement,
            name text not null,
            contact text,
            note text,
            archived integer not null default 0,
            created_at text not null
        );

        create table users (
            id integer primary key autoincrement,
            username text not null,
            display_name text not null,
            password_hash text not null,
            role text not null,
            client_id integer references clients(id),
            active integer not null default 1,
            created_at text not null
        );
        create unique index ux_users_username on users(lower(username));

        create table projects (
            id integer primary key autoincrement,
            client_id integer not null references clients(id),
            name text not null,
            platform text,
            mc_version text,
            current_version text,
            status text not null default 'active',
            note text,
            created_at text not null
        );
        create index ix_projects_client on projects(client_id);

        create table labels (
            id integer primary key autoincrement,
            name text not null,
            color text not null,
            sort_order integer not null default 0
        );
        create unique index ux_labels_name on labels(name);

        create table requirements (
            id integer primary key autoincrement,
            project_id integer not null references projects(id),
            seq integer not null,
            title text not null,
            body text,
            acceptance text,
            status text not null,
            priority integer not null default 2,
            on_hold integer not null default 0,
            quote_cents integer,
            created_by integer not null references users(id),
            created_at text not null,
            updated_at text not null,
            closed_at text
        );
        create unique index ux_req_project_seq on requirements(project_id, seq);
        create index ix_req_status on requirements(status);

        create table requirement_labels (
            requirement_id integer not null references requirements(id) on delete cascade,
            label_id integer not null references labels(id) on delete cascade,
            primary key (requirement_id, label_id)
        );

        create table comments (
            id integer primary key autoincrement,
            requirement_id integer not null references requirements(id) on delete cascade,
            user_id integer not null references users(id),
            body text not null,
            created_at text not null
        );
        create index ix_comments_req on comments(requirement_id);

        create table attachments (
            id integer primary key autoincrement,
            requirement_id integer references requirements(id) on delete cascade,
            comment_id integer references comments(id) on delete cascade,
            user_id integer not null references users(id),
            original_name text not null,
            stored_name text not null,
            size_bytes integer not null,
            content_type text,
            created_at text not null
        );
        create index ix_attachments_req on attachments(requirement_id);

        create table audit_log (
            id integer primary key autoincrement,
            user_id integer references users(id),
            entity text not null,
            entity_id integer not null,
            action text not null,
            detail text,
            created_at text not null
        );
        create index ix_audit_entity on audit_log(entity, entity_id);
        """,
        2 to """
        create table project_members (
            project_id integer not null references projects(id) on delete cascade,
            user_id integer not null references users(id) on delete cascade,
            created_at text not null,
            primary key (project_id, user_id)
        );
        create index ix_members_user on project_members(user_id);

        insert or ignore into project_members(project_id, user_id, created_at)
            select p.id, u.id, strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
            from users u join projects p on p.client_id = u.client_id
            where u.role = 'client' and u.client_id is not null;

        create table projects_v2 (
            id integer primary key autoincrement,
            name text not null,
            platform text,
            mc_version text,
            current_version text,
            status text not null default 'active',
            note text,
            created_at text not null
        );
        insert into projects_v2(id, name, platform, mc_version, current_version, status, note, created_at)
            select id, name, platform, mc_version, current_version, status, note, created_at from projects;
        drop table projects;
        alter table projects_v2 rename to projects;

        alter table users add column is_owner integer not null default 0;
        update users set is_owner = 1 where id = (select min(id) from users where role = 'admin');
        update users set client_id = null;

        update requirements set on_hold = 0
        """,
        3 to """
        update projects set status = 'archived' where status = 'done';

        update users set role = 'client'
            where role = 'admin' and id <> (select min(id) from users where role = 'admin');
        update users set is_owner = 0;

        delete from labels
            where name = '已收款'
            and not exists (select 1 from requirement_labels rl where rl.label_id = labels.id)
        """,
        4 to """
        update requirements set status = 'todo', closed_at = null
            where status in ('draft', 'awaiting_confirm', 'doing')
        """,
        5 to """
        update requirements set status = 'archived', closed_at = coalesce(closed_at, updated_at)
            where status in ('accepted', 'closed')
        """,
        6 to """
        update requirements set status = 'todo', closed_at = null where status = 'rejected';

        insert into comments(requirement_id, user_id, body, created_at)
            select requirement_id, user_id, '__hub_v6_att__' || id, created_at
            from attachments
            where requirement_id is not null and comment_id is null
            order by id;

        update attachments
            set comment_id = (select cm.id from comments cm where cm.body = '__hub_v6_att__' || attachments.id),
                requirement_id = null
            where requirement_id is not null and comment_id is null;

        update comments set body = '' where substr(body, 1, 14) = '__hub_v6_att__'
        """,
        7 to """
        alter table requirements add column estimate_value real;
        alter table requirements add column estimate_unit text
        """,
        8 to """
        alter table requirements add column due_at text
        """,
        9 to """
        alter table requirements add column wanted_at text
        """,
        10 to """
        update requirements set priority = 1 where priority < 1
        """,
        11 to """
        create table releases (
            id integer primary key autoincrement,
            project_id integer not null references projects(id) on delete cascade,
            title text not null,
            notes text,
            created_by integer not null references users(id),
            created_at text not null,
            updated_at text not null
        );
        create index ix_releases_project on releases(project_id, id);

        create table release_assets (
            id integer primary key autoincrement,
            release_id integer not null references releases(id) on delete cascade,
            original_name text not null,
            stored_name text not null,
            size_bytes integer not null,
            content_type text,
            created_at text not null
        );
        create index ix_release_assets_release on release_assets(release_id);

        create table release_requirements (
            release_id integer not null references releases(id) on delete cascade,
            requirement_id integer not null references requirements(id) on delete cascade,
            primary key (release_id, requirement_id)
        );
        create index ix_release_req_req on release_requirements(requirement_id)
        """,
        12 to """
        create table req_versions (
            id integer primary key autoincrement,
            requirement_id integer not null references requirements(id) on delete cascade,
            seq integer not null,
            created_by integer not null references users(id),
            created_at text not null,
            from_release_id integer
        );
        create unique index ux_req_versions_seq on req_versions(requirement_id, seq);

        create table req_version_files (
            id integer primary key autoincrement,
            version_id integer not null references req_versions(id) on delete cascade,
            original_name text not null,
            stored_name text not null,
            size_bytes integer not null,
            content_type text,
            created_at text not null
        );
        create index ix_req_version_files_version on req_version_files(version_id);
        create index ix_req_version_files_stored on req_version_files(stored_name);

        insert into req_versions(requirement_id, seq, created_by, created_at, from_release_id)
            select rr.requirement_id,
                   row_number() over (partition by rr.requirement_id order by rl.id),
                   rl.created_by, rl.created_at, rl.id
            from release_requirements rr join releases rl on rl.id = rr.release_id
            where exists (select 1 from release_assets a where a.release_id = rl.id);

        insert into req_version_files(version_id, original_name, stored_name, size_bytes, content_type, created_at)
            select v.id, a.original_name, a.stored_name, a.size_bytes, a.content_type, a.created_at
            from req_versions v join release_assets a on a.release_id = v.from_release_id
            order by v.id, a.id;

        alter table comments add column edited_at text
        """,
        13 to """
        create table comment_folds (
            user_id integer not null references users(id) on delete cascade,
            comment_id integer not null references comments(id) on delete cascade,
            primary key (user_id, comment_id)
        );
        create index ix_comment_folds_comment on comment_folds(comment_id)
        """,
        14 to """
        drop index if exists ux_users_username;
        create unique index ux_users_username on users(lower(username)) where active = 1
        """,
        15 to """
        create table req_items (
            id integer primary key autoincrement,
            requirement_id integer not null references requirements(id) on delete cascade,
            position integer not null,
            body text not null,
            done_at text,
            tested_at text,
            created_at text not null
        );
        create index ix_req_items_req on req_items(requirement_id, position)
        """,
        16 to """
        alter table attachments add column item_id integer references req_items(id) on delete cascade;
        create index ix_attachments_item on attachments(item_id)
        """,
        17 to """
        create table item_folds (
            user_id integer not null references users(id) on delete cascade,
            requirement_id integer not null references requirements(id) on delete cascade,
            primary key (user_id, requirement_id)
        );
        create index ix_item_folds_req on item_folds(requirement_id)
        """,
        18 to """
        create temp table v18_move as
            select a.id as att_id,
                   (select min(b.id) from attachments b
                     where b.requirement_id = a.requirement_id and b.user_id = a.user_id and b.created_at = a.created_at
                       and b.comment_id is null and b.item_id is null) as grp
            from attachments a
            where a.requirement_id is not null and a.comment_id is null and a.item_id is null;

        insert into comments(requirement_id, user_id, body, created_at)
            select a.requirement_id, a.user_id, '__hub_v18_att__' || a.id, a.created_at
            from attachments a
            where a.id in (select grp from v18_move)
            order by a.id;

        update attachments
            set comment_id = (select cm.id from comments cm where cm.body = '__hub_v18_att__' || (select m.grp from v18_move m where m.att_id = attachments.id)),
                requirement_id = null
            where id in (select att_id from v18_move);

        update comments set body = '' where substr(body, 1, 15) = '__hub_v18_att__';

        drop table v18_move;

        update requirements set body = null
        """,
        19 to """
        alter table req_version_files add column purged_at text
        """,
        20 to """
        create table builds (
            id integer primary key autoincrement,
            project_id integer not null references projects(id) on delete cascade,
            seq integer not null,
            created_by integer not null references users(id),
            created_at text not null
        );
        create unique index ux_builds_seq on builds(project_id, seq);

        create table build_files (
            id integer primary key autoincrement,
            build_id integer not null references builds(id) on delete cascade,
            original_name text not null,
            stored_name text not null,
            size_bytes integer not null,
            content_type text,
            created_at text not null,
            purged_at text
        );
        create index ix_build_files_build on build_files(build_id);
        create index ix_build_files_stored on build_files(stored_name);

        create table build_requirements (
            build_id integer not null references builds(id) on delete cascade,
            requirement_id integer not null references requirements(id) on delete cascade,
            primary key (build_id, requirement_id)
        );
        create index ix_build_req_req on build_requirements(requirement_id);

        insert into builds(id, project_id, seq, created_by, created_at)
            select v.id, r.project_id,
                   row_number() over (partition by r.project_id order by v.created_at, v.id),
                   v.created_by, v.created_at
            from req_versions v join requirements r on r.id = v.requirement_id;

        insert into build_requirements(build_id, requirement_id)
            select id, requirement_id from req_versions;

        insert into build_files(id, build_id, original_name, stored_name, size_bytes, content_type, created_at, purged_at)
            select id, version_id, original_name, stored_name, size_bytes, content_type, created_at, purged_at
            from req_version_files
            order by id;

        drop table req_version_files;
        drop table req_versions;

        alter table projects add column build_seq integer not null default 0;
        update projects set build_seq = coalesce((select max(b.seq) from builds b where b.project_id = projects.id), 0);

        alter table requirements add column accepted_build integer
        """
    )

    fun migrate() {
        Db.read { c ->
            c.exec("create table if not exists schema_version (version integer primary key, applied_at text not null)")
            val current = c.count("select coalesce(max(version), 0) from schema_version")
            for ((version, sql) in migrations) {
                if (version <= current) continue
                runMigration(c, version, sql)
            }
        }
    }

    private fun pragma(c: Connection, sql: String) {
        c.createStatement().use { it.execute(sql) }
    }

    private fun runMigration(c: Connection, version: Int, sql: String) {
        pragma(c, "PRAGMA foreign_keys = OFF")
        try {
            c.autoCommit = false
            try {
                applyScript(c, sql)
                val broken = c.rows("PRAGMA foreign_key_check") { rs -> rs.getString(1) + "#" + rs.getLong(2) }
                if (broken.isNotEmpty()) {
                    throw IllegalStateException("迁移 v$version 后外键校验失败: " + broken.take(5).joinToString(", "))
                }
                c.exec("insert into schema_version(version, applied_at) values(?, ?)", version, Instant.now().toString())
                c.commit()
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            } finally {
                c.autoCommit = true
            }
        } finally {
            pragma(c, "PRAGMA foreign_keys = ON")
        }
    }

    private fun applyScript(c: Connection, script: String) {
        c.createStatement().use { st ->
            script.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { st.execute(it) }
        }
    }
}
