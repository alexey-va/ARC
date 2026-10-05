package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.paper.menu.*
import java.util.Locale
import kotlin.math.ceil

internal data class DungeonCatalogQuery(val search: String = "", val kind: String = "all", val page: Int = 0)

/** The catalog is a view of the installed native registry, never a second maintained list. */
internal fun filterDungeonCatalog(entries: List<DungeonCatalogEntry>, query: DungeonCatalogQuery): List<DungeonCatalogEntry> {
    val words = query.search.lowercase(Locale.ROOT).replace('ё', 'е').split(' ').filter { it.isNotBlank() }
    return entries.filter { entry ->
        val open = entry.type == "OPEN_DUNGEON"
        val kindMatches = query.kind == "all" || (query.kind == "open" && open) || (query.kind == "instanced" && !open)
        val searchable = (listOf(entry.name) + entry.description).map(::plainDungeonQuestText).joinToString(" ")
            .lowercase(Locale.ROOT).replace('ё', 'е')
        kindMatches && words.all(searchable::contains)
    }.sortedWith(compareBy<DungeonCatalogEntry> { it.lowestLevel }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
}

internal class DungeonAdventureMenus(
    private val dungeon: EMDungeonQol,
    private val local: DungeonSaveMenus,
    private val data: DungeonAdventureService,
    private val crystals: (Player) -> String?,
    private val show: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit,
) {
    private val searchInput = PaperDialogInputId.of("dungeon_search")
    private val levelInput = PaperDialogInputId.of("dungeon_level")
    private val pageSize = 10

    fun main(player: Player, feedback: Component? = null) {
        val stats = data.stats(player).orEmpty().associate { it.key to it.value }
        val current = dungeon.panelView(player)
        val rows = listOf(
            label("combatLevel", "Боевой уровень") to value(stats["combatLevel"]),
            label("money", "Кристаллы") to (crystals(player)?.let {
                text("crystals-value", "<white>💎</white> <#c7a0e8><amount>", "amount" to Component.text(it))
            } ?: unavailable()),
            label("activeQuests", "Активные задания") to value(stats["activeQuests"]),
            label("score", "Счёт приключений") to value(stats["score"]),
        )
        val body = mutableListOf(
            PaperDialogBody(text("intro", "<#e8dfd2>Подберите поход или займитесь своим героем. Внутри данжа /данж и Shift + F открывают быстрые действия."), 500),
            table(rows, DialogTables.Frame.EPIC),
        )
        feedback?.let { body += PaperDialogBody(it, 500) }
        val buttons = listOf(
            button("catalog", "catalog-label", "<#ffb277>Каталог данжей ›", "Поиск, уровни и выбор похода") { catalog(player) },
            button("quests", "quests-label", "<#c4abff>Задания ›", "Прогресс, отслеживание и управление заданиями") { local.quests(player) },
            button("party", "party-label", "<#e5ba73>Группа ›", "Собрать группу и пригласить друзей") { local.party(player) },
            button("classes", "classes-label", "<#c4abff>Классы ›", "Выбор класса, способности и ветки развития") { local.classes(player) },
            button("skills", "skills-label", "<#c4abff>Навыки ›", "Уровни оружия и брони, опыт до следующего уровня") { skills(player) },
            button("gear", "gear-label", "<#86dcf1>Снаряжение ›", "Вещи, боевые параметры и выбранные перки") { gear(player) },
            button("stats", "stats-label", "<#86dcf1>Статистика ›", "Рейтинг, пройденные данжи и достижения") { statistics(player) },
            button("bosses", "bosses-label", "<#ffb277>Боссы ›", "Найти живого босса и включить отслеживание") { bosses(player) },
            local.lostLoot(player),
            button("travel", "travel-label", "<#92bed8>Телепорты ›", "Гильдия, магазины, спавн и личный возврат") { travel(player) },
            button("guide", "guide-label", "<white>Гайд ›", "Правила и советы по данжам") {
                if (!HelpCenterModule.openDungeonsGuide(player) { main(player) }) main(player, unavailable())
            },
            button("share", "share-label", "<white>Показать предмет в чате", "Отправить в чат предмет, который вы держите в руке") {
                val held = player.inventory.itemInMainHand
                when {
                    !player.hasPermission("elitemobs.shareitem") -> main(player, text("share-denied", "<#e8dfd2>У вас нет доступа к отправке предметов в чат."))
                    held.type.isAir -> main(player, text("share-empty", "<#e8dfd2>Возьмите предмет в основную руку."))
                    held.itemMeta?.let { it.hasDisplayName() && it.hasLore() } != true ->
                        main(player, text("share-no-description", "<#e8dfd2>У этого предмета нет названия и описания для отправки в чат."))
                    else -> {
                        ArcMenus.closeDialog(player)
                        com.magmaguy.elitemobs.items.ShareItem.showOnChat(player)
                    }
                }
            },
        ).map { it.copy(width = 166) } + listOfNotNull(current?.let {
            button("current", "current-label", "<#ffb277>Текущий данж ›", "Вернуться к быстрым действиям текущего похода") { local.panel(player) }
                .copy(width = 166)
        } ?: if (dungeon.insideInstance(player)) button("quit", "quit-label", "<#e5ba73>Выйти из данжа",
            "Покинуть наблюдение штатным способом", close = true) { dungeon.action(player, "quit") }.copy(width = 166) else null)
        show(player, PaperDialogScreen(id = "dungeon.main", title = text("title", "<#ffb277>Данжи и приключения"),
            body = body, buttons = buttons, exitButton = rootExit(player), columns = 3)) { main(player) }
    }

    fun catalog(player: Player, requested: DungeonCatalogQuery = DungeonCatalogQuery(), feedback: Component? = null) {
        val entries = filterDungeonCatalog(data.catalog(player), requested)
        val pages = ceil(entries.size.toDouble() / pageSize).toInt().coerceAtLeast(1)
        val query = requested.copy(page = requested.page.coerceIn(0, pages - 1))
        val listed = entries.drop(query.page * pageSize).take(pageSize)
        fun entered(context: PaperDialogClickContext) = context.text(searchInput)?.trim()?.take(80) ?: query.search
        val body = mutableListOf(
            PaperDialogBody(text("catalog-intro", "<#e8dfd2>Найдите данж по названию или описанию. На карточке — уровень, тип похода и подробности."), 468),
            table(listOf(label("found", "Найдено") to Component.text(entries.size),
                label("page", "Страница") to Component.text("${query.page + 1} / $pages")), DialogTables.Frame.LEGENDARY),
        )
        if (listed.isEmpty()) body += PaperDialogBody(text("catalog-empty", "<#e8dfd2>По этому запросу ничего не найдено. Измените поиск или выберите «Все данжи»."), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        val filters = listOf("all" to "Все данжи", "open" to "Открытый мир", "instanced" to "Инстансы")
        val contentButtons = filters.map { (kind, fallback) ->
            val selected = kind == query.kind
            button("filter_$kind", "filter-$kind-${if (selected) "on" else "off"}",
                "${if (selected) "<#9bd48d>✔" else "<white>○"} $fallback", "Показать: $fallback") {
                catalog(player, DungeonCatalogQuery(entered(it), kind))
            }
        } + button("search", "search-label", "<white>Найти", "Применить поисковую строку") {
            catalog(player, query.copy(search = entered(it), page = 0))
        } + listed.mapIndexed { index, entry ->
            PaperDialogButton(PaperDialogActionId.of("dungeon_$index"),
                text("catalog-card", "<white><name> <#ffb277>· <level> ›", "name" to Component.text(clean(entry.name)), "level" to Component.text(levelRange(entry))),
                text("catalog-card-tooltip", "<#e8dfd2><type> · уровень <level><newline><newline><description><newline><newline><#ffb277>Открыть подробности похода",
                    "type" to typeLabel(entry), "level" to Component.text(levelRange(entry)),
                    "description" to Component.text(entry.description.map(::clean).joinToString("\n").ifBlank { clean(entry.name) })),
                width = 230, onClick = { detail(player, entry.id, query) })
        }
        val buttons = if (pages > 1) paginatedButtons(
            contentButtons,
            refresh { catalog(player, query) },
            button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Вернуться на предыдущую страницу") {
                val search = entered(it)
                catalog(player, query.copy(search = search, page = if (search == query.search) (query.page - 1 + pages) % pages else 0))
            },
            button("next", "next-label", "<#92bed8>Следующая страница ›", "Открыть следующую страницу") {
                val search = entered(it)
                catalog(player, query.copy(search = search, page = if (search == query.search) (query.page + 1) % pages else 0))
            },
        ) else contentButtons
        show(player, PaperDialogScreen(id = "dungeon.catalog", title = text("catalog-title", "<#ffb277>Каталог данжей"),
            body = body, inputs = listOf(PaperDialogTextInput(searchInput, text("search-input", "<white>Поиск по названию или описанию"),
                initial = query.search, maxLength = 80, width = 468)), buttons = buttons, exitButton = back { main(player) }, columns = 2)) {
            catalog(player, query)
        }
    }

    private fun detail(player: Player, id: String, query: DungeonCatalogQuery, feedback: Component? = null) {
        val entry = data.catalog(player).firstOrNull { it.id == id } ?: run { catalog(player, query, changed()); return }
        val blocked = dungeon.insideInstance(player)
        val rows = listOf(
            label("type", "Тип похода") to typeLabel(entry),
            label("level", "Уровень") to Component.text(levelRange(entry)),
            label("access", "Доступ") to if (entry.permissionGranted) text("access-ready", "<#9bd48d>Открыт") else text("access-locked", "<white>Нет доступа"),
        ) + listOfNotNull(entry.maxPlayers?.let { label("maxPlayers", "Группа") to text("max-players", "<#e8dfd2>До <count> игроков", "count" to Component.text(it)) })
        val body = mutableListOf(table(rows, DialogTables.Frame.ARTIFACT))
        if (entry.description.isNotEmpty()) body += DialogTables.framedBody(
            Component.text(entry.description.map(::clean).joinToString("\n")), frame = DialogTables.Frame.ARTIFACT, width = 320)
        if (blocked) body += PaperDialogBody(leaveFirst(), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        val permitted = entry.permissionGranted && !blocked
        val difficulties = entry.difficulties.ifEmpty { listOf(DungeonDifficultyInfo("", "Войти", null)) }
        val buttons = difficulties.mapIndexed { index, difficulty ->
            button("enter_$index", "enter-label", if (permitted) "<#9bd48d><difficulty> · войти" else "<white>[Недоступно] Войти",
                "Подготовить поход на выбранной сложности", close = permitted) { context ->
                val level = if (entry.dynamic) context.number(levelInput)?.toInt() ?: entry.level else null
                if (!permitted) detail(player, id, query, if (blocked) leaveFirst() else noPermission())
                else {
                    val result = data.enter(player, id, difficulty.id.takeIf { it.isNotBlank() }, level)
                    if (result !in ACCEPTED_ACTIONS) detail(player, id, query, resultText(result))
                }
            }.copy(label = if (permitted && difficulty.id.isBlank()) text("enter-default", "<#9bd48d>Войти в данж")
                else if (permitted) text("enter-difficulty", "<#9bd48d><difficulty> · войти", "difficulty" to Component.text(clean(difficulty.name)))
                else text("enter-disabled", "<white>[Недоступно] <difficulty>", "difficulty" to Component.text(clean(difficulty.name))))
        } + if (entry.type != "OPEN_DUNGEON") listOf(button("lobbies", "lobbies-label", "<#e5ba73>Другие группы ›", "Войти в собирающуюся группу или наблюдать за походом") {
            lobbies(player, id, query)
        }) else emptyList()
        show(player, PaperDialogScreen(id = "dungeon.catalog.detail", title = Component.text(clean(entry.name)).color(net.kyori.adventure.text.format.TextColor.color(0xffb277)).decoration(TextDecoration.ITALIC, false),
            body = body, numberInputs = if (entry.dynamic && entry.availableLevels.isNotEmpty()) listOf(PaperDialogNumberRangeInput(levelInput,
                text("level-input", "<white>Уровень похода"), entry.availableLevels.first().toFloat(), entry.availableLevels.last().toFloat(),
                initial = entry.availableLevels.minBy { kotlin.math.abs(it - entry.level) }.toFloat(), step = 5f, width = 320)) else emptyList(),
            buttons = buttons, exitButton = back { catalog(player, query) }, columns = 2)) { detail(player, id, query) }
    }

    private fun lobbies(player: Player, content: String, query: DungeonCatalogQuery, requestedPage: Int = 0, feedback: Component? = null) {
        val entries = data.lobbies(player, content)
        val pages = ((entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val body = mutableListOf(PaperDialogBody(text("lobbies-intro", "<#e8dfd2>Выберите собирающуюся группу. Идущий поход можно посмотреть, если наблюдение разрешено."), 468))
        if (entries.isEmpty()) body += PaperDialogBody(text("lobbies-empty", "<#e8dfd2>Открытых групп пока нет. Вернитесь к карточке данжа и создайте свой поход."), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        val contentButtons = entries.drop(page * pageSize).take(pageSize).mapIndexed { index, lobby ->
            PaperDialogButton(PaperDialogActionId.of("lobby_$index"),
                text(if (lobby.waiting) "join-lobby" else "spectate-lobby", if (lobby.waiting) "<#9bd48d>Группа <number> · войти" else "<#92bed8>Группа <number> · наблюдать", "number" to Component.text(page * pageSize + index + 1)),
                text("lobby-tooltip", "<#e8dfd2>Уровень <level> · <difficulty><newline><newline><players>",
                    "level" to Component.text(lobby.level), "difficulty" to Component.text(clean(lobby.difficulty)),
                    "players" to Component.text(lobby.players.joinToString(", "))),
                width = 230, closeDialogBeforeAction = true, onClick = {
                    val result = data.join(player, content, lobby.id, !lobby.waiting)
                    if (result !in ACCEPTED_ACTIONS) lobbies(player, content, query, page, resultText(result))
                })
        }
        val buttons = if (pages > 1) paginatedButtons(
            contentButtons,
            refresh { lobbies(player, content, query, page) },
            button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Предыдущие группы") { lobbies(player, content, query, (page - 1 + pages) % pages) },
            button("next", "next-label", "<#92bed8>Следующая страница ›", "Следующие группы") { lobbies(player, content, query, (page + 1) % pages) },
        ) else contentButtons + refresh { lobbies(player, content, query, page) }
        show(player, PaperDialogScreen(id = "dungeon.catalog.lobbies", title = text("lobbies-title", "<#e5ba73>Группы данжа"),
            body = body, buttons = buttons, exitButton = back { detail(player, content, query) }, columns = 2)) { lobbies(player, content, query, page) }
    }

    fun statistics(player: Player) {
        val entries = data.stats(player)
        show(player, PaperDialogScreen(id = "dungeon.statistics", title = text("stats-title", "<#86dcf1>Статистика приключений"),
            body = listOf(if (entries == null) PaperDialogBody(loading(), 320) else table(entries.filter { it.key != "money" }.map {
                label(it.key, STAT_LABELS[it.key] ?: it.key) to value(it.value)
            }, DialogTables.Frame.LEGENDARY)), buttons = listOf(refresh { statistics(player) }), exitButton = back { main(player) })) { statistics(player) }
    }

    fun skills(player: Player, requestedPage: Int = 0) {
        val entries = data.skills(player)
        val pageSize = 6
        val pages = ((entries.orEmpty().size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = entries.orEmpty().drop(page * pageSize).take(pageSize)
        val body = listOf(if (entries == null) PaperDialogBody(loading(), 320) else table(listed.map {
            Component.text(clean(it.name)) to text("skill-progress", "<#c4abff>Уровень <level><newline><#e8dfd2><xp> / <next> опыта",
                "level" to Component.text(it.level), "xp" to Component.text(it.xp), "next" to Component.text(it.nextXp))
        }, DialogTables.Frame.EPIC, separators = true))
        val contentButtons = listed.mapIndexed { index, skill ->
            PaperDialogButton(PaperDialogActionId.of("skill_$index"),
                text("skill-label", "<#c4abff><name> · перки ›", "name" to Component.text(clean(skill.name))),
                text("skill-tooltip", "<#e8dfd2>Выбрать или выключить перки этого навыка"), width = 230,
                onClick = { skillPerks(player, skill.id, page) })
        }
        val buttons = if (pages > 1) paginatedButtons(
            contentButtons,
            refresh { skills(player, page) },
            button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Предыдущие навыки") { skills(player, (page - 1 + pages) % pages) },
            button("next", "next-label", "<#92bed8>Следующая страница ›", "Следующие навыки") { skills(player, (page + 1) % pages) },
        ) else contentButtons + refresh { skills(player, page) }
        show(player, PaperDialogScreen(id = "dungeon.skills", title = text("skills-title", "<#c4abff>Боевые навыки"),
            body = body, buttons = buttons, exitButton = back { main(player) }, columns = 2)) { skills(player, page) }
    }

    private fun skillPerks(player: Player, skillId: String, skillPage: Int, requestedPage: Int = 0, feedback: Component? = null) {
        val view = data.perks(player, skillId) ?: run { skills(player, skillPage); return }
        val pages = ((view.perks.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val body = mutableListOf(table(listOf(
            label("skillLevel", "Уровень навыка") to Component.text(view.skill.level),
            label("activePerks", "Активные перки") to Component.text("${view.perks.count { it.active }} / ${view.maxActive}"),
        ), DialogTables.Frame.EPIC), PaperDialogBody(text("skill-perks-intro", "<#e8dfd2>✔ — выбранный перк. Нажмите для переключения. Требования и эффект указаны в подсказке."), 468))
        feedback?.let { body += PaperDialogBody(it, 468) }
        val contentButtons = view.perks.drop(page * pageSize).take(pageSize).mapIndexed { index, perk ->
            val unlocked = view.skill.level >= perk.requiredLevel
            PaperDialogButton(PaperDialogActionId.of("perk_$index"),
                text(if (perk.active) "perk-active" else if (unlocked) "perk-ready" else "perk-locked",
                    if (perk.active) "<#9bd48d>✔ <name>" else if (unlocked) "<#c4abff>○ <name>" else "<white>[<level>] <name>",
                    "name" to Component.text(clean(perk.name)), "level" to Component.text(perk.requiredLevel)),
                text("perk-tooltip", "<#e8dfd2>Ступень <tier> · нужен уровень <level><newline><newline><description><newline><newline><#c4abff><bonus>",
                    "tier" to Component.text(perk.tier), "level" to Component.text(perk.requiredLevel),
                    "description" to Component.text(perk.description.map(::clean).joinToString("\n")), "bonus" to Component.text(clean(perk.bonus))),
                width = 230, onClick = { skillPerks(player, skillId, skillPage, page, resultText(data.togglePerk(player, skillId, perk.id))) })
        }
        val buttons = if (pages > 1) paginatedButtons(
            contentButtons,
            refresh { skillPerks(player, skillId, skillPage, page) },
            button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Предыдущие перки") { skillPerks(player, skillId, skillPage, (page - 1 + pages) % pages) },
            button("next", "next-label", "<#92bed8>Следующая страница ›", "Следующие перки") { skillPerks(player, skillId, skillPage, (page + 1) % pages) },
        ) else contentButtons
        show(player, PaperDialogScreen(id = "dungeon.skill.perks", title = text("skill-perks-title", "<#c4abff><name> · перки",
            "name" to Component.text(clean(view.skill.name))), body = body, buttons = buttons,
            exitButton = back { skills(player, skillPage) }, columns = 2)) { skillPerks(player, skillId, skillPage, page) }
    }

    fun gear(player: Player) {
        val view = data.gear(player)
        val body = mutableListOf<PaperDialogBody>()
        if (view == null) body += PaperDialogBody(loading(), 320) else {
            body += table(view.summary.filter { it.key in GEAR_OVERVIEW }.map { label(it.key, GEAR_LABELS[it.key] ?: it.key) to value(it.value) }, DialogTables.Frame.EPIC)
            body += table(view.equipment.map { label("slot-${it.slot}", SLOT_LABELS[it.slot] ?: it.slot) to
                Component.text(clean(it.name)) }, DialogTables.Frame.LEGENDARY)

        }
        val buttons = view?.equipment.orEmpty().mapIndexed { index, item ->
            button("equipment_$index", "equipment-label", "<#86dcf1><slot> ›", "Показать предмет, уровень и описание") { equipment(player, item.slot) }
                .copy(label = text("equipment-slot", "<#86dcf1><slot> ›", "slot" to label("slot-${item.slot}", SLOT_LABELS[item.slot] ?: item.slot)))
        } + listOf(
            button("combat", "combat-label", "<#86dcf1>Боевые параметры ›", "Все множители, защита и характеристики") { gearDetail(player, false) },
            button("perks", "perks-label", "<#c4abff>Перки ›", "Выбранные перки оружия и брони") { gearDetail(player, true) },
        )
        show(player, PaperDialogScreen(id = "dungeon.gear", title = text("gear-title", "<#86dcf1>Снаряжение и бой"),
            body = body, buttons = buttons, exitButton = back { main(player) }, columns = 2)) { gear(player) }
    }

    private fun gearDetail(player: Player, perks: Boolean) {
        val view = data.gear(player) ?: run { gear(player); return }
        val body = if (perks) DialogTables.framedBody(text("perks", "<#c4abff>Перки оружия<newline><#e8dfd2><weapon><newline><newline><#c4abff>Перки брони<newline><#e8dfd2><armor>",
            "weapon" to Component.text(view.weaponPerks.map(::clean).joinToString("\n").ifBlank { "Не выбраны" }),
            "armor" to Component.text(view.armorPerks.map(::clean).joinToString("\n").ifBlank { "Не выбраны" })), frame = DialogTables.Frame.EPIC, width = 320)
        else table(view.summary.map { label(it.key, GEAR_LABELS[it.key] ?: it.key) to value(it.value) }, DialogTables.Frame.EPIC)
        show(player, PaperDialogScreen(id = if (perks) "dungeon.gear.perks" else "dungeon.gear.combat",
            title = text(if (perks) "perks-title" else "combat-title", if (perks) "<#c4abff>Перки оружия и брони" else "<#86dcf1>Боевые параметры"),
            body = listOf(body), buttons = listOf(refresh { gearDetail(player, perks) }), exitButton = back { gear(player) })) { gearDetail(player, perks) }
    }

    private fun equipment(player: Player, slot: String) {
        val item = data.gear(player)?.equipment?.firstOrNull { it.slot == slot } ?: run { gear(player); return }
        val lore = Component.empty().children(item.lore.flatMapIndexed { index, line ->
            val component = LegacyComponentSerializer.legacySection().deserialize(line)
            if (index == 0) listOf(component) else listOf(Component.newline(), component)
        }).decoration(TextDecoration.ITALIC, false)
        show(player, PaperDialogScreen(id = "dungeon.equipment", title = Component.text(clean(item.name)).color(net.kyori.adventure.text.format.TextColor.color(0x86dcf1)).decoration(TextDecoration.ITALIC, false),
            body = listOf(table(listOf(label("itemLevel", "Уровень предмета") to Component.text(item.level)), DialogTables.Frame.LEGENDARY),
                DialogTables.framedBody(lore, frame = DialogTables.Frame.LEGENDARY, width = 320)),
            buttons = emptyList(), exitButton = back { gear(player) })) { equipment(player, slot) }
    }

    fun bosses(player: Player, requestedPage: Int = 0, feedback: Component? = null) {
        val entries = data.bosses(player)
        val pages = ceil(entries.size.toDouble() / pageSize).toInt().coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val body = mutableListOf(PaperDialogBody(text("bosses-intro", "<#e8dfd2>Здесь только живые боссы. Нажмите на цель, чтобы включить указатель; повторное нажатие выключит его."), 468))
        if (entries.isEmpty()) body += PaperDialogBody(text("bosses-empty", "<#e8dfd2>Сейчас нет боссов для отслеживания."), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        val contentButtons = entries.drop(page * pageSize).take(pageSize).mapIndexed { index, boss ->
            PaperDialogButton(PaperDialogActionId.of("boss_$index"), Component.text(clean(boss.name)).color(net.kyori.adventure.text.format.TextColor.color(0xffb277)),
                text("boss-tooltip", "<#e8dfd2>Уровень <level> · <world><newline><newline>Включить или выключить отслеживание этого босса",
                    "level" to Component.text(boss.level), "world" to Component.text(boss.world)), width = 230,
                onClick = { bosses(player, page, resultText(data.track(player, boss.id))) })
        }
        val buttons = if (pages > 1) paginatedButtons(
            contentButtons,
            refresh { bosses(player, page) },
            button("previous", "previous-label", "<#92bed8>‹ Предыдущая страница", "Предыдущие боссы") { bosses(player, (page - 1 + pages) % pages) },
            button("next", "next-label", "<#92bed8>Следующая страница ›", "Следующие боссы") { bosses(player, (page + 1) % pages) },
        ) else contentButtons + refresh { bosses(player, page) }
        show(player, PaperDialogScreen(id = "dungeon.bosses", title = text("bosses-title", "<#ffb277>Боссы мира"),
            body = body, buttons = buttons, exitButton = back { main(player) }, columns = 2)) { bosses(player, page) }
    }

    fun travel(player: Player, feedback: Component? = null) {
        val blocked = dungeon.insideInstance(player)
        val destination = dungeon.lastReturn(player)
        val body = mutableListOf(PaperDialogBody(text("travel-intro", "<#e8dfd2>Подготовка к походу, торговцы и возвращение к приключениям."), 468))
        if (blocked) body += PaperDialogBody(leaveFirst(), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        fun command(id: String, key: String, fallback: String, hint: String, action: () -> Unit) =
            button(id, if (blocked) "$key-disabled" else key, if (blocked) "<white>[Недоступно] $fallback" else "<#92bed8>$fallback ›", hint, close = !blocked) {
                if (dungeon.insideInstance(player)) travel(player, leaveFirst()) else action()
            }
        val buttons = listOf(
            command("guild", "guild-label", "Гильдия", "Перейти к порталам и заданиям Гильдии") { dungeon.action(player, "tp") },
            command("shops", "shops-label", "Магазины", "Перейти к торговцам Гильдии") { dungeon.action(player, "shops") },
            command("spawn", "spawn-label", "Спавн", "Вернуться на спавн") { player.performCommand("elitemobs:em spawntp") },
            button("return", if (blocked || destination == null) "return-disabled" else "return-label", if (blocked || destination == null) "<white>[Недоступно] Вернуться в данж" else "<#92bed8>Вернуться в данж ›",
                "Личный портал к месту последнего выхода из обычного данжа", close = !blocked && destination != null) {
                if (blocked) travel(player, leaveFirst())
                else if (destination == null) travel(player, text("return-unavailable", "<#e8dfd2>Нет доступного места выхода. Сначала посетите обычный данж и выйдите из него."))
                else dungeon.returnToLast(player, destination)
            },
        )
        show(player, PaperDialogScreen(id = "dungeon.travel", title = text("travel-title", "<#92bed8>Телепорты приключений"),
            body = body, buttons = buttons, exitButton = back { main(player) }, columns = 2)) { travel(player) }
    }

    private fun typeLabel(entry: DungeonCatalogEntry): Component = text("type-${entry.type.lowercase(Locale.ROOT)}", when (entry.type) {
        "OPEN_DUNGEON" -> "<#92bed8>Открытый мир"
        "ARENA" -> "<#ffb277>Арена"
        else -> "<#c4abff>Отдельный поход"
    })
    private fun levelRange(entry: DungeonCatalogEntry): String = if (entry.lowestLevel != entry.highestLevel)
        "${entry.lowestLevel}–${entry.highestLevel}" else entry.level.toString()
    private fun resultText(result: DungeonAdventureActionResult): Component = when (result) {
        DungeonAdventureActionResult.REQUESTED -> text("action-requested", "<#9bd48d>✔ Подготовка похода запрошена")
        DungeonAdventureActionResult.SPECTATING -> text("action-spectating", "<#92bed8>Запрос на наблюдение отправлен")
        DungeonAdventureActionResult.TRACKED -> text("action-tracked", "<#9bd48d>✔ Отслеживание переключено")
        DungeonAdventureActionResult.UNTRACKED -> text("action-untracked", "<#e8dfd2>Отслеживание выключено")
        DungeonAdventureActionResult.PERK_ACTIVATED -> text("perk-activated", "<#9bd48d>✔ Перк включён")
        DungeonAdventureActionResult.PERK_DEACTIVATED -> text("perk-deactivated", "<#e8dfd2>Перк выключен")
        DungeonAdventureActionResult.LEVEL_REQUIRED -> text("perk-level-required", "<#e8dfd2>Ваш уровень навыка пока недостаточен для этого перка.")
        DungeonAdventureActionResult.PERK_LIMIT -> text("perk-limit", "<#e8dfd2>Достигнут лимит активных перков. Сначала выключите один из выбранных.")
        DungeonAdventureActionResult.INVALID_SELECTION -> text("invalid-selection", "<#e8dfd2>Выбранный уровень или сложность изменились. Выберите их заново.")
        DungeonAdventureActionResult.NO_PERMISSION -> noPermission()
        DungeonAdventureActionResult.IN_INSTANCE -> leaveFirst()
        DungeonAdventureActionResult.MISSING, DungeonAdventureActionResult.CHANGED -> changed()
        DungeonAdventureActionResult.FAILED -> text("action-failed", "<#e8dfd2>Действие не выполнено. Проверьте сообщение и попробуйте снова.")
    }
    private fun rootExit(player: Player) = PaperDialogButton(PaperDialogActionId.of(if (MenuEscapeBehavior.goesBack(player)) "back" else "close"),
        text(if (MenuEscapeBehavior.goesBack(player)) "back-label" else "close-label", if (MenuEscapeBehavior.goesBack(player)) "<white>‹ Назад" else "<white>Закрыть"), width = 200, closeDialogBeforeAction = !MenuEscapeBehavior.goesBack(player), onClick = {})
    private fun back(returnTo: () -> Unit) = PaperDialogButton(PaperDialogActionId.of("back"), text("back-label", "<white>‹ Назад"), width = 200, onClick = { returnTo() })
    private fun refresh(action: () -> Unit) = button("refresh", "refresh-label", "<white>Обновить", "Получить текущие данные") { action() }

    /** Paper dialog buttons are a flat two-column grid; refresh completes an odd content row before pagination. */
    private fun paginatedButtons(
        content: List<PaperDialogButton>,
        refresh: PaperDialogButton,
        previous: PaperDialogButton,
        next: PaperDialogButton,
    ): List<PaperDialogButton> = content + (if (content.size % 2 == 1) listOf(refresh) else emptyList()) + listOf(previous, next)

    private fun button(id: String, key: String, fallback: String, hint: String, close: Boolean = false, action: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), text(key, fallback), text("$key-tooltip", "<#e8dfd2>$hint"), width = 230, closeDialogBeforeAction = close, onClick = action)
    private fun label(key: String, fallback: String) = text("fields.$key", "<#e8dfd2>$fallback")
    private fun value(value: String?) = value?.let { Component.text(clean(it)).color(net.kyori.adventure.text.format.TextColor.color(0xe8dfd2)) } ?: unavailable()
    private fun table(rows: List<Pair<Component, Component>>, frame: DialogTables.Frame, separators: Boolean = false) = DialogTables.body(
        rows, headers = null, frame = frame, width = 320,
        columns = DialogTables.Columns.BALANCED, spec = DialogTables.Spec(rowSeparators = separators))
    private fun clean(value: String) = plainDungeonQuestText(value)
    private fun loading() = text("loading", "<#e8dfd2>Данные героя ещё загружаются. Попробуйте обновить страницу.")
    private fun unavailable() = text("unavailable", "<#e8dfd2>Сейчас недоступно")
    private fun noPermission() = text("no-permission", "<#e8dfd2>У вас нет доступа к этому действию.")
    private fun leaveFirst() = text("leave-first", "<#e8dfd2>Сначала завершите текущий поход или выйдите через панель данжа. Просматривать каталог можно прямо здесь.")
    private fun changed() = text("changed", "<#e8dfd2>Список изменился. Страница обновлена; выберите цель заново.")
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>) =
        dungeon.text("adventure.$key", fallback, *values).decoration(TextDecoration.ITALIC, false)

    companion object {
        private val ACCEPTED_ACTIONS = setOf(DungeonAdventureActionResult.REQUESTED, DungeonAdventureActionResult.SPECTATING,
            DungeonAdventureActionResult.TRACKED, DungeonAdventureActionResult.UNTRACKED)
        private val STAT_LABELS = mapOf("rank" to "Место в рейтинге", "score" to "Счёт приключений", "dungeons" to "Пройдено данжей",
            "combatLevel" to "Боевой уровень", "kills" to "Убито элит", "highestKill" to "Сильнейшая победа", "quests" to "Выполнено заданий",
            "deaths" to "Смерти", "activeQuests" to "Активные задания")
        private val GEAR_OVERVIEW = setOf("combatLevel", "weaponLevel", "armorLevel", "weaponFactor", "defenseMatch", "critChance", "health")
        private val GEAR_LABELS = mapOf("combatLevel" to "Боевой уровень", "referenceLevel" to "Уровень противника", "weaponLevel" to "Уровень оружия",
            "weaponSkill" to "Навык оружия", "weaponSkillLevel" to "Уровень навыка", "armorSkillLevel" to "Навык брони", "armorLevel" to "Защита брони",
            "weaponFactor" to "Множитель оружия", "critChance" to "Шанс крита", "enchantmentBonus" to "Бонус зачарований", "threatMultiplier" to "Множитель угрозы",
            "health" to "Здоровье", "maxHealth" to "Максимум здоровья", "defenseMatch" to "Защита от ударов")
        private val SLOT_LABELS = mapOf("helmet" to "Шлем", "chestplate" to "Нагрудник", "leggings" to "Поножи", "boots" to "Ботинки",
            "mainhand" to "Основная рука", "offhand" to "Вторая рука")
    }
}
