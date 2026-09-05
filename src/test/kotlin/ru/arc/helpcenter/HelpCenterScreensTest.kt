package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HelpCenterScreensTest {
    private lateinit var paper: MockBukkitTestRuntime
    private lateinit var player: Player
    private lateinit var controller: HelpCenterController
    private lateinit var gateway: HelpCenterGateway
    private lateinit var screen: PaperDialogScreen
    private lateinit var preferences: HelpCenterPreferenceStore
    private val directory = Files.createTempDirectory("help-screens")
    private var chatMode = HelpCenterChatMode.LOCAL
    private val executed = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        paper = MockBukkitTestRuntime.open()
        val plugin = paper.createSimplePlugin("HelpScreens")
        Tasks.install(BukkitTaskScheduler(plugin))
        player = paper.addPlayer("Viewer")
        gateway = mockk()
        preferences = mockk()
        every { preferences.load(any()) } returns CompletableFuture.completedFuture(HelpCenterPreferences())
        every { preferences.recordRecent(any(), any()) } returns CompletableFuture.completedFuture(HelpCenterPreferences())
        every { preferences.close() } returns Unit
        every { gateway.features() } returns HelpCenterFeature.entries.toSet()
        every { gateway.onlinePlayers() } returns (1..27).map {
            HelpCenterPlayer(UUID(0, it.toLong()), "Player%02d".format(it), if (it % 2 == 0) "spawn" else "survival")
        }
        every { gateway.context(player) } returns HelpCenterContext(
            "survival", "vanilla", HelpCenterWorldKind.VANILLA, 1, 65, 2, null, null, false,
            HelpCenterFeature.entries.toSet(),
        )
        every { gateway.selectChatMode(player, any()) } answers {
            chatMode = secondArg()
            CompletableFuture.completedFuture(Unit)
        }
        every { gateway.settings(player) } answers { HelpCenterSettingSnapshot(chatMode, false, true) }
        every { gateway.loadHomes(player, any()) } returns CompletableFuture.completedFuture(HelpCenterHomes(emptyList(), 0, 3))
        every { gateway.loadProfile(player, any()) } returns CompletableFuture.completedFuture(
            HelpCenterProfile("Viewer", "survival", "vanilla", 1, 65, 2, "100", "Игрок", HelpCenterHomes(emptyList(), 0, 3), 0),
        )
        every { gateway.execute(player, any()) } answers {
            val command = secondArg<String>()
            executed += command
            if (command == "g") chatMode = HelpCenterChatMode.GLOBAL
            if (command == "l") chatMode = HelpCenterChatMode.LOCAL
            if (command == "sf open_guide") player.openInventory(Bukkit.createInventory(null, 9))
            true
        }
        ConfigManager.clear()
        val inventoryReturn = HelpCenterInventoryReturnRuntime(plugin, returnOnClose = { true })
        controller = HelpCenterController(
            HelpCenterConfig.load(directory).snapshot(), gateway, {}, inventoryReturn,
            { _, _ -> }, preferences, HelpCenterNavigation(plugin, inventoryReturn::cancel), { _, value -> screen = value },
        )
    }

    @AfterEach
    fun cleanup() {
        controller.close()
        Tasks.reset()
        ConfigManager.clear()
        paper.close()
        directory.toFile().deleteRecursively()
    }

    private fun open(page: HelpCenterPage) { controller.open(player, page); paper.performTicks(2) }
    private fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>()
        every { context.text(any()) } returns input
        (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }.onClick.handle(context)
        paper.performTicks(2)
    }
    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    private fun body() = screen.body.joinToString("\n") { plain(it.text) }

    @Test
    fun `root keeps utilities in the last row and activities have one dungeon entry`() {
        open(HelpCenterPage.ROOT)
        assertEquals(listOf("now", "players", "travel", "privat", "root_activities", "root_progress",
            "root_trade", "root_technology", "search", "settings"), screen.buttons.map { it.id.value })
        click("root_activities")
        assertEquals(1, screen.buttons.count { it.id.value == "command_dungeons" })
        assertFalse(screen.buttons.any { it.id.value.contains("dungeon_portals") })
    }

    @Test
    fun `network directory pages all players and preserves filter on back`() {
        open(HelpCenterPage.PLAYERS)
        assertTrue(body().contains("27"))
        click("next")
        assertTrue(body().contains("2/3"))
        click("player_0")
        assertEquals("Player13", plain(screen.title))
        click("back")
        assertTrue(body().contains("2/3"))
        click("next")
        assertEquals(3, screen.buttons.count { it.id.value.startsWith("player_") && it.id.value != "player_scope" })
        assertFalse(screen.buttons.any { it.id.value == "next" })
        click("player_scope")
        assertTrue(body().contains("survival"))
        assertTrue(body().contains("14"))
        click("find_player", "Player27")
        assertTrue(body().contains("1/1"))
        click("clear_filter")
        assertTrue(body().contains("14"))
    }

    @Test
    fun `settings update on the same screen without an extra menu command`() {
        open(HelpCenterPage.SETTINGS)
        assertEquals("Чат: локальный", plain(screen.buttons.first().label))
        assertTrue(screen.buttons.any { plain(it.label) == "След: выключено" })
        assertTrue(screen.buttons.any { plain(it.label) == "Частицы: включено" })
        assertFalse(screen.buttons.any { it.id.value.contains("boost") })
        click("setting_chat_global")
        assertTrue(body().contains("глобальный"))
        assertEquals("Чат: глобальный", plain(screen.buttons.first().label))
        assertTrue(screen.buttons.any { it.id.value == "setting_chat_local" })
        click("setting_chat_local")
        assertTrue(body().contains("локальный"))
        assertEquals(emptyList<String>(), executed)
    }

    @Test
    fun `chat refresh waits for asynchronous selection and does not steal later navigation`() {
        val pending = CompletableFuture<Unit>()
        every { gateway.selectChatMode(player, HelpCenterChatMode.GLOBAL) } returns pending
        open(HelpCenterPage.SETTINGS)
        click("setting_chat_global")
        assertEquals("Чат: локальный", plain(screen.buttons.first().label))
        chatMode = HelpCenterChatMode.GLOBAL
        pending.complete(Unit)
        paper.performTicks(2)
        assertEquals("Чат: глобальный", plain(screen.buttons.first().label))
        val later = CompletableFuture<Unit>()
        every { gateway.selectChatMode(player, HelpCenterChatMode.LOCAL) } returns later
        click("setting_chat_local")
        open(HelpCenterPage.ROOT)
        later.complete(Unit)
        paper.performTicks(2)
        assertEquals("help.root", screen.id)
    }

    @Test
    fun `settings include all legacy groups and mode selectors return to settings`() {
        open(HelpCenterPage.SETTINGS)
        val ids = screen.buttons.map { it.id.value }
        listOf("scoreboard", "tablist", "lands", "portal_by_other", "portal_for_other", "shortcut", "escape",
            "notifications", "flight", "shift_sign_edit", "resource_pack", "totem", "stairs_sit", "tpa", "portal_style")
            .forEach { assertTrue("legacy_$it" in ids, it) }
        assertFalse(ids.any { it.contains("boost") })
        click("legacy_tablist")
        assertEquals(21, screen.buttons.size)
        assertTrue(screen.buttons.all { !it.closeDialogBeforeAction })
        click("back")
        assertEquals("help.category.settings", screen.id)
    }

    @Test
    fun `external inventory returns to the originating technology section once`() {
        open(HelpCenterPage.TECHNOLOGY)
        assertFalse(screen.buttons.single { it.id.value == "command_slimefun" }.closeDialogBeforeAction)
        click("command_slimefun")
        player.closeInventory()
        paper.performTicks(2)
        assertEquals("Технологии", plain(screen.title))
        open(HelpCenterPage.ROOT)
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()
        paper.performTicks(2)
        assertEquals("Главное меню", plain(screen.title))
    }

    @Test
    fun `explicit navigation cancels a pending external inventory return`() {
        open(HelpCenterPage.TECHNOLOGY)
        click("command_slimefun")
        controller.open(player, HelpCenterPage.PLAYERS)
        player.closeInventory()
        paper.performTicks(2)
        assertEquals("Игроки", plain(screen.title))
    }

    @Test
    fun `async favorites cannot replace a newer menu`() {
        val pending = CompletableFuture<HelpCenterPreferences>()
        every { preferences.load(any()) } returns pending
        open(HelpCenterPage.FAVORITES)
        open(HelpCenterPage.ROOT)
        pending.complete(HelpCenterPreferences(listOf("jobs")))
        paper.performTicks(2)
        assertEquals("Главное меню", plain(screen.title))
    }

    @Test
    fun `favorites picker reaches action cards and full list has no destructive add`() {
        every { preferences.load(any()) } returns CompletableFuture.completedFuture(
            HelpCenterPreferences(listOf("jobs", "skills", "auction", "rtp")),
        )
        open(HelpCenterPage.FAVORITES)
        click("find_action")
        click("pick_activities")
        click("pick_battle_pass")
        assertTrue(body().contains("четыре места"))
        assertFalse(screen.buttons.any { it.id.value == "toggle_favorite" })
        click("back")
        assertTrue(screen.buttons.any { it.id.value == "pick_battle_pass" })
    }

    @Test
    fun `nested player forms retain the directory page when cancelled`() {
        open(HelpCenterPage.PLAYERS)
        click("next")
        click("player_0")
        click("message")
        click("back")
        click("pay")
        click("continue", "500")
        click("back")
        click("back")
        click("back")
        assertTrue(body().contains("2/3"))
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `empty goal offers a usable search instead of an invalid dialog`() {
        every { gateway.features() } returns emptySet()
        open(HelpCenterPage.GOALS)
        click("goal_fight")
        assertTrue(body().contains("пока нет доступных"))
        click("empty_search")
        assertEquals("Поиск", plain(screen.title))
    }

    @Test
    fun `together goal offers the real proxy directory`() {
        open(HelpCenterPage.GOALS)
        click("goal_together")
        assertTrue(body().contains("Найдите друга"))
        click("goal_players")
        assertEquals("Игроки", plain(screen.title))
    }

    @Test
    fun `all major screens construct actual published core dialog models`() {
        listOf(HelpCenterPage.ROOT, HelpCenterPage.NOW, HelpCenterPage.COMMANDS, HelpCenterPage.TRAVEL,
            HelpCenterPage.ACTIVITIES, HelpCenterPage.TECHNOLOGY, HelpCenterPage.SETTINGS, HelpCenterPage.RECOVERY,
            HelpCenterPage.FAVORITES, HelpCenterPage.GOALS, HelpCenterPage.ITEM, HelpCenterPage.CONTEXT).forEach {
            open(it)
            assertTrue(screen.buttons.isNotEmpty(), it.name)
            assertFalse(body().contains("<newline>"), it.name)
        }
    }
}
