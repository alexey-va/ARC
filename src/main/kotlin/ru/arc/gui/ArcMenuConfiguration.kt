package ru.arc.gui

import ru.arc.config.Config
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Files
import java.nio.file.Path

/** Stable semantic contract between ARC gameplay code and operator-owned menu YAML. */
object ArcMenuSchema {
    val INVESTIGATION_HUB = MenuId.of("investigation-hub")
    val INVESTIGATION_CASE = MenuId.of("investigation-case")
    val INVESTIGATION_VERDICT = MenuId.of("investigation-verdict")
    val INVESTIGATION_TESTIMONY = MenuId.of("investigation-testimony")
    val CONTRACTS_LIST = MenuId.of("contracts-list")
    val CONTRACTS_DETAIL = MenuId.of("contracts-detail")
    val SCHEDULED_LIST = MenuId.of("scheduled-list")
    val ELITE_LOOT = MenuId.of("elite-loot")
    val PERSONAL_LOOT = (1..6).associateWith { MenuId.of("personal-loot-$it") }
    val PARKOUR_ROOT = MenuId.of("parkour-root")
    val PARKOUR_CATEGORY = MenuId.of("parkour-category")
    val ITEM_CATALOG = MenuId.of("item-catalog")
    val EM_SHOP = MenuId.of("elite-mobs-shop")
    val SCHEDULED_EDIT = MenuId.of("scheduled-edit")
    val BALTOP = MenuId.of("baltop")
    val JOIN_MESSAGES = MenuId.of("join-messages")
    val JOBS_LIST = MenuId.of("jobs-list")
    val JOB_BOOSTS = MenuId.of("job-boosts")
    val BOOST_SHOP = MenuId.of("boost-shop")
    val TREASURE_POOLS = MenuId.of("treasure-pools")
    val TREASURE_POOL = MenuId.of("treasure-pool")
    val TREASURE_EDIT = MenuId.of("treasure-edit")
    val BOARD = MenuId.of("board")
    val BOARD_RATE = MenuId.of("board-rate")
    val BOARD_EDIT = MenuId.of("board-edit")
    val MOUNT_LIST = MenuId.of("mount-list")
    val MOUNT_DETAIL = MenuId.of("mount-detail")
    val MOUNT_PROGRESSION = MenuId.of("mount-progression")
    val MOUNT_SKINS = MenuId.of("mount-skins")
    val MOUNT_CONFIRM = MenuId.of("mount-confirm")
    val STOCK_SYMBOLS = MenuId.of("stock-symbols")
    val STOCK_POSITIONS = MenuId.of("stock-positions")
    val STOCK_PROFILE = MenuId.of("stock-profile")
    val STOCK_POSITION = MenuId.of("stock-position")
    val STOCK_CREATE = MenuId.of("stock-create")
    val STORE = (2..6).associateWith { MenuId.of("store-$it") }

