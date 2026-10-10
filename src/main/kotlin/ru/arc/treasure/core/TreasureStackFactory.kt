package ru.arc.treasure.core

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.enchanting.AdvancedBookCandidate
import ru.arc.enchanting.configuredAdvancedBookLevels
import ru.arc.enchanting.createFreshAdvancedBook
import ru.arc.enchanting.eligibleAdvancedBookCandidates
import ru.arc.helpcenter.HelpCenterEnchantmentsCatalog
import ru.arc.hooks.HookRegistry
import ru.arc.ops.ItemPresets
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/**
 * Materializes a treasure as physical stacks without granting items or running payout effects.
 * Command and money rewards deliberately fail because a chest pool must be all-or-nothing.
 */
internal class TreasureStackFactory(
    private val poolProvider: (String) -> TreasurePool? = Treasures::getPool,
    private val presetResolver: (String, Int) -> List<ItemStack> = { preset, amount ->
        ItemPresets.resolveStacks(preset, amount).getOrThrow()
    },
    private val aeItemResolver: (Treasure.Ae) -> List<ItemStack>? = { AeNativeItems.create(it) },
    private val slimefunResolver: (String) -> ItemStack? = { id ->
        HookRegistry.sfHook?.getSlimefunItemStack(id)
    },
    private val bookCandidatesProvider: () -> Map<String, List<AdvancedBookCandidate>> = {
        val catalog = HelpCenterEnchantmentsCatalog.fromAeApi()
        eligibleAdvancedBookCandidates(
            catalog = catalog,
            groupWeights = PUBLIC_BOOK_GROUPS.associateWith { 1 },
            configuredLevels = ::configuredAdvancedBookLevels,
        )
    },
    private val createBook: (String, Int, Player) -> ItemStack = ::createFreshAdvancedBook,
    private val nextInt: (Int) -> Int = { bound -> ThreadLocalRandom.current().nextInt(bound) },
) {
    @Volatile
    private var cachedBookCandidates: Map<String, List<AdvancedBookCandidate>>? = null

    private val candidateLock = Any()

    /** Builds a standalone treasure. Nested sub-pools are bounded by depth and cycle checks. */
    fun create(treasure: Treasure, player: Player): List<ItemStack> = materialize(treasure, player, emptySet(), 0)

    /** Gives sub-pool traversal the owning pool ID so a back-edge is rejected immediately. */
    internal fun createFromPool(treasure: Treasure, player: Player, sourcePoolId: String): List<ItemStack> =
        materialize(treasure, player, setOf(sourcePoolId), 0)

    private fun materialize(
        treasure: Treasure,
        player: Player,
        visitedPools: Set<String>,
        depth: Int,
    ): List<ItemStack> {
        require(depth <= MAX_SUBPOOL_DEPTH) { "Treasure sub-pool nesting exceeds $MAX_SUBPOOL_DEPTH" }
        val output = mutableListOf<ItemStack>()
        when (treasure) {
            is Treasure.Item -> appendSplit(output, treasure.stack, rollAmount(treasure.min, treasure.max))
            is Treasure.Enchant -> repeat(treasure.amount) { appendStack(output, treasure.randomBook()) }
            is Treasure.Potion -> repeat(treasure.amount) { appendStack(output, Treasure.Potion.randomPotion()) }
            is Treasure.Ae -> {
                if (treasure.kind == AeKind.RANDOM_BOOK) {
                    repeat(treasure.amount) { appendStack(output, createRandomBook(treasure, player)) }
                } else {
                    val stacks = aeItemResolver(treasure)
                        ?: throw IllegalStateException("AdvancedEnchantments item is unavailable: ${treasure.itemName}")
                    stacks.forEach { appendStack(output, it) }
                }
            }
            is Treasure.Preset -> {
                require(treasure.amount in 1..MAX_PRESET_AMOUNT) { "Preset amount must be between 1 and $MAX_PRESET_AMOUNT" }
                val stacks = presetResolver(treasure.preset, treasure.amount)
                require(stacks.isNotEmpty()) { "Item preset '${treasure.preset}' resolved to no stacks" }
                stacks.forEach { appendSplit(output, it, it.amount) }
            }
            is Treasure.Slimefun -> {
                val template = slimefunResolver(treasure.itemId)
                    ?: throw IllegalStateException("Slimefun item is unavailable: ${treasure.itemId}")
                appendSplit(output, template, treasure.rolledAmount)
            }
            is Treasure.SubPool -> {
                require(treasure.poolId !in visitedPools) { "Treasure sub-pool cycle: ${(visitedPools + treasure.poolId).joinToString(" -> ")}" }
                require(depth < MAX_SUBPOOL_DEPTH) { "Treasure sub-pool nesting exceeds $MAX_SUBPOOL_DEPTH" }
                val pool = poolProvider(treasure.poolId)
                    ?: throw IllegalArgumentException("Treasure sub-pool not found: ${treasure.poolId}")
                val child = pool.random()
                    ?: throw IllegalArgumentException("Treasure sub-pool is empty: ${treasure.poolId}")
                output += materialize(child, player, visitedPools + treasure.poolId, depth + 1)
            }
            is Treasure.Money -> throw IllegalArgumentException("Money treasure cannot be placed in a physical chest pool")
            is Treasure.Command -> throw IllegalArgumentException("Command treasure cannot be placed in a physical chest pool")
        }
        require(output.isNotEmpty()) { "Treasure '${treasure.id}' produced no physical items" }
        return output.map(ItemStack::clone)
    }

    private fun createRandomBook(treasure: Treasure.Ae, player: Player): ItemStack {
        val group = treasure.group?.uppercase(Locale.ROOT) ?: AeLoot.randomTier()
        val candidates = candidates()[group]
            ?: throw IllegalStateException("No configured AE acquisition books are available for group $group")
        val eligible = candidates.mapNotNull { candidate ->
            val levels = candidate.levels.filter { level -> treasure.maxLevel == null || level <= treasure.maxLevel }
            candidate.takeIf { levels.isNotEmpty() }?.let { it to levels }
        }
        require(eligible.isNotEmpty()) {
            "No configured AE acquisition books are available for group $group at or below level ${treasure.maxLevel ?: "any"}"
        }
        val (candidate, levels) = choose(eligible)
        return createBook(candidate.id, choose(levels), player).also {
            require(!it.type.isAir && it.amount == 1) { "AE fresh-book factory returned an invalid stack" }
        }
    }

    private fun candidates(): Map<String, List<AdvancedBookCandidate>> {
        cachedBookCandidates?.let { return it }
        synchronized(candidateLock) {
            cachedBookCandidates?.let { return it }
            val loaded = bookCandidatesProvider().mapKeys { it.key.uppercase(Locale.ROOT) }
            // Do not pin provider-unavailable startup state; a later call can bind after AE is enabled.
            if (loaded.isNotEmpty()) cachedBookCandidates = loaded
            return loaded
        }
    }

    private fun <T> choose(options: List<T>): T {
        require(options.isNotEmpty()) { "Cannot select from an empty treasure candidate list" }
        val index = nextInt(options.size)
        require(index in options.indices) { "Random selector returned an out-of-range index" }
        return options[index]
    }

    private fun rollAmount(min: Int, max: Int): Int {
        require(min >= 1 && max >= min) { "Invalid physical treasure amount range $min..$max" }
        if (min == max) return min
        val span = Math.addExact(Math.subtractExact(max, min), 1)
        return min + nextInt(span).also { require(it in 0 until span) { "Random selector returned an out-of-range amount" } }
    }

    private fun appendSplit(output: MutableList<ItemStack>, prototype: ItemStack, amount: Int) {
        require(!prototype.type.isAir) { "Physical treasure factory returned air" }
        require(amount > 0) { "Physical treasure amount must be positive" }
        var remaining = amount
        val maxStack = prototype.maxStackSize.coerceAtLeast(1)
        while (remaining > 0) {
            val stack = prototype.clone().apply { this.amount = minOf(remaining, maxStack) }
            appendStack(output, stack)
            remaining -= stack.amount
        }
    }

    private fun appendStack(output: MutableList<ItemStack>, stack: ItemStack) {
        require(!stack.type.isAir && stack.amount in 1..stack.maxStackSize) { "Physical treasure factory returned an invalid stack" }
        require(output.size < MAX_PHYSICAL_STACKS) {
            "Physical treasure exceeds the chest limit of $MAX_PHYSICAL_STACKS stacks"
        }
        output += stack.clone()
    }

    private companion object {
        const val MAX_SUBPOOL_DEPTH = 8
        const val MAX_PHYSICAL_STACKS = 54
        const val MAX_PRESET_AMOUNT = 64
        val PUBLIC_BOOK_GROUPS = setOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY", "FABLED")
    }
}
