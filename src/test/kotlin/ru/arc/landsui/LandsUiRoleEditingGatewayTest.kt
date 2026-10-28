package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.flags.FlagRegistry
import me.angeschossen.lands.api.flags.enums.RoleFlagCategory
import me.angeschossen.lands.api.flags.type.RoleFlag
import me.angeschossen.lands.api.framework.holder.Changeable
import me.angeschossen.lands.api.land.Area
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.role.Role
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

class LandsUiRoleEditingGatewayTest : StringSpec({
    var runtime: AutoCloseable? = null
    beforeTest { runtime = MockBukkitTestRuntime.open() }
    afterTest { runtime?.close(); runtime = null }

    "role action rechecks policy and native event before toggling and publishing" {
        val f = roleEditFixture()
        f.change() shouldBe LandsUiChangeResult.APPLIED
        f.enabled shouldBe true
        verify(exactly = 2) { f.policy.canEdit(f.player, f.landPlayer, f.land, f.area, f.role, f.flag) }
        verify(exactly = 1) { f.policy.isCancelled(any()) }
        verify(exactly = 1) { (f.role as Changeable).saveAndPublishToRedis() }
        verify(exactly = 0) { f.landPlayer.setEditLand(any()) }
        verify(exactly = 0) { f.player.performCommand(any()) }
    }

    "stale state does not toggle a role" {
        val f = roleEditFixture()
        f.enabled = true
        f.change() shouldBe LandsUiChangeResult.STALE
        verify(exactly = 0) { f.role.toggleFlag(any()) }
        verify(exactly = 0) { f.policy.isCancelled(any()) }
    }

    "denied configured policy and update predicate stop before native events" {
        val f = roleEditFixture()
        every { f.policy.canEdit(any(), any(), any(), any(), any(), any()) } returns false
        f.change() shouldBe LandsUiChangeResult.DENIED
        every { f.policy.canEdit(any(), any(), any(), any(), any(), any()) } returns true
        every { f.flag.updatePredicate } returns Predicate<Role> { false }
        f.change() shouldBe LandsUiChangeResult.DENIED
        verify(exactly = 0) { f.role.toggleFlag(any()) }
        verify(exactly = 0) { f.policy.isCancelled(any()) }
    }

    "cancelled native event leaves the role and persistence unchanged" {
        val f = roleEditFixture()
        every { f.policy.isCancelled(any()) } returns true
        f.change() shouldBe LandsUiChangeResult.REJECTED
        verify(exactly = 0) { f.role.toggleFlag(any()) }
        verify(exactly = 0) { (f.role as Changeable).saveAndPublishToRedis() }
    }

    "removal or changed state during the event invalidates the displayed action" {
        val removed = roleEditFixture()
        every { removed.policy.isCancelled(any()) } answers { removed.roles.clear(); false }
        removed.change() shouldBe LandsUiChangeResult.STALE
        verify(exactly = 0) { removed.role.toggleFlag(any()) }

        val changed = roleEditFixture()
        every { changed.policy.isCancelled(any()) } answers { changed.enabled = true; false }
        changed.change() shouldBe LandsUiChangeResult.STALE
        verify(exactly = 0) { changed.role.toggleFlag(any()) }
    }
})

private class RoleEditFixture {
    val actorId: UUID = UUID.randomUUID()
    val integration = mockk<LandsIntegration>()
    val landPlayer = mockk<LandPlayer>(relaxed = true)
    val land = mockk<Land>(relaxed = true)
    val area = mockk<Area>(relaxed = true)
    val role = mockk<Role>(moreInterfaces = arrayOf(Changeable::class), relaxed = true)
    val flag = mockk<RoleFlag>(relaxed = true)
    val player = mockk<Player>(relaxed = true)
    val policy = mockk<LandsRoleEditing>()
    val roles = mutableListOf(role)
    var enabled = false
    val service: LandsUiManagementService

    init {
        val registry = mockk<FlagRegistry>()
        val landUlid = mockk<ULID>()
        val areaUlid = mockk<ULID>()
        val roleUlid = mockk<ULID>()
        every { player.uniqueId } returns actorId
        every { integration.getLandPlayer(actorId) } returns landPlayer
        every { integration.flagRegistry } returns registry
        every { registry.getRole("block_break") } returns flag
        every { landPlayer.lands } returns setOf(land)
        every { land.exists() } returns true
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns "role-land"
        every { land.isTrusted(actorId) } returns true
        every { land.allAreas } returns listOf(area)
        every { area.ulid } returns areaUlid
        every { areaUlid.toString() } returns "role-area"
        every { area.roles } answers { roles }
        every { role.ulid } returns roleUlid
        every { roleUlid.toString() } returns "role-id"
        every { role.holder } returns area
        every { role.hasFlag(flag) } answers { enabled }
        every { role.toggleFlag(flag) } answers { enabled = !enabled; enabled }
        every { (role as Changeable).saveAndPublishToRedis() } returns CompletableFuture.completedFuture(null)
        every { flag.name } returns "block_break"
        every { flag.category } returns RoleFlagCategory.ACTION
        every { flag.isDisplay } returns true
        every { flag.shouldDisplay(area, landPlayer) } returns true
        every { flag.updatePredicate } returns Predicate<Role> { true }
        every { policy.canEdit(any(), any(), any(), any(), any(), any()) } returns true
        every { policy.isCancelled(any()) } returns false
        service = LandsUiManagementService(integration, { it.name }, policy)
    }

    fun change() = service.change(player, LandsUiContext("role-land", LandsUiAccess.MEMBER, "role-area"),
        LandsUiChange.RoleFlag("role-id", "block_break", false))
}

private fun roleEditFixture() = RoleEditFixture()