    val WITNESSES = MenuRegionId.of("witnesses")
    val VERDICTS = MenuRegionId.of("verdicts")
    val CONTRACT_ORDERS = MenuRegionId.of("orders")
    val SCHEDULED_ENTRIES = MenuRegionId.of("entries")
    val ELITE_LOOT_ITEMS = MenuRegionId.of("items")
    val PERSONAL_LOOT_ITEMS = MenuRegionId.of("loot")
    val PARKOUR_CATEGORIES = MenuRegionId.of("categories")
    val PARKOUR_COURSES = MenuRegionId.of("courses")
    val CATALOG_ITEMS = MenuRegionId.of("catalog-items")
    val EM_SHOP_ITEMS = MenuRegionId.of("shop-items")
    val BALTOP_ENTRIES = MenuRegionId.of("baltop-entries")
    val JOIN_MESSAGE_ENTRIES = MenuRegionId.of("message-entries")
    val JOB_ENTRIES = MenuRegionId.of("jobs")
    val JOB_BOOST_ENTRIES = MenuRegionId.of("boosts")
    val BOOST_SHOP_ENTRIES = MenuRegionId.of("offers")
    val TREASURE_POOL_ENTRIES = MenuRegionId.of("pools")
    val TREASURE_ENTRIES = MenuRegionId.of("treasures")
    val BOARD_ENTRIES = MenuRegionId.of("board-entries")
    val MOUNT_ENTRIES = MenuRegionId.of("mounts")
    val MOUNT_ABILITIES = MenuRegionId.of("abilities")
    val MOUNT_SPEEDS = MenuRegionId.of("speeds")
    val MOUNT_STEPS = MenuRegionId.of("steps")
    val MOUNT_SIZES = MenuRegionId.of("sizes")
    val MOUNT_SKIN_ENTRIES = MenuRegionId.of("skins")
    val STOCK_SYMBOL_ENTRIES = MenuRegionId.of("symbols")
    val STOCK_POSITION_ENTRIES = MenuRegionId.of("positions")
    val STORE_ITEMS = MenuRegionId.of("store-items")

    private fun elements(vararg ids: String) = ids.mapTo(linkedSetOf(), MenuElementId::of)

