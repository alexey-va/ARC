package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountCommandTest : StringSpec({
    val mount = testMount()
    val catalog = MountCatalog(listOf(mount))

    "plain mount command opens the player collection" {
        val fixture = commandFixture(catalog)

        fixture.command.onCommand(fixture.player, fixture.bukkitCommand, "mount", emptyArray()) shouldBe true

        verify(exactly = 1) { fixture.openMenu(fixture.player) }
        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any()) }
    }

    "admin summon uses a configured level instead of arbitrary raw speed" {
        val fixture = commandFixture(catalog, admin = true)
        every {
            fixture.sessions.spawn(
                player = fixture.player,
                definition = mount,
                settings =
                    MountRuntimeSettings(
                        speed = mount.level(3).speed,
                        walkingStepHeight = 4.0,
                        handlingMultiplier = mount.level(3).handlingMultiplier,
                        sprintMultiplier = mount.level(3).sprintMultiplier,
                        scaleMultiplier = mount.level(3).scaleMultiplier,
                        skin = mount.skin("baby"),
                        glow = false,
                        abilityUpgrades = mount.abilities.upgrades,
                    ),
                durationMillis = 10_000L,
            )
        } returns MountSpawnResult.SUCCESS

        fixture.command.onCommand(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "summon", "bee", "3", "baby"),
        ) shouldBe true

        verify(exactly = 1) {
            fixture.sessions.spawn(
                player = fixture.player,
                definition = mount,
                settings =
                    MountRuntimeSettings(
                        speed = 0.9,
                        walkingStepHeight = 4.0,
                        handlingMultiplier = 1.28,
                        sprintMultiplier = 1.12,
                        scaleMultiplier = 1.0,
                        skin = mount.skin("baby"),
                        glow = false,
                        abilityUpgrades = mount.abilities.upgrades,
                    ),
                durationMillis = 10_000L,
            )
        }
    }

    "non-admin cannot use administrative subcommands or see them in completion" {
        val fixture = commandFixture(catalog, admin = false)

        fixture.command.onCommand(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "summon", "bee"),
        ) shouldBe true

        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any()) }
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf(""))
            .shouldContainExactly("help", "menu")
    }

    "admin can grant and revoke levels, skins, glow and abilities" {
        val fixture = commandFixture(catalog, admin = true)

        listOf(
            arrayOf("admin", "grant", "level", "Rider", "bee", "3"),
            arrayOf("admin", "grant", "skin", "Rider", "bee", "baby"),
            arrayOf("admin", "grant", "glow", "Rider", "bee"),
            arrayOf("admin", "grant", "ability", "Rider", "bee", "night-vision"),
            arrayOf("admin", "revoke", "level", "Rider", "bee", "3"),
            arrayOf("admin", "revoke", "skin", "Rider", "bee", "baby"),
            arrayOf("admin", "revoke", "glow", "Rider", "bee"),
            arrayOf("admin", "revoke", "ability", "Rider", "bee", "night-vision"),
        ).forEach { args ->
            fixture.command.onCommand(fixture.console, fixture.bukkitCommand, "mount", args) shouldBe true
        }
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.grantLevel(fixture.playerId, mount, 3) }
        verify(exactly = 1) { fixture.ownership.grantSkin(fixture.playerId, mount, mount.skin("baby")!!) }
        verify(exactly = 1) { fixture.ownership.grantGlow(fixture.playerId, mount) }
        verify(exactly = 1) { fixture.ownership.revokeLevel(fixture.playerId, mount, 3) }
        verify(exactly = 1) { fixture.ownership.revokeSkin(fixture.playerId, mount, mount.skin("baby")!!) }
        verify(exactly = 1) { fixture.ownership.revokeGlow(fixture.playerId, mount) }
        verify(exactly = 1) { fixture.ownership.grantAbility(fixture.playerId, mount, mount.ability("night-vision")!!) }
        verify(exactly = 1) { fixture.ownership.revokeAbility(fixture.playerId, mount, mount.ability("night-vision")!!) }
        verify(exactly = 8) { fixture.console.sendMessage(any<Component>()) }
    }

    "admin completion follows the unified command tree" {
        val specialMount =
            mount.copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("colossal", "Колоссальный", 10.0, grantOnly = true),
                    ),
            )
        val fixture = commandFixture(MountCatalog(listOf(specialMount)), admin = true)

        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf(""))
            .shouldContainExactly("admin", "help", "menu")
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", ""))
            .shouldContainExactly("grant", "grant-all", "revoke", "summon")
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", "grant", ""))
            .shouldContainExactly("ability", "glow", "level", "size", "skin")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "skin", "Rider", "bee", ""),
        ).shouldContainExactly("baby")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "ability", "Rider", "bee", ""),
        ).shouldContainExactly("night-vision")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "size", "Rider", "bee", ""),
        ).shouldContainExactly("colossal")
    }

    "admin can grant and revoke command-only sizes" {
        val special = MountSizeOptionDefinition("colossal", "Колоссальный", 10.0, grantOnly = true)
        val specialMount =
            mount.copy(
                sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0), special),
            )
        val fixture = commandFixture(MountCatalog(listOf(specialMount)), admin = true)

        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "size", "Rider", "bee", "colossal"),
        ) shouldBe true
        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "revoke", "size", "Rider", "bee", "colossal"),
        ) shouldBe true
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.grantSize(fixture.playerId, specialMount, special) }
        verify(exactly = 1) { fixture.ownership.revokeSize(fixture.playerId, specialMount, special) }
    }

    "admin can grant every mount at its maximum level in one command" {
        val secondMount = testMount().copy(id = "bat", displayName = "Летучая мышь")
        val fixture = commandFixture(MountCatalog(listOf(mount, secondMount)), admin = true)

        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant-all", "Rider"),
        ) shouldBe true
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.grantLevel(fixture.playerId, mount, mount.maxLevel) }
        verify(exactly = 1) { fixture.ownership.grantLevel(fixture.playerId, secondMount, secondMount.maxLevel) }
        verify(exactly = 1) { fixture.console.sendMessage(any<Component>()) }
    }

    "admin mutations reject trailing arguments instead of applying a partial parse" {
        val fixture = commandFixture(catalog, admin = true)

        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "level", "Rider", "bee", "3", "unexpected"),
        ) shouldBe true

        verify(exactly = 0) { fixture.ownership.grantLevel(any(), any(), any()) }
    }

    "plugin descriptor exposes only the unified mount command" {
        val descriptor = checkNotNull(MountCommandTest::class.java.getResource("/plugin.yml")).readText()

        Regex("(?m)^  mount:").containsMatchIn(descriptor) shouldBe true
        Regex("(?m)^  mounts:").containsMatchIn(descriptor) shouldBe false
        Regex("(?m)^  ride-mob:").containsMatchIn(descriptor) shouldBe false
        Regex("(?m)^  unlock-mount:").containsMatchIn(descriptor) shouldBe false
        descriptor.contains("arc.mounts.ride") shouldBe false
    }
})

