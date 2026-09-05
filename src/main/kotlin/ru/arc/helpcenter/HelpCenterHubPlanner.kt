package ru.arc.helpcenter

enum class HelpCenterGoal { EARN, BUILD, EXPLORE, FIGHT, DEVELOP, TOGETHER }

enum class HelpCenterWorldKind { VANILLA, MINING, NEW_BIOMES, OTHER }

enum class HelpCenterProblem { CANNOT_TELEPORT, CANNOT_CLAIM, CANNOT_FIND_PLAYER, LOST_ITEM, COMMAND_FAILED }

data class HelpCenterHeldItem(
    val displayName: String,
    val material: String,
    val amount: Int,
    val itemsAdderId: String?,
)

data class HelpCenterContext(
    val server: String,
    val world: String,
    val worldKind: HelpCenterWorldKind,
    val x: Int,
    val y: Int,
    val z: Int,
    val heldItem: HelpCenterHeldItem?,
    val landName: String?,
    val landOwner: Boolean,
    val features: Set<HelpCenterFeature>,
)

data class HelpCenterDiagnosticFact(val id: String, val positive: Boolean)

object HelpCenterHubPlanner {
    fun goalActions(goal: HelpCenterGoal): List<String> = when (goal) {
        HelpCenterGoal.EARN -> listOf("jobs", "sell", "shops", "auction", "bank")
        HelpCenterGoal.BUILD -> listOf("builder", "items", "slimefun", "enchants", "privat")
        HelpCenterGoal.EXPLORE -> listOf("rtp", "biomes", "mining", "warps", "dungeons")
        HelpCenterGoal.FIGHT -> listOf("events", "duels", "dungeons", "battle-pass")
        HelpCenterGoal.DEVELOP -> listOf("rank", "rankup", "quests", "skills", "jobs")
        HelpCenterGoal.TOGETHER -> listOf("players", "duels", "privat", "events", "vote")
    }

    fun itemRecipeCommand(item: HelpCenterHeldItem): String? = item.itemsAdderId?.let { id ->
        require(ITEM_ID.matches(id)) { "Unsafe ItemsAdder item id" }
        "iarecipe $id"
    }

    fun itemActions(item: HelpCenterHeldItem?, features: Set<HelpCenterFeature>): List<String> = buildList {
        if (item?.itemsAdderId != null && HelpCenterFeature.ITEMS in features) add("item-recipe")
        if (HelpCenterFeature.ITEMS in features) add("items")
        add("auction")
        add("sell")
        if (HelpCenterFeature.ENCHANTMENTS in features) add("enchants")
    }

    fun diagnosticFacts(
        problem: HelpCenterProblem,
        context: HelpCenterContext,
        homesLoaded: Boolean?,
    ): List<HelpCenterDiagnosticFact> = when (problem) {
        HelpCenterProblem.CANNOT_TELEPORT -> listOf(
            HelpCenterDiagnosticFact("homes-ready", homesLoaded == true),
            HelpCenterDiagnosticFact("husk-ready", HelpCenterFeature.HUSK_HOMES in context.features),
            HelpCenterDiagnosticFact("known-world", context.worldKind != HelpCenterWorldKind.OTHER),
        )
        HelpCenterProblem.CANNOT_CLAIM -> listOf(
            HelpCenterDiagnosticFact("lands-ready", HelpCenterFeature.LANDS in context.features),
            HelpCenterDiagnosticFact("outside-land", context.landName == null),
            HelpCenterDiagnosticFact("land-owner", context.landOwner),
        )
        HelpCenterProblem.CANNOT_FIND_PLAYER -> listOf(HelpCenterDiagnosticFact("network-list-ready", true))
        HelpCenterProblem.LOST_ITEM -> listOf(HelpCenterDiagnosticFact("held-item", context.heldItem != null))
        HelpCenterProblem.COMMAND_FAILED -> listOf(HelpCenterDiagnosticFact("server-known", context.server.isNotBlank()))
    }

    private val ITEM_ID = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}")
}
