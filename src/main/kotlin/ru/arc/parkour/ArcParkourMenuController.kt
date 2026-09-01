package ru.arc.parkour

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.ConfigItemSpec
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil

private enum class ParkourMenuScreen { ROOT, CATEGORY }

private class ParkourMenuHolder(
    val screen: ParkourMenuScreen,
    val categoryId: String? = null,
    val categorySlots: Map<Int, String> = emptyMap(),
    val courseSlots: Map<Int, String> = emptyMap(),
) : InventoryHolder {
    lateinit var backingInventory: Inventory

    override fun getInventory(): Inventory = backingInventory
}

class ArcParkourMenuController(
    private val settings: ArcParkourSettings,
    private val gateway: ArcParkourGateway,
    private val tasks: LifecycleTaskScope,
) : Listener {
    fun openRoot(player: Player) {
        val grouped = catalog(player) ?: return
        val visible = grouped.take(ROOT_CATEGORY_SLOTS.size)
        val slots = ROOT_CATEGORY_SLOTS.zip(visible.map { it.definition.id }).toMap()
        val holder = ParkourMenuHolder(ParkourMenuScreen.ROOT, categorySlots = slots)
        val inventory = Bukkit.createInventory(holder, MENU_SIZE, title(settings.gui.string("titles.root", "Паркур")))
        holder.backingInventory = inventory
        fill(inventory)
        visible.forEachIndexed { index, category ->
            inventory.setItem(ROOT_CATEGORY_SLOTS[index], categoryItem(category))
        }
        player.openInventory(inventory)
        click(player)
    }

    private fun openCategory(player: Player, categoryId: String) {
        val category = catalog(player)?.firstOrNull { it.definition.id == categoryId } ?: return openRoot(player)
        if (category.courses.isEmpty()) {
            player.sendMessage(TextUtil.mm(settings.noReadyCoursesMessage, true))
            bass(player)
            return
        }
        val courses = category.courses.take(COURSE_SLOTS.size)
        val slots = COURSE_SLOTS.zip(courses.map { it.course.id }).toMap()
        val holder =
            ParkourMenuHolder(
                ParkourMenuScreen.CATEGORY,
                categoryId = category.definition.id,
                courseSlots = slots,
            )
        val renderedTitle =
            template(
                settings.gui.string("titles.category", "Паркур — <category>"),
                "category" to escape(category.definition.name),
            )
        val inventory = Bukkit.createInventory(holder, MENU_SIZE, title(renderedTitle))
        holder.backingInventory = inventory
        fill(inventory)
        courses.forEachIndexed { index, course ->
            inventory.setItem(COURSE_SLOTS[index], courseItem(category.definition, course))
        }
        inventory.setItem(BACK_SLOT, item(settings.back))
        player.openInventory(inventory)
        click(player)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!settings.interceptJoinAllCommand || !ParkourJoinAllCommand.matches(event.message)) return
        if (!event.player.hasPermission(settings.joinAllPermission)) return
        event.isCancelled = true
        openRoot(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLegacyMenu(event: InventoryOpenEvent) {
        if (!settings.interceptLegacyMenu || event.inventory.holder is ParkourMenuHolder) return
        val visibleTitle = PlainTextComponentSerializer.plainText().serialize(event.view.title())
        if (!visibleTitle.equals(settings.legacyMenuTitle, ignoreCase = true)) return
        val player = event.player as? Player ?: return
        if (!player.hasPermission(settings.joinAllPermission)) return
        event.isCancelled = true
        tasks.runLater(1) {
            if (player.isOnline) openRoot(player)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ParkourMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot !in 0 until event.view.topInventory.size) return
        when (holder.screen) {
            ParkourMenuScreen.ROOT -> holder.categorySlots[event.rawSlot]?.let { openCategory(player, it) }

            ParkourMenuScreen.CATEGORY -> {
                when (event.rawSlot) {
                    BACK_SLOT -> openRoot(player)
                    else ->
                        holder.courseSlots[event.rawSlot]?.let { courseId ->
                            player.closeInventory()
                            if (!gateway.join(player, courseId)) bass(player)
                        }
                }
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is ParkourMenuHolder) event.isCancelled = true
    }

    private fun catalog(player: Player): List<ParkourCategorySnapshot>? =
        runCatching { ArcParkourCatalog.group(gateway.readyCourses(player), settings.categories) }
            .onFailure { error("Unable to render ARC Parkour catalog", it) }
            .getOrElse {
                player.sendMessage(TextUtil.mm(settings.unavailableMessage, true))
                bass(player)
                return null
            }

    private fun categoryItem(category: ParkourCategorySnapshot): ItemStack {
        val definition = category.definition
        val lore =
            definition.description +
                listOf(
                    spacer(),
                    template(settings.gui.string("copy.ready-count", "<#8c8c8c>Готово трасс: <#f2f0e6><ready>"), "ready" to category.courses.size.toString()),
                    spacer(),
                    settings.gui.string("copy.action-open", "<#92bed8>Нажмите — открыть трассы"),
                )
        return item(definition.icon, definition.display, lore)
    }

    private fun courseItem(
        category: ParkourCategoryDefinition,
        card: ParkourCourseCard,
    ): ItemStack {
        val completionKey = if (card.course.completed) "copy.completed" else "copy.not-completed"
        val completionFallback = if (card.course.completed) "<#7bd88f>✔ Вы уже проходили эту трассу" else "<#969696>Ещё не пройдена"
        val lore =
            listOf(
                settings.gui.string(completionKey, completionFallback),
                spacer(),
                template(settings.gui.string("copy.checkpoints", "<#8c8c8c>Контрольных точек: <#f2f0e6><checkpoints>"), "checkpoints" to card.course.checkpoints.toString()),
                template(settings.gui.string("copy.players", "<#8c8c8c>Сейчас на трассе: <#f2f0e6><players>"), "players" to card.course.players.toString()),
                spacer(),
                settings.gui.string("copy.action-start", "<#92bed8>Нажмите — начать трассу"),
            )
        val display = template(category.courseDisplay, "course" to escape(card.displayName))
        val icon = category.courseIcons[(card.sequence - 1) % category.courseIcons.size]
        return item(icon, display, lore, glint = card.course.completed)
    }

    private fun fill(inventory: Inventory) {
        val background = item(settings.background, hideTooltip = true)
        for (slot in 0 until inventory.size) inventory.setItem(slot, background)
    }

    private fun item(
        spec: ConfigItemSpec,
        hideTooltip: Boolean = false,
    ): ItemStack =
        item(
            spec.material ?: Material.STONE,
            spec.display ?: " ",
            spec.lore.orEmpty(),
            customModelData = spec.modelData,
            hideTooltip = hideTooltip,
        )

    private fun item(
        material: Material,
        display: String,
        lore: List<String>,
        glint: Boolean = false,
        customModelData: Int? = null,
        hideTooltip: Boolean = false,
    ): ItemStack =
        ItemStack(material).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(title(display))
                meta.lore(lore.map(::title))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                meta.setEnchantmentGlintOverride(glint)
                meta.setHideTooltip(hideTooltip)
                @Suppress("DEPRECATION")
                customModelData?.takeIf { it != 0 }?.let(meta::setCustomModelData)
            }
        }

    private fun title(value: String) = parkourItemText(value)

    private fun template(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { current, (key, value) -> current.replace("<$key>", value) }

    private fun spacer(): String = settings.gui.string("copy.spacer", " ")

    private fun escape(value: String): String = value.replace("<", "\\<").replace(">", "\\>")

    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)

    private fun bass(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)

    companion object {
        private const val MENU_SIZE = 27
        private val ROOT_CATEGORY_SLOTS = listOf(10, 12, 14, 16, 11, 13, 15)
        private val COURSE_SLOTS = (10..16).toList()
        private const val BACK_SLOT = 18
    }
}

internal fun parkourItemText(value: String): Component =
    TextUtil.mm(value, true).decoration(TextDecoration.ITALIC, false)