    val contracts: Map<MenuId, MenuContract> = linkedMapOf(
        INVESTIGATION_HUB to MenuContract(requiredElements = elements("start", "contracts")),
        INVESTIGATION_CASE to MenuContract(
            requiredElements = elements("next-step", "dossier", "evidence", "return"),
            requiredRegions = setOf(WITNESSES),
        ),
        INVESTIGATION_VERDICT to MenuContract(
            requiredElements = elements("question", "back"),
            requiredRegions = setOf(VERDICTS),
        ),
        INVESTIGATION_TESTIMONY to MenuContract(requiredElements = elements("statement")),
        CONTRACTS_LIST to MenuContract(
            requiredElements = elements("info"),
            optionalElements = elements("empty"),
            requiredRegions = setOf(CONTRACT_ORDERS),
        ),
        CONTRACTS_DETAIL to MenuContract(
            requiredElements = elements("resource", "info", "payout", "quantity", "back", "confirm"),
        ),
        SCHEDULED_LIST to MenuContract(
            requiredElements = elements("refresh"),
            requiredRegions = setOf(SCHEDULED_ENTRIES),
        ),
        ELITE_LOOT to MenuContract(
            requiredElements = elements("previous", "next", "back"),
            requiredRegions = setOf(ELITE_LOOT_ITEMS),
        ),
        *PERSONAL_LOOT.values.map { menu ->
            menu to MenuContract(requiredRegions = setOf(PERSONAL_LOOT_ITEMS))
        }.toTypedArray(),
        PARKOUR_ROOT to MenuContract(requiredRegions = setOf(PARKOUR_CATEGORIES)),
        PARKOUR_CATEGORY to MenuContract(
            requiredElements = elements("back"),
            requiredRegions = setOf(PARKOUR_COURSES),
        ),
        ITEM_CATALOG to MenuContract(
            requiredElements = elements("back", "previous", "page", "next"),
            requiredRegions = setOf(CATALOG_ITEMS),
        ),
        EM_SHOP to MenuContract(
            requiredElements = elements("change", "update"),
            requiredRegions = setOf(EM_SHOP_ITEMS),
        ),
        SCHEDULED_EDIT to MenuContract(
            requiredElements = elements("command", "id", "schedule-value", "schedule-type", "servers", "enabled", "back", "save"),
            optionalElements = elements("run-now"),
        ),
        BALTOP to MenuContract(
            requiredElements = elements("back", "previous", "sort", "next"),
            requiredRegions = setOf(BALTOP_ENTRIES),
        ),
        JOIN_MESSAGES to MenuContract(
            requiredElements = elements("back", "previous", "switch", "next"),
            requiredRegions = setOf(JOIN_MESSAGE_ENTRIES),
        ),
        JOBS_LIST to MenuContract(
            requiredElements = elements("back", "buy"),
            requiredRegions = setOf(JOB_ENTRIES),
        ),
        JOB_BOOSTS to MenuContract(
            requiredElements = elements("back", "buy"),
            requiredRegions = setOf(JOB_BOOST_ENTRIES),
        ),
        BOOST_SHOP to MenuContract(
            requiredElements = elements("type", "back"),
            requiredRegions = setOf(BOOST_SHOP_ENTRIES),
        ),
        TREASURE_POOLS to MenuContract(
            requiredElements = elements("back", "previous", "next", "create"),
            requiredRegions = setOf(TREASURE_POOL_ENTRIES),
        ),
        TREASURE_POOL to MenuContract(
            requiredElements = elements("back", "previous", "next", "messages", "add-item", "add-money", "add-command", "delete"),
            requiredRegions = setOf(TREASURE_ENTRIES),
        ),
        TREASURE_EDIT to MenuContract(
            requiredElements = elements("weight", "messages", "delete", "back"),
            optionalElements = elements("amount"),
        ),
        BOARD to MenuContract(
            requiredElements = elements("back", "previous", "next", "publish"),
            requiredRegions = setOf(BOARD_ENTRIES),
        ),
        BOARD_RATE to MenuContract(requiredElements = elements("up", "down", "report", "back")),
        BOARD_EDIT to MenuContract(
            requiredElements = elements("title", "description", "color", "icon", "type", "back", "publish"),
            optionalElements = elements("delete"),
        ),
        MOUNT_LIST to MenuContract(
            requiredElements = elements("info", "back", "previous", "filter", "next"),
            requiredRegions = setOf(MOUNT_ENTRIES),
        ),
        MOUNT_DETAIL to MenuContract(
            requiredElements = elements("icon", "favorite", "upgrade", "summon", "glow", "skins", "back", "whistle"),
            requiredRegions = setOf(MOUNT_ABILITIES),
        ),
        MOUNT_PROGRESSION to MenuContract(
            requiredElements = elements("info", "level", "back", "rider-view"),
            requiredRegions = setOf(MOUNT_SPEEDS, MOUNT_STEPS, MOUNT_SIZES),
        ),
        MOUNT_SKINS to MenuContract(
            requiredElements = elements("back"),
            requiredRegions = setOf(MOUNT_SKIN_ENTRIES),
        ),
        MOUNT_CONFIRM to MenuContract(requiredElements = elements("cancel", "info", "accept")),
        STOCK_SYMBOLS to MenuContract(
            requiredElements = elements("market", "back", "all", "profile"),
            requiredRegions = setOf(STOCK_SYMBOL_ENTRIES),
        ),
        STOCK_POSITIONS to MenuContract(
            requiredElements = elements("back", "create", "profile"),
            requiredRegions = setOf(STOCK_POSITION_ENTRIES),
        ),
        STOCK_PROFILE to MenuContract(requiredElements = elements("statistics", "balance", "auto", "back")),
        STOCK_POSITION to MenuContract(requiredElements = elements("info", "close", "back")),
        STOCK_CREATE to MenuContract(requiredElements = elements("amount", "type", "leverage", "upper", "lower", "create", "back")),
        *STORE.values.map { menu ->
            menu to MenuContract(
                requiredElements = elements("back"),
                requiredRegions = setOf(STORE_ITEMS),
            )
        }.toTypedArray(),
    )

