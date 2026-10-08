package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenus
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class DungeonBestiaryMenus(
    private val dungeon: EMDungeonQol,
    private val entries: (String) -> List<BestiaryMob>,
    private val loadProgress: (UUID) -> CompletableFuture<Set<String>>,
    private val tasks: LifecycleTaskScope,
    private val show: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit) -> Unit = { player, screen, reopen, onDismiss ->
        val close = PaperDialogButton(
            PaperDialogActionId.of("close"), dungeon.text("bestiary.close-label", "<white>Закрыть"),
            width = 200, closeDialogBeforeAction = true, onClick = {},
        )
        val prepared = if (screen.exitButton?.id?.value == "close" && !MenuEscapeBehavior.goesBack(player)) {
            screen.copy(exitButton = null)
        } else screen
        ArcMenus.openDialog(player, prepared, close, reopen, onDismiss)
    },
) {
    private class Request

    private enum class Section(val id: String, val label: String, val key: String) {
        ABILITIES("abilities", "Способности", "abilities"),
        LOOT("loot", "Добыча", "loot"),
        NOTES("notes", "Примечания", "notes"),
    }

    // All reads and writes happen on Paper's main thread; completion callbacks are marshalled by tasks.
    private val pending = mutableMapOf<UUID, Request>()

    fun open(player: Player, contentId: String, dungeonName: String, back: () -> Unit) {
        val mobs = entries(contentId).distinctBy(BestiaryMob::id)
        load(player, contentId, dungeonName, mobs, back)
    }

    private fun load(
        player: Player,
        contentId: String,
        dungeonName: String,
        mobs: List<BestiaryMob>,
        back: () -> Unit,
    ) {
        val playerId = player.uniqueId
        val request = Request()
        pending[playerId] = request
        val token = tasks.token()
        showCatalogLoading(player, dungeonName, back, onDismiss = { cancel(playerId, request) })

        val future = try {
            loadProgress(playerId)
        } catch (_: Exception) {
            showCatalogError(player, contentId, dungeonName, mobs, back, request)
            return
        }
        future.whenComplete { unlocked, failure ->
            tasks.runSync(token) {
                if (pending[playerId] !== request) return@runSync
                pending.remove(playerId)
                if (!player.isOnline) return@runSync
                if (failure != null || unlocked == null) {
                    showCatalogError(player, contentId, dungeonName, mobs, back, request)
                } else {
                    catalog(player, contentId, dungeonName, mobs, unlocked, 0, back)
                }
            }
        }
    }

    private fun showCatalogLoading(player: Player, dungeonName: String, back: () -> Unit, onDismiss: () -> Unit) {
        show(
            player,
            PaperDialogScreen(
                id = CATALOG_SCREEN,
                title = text("loading-title", "<#ffb277>Бестиарий данжа"),
                body = listOf(PaperDialogBody(text("loading", "<#e8dfd2>Загружаем открытия в данже «<name>»…",
                    "name" to plain(dungeonName)), 468)),
                buttons = emptyList(),
                exitButton = exit(back),
                columns = 2,
            ),
            null,
            onDismiss,
        )
    }

    private fun showCatalogError(
        player: Player,
        contentId: String,
        dungeonName: String,
        catalog: List<BestiaryMob>,
        back: () -> Unit,
        request: Request,
    ) {
        show(
            player,
            PaperDialogScreen(
                id = CATALOG_SCREEN,
                title = text("title", "<#ffb277>Бестиарий данжа"),
                body = listOf(PaperDialogBody(text("load-error", "<#e8dfd2>Не удалось загрузить открытия. Попробуйте ещё раз."), 468)),
                buttons = listOf(button("retry", "retry-label", "Повторить загрузку", "Заново получить список изученных противников") {
                    load(player, contentId, dungeonName, catalog, back)
                }),
                exitButton = exit(back),
                columns = 2,
            ),
            { load(player, contentId, dungeonName, catalog, back) },
            { cancel(player.uniqueId, request) },
        )
    }

    private fun catalog(
        player: Player,
        contentId: String,
        dungeonName: String,
        mobs: List<BestiaryMob>,
        unlockedIds: Set<String>,
        requestedPage: Int,
        back: () -> Unit,
    ) {
        val orderedMobs = orderDungeonBestiaryEntries(mobs, unlockedIds)
        val unlockedMobs = orderedMobs.filter { it.id in unlockedIds }
        val unlocked = unlockedMobs.size
        val pages = pageCount(unlocked, CATALOG_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val pageMobs = unlockedMobs.drop(page * CATALOG_PAGE_SIZE).take(CATALOG_PAGE_SIZE)
        val body = mutableListOf(
            PaperDialogBody(text("intro", "<#e8dfd2><name> · изучайте противников после первой победы над ними в этом данже.",
                "name" to plain(dungeonName)), 468),
            DialogTables.body(
                listOf(
                    text("progress-label", "<#e8dfd2>Изучено") to text("progress-value", "<#ffb277><unlocked> / <total>",
                        "unlocked" to Component.text(unlocked), "total" to Component.text(orderedMobs.size)),
                    text("page-label", "<#e8dfd2>Страница") to text("page-value", "<#e8dfd2><current> / <total>",
                        "current" to Component.text(page + 1), "total" to Component.text(pages)),
                ),
                frame = DialogTables.Frame.ARTIFACT,
                width = 320,
                columns = DialogTables.Columns.BALANCED,
            ),
        )
        if (orderedMobs.isEmpty()) body += PaperDialogBody(text("catalog-empty", "<#e8dfd2>В этом данже пока нет записей о противниках."), 468)
        else if (unlockedMobs.isEmpty()) body += PaperDialogBody(
            text("catalog-first-victory", "<#e8dfd2>Одержите первую победу над противником в этом данже, чтобы открыть его запись и увидеть способности и добычу."),
            468,
        )

        val cards = pageMobs.mapIndexed { index, mob ->
            val catalogIndex = page * CATALOG_PAGE_SIZE + index
            PaperDialogButton(
                PaperDialogActionId.of("mob_$catalogIndex"),
                text("mob-label", "<white><name> <#ffb277>· <kind> ›",
                    "name" to plain(shortLabel(mob.name)), "kind" to plain(shortLabel(mob.kind))),
                text("mob-label-tooltip", "<#e8dfd2>Открыть сведения о противнике: <name>",
                    "name" to plain(mob.name)),
                width = 230,
                onClick = {
                    val initialSection = availableSections(mob).firstOrNull() ?: Section.ABILITIES
                    detail(player, contentId, dungeonName, orderedMobs, unlockedIds, mob, initialSection, 0, page, back)
                },
            )
        }.toMutableList()
        if (pages > 1) {
            cards += button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Показать предыдущих противников") {
                catalog(player, contentId, dungeonName, mobs, unlockedIds, (page - 1 + pages) % pages, back)
            }
            cards += button("next", "next-label", "<#92bed8>Следующая страница ›", "Показать следующих противников") {
                catalog(player, contentId, dungeonName, mobs, unlockedIds, (page + 1) % pages, back)
            }
        }
        show(
            player,
            PaperDialogScreen(
                id = CATALOG_SCREEN,
                title = text("title", "<#ffb277>Бестиарий данжа"),
                body = body,
                buttons = cards,
                exitButton = exit(back),
                columns = 2,
            ),
            { load(player, contentId, dungeonName, orderedMobs, back) },
            {},
        )
    }

    private fun detail(
        player: Player,
        contentId: String,
        dungeonName: String,
        mobs: List<BestiaryMob>,
        unlockedIds: Set<String>,
        mob: BestiaryMob,
        section: Section,
        requestedPage: Int,
        catalogPage: Int,
        back: () -> Unit,
    ) {
        // Re-check the unlock before rendering so a stale catalog callback cannot reveal a locked entry.
        if (mobs.none { it.id == mob.id } || mob.id !in unlockedIds) {
            catalog(player, contentId, dungeonName, mobs, unlockedIds, 0, back)
            return
        }

        val sections = availableSections(mob)
        val activeSection = section.takeIf { it in sections } ?: sections.firstOrNull() ?: Section.ABILITIES
        val facts = factsFor(mob, activeSection)
        val pages = pageCount(facts.size, FACT_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val pageFacts = facts.drop(page * FACT_PAGE_SIZE).take(FACT_PAGE_SIZE)
        val body = mutableListOf(
            DialogTables.body(
                listOf(
                    text("kind-label", "<#e8dfd2>Тип") to plain(mob.kind),
                    text("level-label", "<#e8dfd2>Уровень") to plain(mob.level),
                ),
                frame = DialogTables.Frame.ARTIFACT,
                width = 320,
                columns = DialogTables.Columns.BALANCED,
            ),
        )
        if (isShortened(mob.name, TITLE_NAME_LIMIT)) body += PaperDialogBody(plain(compactText(mob.name)), 468)
        if (sections.isEmpty()) {
            body += PaperDialogBody(text("facts-none", "<#e8dfd2>Дополнительных сведений пока нет."), 468)
        } else {
            body += PaperDialogBody(text("section-title", "<#ffb277><section>",
                "section" to text("section.${activeSection.key}-label", activeSection.label)), 320)
            pageFacts.forEach { fact -> body += factBody(fact) }
            if (pages > 1) body += PaperDialogBody(text("page-status", "<#e8dfd2>Страница <current> / <total>",
                "current" to Component.text(page + 1), "total" to Component.text(pages)), 320)
        }

        val detailColumns = sections.size.coerceIn(2, 3)
        val detailButtonWidth = if (detailColumns == 2) 230 else 150
        val tabButtons: MutableList<PaperDialogButton> = if (sections.size > 1) sections.map { tab ->
            val selected = tab == activeSection
            val marker = if (selected) "<#9bd48d>✔" else "<white>○"
            button("section_${tab.id}", "section-${tab.key}-${if (selected) "selected" else "available"}",
                "$marker ${tab.label}", "Показать раздел «${tab.label.lowercase()}»", width = detailButtonWidth) {
                detail(player, contentId, dungeonName, mobs, unlockedIds, mob, tab, 0, catalogPage, back)
            }
        }.toMutableList() else mutableListOf()
        if (pages > 1) {
            tabButtons += button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Показать предыдущие сведения", width = detailButtonWidth) {
                detail(player, contentId, dungeonName, mobs, unlockedIds, mob, activeSection, (page - 1 + pages) % pages, catalogPage, back)
            }
            tabButtons += button("next", "next-label", "<#92bed8>Следующая страница ›", "Показать следующие сведения", width = detailButtonWidth) {
                detail(player, contentId, dungeonName, mobs, unlockedIds, mob, activeSection, (page + 1) % pages, catalogPage, back)
            }
        }

        show(
            player,
            PaperDialogScreen(
                id = DETAIL_SCREEN,
                title = text("detail-title", "<#ffb277><name>", "name" to plain(shortLabel(mob.name, TITLE_NAME_LIMIT))),
                body = body,
                buttons = tabButtons,
                exitButton = exit { catalog(player, contentId, dungeonName, mobs, unlockedIds, catalogPage, back) },
                columns = detailColumns,
            ),
            { detail(player, contentId, dungeonName, mobs, unlockedIds, mob, activeSection, page, catalogPage, back) },
            {},
        )
    }

    private fun cancel(playerId: UUID, request: Request) {
        if (pending[playerId] === request) pending.remove(playerId)
    }

    private fun pageCount(size: Int, pageSize: Int): Int = ((size + pageSize - 1) / pageSize).coerceAtLeast(1)

    private fun availableSections(mob: BestiaryMob): List<Section> =
        Section.values().filter { factsFor(mob, it).isNotEmpty() }

    private fun factsFor(mob: BestiaryMob, section: Section): List<BestiaryFact> = when (section) {
        Section.ABILITIES -> mob.abilities
        Section.LOOT -> mob.loot
        Section.NOTES -> mob.notes.mapIndexed { index, note -> BestiaryFact("Примечание ${index + 1}", note) }
    }

    private fun factBody(fact: BestiaryFact) = PaperDialogBody(
        Component.text(fact.name, TextColor.color(0xffb277))
            .decoration(TextDecoration.ITALIC, false)
            .append(Component.newline())
            .append(plain(fact.description)),
        468,
    )

    private fun shortLabel(value: String, limit: Int = LABEL_LIMIT): String {
        val compact = compactText(value)
        val count = compact.codePointCount(0, compact.length)
        if (count <= limit) return compact.ifBlank { "—" }
        return compact.substring(0, compact.offsetByCodePoints(0, limit - 1)) + "…"
    }

    private fun isShortened(value: String, limit: Int): Boolean {
        val compact = compactText(value)
        return compact.codePointCount(0, compact.length) > limit
    }

    private fun compactText(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()

    private fun plain(value: String): Component = Component.text(value, TextColor.color(0xe8dfd2))
        .decoration(TextDecoration.ITALIC, false)

    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>) =
        dungeon.text("bestiary.$key", fallback, *values).decoration(TextDecoration.ITALIC, false)

    private fun exit(action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of("back"), text("back-label", "<white>‹ Назад"), width = 200, onClick = { action() },
    )

    private fun button(
        id: String,
        key: String,
        fallback: String,
        tooltip: String,
        vararg values: Pair<String, Component>,
        width: Int = 230,
        action: () -> Unit,
    ) = PaperDialogButton(
        PaperDialogActionId.of(id),
        text(key, fallback, *values),
        text("$key-tooltip", "<#e8dfd2>$tooltip"),
        width = width,
        onClick = { action() },
    )

    private companion object {
        const val CATALOG_SCREEN = "dungeon.bestiary.catalog"
        const val DETAIL_SCREEN = "dungeon.bestiary.detail"
        const val CATALOG_PAGE_SIZE = 12
        const val FACT_PAGE_SIZE = 6
        const val LABEL_LIMIT = 48
        const val TITLE_NAME_LIMIT = 72
    }
}

internal fun orderDungeonBestiaryEntries(mobs: List<BestiaryMob>, unlockedIds: Set<String>): List<BestiaryMob> =
    mobs.sortedWith(
        compareBy<BestiaryMob> { it.id !in unlockedIds }
            .thenBy { it.name.lowercase(Locale.ROOT).replace('ё', 'е') }
            .thenBy(BestiaryMob::id),
    )
