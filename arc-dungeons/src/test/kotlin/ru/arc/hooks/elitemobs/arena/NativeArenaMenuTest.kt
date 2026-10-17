package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.config.DefaultConfig
import com.magmaguy.elitemobs.config.DungeonsConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.config.menus.premade.ArenaMenuConfig
import com.magmaguy.elitemobs.instanced.arena.ArenaInstance
import com.magmaguy.elitemobs.instanced.arena.ArenaWaves
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class NativeArenaMenuTest : FreeSpec({
    "native inventory callbacks preserve stale acknowledgement and ignore bottom inventory clicks" {
        ArenaMenuHarness().use { h ->
            val first = h.paper.addPlayer("First")
            val second = h.paper.addPlayer("Second")
            h.menus.open(first, "test.yml")
            h.click(first, 5, ClickType.SHIFT_LEFT)
            h.menus.open(second, "test.yml")
            h.click(second, 5, ClickType.LEFT)
            h.click(second, 15)
            verify(exactly = 0) { h.arena.addNewPlayer(any()) }
            h.click(first, 6)
            h.levels.lobby(h.arena).selected shouldBe 61
            h.menus.refresh()
            h.plain(second, 4) shouldBe "Выбран уровень старта: 61"
            second.openInventory.topInventory.getItem(3) shouldBe null
            h.click(second, 6)
            verify(exactly = 0) { h.arena.addNewPlayer(second) }
            h.click(second, 6)
            verify(exactly = 1) { h.arena.addNewPlayer(second) }
        }
    }

    "closed admission hides join while spectators and original-only fallback stay truthful" {
        ArenaMenuHarness().use { h ->
            val player = h.paper.addPlayer("Viewer")
            h.menus.open(player, "test.yml")
            every { h.arena.isAcceptingNewPlayers } returns false
            h.menus.refresh()
            player.openInventory.topInventory.getItem(6) shouldBe null
            player.openInventory.topInventory.getItem(2)?.type shouldBe Material.SPYGLASS
            every { h.arena.isAcceptingNewPlayers } returns true
            h.originalOnly()
            h.menus.open(player, "test.yml")
            h.plain(player, 4) shouldBe "Исходная сложность"
            val allLore = player.openInventory.topInventory.contents.filterNotNull().flatMap { it.lore().orEmpty() }
                .joinToString { PlainTextComponentSerializer.plainText().serialize(it) }
            allLore.contains("ур. 0") shouldBe false
            allLore.contains("старте: 0") shouldBe false
            player.openInventory.topInventory.getItem(3) shouldBe null
            player.openInventory.topInventory.getItem(5) shouldBe null
        }
    }

    "all generated and native metadata roots are explicitly nonitalic and custom slots are used" {
        ArenaMenuHarness(mapOf("menu.player-slot" to 8, "menu.spectator-slot" to 0)).use { h ->
            val player = h.paper.addPlayer("Viewer")
            h.menus.open(player, "test.yml")
            val items = player.openInventory.topInventory.contents.filterNotNull()
            player.openInventory.topInventory.getItem(8)?.type shouldBe Material.DIAMOND_SWORD
            player.openInventory.topInventory.getItem(0)?.type shouldBe Material.SPYGLASS
            items.forEach { item ->
                item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                item.lore().orEmpty().filterNot { it == net.kyori.adventure.text.Component.empty() }.forEach {
                    it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                }
            }
        }
    }
})

/** Shares the actual production menu between event tests and the offline ItemMeta exporter. */
internal class ArenaMenuHarness(
    slots: Map<String, Int> = emptyMap(),
    nativeMenu: YamlConfiguration? = null,
) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin("ArenaMenuTest")
    val config = Config(Files.createTempDirectory("arena-menu-test"), "arena.yml")
    val arena = mockk<ArenaInstance>(relaxed = true)
    private val players = hashSetOf<Player>()
    private val definitions = mutableMapOf<String, CustomBossesConfigFields>()
    val levels: NativeArenaLevels
    val menus: NativeArenaMenu

    init {
        slots.forEach(config::setInt)
        mockkStatic(ArenaMenuConfig::class, DefaultConfig::class, DungeonsConfig::class, CustomBossesConfig::class)
        every { ArenaMenuConfig.getPlayerItemSlot() } returns 6
        every { ArenaMenuConfig.getSpectatorItemSlot() } returns 2
        every { ArenaMenuConfig.getMenuName() } returns nativeMenu?.getString("menuName").orEmpty()
        every { ArenaMenuConfig.getPlayerItem() } returns nativeItem(nativeMenu, "playerItem", Material.DIAMOND_SWORD, "&4Выйти на арену!", "&2Сражаться на арене!")
        every { ArenaMenuConfig.getSpectatorItem() } returns nativeItem(nativeMenu, "spectatorItem", Material.SPYGLASS, "&aНаблюдать!", "&2Наблюдать за боями на арене!")
        every { DefaultConfig.useResourcePackModels() } returns false
        every { DungeonsConfig.isAllowSpectatorsInInstancedContent() } returns true
        every { CustomBossesConfig.getCustomBoss(any()) } answers { definitions[firstArg()] }
        definitions["first.yml"] = definition("first.yml", 51)
        definitions["last.yml"] = definition("last.yml", 100)
        every { arena.customArenasConfigFields.waveCount } returns 2
        every { arena.customArenasConfigFields.arenaName } returns "Железная арена"
        every { arena.arenaWaves } returns ArenaWaves(listOf(
            "wave=1:spawnpoint=gate:boss=first.yml", "wave=2:spawnpoint=gate:boss=last.yml",
        ))
        every { arena.isAcceptingNewPlayers } returns true
        every { arena.players } returns players
        every { arena.addNewPlayer(any()) } answers { players.add(firstArg()) }
        ArenaInstance.getArenaInstances()["test.yml"] = arena
        levels = NativeArenaLevels(plugin, config, TestTaskScheduler())
        menus = NativeArenaMenu(plugin, config, TestTaskScheduler(), levels)
    }

    fun originalOnly() {
        definitions["first.yml"] = definition("first.yml", -1)
        levels.reload()
    }

    fun click(player: Player, slot: Int, click: ClickType = ClickType.LEFT) {
        paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL))
    }

    fun plain(player: Player, slot: Int): String = PlainTextComponentSerializer.plainText()
        .serialize(player.openInventory.topInventory.getItem(slot)!!.itemMeta.displayName()!!)

    override fun close() {
        menus.close()
        levels.close()
        ArenaInstance.getArenaInstances().remove("test.yml", arena)
        unmockkStatic(ArenaMenuConfig::class, DefaultConfig::class, DungeonsConfig::class, CustomBossesConfig::class)
        paper.close()
    }

    private fun definition(name: String, level: Int) = mockk<CustomBossesConfigFields>(relaxed = true) {
        every { filename } returns name
        every { getLevel() } returns level
    }

    private fun nativeItem(yaml: YamlConfiguration?, key: String, material: Material, name: String, lore: String): ItemStack =
        ItemStack(Material.matchMaterial(yaml?.getString("$key.material").orEmpty()) ?: material).apply {
            editMeta { meta ->
                meta.displayName(NativeArenaMenu.text(yaml?.getString("$key.name") ?: name))
                meta.lore((yaml?.getStringList("$key.lore") ?: listOf(lore)).map(NativeArenaMenu::text))
            }
        }
}