    val textContracts: Map<String, PaperMenuTextContract> = mapOf(
        "background" to PaperMenuTextContract(),
        "store-back" to PaperMenuTextContract(),
        "investigation-start" to PaperMenuTextContract(
            values = setOf("fee", "reward", "duration", "cooldown", "action"),
        ),
        "investigation-contracts" to PaperMenuTextContract(values = setOf("action")),
        "investigation-next-step" to PaperMenuTextContract(
            values = setOf("time", "clues"),
            repeats = mapOf("directions" to setOf("line"), "warnings" to setOf("line")),
        ),
        "investigation-dossier" to PaperMenuTextContract(
            values = setOf("title"),
            repeats = mapOf("dossier" to setOf("line")),
        ),
        "investigation-evidence" to PaperMenuTextContract(
            flags = setOf("has-links"),
            repeats = mapOf("timeline" to setOf("line"), "links" to setOf("line")),
        ),
        "investigation-return" to PaperMenuTextContract(
            values = setOf("clues", "missing", "action"),
            flags = setOf("ready"),
        ),
        "investigation-witness" to PaperMenuTextContract(
            values = setOf("name", "location"),
            flags = setOf("collected"),
            repeats = mapOf("body" to setOf("line")),
        ),
        "investigation-question" to PaperMenuTextContract(values = setOf("question")),
        "investigation-verdict" to PaperMenuTextContract(
            values = setOf("title"),
            flags = setOf("ready"),
            repeats = mapOf("explanation" to setOf("line")),
        ),
        "investigation-back" to PaperMenuTextContract(values = setOf("action")),
        "investigation-testimony" to PaperMenuTextContract(
            values = setOf("name"),
            repeats = mapOf("testimony" to setOf("line")),
        ),
        "investigation-verdict-one" to verdictContract(),
        "investigation-verdict-two" to verdictContract(),
        "investigation-verdict-three" to verdictContract(),
        "investigation-verdict-four" to verdictContract(),
        "investigation-verdict-five" to verdictContract(),
        "contracts-list-info" to PaperMenuTextContract(values = setOf("heading", "description-one", "description-two")),
        "contracts-list-empty" to PaperMenuTextContract(values = setOf("empty")),
        "contracts-order" to PaperMenuTextContract(
            values = setOf(
                "contract-name", "available", "remaining", "target", "player-remaining", "payout",
                "cap-bonus", "payout-bonus", "ends-at", "action",
            ),
        ),
        "contracts-detail-resource" to PaperMenuTextContract(
            values = setOf("contract-name", "available", "remaining", "player-remaining"),
        ),
        "contracts-detail-info" to PaperMenuTextContract(values = setOf("heading", "accepted", "target", "contributors")),
        "contracts-detail-payout" to PaperMenuTextContract(values = setOf("payout", "per-unit", "payout-bonus")),
        "contracts-detail-quantity" to PaperMenuTextContract(values = setOf("selected", "minimum", "maximum")),
        "contracts-detail-back" to PaperMenuTextContract(),
        "contracts-detail-confirm" to PaperMenuTextContract(
            values = setOf("selected", "payout"),
            flags = setOf("can-submit"),
        ),
        "scheduled-refresh" to PaperMenuTextContract(),
        "scheduled-entry-enabled" to scheduledEntryContract(),
        "scheduled-entry-disabled" to scheduledEntryContract(),
        "elite-loot-previous" to PaperMenuTextContract(),
        "elite-loot-next" to PaperMenuTextContract(),
        "elite-loot-back" to PaperMenuTextContract(),
        "parkour-category" to PaperMenuTextContract(
            values = setOf("category", "ready"),
            repeats = mapOf("description" to setOf("line")),
        ),
        "parkour-course" to PaperMenuTextContract(
            values = setOf("course", "checkpoints", "players"),
            flags = setOf("completed"),
        ),
        "parkour-back" to PaperMenuTextContract(),
        "catalog-root-entry" to PaperMenuTextContract(
            values = setOf("name", "categories", "items", "action"),
            repeats = mapOf("description" to setOf("line")),
        ),
        "catalog-category" to PaperMenuTextContract(values = setOf("name", "items", "action")),
        "catalog-preview" to PaperMenuTextContract(
            values = setOf("name", "id", "action"),
            repeats = mapOf("original" to setOf("line")),
        ),
        "catalog-back" to PaperMenuTextContract(),
        "catalog-previous" to PaperMenuTextContract(),
        "catalog-page" to PaperMenuTextContract(values = setOf("page", "pages", "shown", "total")),
        "catalog-next" to PaperMenuTextContract(),
        "em-shop-change" to PaperMenuTextContract(values = setOf("type")),
        "em-shop-update" to PaperMenuTextContract(values = setOf("minutes", "balance", "player")),
        "em-shop-item" to PaperMenuTextContract(
            values = setOf("name", "price"),
            repeats = mapOf("original" to setOf("line")),
        ),
        "scheduled-edit-command" to PaperMenuTextContract(values = setOf("command")),
        "scheduled-edit-id" to PaperMenuTextContract(values = setOf("id")),
        "scheduled-edit-value-cron" to PaperMenuTextContract(values = setOf("value", "hint"), flags = setOf("weekly")),
        "scheduled-edit-value-interval" to PaperMenuTextContract(values = setOf("value", "hint"), flags = setOf("weekly")),
        "scheduled-edit-value-time" to PaperMenuTextContract(values = setOf("value", "hint"), flags = setOf("weekly")),
        "scheduled-edit-type" to PaperMenuTextContract(values = setOf("type")),
        "scheduled-edit-servers" to PaperMenuTextContract(values = setOf("servers")),
        "scheduled-edit-enabled" to PaperMenuTextContract(flags = setOf("enabled")),
        "scheduled-edit-enabled-on" to PaperMenuTextContract(),
        "scheduled-edit-enabled-off" to PaperMenuTextContract(),
        "scheduled-edit-back" to PaperMenuTextContract(),
        "scheduled-edit-run-now" to PaperMenuTextContract(),
        "scheduled-edit-save" to PaperMenuTextContract(),
        "baltop-entry" to PaperMenuTextContract(values = setOf("player", "balance", "bank", "total")),
        "baltop-sort" to PaperMenuTextContract(values = setOf("sort")),
        "baltop-back" to PaperMenuTextContract(),
        "menu-previous" to PaperMenuTextContract(),
        "menu-next" to PaperMenuTextContract(),
        "join-message-entry" to PaperMenuTextContract(
            values = setOf("name"),
            repeats = mapOf("lore" to setOf("line")),
        ),
        "join-message-switch" to PaperMenuTextContract(values = setOf("mode")),
        "join-message-back" to PaperMenuTextContract(),
        "jobs-entry" to PaperMenuTextContract(values = setOf("job", "exp", "money", "points")),
        "jobs-back" to PaperMenuTextContract(),
        "jobs-buy" to PaperMenuTextContract(),
        "job-base-boost" to PaperMenuTextContract(values = setOf("type", "boost")),
        "job-player-boost" to PaperMenuTextContract(values = setOf("type", "amount", "expire", "id")),
        "boost-shop-type" to PaperMenuTextContract(values = setOf("type")),
        "boost-shop-offer" to PaperMenuTextContract(
            values = setOf("name", "price", "boost", "currency", "permission", "time", "type", "job", "player-currency", "currency-lack"),
            flags = setOf("owned", "no-permission", "no-funds", "available"),
            repeats = mapOf("details" to setOf("line")),
        ),
        "treasure-pool-entry" to PaperMenuTextContract(values = setOf("pool", "size", "weight")),
        "treasure-create-pool" to PaperMenuTextContract(),
        "treasure-pool-messages" to PaperMenuTextContract(
            values = setOf("count"), flags = setOf("empty"), repeats = mapOf("messages" to setOf("line")),
        ),
        "treasure-action" to PaperMenuTextContract(values = setOf("name", "action")),
        "treasure-delete-pool" to PaperMenuTextContract(),
        "treasure-entry" to PaperMenuTextContract(
            values = setOf("name", "type", "weight"), repeats = mapOf("original" to setOf("line")),
        ),
        "treasure-edit-weight" to PaperMenuTextContract(values = setOf("weight")),
        "treasure-edit-amount" to PaperMenuTextContract(values = setOf("name", "value")),
        "treasure-edit-messages" to PaperMenuTextContract(
            values = setOf("count"), flags = setOf("empty"), repeats = mapOf("messages" to setOf("line")),
        ),
        "treasure-edit-delete" to PaperMenuTextContract(),
        "treasure-back" to PaperMenuTextContract(),
        "board-contract" to PaperMenuTextContract(values = setOf("name", "status", "item", "accepted", "reserved", "target", "progress", "remaining", "payout", "budget", "ends", "action")),
        "board-contract-empty" to PaperMenuTextContract(values = setOf("state", "budget")),
        "board-publish" to PaperMenuTextContract(values = setOf("cost")),
        "board-back" to PaperMenuTextContract(),
        "board-rate-action" to PaperMenuTextContract(values = setOf("name"), flags = setOf("applied")),
        "board-rate-back" to PaperMenuTextContract(),
        "board-edit-field" to PaperMenuTextContract(values = setOf("name", "value", "action"), repeats = mapOf("details" to setOf("line"))),
        "board-edit-color" to PaperMenuTextContract(values = setOf("color")),
        "board-edit-type" to PaperMenuTextContract(values = setOf("type")),
        "board-edit-icon" to PaperMenuTextContract(),
        "board-edit-submit" to PaperMenuTextContract(values = setOf("action", "cost")),
        "board-edit-delete" to PaperMenuTextContract(flags = setOf("confirm")),
    )

