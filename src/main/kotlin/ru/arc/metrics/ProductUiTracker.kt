package ru.arc.metrics

/** Main-thread UI visit state. A rendered button is exposed once per visit, even across refreshes/pages. */
internal class ProductUiTracker(private val record: (String, ProductUiKind, ProductUiView, String, Long, Long) -> Unit) {
    private data class Visit(val id: String, var view: ProductUiView, val started: Long,
        val impressed: MutableSet<String> = linkedSetOf(), var selected: Boolean = false)
    private val visits = linkedMapOf<String, Visit>()

    fun open(player: String, id: String, view: ProductUiView, now: Long) {
        val current = visits[player]
        if (current?.id == id) { render(player, id, view, now); return }
        if (current != null) close(player, current.id, now, censored = true)
        val visit = Visit(id, view, now)
        visits[player] = visit
        record(player, ProductUiKind.OPEN, view, "_menu", 0, now)
        impressions(player, visit, view, now)
    }

    fun render(player: String, id: String, view: ProductUiView, now: Long) {
        val current = visits[player]?.takeIf { it.id == id } ?: return
        current.view = view
        impressions(player, current, view, now)
    }

    private fun impressions(player: String, visit: Visit, view: ProductUiView, now: Long) {
        view.buttons.forEach { (button, _) ->
            if (visit.impressed.size < 128 && visit.impressed.add(button))
                record(player, ProductUiKind.IMPRESSION, view, button, 0, now)
        }
    }

    fun click(player: String, id: String, _view: ProductUiView, button: String, accepted: Boolean, now: Long) {
        val visit = visits[player]?.takeIf { it.id == id } ?: return
        if (!ProductUiCodec.ID.matches(button)) return
        visit.selected = true
        if (accepted) {
            // Event order/adapter gaps must not invent an impression. Coverage remains visible as click > impression.
            record(player, ProductUiKind.CLICK, visit.view, button, 0, now)
        } else record(player, ProductUiKind.BLOCKED, visit.view, button, 0, now)
    }

    fun attempt(player: String, id: String, _view: ProductUiView, button: String, now: Long) {
        val visit = visits[player]?.takeIf { it.id == id } ?: return
        if (!ProductUiCodec.ID.matches(button)) return
        visit.selected = true
        record(player, ProductUiKind.ATTEMPT, visit.view, button, 0, now)
    }

    fun close(player: String, id: String?, now: Long, censored: Boolean) {
        val visit = visits[player]?.takeIf { id == null || it.id == id } ?: return
        visits.remove(player)
        record(player, if (censored) ProductUiKind.CENSORED else ProductUiKind.CLOSE,
            visit.view, "_menu", if (censored) 0 else (now - visit.started).coerceIn(0, 86_400_000), now)
        if (!censored && !visit.selected) record(player, ProductUiKind.NO_CHOICE, visit.view, "_menu", 0, now)
    }

    fun shutdown(now: Long) = visits.keys.toList().forEach { close(it, null, now, censored = true) }
}