private data class MountCommandFixture(
    val command: MountCommand,
    val player: Player,
    val console: CommandSender,
    val bukkitCommand: Command,
    val sessions: MountSessionController,
    val ownership: MountOwnership,
    val openMenu: (Player) -> Unit,
    val scheduler: TestTaskScheduler,
    val playerId: UUID,
)

private fun commandFixture(catalog: MountCatalog, admin: Boolean = false): MountCommandFixture {
    val playerId = UUID.randomUUID()
    val server = mockk<Server>(relaxed = true)
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns playerId
        every { name } returns "Rider"
        every { hasPermission("arc.mounts.admin") } returns admin
        every { this@mockk.server } returns server
    }
    val console = mockk<CommandSender>(relaxed = true) {
        every { hasPermission("arc.mounts.admin") } returns true
        every { this@mockk.server } returns server
    }
    every { server.getPlayerExact("Rider") } returns player
    every { server.onlinePlayers } returns mutableListOf(player)
    val ownership = mockk<MountOwnership> {
        every { resolveUniqueId(any()) } returns CompletableFuture.completedFuture(playerId)
        every { grantLevel(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantSkin(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantGlow(any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeLevel(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeSkin(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeGlow(any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantAbility(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeAbility(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantSize(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeSize(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
    }
    val config = mockk<MountModuleConfig> {
        every { adminSessionDuration } returns java.time.Duration.ofSeconds(10)
        every { tuning } returns MountTuningDefinition(listOf(50, 65, 80, 90, 100), listOf(110, 150, 200, 300, 400), listOf(110, 200, 400))
        every { message(any(), any()) } answers { secondArg() }
    }
    val sessions = mockk<MountSessionController>(relaxed = true)
    val openMenu = mockk<(Player) -> Unit>(relaxed = true)
    val scheduler = TestTaskScheduler()
    val command =
        MountCommand(
            config = { config },
            catalog = { catalog },
            ownership = ownership,
            sessions = sessions,
            scheduler = scheduler,
            openMenu = openMenu,
        )
    return MountCommandFixture(command, player, console, mockk(relaxed = true), sessions, ownership, openMenu, scheduler, playerId)
}