    private fun verdictContract() = PaperMenuTextContract(
        values = setOf("title"),
        flags = setOf("ready"),
        repeats = mapOf("explanation" to setOf("line")),
    )

    private fun scheduledEntryContract() = PaperMenuTextContract(
        values = setOf("id", "schedule", "command", "servers", "action"),
    )
}

object ArcMenuConfiguration {
    const val RESOURCE = "guis/menus.yml"

    fun load(dataRoot: Path): PaperMenuConfiguration = parse(Config(dataRoot, RESOURCE))

    internal fun loadResource(classLoader: ClassLoader): PaperMenuConfiguration {
        val root = Files.createTempDirectory("arc-menu-resource")
        val target = root.resolve(RESOURCE)
        Files.createDirectories(target.parent)
        classLoader.getResourceAsStream(RESOURCE).use { source ->
            requireNotNull(source) { "Bundled $RESOURCE is missing" }
            Files.copy(source, target)
        }
        return load(root)
    }

    private fun parse(config: Config): PaperMenuConfiguration =
        PaperMenuConfigurationParser.require(
            config,
            "menus.layouts",
            "menus.templates",
            ArcMenuSchema.contracts,
            requiredTemplates = setOf(
                "investigation-witness",
                "investigation-verdict-one",
                "investigation-verdict-two",
                "investigation-verdict-three",
                "investigation-verdict-four",
                "investigation-verdict-five",
                "contracts-order",
                "scheduled-entry-enabled",
                "scheduled-entry-disabled",
                "parkour-category",
                "parkour-course",
                "catalog-root-entry",
                "catalog-category",
                "catalog-preview",
                "em-shop-item",
                "scheduled-edit-value-cron",
                "scheduled-edit-value-interval",
                "scheduled-edit-value-time",
                "scheduled-edit-enabled-on",
                "scheduled-edit-enabled-off",
                "baltop-entry",
                "join-message-entry",
                "jobs-entry",
                "job-base-boost",
                "job-player-boost",
                "boost-shop-offer",
                "treasure-pool-entry",
                "treasure-entry",
                "board-contract",
                "board-edit-field",
            ),
            textContracts = ArcMenuSchema.textContracts,
        )
}
