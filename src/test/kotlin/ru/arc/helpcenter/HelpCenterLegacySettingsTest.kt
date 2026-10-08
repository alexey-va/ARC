package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import ru.arc.chestpreview.ChestPreviewPreferences
import ru.arc.iteminfo.ItemInfoMode
import ru.arc.iteminfo.ItemInfoPreferences
import ru.arc.sidebar.SIDEBAR_SKILLS_META_KEY
import ru.arc.sidebar.SidebarSection
import ru.arc.tablist.TABLIST_SKILLS_META_KEY
import ru.arc.tablist.TablistCapacity
import ru.arc.tablist.TablistSection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HelpCenterLegacySettingsTest {
    @Test
    fun `scoreboard section preferences persist across reopen without changing the tablist`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        backend.permissions["tab.scoreboard20"] = true
        backend.permissions["tab.tablist3"] = true
        val settings = HelpCenterLegacySettings(backend)
        SidebarSection.entries.forEach { section ->
            assertEquals(section.defaultEnabled, settings.scoreboardSectionEnabled(player, section))
            assertTrue(settings.execute(player, "scoreboard-section-${section.id}").join())
            assertEquals(!section.defaultEnabled, HelpCenterLegacySettings(backend).scoreboardSectionEnabled(player, section))
        }
        assertTrue(settings.execute(player, "scoreboard-toggle").join())
        assertEquals("off", settings.entries(player).first { it.id == "scoreboard" }.state)
        assertTrue(settings.execute(player, "scoreboard-toggle").join())
        assertEquals("on", settings.entries(player).first { it.id == "scoreboard" }.state)
        assertEquals("on", settings.entries(player).first { it.id == "tablist" }.state)
        SidebarSection.entries.forEach { assertEquals(!it.defaultEnabled, settings.scoreboardSectionEnabled(player, it)) }
        assertEquals(false, settings.execute(player, "scoreboard-section-unknown").join())
    }

    @Test
    fun `tablist preferences survive disable and reopen independently of scoreboard and skills`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        backend.permissions["tab.scoreboard5"] = true
        backend.permissions["tab.tablist3"] = true
        backend.metadata[SidebarSection.LOCATION.metaKey] = "false"
        backend.metadata[SIDEBAR_SKILLS_META_KEY] = "excavation,foraging"
        backend.metadata[TABLIST_SKILLS_META_KEY] = "farming,mining"
        val settings = HelpCenterLegacySettings(backend)

        settings.entries(player).first { it.id == "tablist" }.state.shouldBe("on")
        settings.entries(player).first { it.id == "scoreboard" }.state.shouldBe("on")
        settings.scoreboardSkills(player).shouldBe(listOf("excavation", "foraging"))
        settings.tablistSkills(player).shouldBe(listOf("farming", "mining"))
        settings.scoreboardSectionEnabled(player, SidebarSection.LOCATION).shouldBeFalse()
        TablistSection.entries.forEach { settings.tablistSectionEnabled(player, it).shouldBe(it.defaultEnabled) }

        settings.execute(player, "tablist-section-profile").join().shouldBeTrue()
        settings.execute(player, "tablist-section-location").join().shouldBeTrue()
        settings.tablistSectionEnabled(player, TablistSection.PROFILE).shouldBeFalse()
        settings.tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeTrue()
        settings.scoreboardSectionEnabled(player, SidebarSection.LOCATION).shouldBeFalse()
        settings.execute(player, "tablist-toggle").join().shouldBeTrue()

        val reopened = HelpCenterLegacySettings(backend)
        reopened.entries(player).first { it.id == "tablist" }.state.shouldBe("off")
        reopened.entries(player).first { it.id == "scoreboard" }.state.shouldBe("on")
        reopened.tablistSectionEnabled(player, TablistSection.PROFILE).shouldBeFalse()
        reopened.tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeTrue()
        reopened.scoreboardSectionEnabled(player, SidebarSection.LOCATION).shouldBeFalse()
        reopened.scoreboardSkills(player).shouldBe(listOf("excavation", "foraging"))
        reopened.tablistSkills(player).shouldBe(listOf("farming", "mining"))

        reopened.execute(player, "tablist-toggle").join().shouldBeTrue()
        val enabledAgain = HelpCenterLegacySettings(backend)
        enabledAgain.entries(player).first { it.id == "tablist" }.state.shouldBe("on")
        enabledAgain.entries(player).first { it.id == "scoreboard" }.state.shouldBe("on")
        enabledAgain.tablistSectionEnabled(player, TablistSection.PROFILE).shouldBeFalse()
        enabledAgain.tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeTrue()
        enabledAgain.scoreboardSectionEnabled(player, SidebarSection.LOCATION).shouldBeFalse()
        enabledAgain.tablistSkills(player).shouldBe(listOf("farming", "mining"))

        enabledAgain.execute(player, "tablist-skills-auto").join().shouldBeTrue()
        val automatic = HelpCenterLegacySettings(backend)
        automatic.tablistSkills(player).shouldBe(emptyList())
        automatic.scoreboardSkills(player).shouldBe(listOf("excavation", "foraging"))
    }

    @Test
    fun `tablist capacity rejects additions before metadata writes but allows removals and fitting additions`() {
        val player = mockPlayer()
        val backend = FakeBackend().apply { capacity = TablistCapacity(selectedCount = 5, usedRows = 24, maximumRows = 22) }
        val settings = HelpCenterLegacySettings(backend)
        val defaultSections = TablistSection.entries.filter { it.defaultEnabled }.map { it.id }.toSet()

        settings.execute(player, "tablist-section-location").join().shouldBeFalse()
        backend.lastCapacitySections.shouldBe(defaultSections + TablistSection.LOCATION.id)
        backend.metadata.containsKey(TablistSection.LOCATION.metaKey).shouldBeFalse()
        HelpCenterLegacySettings(backend).tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeFalse()

        settings.execute(player, "tablist-section-profile").join().shouldBeTrue()
        backend.metadata[TablistSection.PROFILE.metaKey].shouldBe("false")

        backend.capacity = TablistCapacity(selectedCount = 4, usedRows = 22, maximumRows = 22)
        settings.execute(player, "tablist-section-location").join().shouldBeTrue()
        backend.metadata[TablistSection.LOCATION.metaKey].shouldBe("true")
        HelpCenterLegacySettings(backend).tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeTrue()
    }

    @Test
    fun `pending tablist section saves reserve capacity and release it on success or failure`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        var maximumRows = 21
        backend.capacityForSections = { sections ->
            TablistCapacity(sections.size, usedRows = 16 + sections.size, maximumRows = maximumRows)
        }
        val settings = HelpCenterLegacySettings(backend)
        val defaultSections = TablistSection.entries.filter { it.defaultEnabled }.map { it.id }.toSet()
        val locationSave = CompletableFuture<Boolean>()
        backend.deferredMetaWrites[TablistSection.LOCATION.metaKey] = locationSave

        val first = settings.execute(player, "tablist-section-location")
        first.isDone.shouldBeFalse()
        settings.tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeTrue()
        settings.execute(player, "tablist-section-coordinates").join().shouldBeFalse()
        backend.lastCapacitySections.shouldBe(defaultSections + TablistSection.LOCATION.id + TablistSection.COORDINATES.id)
        backend.metaWrites.map { it.first }.shouldBe(listOf(TablistSection.LOCATION.metaKey))

        locationSave.complete(true)
        first.join().shouldBeTrue()
        settings.tablistSectionEnabled(player, TablistSection.LOCATION).shouldBeTrue()

        maximumRows = 22
        val coordinatesSave = CompletableFuture<Boolean>()
        backend.deferredMetaWrites[TablistSection.COORDINATES.metaKey] = coordinatesSave
        val second = settings.execute(player, "tablist-section-coordinates")
        second.isDone.shouldBeFalse()
        settings.execute(player, "tablist-section-coordinates").join().shouldBeFalse()
        backend.metaWrites.count { it.first == TablistSection.COORDINATES.metaKey }.shouldBe(1)

        coordinatesSave.completeExceptionally(IllegalStateException("metadata save failed"))
        runCatching { second.join() }.isFailure.shouldBeTrue()
        settings.tablistSectionEnabled(player, TablistSection.COORDINATES).shouldBeFalse()
        settings.execute(player, "tablist-section-coordinates").join().shouldBeTrue()
        backend.metaWrites.count { it.first == TablistSection.COORDINATES.metaKey }.shouldBe(2)
        settings.tablistSectionEnabled(player, TablistSection.COORDINATES).shouldBeTrue()
    }

    @Test
    fun `legacy tablist mode actions remain available for compatibility`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        val settings = HelpCenterLegacySettings(backend)

        settings.execute(player, "tablist-7").join().shouldBeTrue()
        backend.permissions["tab.tablist7"].shouldBe(true)
        HelpCenterLegacySettings(backend).entries(player).first { it.id == "tablist" }.state.shouldBe("on")
        settings.execute(player, "tablist-21").join().shouldBeFalse()
        settings.execute(player, "tablist-off").join().shouldBeTrue()
        HelpCenterLegacySettings(backend).entries(player).first { it.id == "tablist" }.state.shouldBe("off")
    }

    @Test
    fun `quest reward visibility defaults on and persists independently of sidebar style`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        backend.permissions["tab.scoreboard3"] = true
        val settings = HelpCenterLegacySettings(backend)
        assertTrue(settings.scoreboardRewardsEnabled(player))
        assertTrue(settings.execute(player, "scoreboard-rewards").join())
        val reopened = HelpCenterLegacySettings(backend)
        assertEquals(false, reopened.scoreboardRewardsEnabled(player))
        assertEquals("on", reopened.entries(player).first { it.id == "scoreboard" }.state)
        assertTrue(reopened.execute(player, "scoreboard-rewards").join())
        assertTrue(HelpCenterLegacySettings(backend).scoreboardRewardsEnabled(player))
    }

    @Test
    fun `input settings default safely and roundtrip through persistent metadata`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        val settings = HelpCenterLegacySettings(backend)
        assertEquals("main", settings.entries(player).first { it.id == "shortcut" }.state)
        assertEquals("back", settings.entries(player).first { it.id == "escape" }.state)
        assertEquals("hologram", settings.entries(player).first { it.id == "item-info" }.state)
        assertEquals(ItemInfoPreferences.DEFAULT, settings.itemInfoPreferences(player))
        assertTrue(settings.execute(player, "shortcut-mount").join())
        assertTrue(settings.execute(player, "escape-back").join())
        val reopened = HelpCenterLegacySettings(backend)
        assertEquals("mount", reopened.entries(player).first { it.id == "shortcut" }.state)
        assertEquals("back", reopened.entries(player).first { it.id == "escape" }.state)
        assertEquals(false, reopened.execute(player, "shortcut-op").join())
        assertTrue(reopened.execute(player, "shortcut-disabled").join())
        assertTrue(reopened.execute(player, "escape-close").join())
        assertTrue(reopened.execute(player, "item-info-bossbar").join())
        assertTrue(reopened.execute(player, "item-info-id-toggle").join())
        assertTrue(reopened.saveItemInfoHologram(player, 0.85f, 0.35, -0.60).join())
        assertEquals("disabled", reopened.entries(player).first { it.id == "shortcut" }.state)
        assertEquals("close", reopened.entries(player).first { it.id == "escape" }.state)
        assertEquals("bossbar", reopened.entries(player).first { it.id == "item-info" }.state)
        assertEquals(true, reopened.itemInfoPreferences(player).showNamespacedId)
        assertEquals(0.85f, reopened.itemInfoPreferences(player).hologramScale)
        assertEquals(0.35, reopened.itemInfoPreferences(player).verticalOffset)
        assertEquals(-0.60, reopened.itemInfoPreferences(player).horizontalOffset)
        assertTrue(ItemInfoMode.META_KEY in backend.metadata)
        assertTrue(ItemInfoPreferences.SHOW_ID_META_KEY in backend.metadata)
        assertTrue(ItemInfoPreferences.LAYOUT_META_KEY in backend.metadata)
    }

    @Test
    fun `container preview overrides persist separately and reset to inherited server defaults`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        val settings = HelpCenterLegacySettings(backend)
        val preferences = ChestPreviewPreferences(enabled = false, scale = 1.25f, columns = 5, showCounts = false)

        assertTrue(settings.saveContainerPreviewPreferences(player, preferences).join())
        backend.metadata[ChestPreviewPreferences.META_KEY].shouldBe(preferences.stored())
        HelpCenterLegacySettings(backend).containerPreviewPreferences(player).shouldBe(preferences)
        ItemInfoMode.fromStored(backend.metadata[ItemInfoMode.META_KEY]).shouldBe(ItemInfoMode.HOLOGRAM)

        assertTrue(settings.saveContainerPreviewPreferences(player, ChestPreviewPreferences()).join())
        backend.metadata[ChestPreviewPreferences.META_KEY].shouldBe("default")
        HelpCenterLegacySettings(backend).containerPreviewPreferences(player).shouldBe(ChestPreviewPreferences())
    }

    @Test
    fun `entries expose canonical states and execute only typed actions`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        backend.permissions["arc.chat.notify"] = true
        backend.permissions["tab.scoreboard3"] = true
        backend.permissions["tab.group.admin"] = true

        val settings = HelpCenterLegacySettings(backend)
        assertEquals("on", settings.entries(player).first { it.id == "scoreboard" }.state)
        assertEquals("on", settings.entries(player).first { it.id == "notifications" }.state)

        assertTrue(settings.execute(player, "notifications").join())
        assertEquals(false, backend.permissions["arc.chat.notify"])
        assertTrue(settings.execute(player, "flight-recharge").join())
        assertEquals(HelpCenterLegacySettings.PlayerCommand.FLIGHT_RECHARGE, backend.commands.single())
        assertEquals(null, settings.entries(player).first { it.id == "shift-sign-edit" }.state)
        assertTrue(settings.execute(player, "admin").join())
        assertEquals(HelpCenterLegacySettings.ConsoleCommand.OPEN_ADMIN_SETTINGS, backend.consoleCommands.single())
    }

    @Test
    fun `portal style selection normalizes removed preferences and preserves legacy`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        val settings = HelpCenterLegacySettings(backend)
        listOf("chaos", "solar").forEach { removedStyle ->
            backend.metadata["arc-portal-style"] = removedStyle
            assertEquals("origin", settings.entries(player).first { it.id == "portal-style" }.state)
            assertTrue(settings.execute(player, "portal-style").join())
            assertEquals("astral", backend.metadata["arc-portal-style"])
        }
        assertTrue(settings.execute(player, "portal-style-legacy").join())
        assertEquals("legacy", backend.metadata["arc-portal-style"])
        assertEquals(false, settings.execute(player, "portal-style-chaos").join())
        assertEquals(false, settings.execute(player, "portal-style-solar extra").join())
        assertTrue(settings.execute(player, "flight-enable").join())
        assertTrue(settings.execute(player, "flight-disable").join())
        assertEquals(listOf("flyc true", "flyc false"), backend.commands.map { it.value })
    }

    @Test
    fun `unknown ids cannot execute arbitrary commands`() {
        val player = mockPlayer()
        val backend = FakeBackend()
        val settings = HelpCenterLegacySettings(backend)
        assertEquals(false, settings.execute(player, "console rm -rf /").join())
        assertTrue(backend.commands.isEmpty())
    }

    private fun mockPlayer() = mockk<Player> {
        every { uniqueId } returns UUID.randomUUID()
    }

    private class FakeBackend : HelpCenterLegacySettings.Backend {
        val metadata = mutableMapOf<String, String>()
        val permissions = mutableMapOf<String, Boolean>()
        val commands = mutableListOf<HelpCenterLegacySettings.PlayerCommand>()
        val consoleCommands = mutableListOf<HelpCenterLegacySettings.ConsoleCommand>()
        var capacity: TablistCapacity? = null
        var capacityForSections: (Set<String>) -> TablistCapacity? = { capacity }
        var lastCapacitySections = emptySet<String>()
        val metaWrites = mutableListOf<Pair<String, String>>()
        val deferredMetaWrites = mutableMapOf<String, CompletableFuture<Boolean>>()
        override fun hasPermission(player: Player, node: String) = permissions[node] == true
        override fun meta(player: Player, key: String) = metadata[key]
        override fun tablistCapacity(player: Player, sectionIds: Set<String>) = capacityForSections(sectionIds).also { lastCapacitySections = sectionIds }
        override fun cmiOption(player: Player, option: HelpCenterLegacySettings.CmiOption) = null
        override fun flightState(player: Player) = null
        override fun tpaEnabled(player: Player) = null
        override fun setPermission(player: Player, node: String, enabled: Boolean) = CompletableFuture.completedFuture(permissions.put(node, enabled) == null || true)
        override fun setExclusiveMode(player: Player, prefix: String, mode: Int?): CompletableFuture<Boolean> {
            (1..20).forEach { index -> permissions[if (index == 1) prefix else "$prefix$index"] = mode == index }
            return CompletableFuture.completedFuture(true)
        }
        override fun setMeta(player: Player, key: String, value: String): CompletableFuture<Boolean> {
            metaWrites += key to value
            val deferred = deferredMetaWrites.remove(key)
            if (deferred != null) {
                return deferred.whenComplete { accepted, failure ->
                    if (failure == null && accepted == true) metadata[key] = value
                }
            }
            return CompletableFuture.completedFuture(true).also { metadata[key] = value }
        }
        override fun command(player: Player, command: HelpCenterLegacySettings.PlayerCommand) = CompletableFuture.completedFuture(commands.add(command).let { true })
        override fun consoleCommand(player: Player, command: HelpCenterLegacySettings.ConsoleCommand) = CompletableFuture.completedFuture(consoleCommands.add(command).let { true })
    }
}
