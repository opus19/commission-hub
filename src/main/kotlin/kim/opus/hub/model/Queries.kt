package kim.opus.hub.model

object ReqTabs {
    const val DEFAULT = "todo"
    val keys: List<String> = listOf("todo", "testing", "archived")

    fun emptyText(key: String): String = "没有" + label(key) + "的需求"

    fun defaultFor(counts: Map<String, Long>): String =
        if ((counts["todo"] ?: 0L) == 0L && (counts["testing"] ?: 0L) > 0L) "testing" else DEFAULT

    fun normalize(key: String?, fallback: String): String = if (key != null && key in keys) key else fallback

    fun label(key: String): String = when (key) {
        "testing" -> "待测试"
        "archived" -> "已归档"
        else -> "待开发"
    }

    fun clause(key: String): String = when (key) {
        "testing" -> "r.status = 'testing'"
        "archived" -> "r.status = 'archived'"
        else -> "r.status = 'todo'"
    }
}

object ReqOrders {
    const val DEFAULT = "priority"

    private val all: List<Pair<String, String>> = listOf(
        "priority" to "优先级",
        "active" to "最近更新",
        "newest" to "最新创建",
        "wanted" to "期望交付时间"
    )

    val options: List<Pair<String, String>> get() = all

    fun normalize(key: String?): String =
        all.firstOrNull { it.first == key }?.first ?: DEFAULT

    fun sql(key: String): String = when (key) {
        "newest" -> "r.created_at desc"
        "priority" -> "r.priority asc, r.updated_at desc"
        "wanted" -> "(r.wanted_at is null) asc, case length(r.wanted_at) when 4 then r.wanted_at || '-99' when 7 then r.wanted_at || '-99' when 10 then r.wanted_at || 'T99' else r.wanted_at end asc, r.updated_at desc"
        else -> "r.updated_at desc"
    }

    fun label(key: String): String = all.firstOrNull { it.first == key }?.second ?: "优先级"
}

data class ReqQuery(
    val memberId: Long? = null,
    val projectId: Long? = null,
    val tab: String = ReqTabs.DEFAULT,
    val order: String = ReqOrders.DEFAULT,
    val page: Int = 1,
    val pageSize: Int = 10
)
