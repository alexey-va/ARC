package ru.arc.metrics

import java.util.UUID

internal data class ProductMenuEvent(
    val player: String,
    val visit: String,
    val surface: String,
    val event: String,
    val at: Long,
    val actionId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

/** Main-thread UI visit state shared by durable menu history and product counters. */
internal class ProductUiTracker(
    private val menu: (ProductMenuEvent) -> Unit = {},
    private val record: (String, String, ProductUiKind, ProductUiView, String, Long, Long) -> Unit,
) {
    private data class Visit(val id: String, var view: ProductUiView, val started: Long,
        val impressed: MutableSet<String> = linkedSetOf(), var selected: Boolean = false, var frame: Int = 0)
    private val visits = linkedMapOf<String, Visit>()

    fun open(player: String, id: String, view: ProductUiView, now: Long) {
        val current = visits[player]
        if (current?.id == id) { render(player, id, view, now); return }
        if (current != null) close(player, current.id, now, censored = true, reason = "replaced")
        val visit = Visit(id, view, now)
        visits[player] = visit
        screen(player, visit, now, current?.id)
        record(player, id, ProductUiKind.OPEN, view, "_menu", 0, now)
        impressions(player, visit, view, now)
    }

    fun render(player: String, id: String, view: ProductUiView, now: Long) {
        val current = visits[player]?.takeIf { it.id == id } ?: return
        val changed = current.view.revision != view.revision || current.view.buttons != view.buttons ||
            current.view.visibleSlots != view.visibleSlots ||
            SCREEN_FIELDS.any { current.view.details[it] != view.details[it] }
        current.view = view
        if (changed) {
            current.frame++
            screen(player, current, now)
        }
        impressions(player, current, view, now)
    }

    private fun screen(player: String, visit: Visit, now: Long, previous: String? = null) {
        val view = visit.view
        val buttons = view.visibleSlots?.entries?.sortedBy { it.key }?.joinToString(",") { (slot, key) -> "$key=$slot" }
            ?: view.buttons.entries.sortedWith(compareBy({ it.value.slot }, { it.key }))
                .joinToString(",") { (key, button) -> "$key=${button.slot}" }
        val details = buildMap {
            putAll(view.details.filterKeys { it in SCREEN_FIELDS })
            put("revision", view.revision)
            put("frame", visit.frame.toString())
            put("buttons", buttons)
            previous?.let { put("previousVisitId", it) }
        }
        menu(ProductMenuEvent(player, visit.id, view.surface, "menu.view", now, view.details["triggerAttemptId"], details))
    }

    private fun impressions(player: String, visit: Visit, view: ProductUiView, now: Long) {
        view.buttons.forEach { (button, _) ->
            if (visit.impressed.size < 128 && visit.impressed.add(button))
                record(player, visit.id, ProductUiKind.IMPRESSION, view, button, 0, now)
        }
    }

    fun click(
        player: String, id: String, view: ProductUiView, button: String, accepted: Boolean, now: Long,
        actionId: String? = null,
    ) = action(player, id, view, button, if (accepted) "accepted" else "blocked",
        if (accepted) ProductUiKind.CLICK else ProductUiKind.BLOCKED, now, actionId)

    fun attempt(player: String, id: String, view: ProductUiView, button: String, now: Long) =
        action(player, id, view, button, "attempt_only", ProductUiKind.ATTEMPT, now)

    private fun action(
        player: String, id: String, view: ProductUiView, button: String, admission: String,
        kind: ProductUiKind, now: Long, actionId: String? = null,
    ) {
        val visit = visits[player]?.takeIf { it.id == id } ?: return
        if (!ProductUiCodec.ID.matches(button)) return
        visit.selected = true
        val details = (view.details - "disabledSlots") + mapOf(
            "button" to button, "admission" to admission, "frame" to visit.frame.toString(),
        ) + ((view.details["rawSlot"] ?: visit.view.buttons[button]?.slot?.toString())
            ?.let { mapOf("slot" to it) } ?: emptyMap())
        menu(ProductMenuEvent(player, id, visit.view.surface, "menu.action", now,
            actionId ?: UUID.randomUUID().toString(), details))
        record(player, id, kind, visit.view.copy(details = view.details), button, 0, now)
    }

    fun close(player: String, id: String?, now: Long, censored: Boolean, reason: String = if (censored) "unknown" else "user") {
        val visit = visits[player]?.takeIf { id == null || it.id == id } ?: return
        visits.remove(player)
        val elapsed = (now - visit.started).coerceIn(0, 86_400_000)
        menu(ProductMenuEvent(player, visit.id, visit.view.surface, "menu.end", now, details = mapOf(
            "reason" to reason, "durationMs" to elapsed.toString(), "attempted" to visit.selected.toString(),
        )))
        record(player, visit.id, if (censored) ProductUiKind.CENSORED else ProductUiKind.CLOSE,
            visit.view, "_menu", if (censored) 0 else elapsed, now)
        if (!censored && !visit.selected) record(player, visit.id, ProductUiKind.NO_CHOICE, visit.view, "_menu", 0, now)
    }

    fun shutdown(now: Long, reason: String) = visits.keys.toList().forEach { close(it, null, now, censored = true, reason = reason) }

    private companion object {
        val SCREEN_FIELDS = setOf("pageIndex", "pageCount", "state", "target", "revisionBasis", "disabledSlots")
    }
}
