package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.mobconstructor.BossType
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.InstancedBossEntity
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.io.File

class BossEnchantmentRewardsTest : FreeSpec({
    "boss reward handler runs after EliteMobs and receives the native mutable death drops" {
        val handler =
            EMListener::class.java.getDeclaredMethod(
                "dropBossEnchantmentSupply",
                EliteMobDeathEvent::class.java,
            )
        handler.getAnnotation(EventHandler::class.java).priority shouldBe EventPriority.HIGHEST

        YamlConfiguration.loadConfiguration(File("src/main/resources/plugin.yml"))
            .getStringList("softdepend") shouldContain "EliteMobs"

        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val player = paper.addPlayer("BossSupplyTest")
                player.gameMode = GameMode.SURVIVAL
                val world = player.world
                val entity = world.spawn(Location(world, 0.0, 64.0, 0.0), Zombie::class.java)
                val nativeDrops = mutableListOf(ItemStack(Material.ROTTEN_FLESH))
                val nativeDeath = EntityDeathEvent(entity, mockk<DamageSource>(relaxed = true), nativeDrops, 0)
                val boss = boss(BossType.BOSS, player, entity)
                val event = EliteMobDeathEvent(boss, nativeDeath)
                val presetItem = ItemStack(Material.ENCHANTED_BOOK)
                val calls = mutableListOf<Pair<String, Int>>()
                val config = config(enabled = true, preset = "enchant_boss_supply")
                val rewards =
                    BossEnchantmentRewards(
                        config = config,
                        resolvePreset = { id, amount ->
                            calls += id to amount
                            Result.success(listOf(presetItem))
                        },
                    )

                EMListener(config = config, bossEnchantmentRewards = rewards)
                    .dropBossEnchantmentSupply(event)

                calls shouldBe listOf("enchant_boss_supply" to 1)
                nativeDeath.drops shouldBe listOf(nativeDrops.first(), presetItem)
            }
        }
    }

    "one locked-out or otherwise ineligible damager cannot authorize a shared drop" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val player = paper.addPlayer("LockedOutBossSupplyTest")
                player.gameMode = GameMode.SURVIVAL
                val world = player.world
                val entity = world.spawn(Location(world, 0.0, 64.0, 0.0), Zombie::class.java)
                val nativeDrops = mutableListOf(ItemStack(Material.ROTTEN_FLESH))
                val nativeDeath = EntityDeathEvent(entity, mockk<DamageSource>(relaxed = true), nativeDrops, 0)
                val config = config(enabled = true)
                var resolutions = 0
                val rewards =
                    BossEnchantmentRewards(
                        config = config,
                        resolvePreset = { _, _ ->
                            resolutions++
                            Result.success(listOf(ItemStack(Material.ENCHANTED_BOOK)))
                        },
                    )
                val lockedBoss = boss(BossType.BOSS, player, entity, instanced = true, lockedOut = setOf(player))

                rewards.addToNativeDeathDrops(EliteMobDeathEvent(lockedBoss, nativeDeath))
                nativeDeath.drops shouldBe nativeDrops

                val normalBoss = boss(BossType.NORMAL, player, entity)
                rewards.addToNativeDeathDrops(EliteMobDeathEvent(normalBoss, nativeDeath))

                val reinforcementBoss = boss(BossType.BOSS, player, entity, reinforcement = true)
                rewards.addToNativeDeathDrops(EliteMobDeathEvent(reinforcementBoss, nativeDeath))

                val summonedBoss = boss(BossType.BOSS, player, entity, reinforcementOrMount = true)
                rewards.addToNativeDeathDrops(EliteMobDeathEvent(summonedBoss, nativeDeath))

                val antiExploitBoss = boss(BossType.BOSS, player, entity, antiExploit = true)
                rewards.addToNativeDeathDrops(EliteMobDeathEvent(antiExploitBoss, nativeDeath))

                player.gameMode = GameMode.CREATIVE
                val creativeBoss = boss(BossType.BOSS, player, entity)
                rewards.addToNativeDeathDrops(EliteMobDeathEvent(creativeBoss, nativeDeath))

                resolutions shouldBe 0
                nativeDeath.drops shouldBe nativeDrops
            }
        }
    }

    "preset resolution failure leaves native drops untouched and special events without a death payload are ignored" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val player = paper.addPlayer("BossSupplyFailureTest")
                player.gameMode = GameMode.ADVENTURE
                val world = player.world
                val entity = world.spawn(Location(world, 0.0, 64.0, 0.0), Zombie::class.java)
                val nativeDrops = mutableListOf(ItemStack(Material.ROTTEN_FLESH))
                val nativeDeath = EntityDeathEvent(entity, mockk<DamageSource>(relaxed = true), nativeDrops, 0)
                val reported = mutableListOf<String>()
                val config = config(enabled = true)
                val rewards =
                    BossEnchantmentRewards(
                        config = config,
                        resolvePreset = { id, amount ->
                            amount shouldBe 1
                            Result.failure(IllegalArgumentException("missing preset"))
                        },
                        reportPresetFailure = { preset, _ -> reported += preset },
                    )
                val boss = boss(BossType.BOSS, player, entity)

                rewards.addToNativeDeathDrops(EliteMobDeathEvent(boss, nativeDeath))
                rewards.addToNativeDeathDrops(EliteMobDeathEvent(boss))

                reported shouldBe listOf("enchant_boss_supply")
                nativeDeath.drops shouldBe nativeDrops
            }
        }
    }
})

private fun config(enabled: Boolean, preset: String = "enchant_boss_supply"): Config =
    mockk {
        every { bool("boss-enchantment-rewards.enabled", true) } returns enabled
        every { string("boss-enchantment-rewards.preset", "enchant_boss_supply") } returns preset
    }

private fun boss(
    type: BossType,
    player: Player,
    entity: LivingEntity,
    reinforcement: Boolean = false,
    reinforcementOrMount: Boolean = false,
    antiExploit: Boolean = false,
    level: Int = 10,
    instanced: Boolean = false,
    lockedOut: Set<Player> = emptySet(),
): CustomBossEntity {
    val fields = mockk<CustomBossesConfigFields> {
        every { bossType } returns type
        every { isReinforcement } returns reinforcement
    }
    val damagers = mapOf(player to 1.0)

    return if (instanced) {
        mockk<InstancedBossEntity> {
            every { customBossesConfigFields } returns fields
            every { this@mockk.damagers } returns damagers
            every { this@mockk.level } returns level
            every { isReinforcementOrMount } returns reinforcementOrMount
            every { isTriggeredAntiExploit } returns antiExploit
            every { lockoutPlayers } returns lockedOut
            every { unsyncedLivingEntity } returns entity
        }
    } else {
        mockk<CustomBossEntity> {
            every { customBossesConfigFields } returns fields
            every { this@mockk.damagers } returns damagers
            every { this@mockk.level } returns level
            every { isReinforcementOrMount } returns reinforcementOrMount
            every { isTriggeredAntiExploit } returns antiExploit
            every { unsyncedLivingEntity } returns entity
        }
    }
}
