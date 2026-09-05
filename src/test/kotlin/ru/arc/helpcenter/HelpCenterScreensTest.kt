package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
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
    private var screenCount = 0
    private lateinit var legacy: HelpCenterLegacySettings
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
        every { gateway.pendingRequests(player) } returns HelpCenterPendingRequests()
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
            if (command in setOf("sf open_guide", "cmi flightcharge recharge", "elitemobs:em")) player.openInventory(Bukkit.createInventory(null, 9))
            true
        }
        ConfigManager.clear()
        val inventoryReturn = HelpCenterInventoryReturnRuntime(plugin, returnOnClose = { true })
        legacy = spyk(HelpCenterLegacySettings())
        every { legacy.flightSnapshot(any()) } returns HelpCenterFlightSnapshot(50_000.0, 100_000, false)
        controller = HelpCenterController(
            HelpCenterConfig.load(directory).snapshot(), gateway, {}, inventoryReturn,
            { _, _ -> }, preferences, HelpCenterNavigation(plugin, inventoryReturn::cancel), { _, value -> screen = value; screenCount++ }, legacy,
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
    fun `request response buttons exist only for current incoming requests`() {
        for (pending in listOf(
            HelpCenterPendingRequests(), HelpCenterPendingRequests(teleport = true),
            HelpCenterPendingRequests(duel = true), HelpCenterPendingRequests(teleport = true, duel = true),
        )) {
            every { gateway.pendingRequests(player) } returns pending
            open(HelpCenterPage.REQUESTS)
            val ids = screen.buttons.map { it.id.value }
            assertEquals(pending.teleport, "tpa_accept" in ids)
            assertEquals(pending.teleport, "tpa_deny" in ids)
            assertEquals(pending.duel, "duel_accept" in ids)
            assertEquals(pending.duel, "duel_deny" in ids)
        }
    }

    @Test
    fun `expired request is removed on click without dispatching an accept command`() {
        for (id in listOf("tpa_accept", "duel_accept")) {
            every { gateway.pendingRequests(player) } returns HelpCenterPendingRequests(true, true)
            open(HelpCenterPage.REQUESTS)
            assertFalse(screen.buttons.single { it.id.value == id }.closeDialogBeforeAction)
            every { gateway.pendingRequests(player) } returns HelpCenterPendingRequests()
            click(id)
            assertTrue(executed.isEmpty())
            assertFalse(screen.buttons.any { it.id.value == id })
        }
    }

    @Test
    fun `active request dispatches its response after revalidation`() {
        every { gateway.pendingRequests(player) } returns HelpCenterPendingRequests(true, true)
        open(HelpCenterPage.REQUESTS)
        click("tpa_accept")
        assertEquals(listOf("huskhomes:tpaccept"), executed)
        open(HelpCenterPage.REQUESTS)
        click("duel_deny")
        assertEquals(listOf("huskhomes:tpaccept", "duel deny"), executed)
    }

    @Test
    fun `request load failure hides responses and explains the unavailable state`() {
        every { gateway.pendingRequests(player) } throws IllegalStateException("offline")
        open(HelpCenterPage.REQUESTS)
        assertFalse(screen.buttons.any { it.id.value in setOf("tpa_accept", "duel_accept") })
        assertTrue(body().contains("Не удалось проверить"))
    }

    @Test
    fun `jobs opens ArcEcoJobs dialog without arming inventory return`() {
        open(HelpCenterPage.GUIDE)
        click("jobs")
        assertEquals(listOf("arcjobs dialog"), executed)
        val previous = screen
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()
        paper.performTicks(3)
        assertSame(previous, screen)
    }

    @Test
    fun `dungeons entry opens a hub and guide chapters before any EliteMobs command`() {
        open(HelpCenterPage.ACTIVITIES)
        click("command_dungeons")
        assertEquals("help.dungeons", screen.id)
        assertEquals(listOf("dungeons_portals", "dungeons_guide", "dungeons_menu"), screen.buttons.map { it.id.value })
        assertTrue(executed.isEmpty())
        click("dungeons_guide")
        assertEquals("help.dungeons.guide", screen.id)
        for (topic in HelpCenterDungeonsController.TOPICS) {
            click("dungeons_guide_$topic")
            assertEquals("help.dungeons.guide.$topic", screen.id)
            assertEquals(3, screen.body.size)
            assertTrue(screen.body.all { plain(it.text).isNotBlank() })
            click("back")
            assertEquals("help.dungeons.guide", screen.id)
        }
        click("back")
        assertEquals("help.dungeons", screen.id)
        click("back")
        assertEquals("help.category.activities", screen.id)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `EliteMobs inventory returns to the exact guide chapter`() {
        open(HelpCenterPage.ACTIVITIES)
        click("command_dungeons")
        click("dungeons_guide")
        click("dungeons_guide_start")
        assertFalse(screen.buttons.single { it.id.value == "dungeons_menu" }.closeDialogBeforeAction)
        click("dungeons_menu")
        assertEquals("elitemobs:em", executed.last())
        player.closeInventory()
        paper.performTicks(3)
        assertEquals("help.dungeons.guide.start", screen.id)
    }

    @Test
    fun `dungeon portals close the menu and use the existing guild warp`() {
        open(HelpCenterPage.ACTIVITIES)
        click("command_dungeons")
        assertTrue(screen.buttons.single { it.id.value == "dungeons_portals" }.closeDialogBeforeAction)
        click("dungeons_portals")
        assertEquals(listOf("pw aguild"), executed)
        val previous = screen
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()
        paper.performTicks(3)
        assertSame(previous, screen)
    }

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
    fun `settings update inside the selected section without an extra menu command`() {
        open(HelpCenterPage.SETTINGS)
        click("settings_social")
        assertEquals("Канал чата: локальный", plain(screen.buttons.first().label))
        click("setting_chat_global")
        assertEquals("help.settings.section.social", screen.id)
        assertEquals("Канал чата: глобальный", plain(screen.buttons.first().label))
        click("setting_chat_local")
        assertEquals("Канал чата: локальный", plain(screen.buttons.first().label))
        assertEquals(emptyList<String>(), executed)
    }

    @Test
    fun `chat refresh waits for asynchronous selection and does not steal later navigation`() {
        val pending = CompletableFuture<Unit>()
        every { gateway.selectChatMode(player, HelpCenterChatMode.GLOBAL) } returns pending
        open(HelpCenterPage.SETTINGS)
        click("settings_social")
        click("setting_chat_global")
        assertEquals("Канал чата: локальный", plain(screen.buttons.first().label))
        chatMode = HelpCenterChatMode.GLOBAL
        pending.complete(Unit)
        paper.performTicks(2)
        assertEquals("Канал чата: глобальный", plain(screen.buttons.first().label))
        val later = CompletableFuture<Unit>()
        every { gateway.selectChatMode(player, HelpCenterChatMode.LOCAL) } returns later
        click("setting_chat_local")
        open(HelpCenterPage.ROOT)
        later.complete(Unit)
        paper.performTicks(2)
        assertEquals("help.root", screen.id)
    }

    @Test
    fun `settings retain every legacy control in four groups and options return to their group`() {
        val expected = mapOf(
            "controls" to listOf("legacy_shortcut", "legacy_escape", "legacy_shift_sign_edit", "legacy_stairs_sit"),
            "interface" to listOf("legacy_scoreboard", "legacy_tablist", "setting_particles", "legacy_totem", "legacy_resource_pack"),
            "social" to listOf("setting_chat_global", "legacy_notifications", "legacy_tpa"),
            "world" to listOf("setting_trails_on", "legacy_flight", "legacy_lands", "legacy_portal_style", "legacy_portal_by_other", "legacy_portal_for_other"),
        )
        expected.forEach { (group, ids) ->
            open(HelpCenterPage.SETTINGS)
            assertEquals(4, screen.buttons.size)
            click("settings_$group")
            assertEquals(ids, screen.buttons.map { it.id.value })
            assertTrue(screen.buttons.all { it.width == 230 && !it.closeDialogBeforeAction })
        }
        open(HelpCenterPage.SETTINGS)
        click("settings_interface")
        click("legacy_tablist")
        assertEquals(21, screen.buttons.size)
        assertTrue(plain(screen.buttons.last().label).startsWith("✔"))
        click("back")
        assertEquals("help.settings.section.interface", screen.id)
        click("back")
        assertEquals("help.category.settings", screen.id)
    }

    @Test
    fun `unavailable trails preserve world layout without exposing a mutation`() {
        every { gateway.features() } returns HelpCenterFeature.entries.toSet() - HelpCenterFeature.TRAILS
        open(HelpCenterPage.SETTINGS)
        click("settings_world")
        assertEquals("setting_trails_unavailable", screen.buttons.first().id.value)
        assertTrue(plain(screen.buttons.first().label).contains("недоступно"))
        assertEquals(6, screen.buttons.size)
        click("setting_trails_unavailable")
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `flight shows real charge and isolates recharge selection from flight actions`() {
        open(HelpCenterPage.SETTINGS)
        click("settings_world")
        val trails = screen.buttons.single { it.id.value == "setting_trails_on" }
        assertTrue(plain(requireNotNull(trails.tooltip)).contains("протаптывают землю"))
        click("legacy_flight")
        assertEquals(3, screen.body.size)
        assertTrue(body().contains("50%"))
        assertTrue(body().contains("■■■■■■■■□□□□□□□□"))
        assertEquals("Включить полёт", plain(screen.buttons.first().label))
        assertEquals(listOf("legacy_flight_toggle", "legacy_flight_recharge", "flight_auto"), screen.buttons.map { it.id.value })
        click("flight_auto")
        assertEquals("help.settings.flight.recharge", screen.id)
        assertEquals(3, screen.buttons.size)
        assertTrue(body().contains("автоматически"))
        assertEquals(3, screen.buttons.map { plain(requireNotNull(it.tooltip)) }.distinct().size)
        click("back")
        assertEquals("help.settings.flight", screen.id)
        click("back")
        assertEquals("help.settings.section.world", screen.id)
    }

    @Test
    fun `missing flight data never fabricates a charge or enables an unknown mode`() {
        every { legacy.flightSnapshot(any()) } returns null
        open(HelpCenterPage.SETTINGS)
        click("settings_world")
        click("legacy_flight")
        assertTrue(body().contains("недоступны"))
        assertFalse(body().contains("%"))
        assertEquals("Полёт: неизвестно", plain(screen.buttons.first().label))
        click("legacy_flight_toggle")
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `invalid charge disables toggle and explicit flight actions cannot invert another flight source`() {
        every { legacy.flightSnapshot(any()) } returns HelpCenterFlightSnapshot(Double.NaN, 0, false)
        open(HelpCenterPage.SETTINGS)
        click("settings_world")
        click("legacy_flight")
        assertTrue(body().contains("недоступны"))
        val unchanged = screenCount
        click("legacy_flight_toggle")
        assertEquals(unchanged, screenCount)
        io.mockk.verify(exactly = 0) { legacy.execute(player, any()) }
        every { legacy.flightSnapshot(any()) } returns HelpCenterFlightSnapshot(1_000.0, 1_000, false)
        every { legacy.execute(player, "flight-enable") } returns CompletableFuture.completedFuture(true)
        open(HelpCenterPage.SETTINGS)
        click("settings_world")
        click("legacy_flight")
        assertTrue(body().contains("100%"))
        click("legacy_flight_toggle")
        io.mockk.verify(exactly = 1) { legacy.execute(player, "flight-enable") }
        io.mockk.verify(exactly = 0) { legacy.execute(player, "flight-toggle") }
    }

    @Test
    fun `manual flight recharge opens inventory without immediately covering it with a dialog`() {
        open(HelpCenterPage.SETTINGS)
        click("settings_world")
        click("legacy_flight")
        val before = screenCount
        click("legacy_flight_recharge")
        assertEquals(listOf("cmi flightcharge recharge"), executed)
        assertEquals(9, player.openInventory.topInventory.size)
        assertEquals(before, screenCount)
        player.closeInventory()
        paper.performTicks(2)
        assertEquals("help.settings.flight", screen.id)
        assertEquals(before + 1, screenCount)
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
