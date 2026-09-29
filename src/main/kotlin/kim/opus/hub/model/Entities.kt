package kim.opus.hub.model

data class User(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val active: Boolean,
    val createdAt: String
) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN
}

data class Project(
    val id: Long,
    val name: String,
    val status: String,
    val createdAt: String
) {
    val statusEnum: ProjectStatus get() = ProjectStatus.of(status)
    val isArchived: Boolean get() = statusEnum == ProjectStatus.ARCHIVED
}

data class ProjectView(
    val project: Project,
    val todoCount: Long,
    val testingCount: Long,
    val archivedCount: Long,
    val lastActivity: String
)

data class Requirement(
    val id: Long,
    val projectId: Long,
    val seq: Int,
    val title: String,
    val body: String?,
    val status: String,
    val priority: Int,
    val createdBy: Long,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String?,
    val wantedAt: String?
) {
    val statusEnum: ReqStatus get() = ReqStatus.of(status)
    val priorityEnum: Priority get() = Priority.of(priority)
    val wantedText: String? get() = Wanted.full(wantedAt)
    val isOverdue: Boolean get() = Wanted.overdue(wantedAt, statusEnum)
}

data class RequirementView(
    val requirement: Requirement,
    val attachmentCount: Long,
    val commentCount: Long,
    val projectName: String,
    val projectStatus: String
) {
    val projectArchived: Boolean get() = ProjectStatus.of(projectStatus) == ProjectStatus.ARCHIVED
    val readOnly: Boolean get() = projectArchived || requirement.statusEnum == ReqStatus.ARCHIVED
}

data class Comment(
    val id: Long,
    val requirementId: Long,
    val userId: Long,
    val authorName: String,
    val body: String,
    val createdAt: String,
    val editedAt: String?,
    val attachments: List<Attachment>
)

data class NewFile(
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val contentType: String?
)

data class VersionFile(
    val id: Long,
    val versionId: Long,
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val contentType: String?,
    val createdAt: String
)

data class ReqVersion(
    val id: Long,
    val requirementId: Long,
    val seq: Int,
    val createdAt: String,
    val files: List<VersionFile>
)

data class Attachment(
    val id: Long,
    val requirementId: Long?,
    val commentId: Long?,
    val userId: Long,
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val contentType: String?,
    val createdAt: String
)
