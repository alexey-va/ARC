package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import com.magmaguy.elitemobs.mobconstructor.BossType
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.InstancedBossEntity
import org.bukkit.GameMode
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.ops.ItemPresets
import ru.arc.util.Logging.error

internal class BossEnchantmentRewards(
    private val config: Config,
    private val resolvePreset: (String, Int) -> Result<List<ItemStack>> = ItemPresets::resolveStacks,
    private val reportPresetFailure: (String, Throwable) -> Unit = { preset, failure ->
        error("Unable to resolve EliteMobs boss reward preset '$preset'", failure)
    },
) {
    fun addToNativeDeathDrops(event: EliteMobDeathEvent) {
        if (!config.bool("boss-enchantment-rewards.enabled", true)) return

        val deathEvent = event.entityDeathEvent ?: return
        val boss = event.eliteEntity as? CustomBossEntity ?: return
        if (!isEligibleBossDeath(boss, deathEvent.entity.world.uid)) return

        val preset = config.string("boss-enchantment-rewards.preset", DEFAULT_PRESET).trim()
        if (preset.isEmpty()) {
            reportPresetFailure(DEFAULT_PRESET, IllegalArgumentException("Configured preset ID is blank"))
            return
        }

        val result = resolvePreset(preset, 1)
        if (result.isFailure) {
            reportPresetFailure(preset, requireNotNull(result.exceptionOrNull()))
            return
        }
        val stacks = result.getOrThrow()
        if (stacks.isNotEmpty()) deathEvent.drops.addAll(stacks)
    }
}

private fun isEligibleBossDeath(boss: CustomBossEntity, deathWorldId: java.util.UUID): Boolean {
    val fields = boss.customBossesConfigFields
    if (fields.bossType != BossType.BOSS || fields.isReinforcement) return false
    if (boss.isReinforcementOrMount || boss.isTriggeredAntiExploit || boss.level < 1) return false

    val lockedOut = (boss as? InstancedBossEntity)?.lockoutPlayers.orEmpty()
    return boss.damagers.any { (player, damage) ->
        damage > 0.0 &&
            player.isOnline &&
            !player.hasMetadata("NPC") &&
            player.world.uid == deathWorldId &&
            player.gameMode in ELIGIBLE_GAME_MODES &&
            player !in lockedOut
    }
}

private val ELIGIBLE_GAME_MODES = setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)
private const val DEFAULT_PRESET = "enchant_boss_supply"
