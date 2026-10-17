package ru.arc.dungeons.menu

import ru.arc.config.Config
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Path

/** ArcDungeons-owned semantic contracts for the three configured inventory menus. */
object DungeonsMenuSchema {
    const val RESOURCE = "guis/menus.yml"

    private const val LAYOUT_ROOT = "menus.layouts"
    private const val TEMPLATE_ROOT = "menus.templates"

    val ELITE_LOOT: MenuId = MenuId.of("elite-loot")
    val LOST_LOOT: MenuId = MenuId.of("lost-elite-loot")
    val EM_SHOP: MenuId = MenuId.of("elite-mobs-shop")

    val ELITE_LOOT_ITEMS: MenuRegionId = MenuRegionId.of("items")
    val LOST_LOOT_ITEMS: MenuRegionId = MenuRegionId.of("lost-items")
    val EM_SHOP_ITEMS: MenuRegionId = MenuRegionId.of("shop-items")

    private val layoutNames = setOf(ELITE_LOOT.value, LOST_LOOT.value, EM_SHOP.value)
    private val dynamicTemplates = setOf("em-shop-item")

    private fun elements(vararg ids: String): Set<MenuElementId> =
        ids.mapTo(linkedSetOf(), MenuElementId::of)

    val contracts: Map<MenuId, MenuContract> = linkedMapOf(
        ELITE_LOOT to MenuContract(
            requiredElements = elements("previous", "next", "back"),
            requiredRegions = setOf(ELITE_LOOT_ITEMS),
        ),
        LOST_LOOT to MenuContract(
            requiredElements = elements("back", "info", "previous", "next"),
            requiredRegions = setOf(LOST_LOOT_ITEMS),
        ),
        EM_SHOP to MenuContract(
            requiredElements = elements("change", "update"),
            requiredRegions = setOf(EM_SHOP_ITEMS),
        ),
    )

    val textContracts: Map<String, PaperMenuTextContract> = mapOf(
        "em-shop-change" to PaperMenuTextContract(values = setOf("type")),
        "em-shop-update" to PaperMenuTextContract(values = setOf("minutes", "balance", "player")),
        "em-shop-item" to PaperMenuTextContract(
            values = setOf("name", "price"),
            repeats = mapOf("original" to setOf("line")),
        ),
    )

    /**
     * Loads the operator-owned legacy menu file under [legacyDataRoot]. Only the
     * selected layouts and their referenced templates are retained in this
     * Config instance before validation; the on-disk file is never saved here.
     */
    fun load(legacyDataRoot: Path): PaperMenuConfiguration {
        return bind(prepare(legacyDataRoot))
    }

    /** Worker-only YAML projection; native material checks remain in [bind]. */
    internal fun prepare(legacyDataRoot: Path): Config {
        val config = Config(legacyDataRoot, RESOURCE)

        config.keys(LAYOUT_ROOT)
            .filterNot(layoutNames::contains)
            .forEach { config.removeKey("$LAYOUT_ROOT.$it") }

        val retainedTemplates = referencedTemplates(config) + dynamicTemplates
        config.keys(TEMPLATE_ROOT)
            .filterNot(retainedTemplates::contains)
            .forEach { config.removeKey("$TEMPLATE_ROOT.$it") }

        return config
    }

    internal fun bind(config: Config): PaperMenuConfiguration = PaperMenuConfigurationParser.require(
            config = config,
            layoutRoot = LAYOUT_ROOT,
            templateRoot = TEMPLATE_ROOT,
            contracts = contracts,
            requiredTemplates = dynamicTemplates,
            textContracts = textContracts,
        )

    private fun referencedTemplates(config: Config): Set<String> = buildSet {
        layoutNames.forEach { layout ->
            val path = "$LAYOUT_ROOT.$layout"
            config.stringOrNull("$path.background.template")?.let(::add)
            config.keys("$path.elements").forEach { element ->
                config.stringOrNull("$path.elements.$element.template")?.let(::add)
            }
            config.keys("$path.legend").forEach { symbol ->
                config.stringOrNull("$path.legend.$symbol.template")?.let(::add)
            }
        }
    }
}
