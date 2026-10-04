package ru.arc.treasurechests

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.arc.KotestTestBase
import ru.arc.common.chests.VanillaChest
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.config.ConfigManager
import ru.arc.core.TestTaskScheduler

class TreasureHuntPresentationReloadTest : KotestTestBase({
    fun settings(pool: String, visible: Boolean): TreasureHuntModuleConfig {
        ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
            """
            treasure-hunt-types:
              reload-test:
                location-pool-id: $pool
                seconds-ttl: 900
                boss-bar-message: 'Охота за сокровищами · Осталось %left%'
                boss-bar-visible: $visible
                boss-bar-color: YELLOW
                stop-message: 'Поиск завершён.'
                chest-types:
                  vanilla:
                    type: VANILLA
                    treasure-pool-id: changed-rewards
                    weight: 1
            """.trimIndent(),
        )
        ConfigManager.reloadAll()
        return TreasureHuntModuleConfig.load(plugin.dataFolder.toPath())
    }

    it("refreshes a running preset's presentation while preserving its placement, rewards and deadline") {
        val world = server.addSimpleWorld("hunt-presentation-reload")
        val block = world.getBlockAt(10, 70, 10)
        val pool = LocationPoolManager.createPool("hunt-presentation-reload")
        pool.addLocation(block.location)
        val initial = TreasureHuntConfig.simple("reload-test", pool.id, ChestType.vanilla("original-rewards"))
            .copy(timeoutSeconds = 1800)
        val spawner = mockk<ChestSpawner>()
        every { spawner.createChest(any(), ChestVariant.VANILLA, null) } answers { VanillaChest(firstArg<Block>()) }
        val service = TreasureHuntService(settings(pool.id, true), TestTaskScheduler(), mockk(relaxed = true), spawner)
        try {
            val hunt = service.startHunt(initial, 1, false).shouldNotBeNull()
            val startTime = hunt.startTime
            val bar = BossBar.bossBar(Component.text("Сокровища Origin"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS)
            hunt.bossBar = bar

            service.reloadConfig(settings("different-pool", true))

            service.getActiveHunts() shouldBe listOf(hunt)
            hunt.remainingChests shouldBe 1
            hunt.startTime shouldBe startTime
            hunt.config.locationPoolId shouldBe pool.id
            hunt.config.timeoutSeconds shouldBe 1800
            hunt.config.getRandomChestType().treasurePoolId shouldBe "original-rewards"
            hunt.config.announcements.stopMessage shouldBe "Поиск завершён."
            PlainTextComponentSerializer.plainText().serialize(bar.name()) shouldBe "Охота за сокровищами · Осталось 1"
            bar.color() shouldBe BossBar.Color.YELLOW
            verify(exactly = 1) { spawner.createChest(block, ChestVariant.VANILLA, null) }
        } finally {
            service.stopAll()
        }
    }

    it("hides the running bossbar when the reloaded preset disables it") {
        val world = server.addSimpleWorld("hunt-presentation-hidden")
        val block = world.getBlockAt(20, 70, 20)
        val pool = LocationPoolManager.createPool("hunt-presentation-hidden")
        pool.addLocation(block.location)
        val spawner = mockk<ChestSpawner>()
        every { spawner.createChest(any(), ChestVariant.VANILLA, null) } answers { VanillaChest(firstArg<Block>()) }
        val service = TreasureHuntService(settings(pool.id, true), TestTaskScheduler(), mockk(relaxed = true), spawner)
        try {
            val hunt = service.startHunt(TreasureHuntConfig.simple("reload-test", pool.id, ChestType.vanilla("original")), 1, false)
                .shouldNotBeNull()
            val bar = BossBar.bossBar(Component.text("Old title"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS)
            val player = mockk<Player>(relaxed = true)
            hunt.bossBar = bar
            hunt.bossBarAudience.add(player)

            service.reloadConfig(settings(pool.id, false))

            hunt.bossBar shouldBe null
            hunt.bossBarAudience.isEmpty() shouldBe true
            verify(exactly = 1) { player.hideBossBar(bar) }
        } finally {
            service.stopAll()
        }
    }
})
