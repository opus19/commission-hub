package kim.opus.hub.web

import io.javalin.http.Context
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object ListParams {

    class State(val query: ReqQuery, val counts: Map<String, Long>, val defaultTab: String)

    fun parse(ctx: Context, user: User, projectId: Long): State {
        val scope = ReqQuery(memberId = if (user.isAdmin) null else user.id, projectId = projectId)
        val counts = ReqRepo.tabCounts(scope)
        val defaultTab = ReqTabs.defaultFor(counts)
        val query = scope.copy(
            tab = ReqTabs.normalize(ctx.queryParam("tab"), defaultTab),
            order = ReqOrders.normalize(ctx.queryParam("order")),
            page = ctx.queryParam("page")?.toIntOrNull() ?: 1
        )
        return State(query, counts, defaultTab)
    }

    fun href(
        base: String,
        s: State,
        tab: String = s.query.tab,
        order: String = s.query.order,
        page: Int = 1
    ): String = base + qs(
        "tab" to (if (tab == s.defaultTab) null else tab),
        "order" to (if (order == ReqOrders.DEFAULT) null else order),
        "page" to (if (page > 1) page else null)
    )

    fun spec(base: String, heading: String, s: State): ListSpec = ListSpec(
        heading = heading,
        result = ReqRepo.search(s.query),
        counts = s.counts,
        tab = s.query.tab,
        order = s.query.order,
        orders = ReqOrders.options,
        tabHref = { key -> href(base, s, tab = key) },
        orderHref = { key -> href(base, s, order = key) },
        pageHref = { n -> href(base, s, page = n) },
        emptyText = ReqTabs.emptyText(s.query.tab)
    )
}
