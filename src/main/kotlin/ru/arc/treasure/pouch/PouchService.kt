package ru.arc.treasure.pouch

import org.bukkit.entity.Player
import ru.arc.treasure.core.GiveConfig
import ru.arc.treasure.core.GiveResult
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import java.util.concurrent.ThreadLocalRandom

data class PouchOpenResult(
    val attempted: Int,
    val awarded: Int,
    val failures: List<String>,
) {
    val shouldConsume: Boolean get() = awarded > 0
}

class PouchService(
    private val poolProvider: (String) -> TreasurePool?,
    private val giveTreasure: (Treasure, Player, GiveConfig) -> GiveResult,
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val nextIntInclusive: (Int, Int) -> Int = { min, max ->
        if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)
    },
) {
    fun open(definition: PouchDefinition, player: Player): PouchOpenResult {
        val pools = definition.rewards.associate { source ->
            source.poolId to poolProvider(source.poolId)
        }
        val invalid = pools.filterValues { it == null || it.isEmpty() }.keys
        if (invalid.isNotEmpty()) {
            return PouchOpenResult(0, 0, invalid.sorted().map { "Pool unavailable: $it" })
        }

        var attempted = 0
        var awarded = 0
        val failures = mutableListOf<String>()
        definition.rewards.forEach { source ->
            if (source.chance < 1.0 && nextDouble() >= source.chance) return@forEach
            val pool = checkNotNull(pools[source.poolId])
            repeat(nextIntInclusive(source.rolls.min, source.rolls.max)) {
                attempted++
                val treasure = runCatching { pool.random() }.getOrElse { failure ->
                    failures += "Pool selection failed: ${source.poolId} (${failure::class.simpleName})"
                    return@repeat
                }
                if (treasure == null) {
                    failures += "Pool has no positive-weight rewards: ${source.poolId}"
                } else {
                    val result = runCatching {
                        giveTreasure(treasure, player, GiveConfig.SILENT)
                    }.getOrElse { failure ->
                        failures += "Reward handler failed: ${source.poolId} (${failure::class.simpleName})"
                        return@repeat
                    }
                    when (result) {
                        is GiveResult.Success -> awarded++
                        is GiveResult.Failure -> failures += result.reason
                    }
                }
            }
        }
        return PouchOpenResult(attempted, awarded, failures)
    }
}
