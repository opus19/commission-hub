package kim.opus.hub.web

import io.javalin.http.ForbiddenResponse
import io.javalin.http.NotFoundResponse
import kim.opus.hub.data.*
import kim.opus.hub.model.*

object Access {
    fun canSee(user: User, projectId: Long): Boolean = user.isAdmin || MemberRepo.isMember(projectId, user.id)

    fun project(user: User, projectId: Long): Project {
        val project = ProjectRepo.byId(projectId) ?: throw NotFoundResponse("项目不存在")
        if (!canSee(user, project.id)) throw NotFoundResponse("项目不存在")
        return project
    }

    fun requirement(user: User, requirementId: Long): RequirementView {
        val view = ReqRepo.view(requirementId) ?: throw NotFoundResponse("需求不存在")
        if (!canSee(user, view.requirement.projectId)) throw NotFoundResponse("需求不存在")
        return view
    }

    fun version(user: User, versionId: Long): Pair<ReqVersion, RequirementView> {
        val version = VersionRepo.byId(versionId) ?: throw NotFoundResponse("版本不存在")
        return version to requirement(user, version.requirementId)
    }

    fun versionFile(user: User, fileId: Long): Pair<VersionFile, RequirementView> {
        val file = VersionRepo.fileById(fileId) ?: throw NotFoundResponse("文件不存在")
        val (_, view) = version(user, file.versionId)
        return file to view
    }

    fun comment(user: User, commentId: Long): Pair<Comment, RequirementView> {
        val comment = CommentRepo.byId(commentId) ?: throw NotFoundResponse("补充信息不存在")
        return comment to requirement(user, comment.requirementId)
    }

    fun item(user: User, itemId: Long): Pair<ReqItem, RequirementView> {
        val item = ItemRepo.byId(itemId) ?: throw NotFoundResponse("清单项不存在")
        return item to requirement(user, item.requirementId)
    }

    fun writable(view: RequirementView) {
        if (view.readOnly) throw ForbiddenResponse("需求已归档，现在是只读的")
    }

    fun attachment(user: User, attachmentId: Long): Pair<Attachment, RequirementView> {
        val file = AttachmentRepo.byId(attachmentId) ?: throw NotFoundResponse("附件不存在")
        val reqId = file.requirementId ?: run {
            val commentId = file.commentId ?: throw NotFoundResponse("附件不存在")
            Db.read { c ->
                c.row("select requirement_id from comments where id = ?", commentId) { rs -> rs.getLong(1) }
            } ?: throw NotFoundResponse("附件不存在")
        }
        return file to requirement(user, reqId)
    }
}

object Transitions {
    fun allowed(user: User, view: RequirementView): List<ReqStatus> {
        val current = view.requirement.statusEnum
        return when {
            current == ReqStatus.ARCHIVED -> emptyList()
            user.isAdmin -> ReqStatus.entries.filter { it != current && it != ReqStatus.ARCHIVED }
            current == ReqStatus.TESTING -> listOf(ReqStatus.ARCHIVED)
            else -> emptyList()
        }
    }

    fun check(user: User, view: RequirementView, target: ReqStatus) {
        if (target !in allowed(user, view)) {
            throw ForbiddenResponse("当前状态不允许改成「${target.label}」")
        }
    }
}
