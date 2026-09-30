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
    val createdAt: String
)

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
    val isOverdue: Boolean get() = Wanted.overdue(wantedAt, statusEnum)
}

data class ReqItem(
    val id: Long,
    val requirementId: Long,
    val position: Int,
    val body: String,
    val doneAt: String?,
    val testedAt: String?
) {
    val done: Boolean get() = doneAt != null
    val tested: Boolean get() = testedAt != null
}

data class ItemInput(val id: Long?, val body: String)

data class RequirementView(
    val requirement: Requirement,
    val projectName: String
) {
    val readOnly: Boolean get() = requirement.statusEnum == ReqStatus.ARCHIVED
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

data class FileChanges(
    val body: List<NewFile>,
    val items: List<List<NewFile>>,
    val removed: Set<Long>
) {
    val stored: List<NewFile> get() = body + items.flatten()
}

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
    val itemId: Long?,
    val userId: Long,
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val contentType: String?,
    val createdAt: String
)
