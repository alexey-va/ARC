package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.player.invite.Invite
import me.angeschossen.lands.api.player.invite.InviteIntent
import org.bukkit.entity.Player
import java.util.UUID

class LandsUiInvitationsTest : StringSpec({
    "inbox lists only the viewer's current invitations with literal land names" {
        val f = invitationFixture()
        f.service.list(f.player) shouldBe listOf(f.snapshot)
        every { f.invite.receiver } returns UUID.randomUUID()
        f.service.list(f.player) shouldBe emptyList()
    }

    "changed invitation and revoked permissions cannot accept a stale offer" {
        val f = invitationFixture()
        every { f.invite.time } returns f.snapshot.createdAt + 1
        f.service.respond(f.player, f.snapshot, true).join() shouldBe LandsUiChangeResult.STALE
        every { f.player.hasPermission("lands.command.invites.accept") } returns false
        f.service.respond(f.player, f.snapshot, true).join() shouldBe LandsUiChangeResult.DENIED
        verify(exactly = 0) { f.invite.accept(any()) }
    }

    "accept uses the native command preflight without selecting another land" {
        val f = invitationFixture()
        every { f.player.performCommand("lands invites accept Home.City") } returns true
        f.service.respond(f.player, f.snapshot, true).join() shouldBe LandsUiChangeResult.DISPATCHED
        verify(exactly = 0) { f.invite.accept(any()) }
        verify(exactly = 1) { f.player.performCommand("lands invites accept Home.City") }
        verify(exactly = 0) { f.landPlayer.setEditLand(any()) }
    }

    "native rejection is not reported as accepted membership" {
        val f = invitationFixture()
        every { f.player.performCommand("lands invites accept Home.City") } returns false
        f.service.respond(f.player, f.snapshot, true).join() shouldBe LandsUiChangeResult.REJECTED
    }

    "decline requires its permission and denies only the matching snapshot" {
        val f = invitationFixture()
        every { f.player.hasPermission("lands.command.invites.decline") } returns false
        f.service.respond(f.player, f.snapshot, false).join() shouldBe LandsUiChangeResult.DENIED
        verify(exactly = 0) { f.invite.deny() }
        every { f.player.hasPermission("lands.command.invites.decline") } returns true
        every { f.player.performCommand("lands invites decline Home.City") } returns true
        f.service.respond(f.player, f.snapshot, false).join() shouldBe LandsUiChangeResult.DISPATCHED
        verify(exactly = 1) { f.player.performCommand("lands invites decline Home.City") }
        verify(exactly = 0) { f.invite.deny() }
    }
})

private class InvitationFixture {
    private val playerId = UUID.randomUUID()
    private val sender = UUID.randomUUID()
    private val integration = mockk<LandsIntegration>()
    val player = mockk<Player>(relaxed = true)
    val landPlayer = mockk<LandPlayer>(relaxed = true)
    val invite = mockkClass(Invite::class, relaxed = true)
    private val land = mockk<Land>(relaxed = true)
    private val ulid = mockk<ULID>()
    val snapshot = LandsUiInvitation("01LANDINVITE", "Home.City", sender, 12345L, LandsUiInviteIntent.TRUST)
    val service = LandsUiInvitations(integration)

    init {
        every { player.uniqueId } returns playerId
        every { player.hasPermission(any<String>()) } returns true
        every { integration.getLandPlayer(playerId) } returns landPlayer
        every { landPlayer.invites } returns listOf(invite)
        every { invite.receiver } returns playerId
        every { invite.sender } returns sender
        every { invite.time } returns 12345L
        every { invite.intent } returns InviteIntent.TRUST
        every { invite.land } returns land
        every { land.exists() } returns true
        every { land.name } returns "Home.City"
        every { land.ulid } returns ulid
        every { ulid.toString() } returns "01LANDINVITE"
    }
}

private fun invitationFixture() = InvitationFixture()
