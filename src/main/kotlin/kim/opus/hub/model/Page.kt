package kim.opus.hub.model

data class Page<T>(val items: List<T>, val total: Long, val page: Int, val pageSize: Int) {
    val pages: Int get() = if (total <= 0) 1 else ((total + pageSize - 1) / pageSize).toInt()
}
