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
        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    "admin summon uses a configured level instead of arbitrary raw speed" {
        val fixture = commandFixture(catalog, admin = true)
        every {
            fixture.sessions.spawn(
                player = fixture.player,
                definition = mount,
                speed = mount.level(3).speed,
                handlingMultiplier = mount.level(3).handlingMultiplier,
                sprintMultiplier = mount.level(3).sprintMultiplier,
                durationMillis = 10_000L,
                glow = false,
                skin = mount.skin("baby"),
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
                speed = 0.9,
                handlingMultiplier = 1.28,
                sprintMultiplier = 1.12,
                durationMillis = 10_000L,
                glow = false,
                skin = mount.skin("baby"),
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

        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any(), any(), any(), any(), any()) }
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf(""))
            .shouldContainExactly("help", "menu")
    }

    "admin can grant and revoke levels, skins and glow" {
        val fixture = commandFixture(catalog, admin = true)

        listOf(
            arrayOf("admin", "grant", "level", "Rider", "bee", "3"),
            arrayOf("admin", "grant", "skin", "Rider", "bee", "baby"),
            arrayOf("admin", "grant", "glow", "Rider", "bee"),
            arrayOf("admin", "revoke", "level", "Rider", "bee", "3"),
            arrayOf("admin", "revoke", "skin", "Rider", "bee", "baby"),
            arrayOf("admin", "revoke", "glow", "Rider", "bee"),
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
        verify(exactly = 6) { fixture.console.sendMessage(any<Component>()) }
    }

    "admin completion follows the unified command tree" {
        val fixture = commandFixture(catalog, admin = true)

        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf(""))
            .shouldContainExactly("admin", "help", "menu")
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", ""))
            .shouldContainExactly("grant", "revoke", "summon")
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", "grant", ""))
            .shouldContainExactly("glow", "level", "skin")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "skin", "Rider", "bee", ""),
        ).shouldContainExactly("baby")
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
    }
    val config = mockk<MountModuleConfig> {
        every { adminSessionDuration } returns java.time.Duration.ofSeconds(10)
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
