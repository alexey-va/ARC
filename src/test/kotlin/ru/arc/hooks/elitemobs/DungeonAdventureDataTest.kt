package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.menus.premade.PlayerStatusMenuConfig
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.bonuses.SkillBonusRegistry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.attribute.Attribute
import ru.arc.paper.testing.MockBukkitTestRuntime

class DungeonAdventureDataTest : FreeSpec({
    "native gear remains usable without EliteMobs private unrelocated MagmaCore classes" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("gear-health")
            player.getAttribute(Attribute.MAX_HEALTH)!!.baseValue = 36.0
            player.health = 17.0
            mockkStatic(PlayerData::class, ElitePlayerInventory::class, PlayerStatusMenuConfig::class, SkillBonusRegistry::class)
            try {
                every { PlayerData.isDataLoaded(player.uniqueId) } returns true
                every { PlayerData.getSkillXP(player.uniqueId, any()) } returns 0L
                every { ElitePlayerInventory.getPlayer(player) } returns null
                every { PlayerStatusMenuConfig.getGearUnarmedLabel() } returns "Без оружия"
                every { SkillBonusRegistry.getFormattedBonuses(player, any()) } returns emptyList()

                // The server does not expose EliteMobs' relocated utility classes to ARC.
                // Load the real adapter with that same restriction, not a mocked service.
                val adapter = NativeDungeonAdventureService::class.java
                val loader = object : ClassLoader(adapter.classLoader) {
                    override fun loadClass(name: String, resolve: Boolean): Class<*> {
                        if (name.startsWith("com.magmaguy.magmacore.")) throw ClassNotFoundException(name)
                        if (name != adapter.name) return super.loadClass(name, resolve)
                        return findLoadedClass(name) ?: adapter.getResourceAsStream("/${name.replace('.', '/')}.class")!!.use {
                            val bytes = it.readBytes()
                            defineClass(name, bytes, 0, bytes.size).also { loaded -> if (resolve) resolveClass(loaded) }
                        }
                    }
                }
                val service = loader.loadClass(adapter.name).getField("INSTANCE").get(null) as DungeonAdventureService
                repeat(2) {
                    val gear = service.gear(player)!!
                    gear.summary.associate { it.key to it.value }.let { values ->
                        values["health"] shouldBe "17"
                        values["maxHealth"] shouldBe "36"
                    }
                    gear.equipment.map { it.slot } shouldBe listOf("helmet", "chestplate", "leggings", "boots", "mainhand", "offhand")
                    gear.equipment.all { it.name == "Пусто" } shouldBe true
                }
            } finally {
                unmockkStatic(PlayerData::class, ElitePlayerInventory::class, PlayerStatusMenuConfig::class, SkillBonusRegistry::class)
            }
        }
    }

    "dynamic levels match the native five-level range at minimum and maximum bounds" {
        dynamicDungeonAvailableLevels(1) shouldBe listOf(5)
        dynamicDungeonAvailableLevels(6) shouldBe listOf(5, 10)
        dynamicDungeonAvailableLevels(195) shouldBe listOf(190, 195, 200)
        dynamicDungeonAvailableLevels(200) shouldBe listOf(195, 200)
        // Native DynamicDungeonBrowser computes min=235 and max=200 here; the loop is empty.
        dynamicDungeonAvailableLevels(240) shouldBe emptyList()
    }

    "dynamic default chooses the nearest native option and breaks ties downward" {
        recommendedDynamicDungeonLevel(listOf(5, 10), 7) shouldBe 5
        recommendedDynamicDungeonLevel(listOf(5, 10), 8) shouldBe 10
        recommendedDynamicDungeonLevel(emptyList(), 40) shouldBe null
    }

    "difficulty selection keeps the stable id separate from the exact native name" {
        nativeDungeonDifficulty(mapOf("id" to "hard_mode", "name" to "Hard: Tier II", "level" to "50")) shouldBe
            DungeonDifficultyInfo("hard_mode", "Hard: Tier II", 50)
        nativeDungeonDifficulty(mapOf("name" to "Default")) shouldBe
            DungeonDifficultyInfo("Default", "Default", null)
        nativeDungeonDifficulty(mapOf("id" to "missing-name")) shouldBe null
    }

    "skill perk toggles check unlock level before the active state, as the native menu does" {
        skillPerkToggleGuard(playerLevel = 9, requiredLevel = 10, isActive = true, activeCount = 3, maxActive = 3) shouldBe
            DungeonAdventureActionResult.LEVEL_REQUIRED
    }

    "skill perk toggles allow deactivation at the active limit and reject another activation" {
        skillPerkToggleGuard(playerLevel = 10, requiredLevel = 10, isActive = true, activeCount = 3, maxActive = 3) shouldBe
            DungeonAdventureActionResult.PERK_DEACTIVATED
        skillPerkToggleGuard(playerLevel = 10, requiredLevel = 10, isActive = false, activeCount = 3, maxActive = 3) shouldBe
            DungeonAdventureActionResult.PERK_LIMIT
    }

    "skill perk toggles activate an unlocked perk while a slot remains" {
        skillPerkToggleGuard(playerLevel = 10, requiredLevel = 10, isActive = false, activeCount = 2, maxActive = 3) shouldBe
            DungeonAdventureActionResult.PERK_ACTIVATED
    }

    "skill type ids normalize case and reject unknown values" {
        skillTypeFromId(" swords ") shouldBe com.magmaguy.elitemobs.skills.SkillType.SWORDS
        skillTypeFromId("not-a-skill") shouldBe null
    }
})
