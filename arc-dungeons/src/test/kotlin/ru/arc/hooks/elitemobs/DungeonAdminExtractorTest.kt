package ru.arc.hooks.elitemobs

import com.Zrips.CMI.Modules.Teleportations.CMITeleportType
import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent
import com.magmaguy.elitemobs.instanced.MatchInstance
import io.kotest.core.spec.style.FreeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

class DungeonAdminExtractorTest : FreeSpec({
    lateinit var actor: Player
    lateinit var target: Player
    lateinit var match: MatchInstance
    lateinit var players: MutableMap<UUID, Player>
    var now = 1_000L

    beforeEach {
        actor = player("admin", permission = true)
        target = player("target")
        match = mockk(relaxed = true)
        players = mutableMapOf(actor.uniqueId to actor, target.uniqueId to target)
        now = 1_000L
    }

    fun extractor(matchFor: (Player) -> MatchInstance? = { match }) =
        DungeonAdminExtractor(
            onlinePlayer = players::get,
            matchFor = matchFor,
            clock = { now },
        )

    "authorized CMI TpHere removes native dungeon participation before teleport" {
        val extractor = extractor()
        extractor.prepare(cmiTeleport(actor, target, CMITeleportType.TpHere))

        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        verify(exactly = 1) { match.removeAnyKind(target) }
    }

    "other CMI teleport types do not extract the player" {
        val extractor = extractor()
        extractor.prepare(cmiTeleport(actor, target, CMITeleportType.Tp))

        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        verify(exactly = 0) { match.removeAnyKind(any()) }
    }

    "request is consumed by the first teleport attempt and cannot leak" {
        val extractor = extractor()
        extractor.prepare(cmiTeleport(actor, target, CMITeleportType.TpHere))

        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN))
        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        verify(exactly = 0) { match.removeAnyKind(any()) }
    }

    "expired request and unauthorized actor fail closed" {
        val extractor = extractor()
        extractor.prepare(cmiTeleport(actor, target, CMITeleportType.TpHere))
        now += 15_000_000_001L
        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        val denied = player("denied", permission = false)
        players[denied.uniqueId] = denied
        extractor.prepare(cmiTeleport(denied, target, CMITeleportType.TpHere))
        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        verify(exactly = 0) { match.removeAnyKind(any()) }
    }

    "quit clears requests for both target and actor" {
        val extractor = extractor()
        extractor.prepare(cmiTeleport(actor, target, CMITeleportType.TpHere))
        extractor.forget(mockk<PlayerQuitEvent> { every { player } returns actor })

        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        verify(exactly = 0) { match.removeAnyKind(any()) }
    }

    "player outside an EliteMobs instance remains a normal TpHere" {
        val extractor = extractor(matchFor = { null })
        extractor.prepare(cmiTeleport(actor, target, CMITeleportType.TpHere))

        extractor.extract(bukkitTeleport(target, PlayerTeleportEvent.TeleportCause.COMMAND))

        verify(exactly = 0) { match.removeAnyKind(any()) }
    }
})

private fun player(name: String, permission: Boolean = false): Player =
    mockk {
        every { this@mockk.name } returns name
        every { uniqueId } returns UUID.nameUUIDFromBytes(name.toByteArray())
        every { hasPermission("arc.dungeon.admin.extract") } returns permission
    }

private fun cmiTeleport(
    actor: Player,
    target: Player,
    type: CMITeleportType,
): CMIAsyncPlayerTeleportEvent =
    mockk {
        every { sender } returns actor
        every { player } returns target
        every { this@mockk.type } returns type
        every { to } returns mockk<Location>()
    }

private fun bukkitTeleport(
    target: Player,
    cause: PlayerTeleportEvent.TeleportCause,
): PlayerTeleportEvent =
    mockk {
        every { player } returns target
        every { this@mockk.cause } returns cause
    }
