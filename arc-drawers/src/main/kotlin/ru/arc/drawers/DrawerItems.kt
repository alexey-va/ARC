package ru.arc.drawers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage

internal enum class DrawerItem(val id: String, val material: Material) {
    ONE("drawer_1", Material.BARREL),
    TWO("drawer_2", Material.BARREL),
    FOUR("drawer_4", Material.BARREL),
    LOCK("lock_key", Material.TRIPWIRE_HOOK),
    COUNTS("count_key", Material.TRIPWIRE_HOOK),
    DOUBLE("capacity_2", Material.IRON_INGOT),
    QUADRUPLE("capacity_4", Material.GOLD_INGOT),
    OCTUPLE("capacity_8", Material.DIAMOND),
    VOID("void_upgrade", Material.BLAZE_POWDER);

    val layout get() = when (this) {
        ONE -> DrawerLayout.ONE
        TWO -> DrawerLayout.TWO
        FOUR -> DrawerLayout.FOUR
        else -> null
    }
    val capacity get() = when (this) {
        DOUBLE -> Capacity.DOUBLE
        QUADRUPLE -> Capacity.QUADRUPLE
        OCTUPLE -> Capacity.OCTUPLE
        else -> null
    }

    companion object {
        val byId = entries.associateBy(DrawerItem::id)
        fun forLayout(layout: DrawerLayout) = entries.first { it.layout == layout }
        fun forCapacity(capacity: Capacity) = entries.first { it.capacity == capacity }
    }
}

internal class DrawerItems(private val plugin: Plugin) : AutoCloseable {
    private val marker = NamespacedKey(plugin, "item")
    private val registered = mutableListOf<NamespacedKey>()

    fun recipeKeys(): List<NamespacedKey> = registered.toList()

    fun identify(stack: ItemStack?): DrawerItem? {
        if (stack == null || stack.isEmpty) return null
        val id = stack.persistentDataContainer.get(marker, PersistentDataType.STRING) ?: return null
        return DrawerItem.byId[id]?.takeIf { it.material == stack.type }
    }

    fun create(type: DrawerItem, amount: Int = 1): ItemStack = ItemStack(type.material, amount).apply {
        editMeta {
            it.itemName(DrawerText.message(type.id))
            it.lore(listOf(DrawerText.message("${type.id}.hint").decoration(TextDecoration.ITALIC, false)))
            it.persistentDataContainer.set(marker, PersistentDataType.STRING, type.id)
        }
    }

    fun register() {
        add(DrawerItem.ONE, Material.BARREL, Material.IRON_NUGGET)
        add(DrawerItem.TWO, Material.BARREL, Material.BARREL, Material.IRON_NUGGET)
        add(DrawerItem.FOUR, Material.BARREL, Material.BARREL, Material.BARREL, Material.BARREL, Material.IRON_NUGGET)
        add(DrawerItem.LOCK, Material.TRIPWIRE_HOOK, Material.COPPER_INGOT)
        add(DrawerItem.COUNTS, Material.TRIPWIRE_HOOK, Material.REDSTONE)
        add(DrawerItem.DOUBLE, Material.CHEST, Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT)
        add(DrawerItem.QUADRUPLE, Material.GOLD_INGOT, Material.GOLD_INGOT, Material.GOLD_INGOT, Material.GOLD_INGOT, previous = DrawerItem.DOUBLE)
        add(DrawerItem.OCTUPLE, Material.DIAMOND, Material.DIAMOND, previous = DrawerItem.QUADRUPLE)
        add(DrawerItem.VOID, Material.LAVA_BUCKET, Material.OBSIDIAN, Material.OBSIDIAN, Material.BLAZE_POWDER)
    }

