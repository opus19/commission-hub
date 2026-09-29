package kim.opus.hub.model

const val ROLE_ADMIN = "admin"
const val ROLE_CLIENT = "client"

enum class ReqStatus(val code: String, val label: String, val icon: String) {
    TODO("todo", "待开发", "bi-record-circle"),
    TESTING("testing", "待测试", "bi-hourglass-split"),
    ARCHIVED("archived", "已归档", "bi-archive");

    companion object {
        fun of(code: String?): ReqStatus = entries.firstOrNull { it.code == code } ?: TODO
    }
}

enum class Priority(val level: Int, val label: String, val longLabel: String, val icon: String) {
    HIGH(1, "高", "高优先级", "bi-reception-3"),
    NORMAL(2, "中", "中优先级", "bi-reception-2"),
    LOW(3, "低", "低优先级", "bi-reception-1");

    companion object {
        fun of(level: Int): Priority = entries.firstOrNull { it.level == level } ?: NORMAL
    }
}
