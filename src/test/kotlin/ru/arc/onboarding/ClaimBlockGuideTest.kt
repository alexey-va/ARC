package ru.arc.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.landsui.LandsUiModule
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.function.Consumer

class ClaimBlockGuideTest : StringSpec({
    "a disabled Lands world keeps the scheduled current-chunk guide and forward hologram until the item is removed" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val previousPlugin = ARC.plugin
            ARC.plugin = mockk(relaxed = true)
            val inventory = paper.addPlayer("claim-guide-inventory").inventory
            inventory.setItemInMainHand(ItemStack(Material.GOLD_BLOCK).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                    meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 4)
                }
            })
            val root = Files.createTempDirectory("arc-claim-guide-")
            Files.createDirectories(root.resolve("modules"))
            Files.writeString(root.resolve("modules/onboarding.yml"), "worlds: [mining]\n")
            val config = OnboardingConfig.load(Config(root, "modules/onboarding.yml"))
            val lands = mockk<LandsIntegration>()
            val world = mockk<World>(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            var position = Location(world, -0.5, 64.0, 15.5)
            val borders = mutableListOf<BlockDisplay>()
            val labels = mutableListOf<TextDisplay>()
            val messages = mutableListOf<Component>()

            every { world.name } returns "mining"
            every { world.uid } returns UUID(0, 2)
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { player.uniqueId } returns UUID(0, 1)
            every { player.world } returns world
            every { player.inventory } returns inventory
            every { player.location } answers { position.clone() }
            every { player.eyeLocation } answers { position.clone().add(0.0, 1.62, 0.0) }
            every { player.gameMode } returns GameMode.SURVIVAL
            every { player.isDead } returns false
            every { player.isSneaking } returns false
            every { lands.getWorld(world) } returns null
            every { world.spawn(any<Location>(), eq(BlockDisplay::class.java), any<Consumer<BlockDisplay>>()) } answers {
                var at = firstArg<Location>().clone()
                val display = mockk<BlockDisplay>(relaxed = true)
                every { display.isValid } returns true
                every { display.world } returns world
                every { display.location } answers { at.clone() }
                every { display.teleport(any<Location>()) } answers { at = firstArg<Location>().clone(); true }
                thirdArg<Consumer<BlockDisplay>>().accept(display)
                borders += display
                display
            }
            every { world.spawn(any<Location>(), eq(TextDisplay::class.java), any<Consumer<TextDisplay>>()) } answers {
                var at = firstArg<Location>().clone()
                val display = mockk<TextDisplay>(relaxed = true)
                every { display.isValid } returns true
                every { display.world } returns world
                every { display.location } answers { at.clone() }
                every { display.teleport(any<Location>()) } answers { at = firstArg<Location>().clone(); true }
                every { display.text(any<Component>()) } answers { messages += firstArg<Component>(); Unit }
                thirdArg<Consumer<TextDisplay>>().accept(display)
                labels += display
                display
            }
            mockkStatic(LandsIntegration::class, Bukkit::class)
            mockkObject(LandsUiModule)
            var guide: ClaimBlockGuide? = null
            try {
                every { LandsIntegration.of(ARC.instance) } returns lands
                every { Bukkit.getOnlinePlayers() } returns listOf(player)
                every { LandsUiModule.isLookingAtClaimMenu(player) } returns false
                every { LandsUiModule.hasClaimMenu(player) } returns false
                every { LandsUiModule.isAvailable() } returns true
                val active = ClaimBlockGuide(config, emptySet()).also { guide = it; it.start() }

                scheduler.timerCount() shouldBe 1
                scheduler.tick()
                active.hasHologram(player) shouldBe true
                borders.size shouldBe 4
                labels.size shouldBe 1
                borders.map { it.location.x to it.location.z }.toSet() shouldBe
                    setOf(-16.0 to 0.0, -16.0 to 16.0, 0.0 to 0.0)
                labels.single().location shouldBe claimGuideLabelLocation(player.eyeLocation)
                PlainTextComponentSerializer.plainText().serialize(messages.last()) shouldBe
                    "В этом мире приват недоступен\nShift + F - меню"

                position = position.clone().add(0.0, 0.0, -1.0).apply { yaw = 45f }
                scheduler.tick(4)
                borders.size shouldBe 4
                labels.size shouldBe 1
                labels.single().location shouldBe claimGuideLabelLocation(player.eyeLocation)
                verify(exactly = 2) { lands.getWorld(world) }
                verify(exactly = 0) { lands.getLandByUnloadedChunk(any(), any(), any()) }
                verify(exactly = 0) { lands.getLandPlayer(any<UUID>()) }
                verify(exactly = 0) { player.rayTraceBlocks(any<Double>(), any()) }

                inventory.setItemInMainHand(ItemStack(Material.AIR))
                scheduler.tick(5)
                active.hasHologram(player) shouldBe false
                borders.forEach { display -> verify(exactly = 1) { display.remove() } }
                verify(exactly = 1) { labels.single().remove() }
                active.close()
                scheduler.timerCount() shouldBe 0
            } finally {
                guide?.close()
                unmockkObject(LandsUiModule)
                unmockkStatic(LandsIntegration::class, Bukkit::class)
                ARC.plugin = previousPlugin
                Tasks.reset()
            }
        }
    }
})
