package kim.opus.hub.web

import io.javalin.http.Context
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*

object ListParams {

    fun parse(ctx: Context, user: User, projectId: Long): ReqQuery = ReqQuery(
        memberId = if (user.isAdmin) null else user.id,
        projectId = projectId,
        tab = ReqTabs.normalize(ctx.queryParam("tab")),
        order = ReqOrders.normalize(ctx.queryParam("order")),
        page = ctx.queryParam("page")?.toIntOrNull() ?: 1
    )

    fun href(
        base: String,
        q: ReqQuery,
        tab: String = q.tab,
        order: String = q.order,
        page: Int = 1
    ): String = base + qs(
        "tab" to (if (tab == ReqTabs.DEFAULT) null else tab),
        "order" to (if (order == ReqOrders.DEFAULT) null else order),
        "page" to (if (page > 1) page else null)
    )

    fun spec(base: String, heading: String, q: ReqQuery, emptyText: String): ListSpec = ListSpec(
        heading = heading,
        result = ReqRepo.search(q),
        counts = ReqRepo.tabCounts(q),
        tab = q.tab,
        order = q.order,
        orders = ReqOrders.options,
        tabHref = { key -> href(base, q, tab = key) },
        orderHref = { key -> href(base, q, order = key) },
        pageHref = { n -> href(base, q, page = n) },
        emptyText = emptyText
    )
}
