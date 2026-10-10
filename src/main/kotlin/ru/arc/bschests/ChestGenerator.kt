package ru.arc.bschests

import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.network.repos.ItemList
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.TreasureStackFactory
import ru.arc.treasure.core.Treasures
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** Rolls a bounded physical reward list from native weighted treasure pools. */
internal class ChestGenerator(
    private val config: Config,
    private val stackFactory: TreasureStackFactory = TreasureStackFactory(),
    private val poolProvider: (String) -> TreasurePool? = Treasures::getPool,
    private val nextInt: (Int) -> Int = { bound -> ThreadLocalRandom.current().nextInt(bound) },
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) {
    fun generate(player: Player, poolName: String?): ItemList {
        val commonPoolId = config.string("loot.common-pool", "structures_common").trim().lowercase(Locale.ROOT)
        require(commonPoolId.isNotEmpty()) { "Personal-loot common pool is empty" }
        val selectedPoolId = when (val requestedPoolId = poolName?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "default", "generic_bs" -> commonPoolId
            else -> requestedPoolId
        }
        val pool = poolProvider(selectedPoolId)
            ?: throw IllegalArgumentException("Personal-loot treasure pool not found: $selectedPoolId")

        val minRolls = config.integer("loot.min-rolls", DEFAULT_MIN_ROLLS)
        val maxRolls = config.integer("loot.max-rolls", DEFAULT_MAX_ROLLS)
        require(minRolls in 1..MAX_ROLLS && maxRolls in minRolls..MAX_ROLLS) {
            "Personal-loot roll range must be between 1 and $MAX_ROLLS"
        }
        val bonusChance = config.double("loot.bonus-chance", DEFAULT_BONUS_CHANCE)
        require(bonusChance.isFinite() && bonusChance in 0.0..1.0) {
            "Personal-loot bonus chance must be between 0 and 1"
        }
        val bonusPoolId = config.string("loot.bonus-pool", "structures_special").trim().lowercase(Locale.ROOT)
        require(bonusChance == 0.0 || bonusPoolId.isNotEmpty()) { "Personal-loot bonus pool is empty" }

        val contents = ArrayList<org.bukkit.inventory.ItemStack>(MAX_STACKS)
        repeat(rollInclusive(minRolls, maxRolls)) {
            val treasure = pool.random()
                ?: throw IllegalStateException("Personal-loot treasure pool is empty: $selectedPoolId")
            contents += stackFactory.createFromPool(treasure, player, selectedPoolId)
            require(contents.size <= MAX_STACKS) { "Personal-loot exceeded the $MAX_STACKS-stack chest limit" }
        }

        if (bonusChance > 0.0 && nextDouble().also { require(it.isFinite() && it in 0.0..<1.0) } < bonusChance) {
            val bonusPool = poolProvider(bonusPoolId)
                ?: throw IllegalArgumentException("Personal-loot bonus pool not found: $bonusPoolId")
            val bonusTreasure = bonusPool.random()
                ?: throw IllegalStateException("Personal-loot bonus pool is empty: $bonusPoolId")
            contents += stackFactory.createFromPool(bonusTreasure, player, bonusPoolId)
            require(contents.size <= MAX_STACKS) { "Personal-loot exceeded the $MAX_STACKS-stack chest limit" }
        }

        require(contents.isNotEmpty()) { "Personal-loot generation produced no items" }
        contents.shuffle()
        return ItemList().apply { addAll(contents) }
    }

    private fun rollInclusive(min: Int, max: Int): Int {
        if (min == max) return min
        val span = max - min + 1
        val offset = nextInt(span)
        require(offset in 0 until span) { "Random selector returned an out-of-range roll count" }
        return min + offset
    }

    private companion object {
        const val DEFAULT_MIN_ROLLS = 3
        const val DEFAULT_MAX_ROLLS = 5
        const val DEFAULT_BONUS_CHANCE = 0.10
        const val MAX_ROLLS = 50
        const val MAX_STACKS = 50
    }
}