    private fun add(type: DrawerItem, vararg materials: Material, previous: DrawerItem? = null) {
        val key = NamespacedKey(plugin, type.id)
        val recipe = ShapelessRecipe(key, create(type))
        recipe.group = "arcdrawers"
        materials.forEach { recipe.addIngredient(it) }
        previous?.let { recipe.addIngredient(RecipeChoice.ExactChoice(create(it))) }
        check(plugin.server.addRecipe(recipe)) { "Cannot register recipe $key" }
        registered += key
    }

    override fun close() {
        registered.forEach { plugin.server.removeRecipe(it) }
        registered.clear()
    }
}

internal object DrawerText {
    private val values = mapOf(
        "prefix" to "",
        "drawer_1" to "<white>Бочка на 1 отделение",
        "drawer_2" to "<white>Бочка на 2 отделения",
        "drawer_4" to "<white>Бочка на 4 отделения",
        "drawer_1.hint" to "<gray>32 стопки в отделении",
        "drawer_2.hint" to "<gray>16 стопок в каждом отделении",
        "drawer_4.hint" to "<gray>8 стопок в каждом отделении",
        "lock_key" to "<gold>Ключ фиксации",
        "lock_key.hint" to "<gray>ПКМ по бочке закрепляет образцы",
        "count_key" to "<gold>Ключ индикации",
        "count_key.hint" to "<gray>ПКМ по бочке скрывает или показывает числа",
        "capacity_2" to "<white>Улучшение вместимости ×2",
        "capacity_4" to "<yellow>Улучшение вместимости ×4",
        "capacity_8" to "<aqua>Улучшение вместимости ×8",
        "capacity_2.hint" to "<gray>ПКМ по бочке увеличивает вместимость",
        "capacity_4.hint" to "<gray>ПКМ по бочке увеличивает вместимость",
        "capacity_8.hint" to "<gray>ПКМ по бочке увеличивает вместимость",
        "void_upgrade" to "<light_purple>Улучшение утилизации",
        "void_upgrade.hint" to "<gray>Уничтожает лишние предметы, совпадающие с образцом",
        "locked" to "<green>Образцы закреплены",
        "unlocked" to "<gray>Фиксация снята",
        "counts_on" to "<gray>Количество показано",
        "counts_off" to "<gray>Количество скрыто",
        "full" to "<yellow>Отделение заполнено",
        "different" to "<yellow>Положите предмет, совпадающий с образцом",
        "no_space" to "<yellow>В инвентаре нет места",
        "empty_first" to "<yellow>Сначала освободите все отделения",
        "damaged" to "<red>Бочку не удалось прочитать",
        "higher" to "<yellow>Уже установлено улучшение этой или большей вместимости",
        "expanded" to "<green>Вместимость увеличена до <stacks> стопок на отделение",
        "void_on" to "<yellow>Лишние предметы теперь утилизируются",
        "void_present" to "<gray>Улучшение утилизации уже установлено",
        "discarded" to "<yellow>Утилизировано <count>",
        "too_many" to "<yellow>Освободите место, чтобы снять улучшение вместимости",
        "removed" to "<gray>Улучшения сняты",
        "status" to "<white><name> <gray>· <count>/<limit> <flags>",
        "empty" to "Пусто",
        "flag_locked" to "<gray>· образцы закреплены",
        "flag_void" to "<yellow>· утилизация",
        "help" to "<white>Бочки для хранения</white>\n<gray>ПКМ по отделению — положить стопку. Двойной ПКМ — сложить все такие предметы.\nЛКМ — взять один. Приседание + ЛКМ — взять стопку.\nПриседание + ПКМ пустой рукой по боку — снять улучшения.\nРецепты бочек, ключей и улучшений открыты в книге рецептов.</gray>",
        "gave" to "<gray>Выдано: <item> ×<count>",
    )
    private val catalog = object : LocaleCatalog {
        override fun scalar(path: String) = values[path]
        override fun lines(path: String): List<String>? = null
    }
    private val text = LocalizedMiniMessage(mapOf("ru" to catalog), { "ru" })
    fun message(key: String, vararg values: Pair<String, Component>): Component = text.render(key, values = mapOf(*values))
}
