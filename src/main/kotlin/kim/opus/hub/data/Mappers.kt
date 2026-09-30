package kim.opus.hub.data

import kim.opus.hub.model.*
import java.sql.ResultSet

internal fun mapUser(rs: ResultSet) = User(
    id = rs.getLong("id"),
    username = rs.getString("username"),
    displayName = rs.getString("display_name"),
    role = rs.getString("role"),
    active = rs.getInt("active") == 1,
    createdAt = rs.getString("created_at")
)

internal fun mapProject(rs: ResultSet) = Project(
    id = rs.getLong("id"),
    name = rs.getString("name"),
    createdAt = rs.getString("created_at")
)

internal fun mapRequirement(rs: ResultSet) = Requirement(
    id = rs.getLong("id"),
    projectId = rs.getLong("project_id"),
    seq = rs.getInt("seq"),
    title = rs.getString("title"),
    body = rs.getString("body"),
    status = rs.getString("status"),
    priority = rs.getInt("priority"),
    createdBy = rs.getLong("created_by"),
    createdAt = rs.getString("created_at"),
    updatedAt = rs.getString("updated_at"),
    closedAt = rs.getString("closed_at"),
    wantedAt = rs.getString("wanted_at")
)

internal fun mapItem(rs: ResultSet) = ReqItem(
    id = rs.getLong("id"),
    requirementId = rs.getLong("requirement_id"),
    position = rs.getInt("position"),
    body = rs.getString("body"),
    doneAt = rs.getString("done_at"),
    testedAt = rs.getString("tested_at")
)

internal fun mapAttachment(rs: ResultSet) = Attachment(
    id = rs.getLong("id"),
    requirementId = rs.longOrNull("requirement_id"),
    commentId = rs.longOrNull("comment_id"),
    itemId = rs.longOrNull("item_id"),
    userId = rs.getLong("user_id"),
    originalName = rs.getString("original_name"),
    storedName = rs.getString("stored_name"),
    sizeBytes = rs.getLong("size_bytes"),
    contentType = rs.getString("content_type"),
    createdAt = rs.getString("created_at")
)

internal fun mapComment(rs: ResultSet) = Comment(
    id = rs.getLong("id"),
    requirementId = rs.getLong("requirement_id"),
    userId = rs.getLong("user_id"),
    authorName = rs.getString("author_name"),
    body = rs.getString("body").orEmpty(),
    createdAt = rs.getString("created_at"),
    editedAt = rs.getString("edited_at"),
    attachments = emptyList()
)

internal fun mapVersionFile(rs: ResultSet) = VersionFile(
    id = rs.getLong("id"),
    versionId = rs.getLong("version_id"),
    originalName = rs.getString("original_name"),
    storedName = rs.getString("stored_name"),
    sizeBytes = rs.getLong("size_bytes"),
    contentType = rs.getString("content_type"),
    createdAt = rs.getString("created_at")
)

internal fun mapVersion(rs: ResultSet) = ReqVersion(
    id = rs.getLong("id"),
    requirementId = rs.getLong("requirement_id"),
    seq = rs.getInt("seq"),
    createdAt = rs.getString("created_at"),
    files = emptyList()
)
