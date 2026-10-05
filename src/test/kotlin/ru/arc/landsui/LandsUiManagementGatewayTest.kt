package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.configuration.Configuration
import me.angeschossen.lands.api.configuration.MainConfig
import me.angeschossen.lands.api.flags.FlagRegistry
import me.angeschossen.lands.api.flags.type.Flags
import me.angeschossen.lands.api.flags.type.NaturalFlag
import me.angeschossen.lands.api.flags.type.RoleFlag
import me.angeschossen.lands.api.framework.holder.Changeable
import me.angeschossen.lands.api.land.Area
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.player.Selection
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

class LandsUiManagementGatewayTest : StringSpec({
    val originalFlags = mutableMapOf<String, Any?>()
    val staticRoleFlags = listOf("PLAYER_TRUST", "PLAYER_UNTRUST", "PLAYER_SETROLE", "LAND_CLAIM", "SETTING_EDIT_LAND")

    beforeSpec {
        staticRoleFlags.forEach { name ->
            val field = Flags::class.java.getField(name)
            originalFlags[name] = field.get(null)
            field.set(null, mockk<RoleFlag>(relaxed = true))
        }
    }

    afterSpec {
        staticRoleFlags.forEach { name ->
            Flags::class.java.getField(name).set(null, originalFlags[name])
        }
    }

    "admin trust targets the selected subarea with the area-scoped native permission" {
        val fixture = adminFixture()
        val command = "lands area Green_House member trust NewBuilder"
        every { fixture.player.hasPermission("lands.command.area.member.trust") } returns true
        every { fixture.player.performCommand(command) } returns true

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.ADMIN, fixture.areaId),
            LandsUiChange.Trust("NewBuilder"),
        )

        result shouldBe LandsUiChangeResult.DISPATCHED
        verifyOrder {
            fixture.landPlayer.setEditLand(fixture.land)
            fixture.player.performCommand(command)
        }
    }

    "admin subarea native menu uses the area menu command and requires its permission" {
        val fixture = adminFixture()
        val command = "lands area Green_House menu"
        every { fixture.player.hasPermission("lands.command.area.menu") } returns true
        every { fixture.player.performCommand(command) } returns true

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.ADMIN, fixture.areaId),
            LandsUiChange.NativeMenu,
        )

        result shouldBe LandsUiChangeResult.DISPATCHED
        verifyOrder {
            fixture.landPlayer.setEditLand(fixture.land)
            fixture.player.performCommand(command)
        }
    }

    "revoked scoped admin permission denies the action before selection or dispatch" {
        val fixture = adminFixture()
        every { fixture.player.hasPermission("lands.command.area.member.trust") } returns false

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.ADMIN, fixture.areaId),
            LandsUiChange.Trust("NewBuilder"),
        )

        result shouldBe LandsUiChangeResult.DENIED
        verify(exactly = 0) { fixture.landPlayer.setEditLand(any()) }
        verify(exactly = 0) { fixture.player.performCommand(any()) }
    }

    "edit-by-location rejects a land command when the player is at another land" {
        val fixture = adminFixture()
        val otherLand = mockk<Land>()
        val otherUlid = mockk<ULID>()
        val location = mockk<Location>()
        val world = mockk<World>()
        every { fixture.land.defaultArea } returns mockk<Area>()
        every { fixture.player.hasPermission("lands.command.rename") } returns true
        every { fixture.player.hasPermission("lands.admin.setting_edit_land") } returns true
        every { fixture.player.location } returns location
        every { location.world } returns world
        every { location.blockX } returns 32
        every { location.blockZ } returns 64
        every { fixture.integration.getLandByUnloadedChunk(world, 2, 4) } returns otherLand
        every { otherLand.ulid } returns otherUlid
        every { fixture.mainConfig.getBoolean("general.edit-by-loc") } returns true

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.ADMIN, fixture.areaId),
            LandsUiChange.Rename("Changed"),
        )

        result shouldBe LandsUiChangeResult.STALE
        verify(exactly = 0) { fixture.landPlayer.setEditLand(any()) }
        verify(exactly = 0) { fixture.player.performCommand(any()) }
    }

    "current-land borders handoff runs in place without changing selected land" {
        val playerId = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val landPlayer = mockk<LandPlayer>()
        val land = mockk<Land>()
        val landUlid = mockk<ULID>()
        val area = mockk<Area>()
        val player = mockk<Player>()
        val location = mockk<Location>()
        val world = mockk<World>()
        every { player.uniqueId } returns playerId
        every { player.location } returns location
        every { location.world } returns world
        every { location.blockX } returns 32
        every { location.blockZ } returns 64
        every { integration.getLandPlayer(playerId) } returns landPlayer
        every { integration.getLandByUnloadedChunk(world, 2, 4) } returns land
        every { land.exists() } returns true
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns "01KCURRENT"
        every { land.allAreas } returns listOf(area)
        every { land.getArea(location) } returns area
        every { player.hasPermission("lands.command.view") } returns true
        every { player.performCommand("lands view") } returns true

        val result = BukkitLandsUiGateway(integration).change(
            player,
            LandsUiContext("01KCURRENT", LandsUiAccess.CURRENT),
            LandsUiChange.Borders,
        )

        result shouldBe LandsUiChangeResult.DISPATCHED
        verify(exactly = 0) { landPlayer.setEditLand(any()) }
        verify(exactly = 1) { player.performCommand("lands view") }
    }

    "claim is rejected while a Lands selection is active" {
        val playerId = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val landPlayer = mockk<LandPlayer>()
        val land = mockk<Land>()
        val landUlid = mockk<ULID>()
        val area = mockk<Area>()
        val selection = mockk<Selection>()
        val player = mockk<Player>()
        every { player.uniqueId } returns playerId
        every { integration.getLandPlayer(playerId) } returns landPlayer
        every { landPlayer.lands } returns setOf(land)
        every { landPlayer.selection } returns selection
        every { land.exists() } returns true
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns "01KCLAIM"
        every { land.isTrusted(playerId) } returns true
        every { land.defaultArea } returns area
        every { land.allAreas } returns listOf(area)
        every { area.hasRoleFlag(landPlayer, Flags.LAND_CLAIM, null, false) } returns true
        every { player.hasPermission("lands.command.claim") } returns true

        val result = BukkitLandsUiGateway(integration).change(
            player,
            LandsUiContext("01KCLAIM", LandsUiAccess.MEMBER),
            LandsUiChange.Claim,
        )

        result shouldBe LandsUiChangeResult.ACTIVE_SELECTION
        verify(exactly = 0) { landPlayer.setEditLand(any()) }
        verify(exactly = 0) { player.performCommand(any()) }
    }

    "natural flag toggle requires both flag permission and effective area setting permission" {
        listOf(false to true, true to false).forEach { (toggleAllowed, settingAllowed) ->
            val fixture = memberAreaFixture()
            every { fixture.flag.togglePermission } returns "lands.flag.fire_spread"
            every { fixture.flag.isDisplay } returns true
            every { fixture.flag.shouldDisplay(fixture.area, fixture.landPlayer) } returns true
            every { fixture.flag.isApplyInSubareas } returns true
            every { fixture.area.isDefault } returns false
            every { fixture.player.hasPermission("lands.flag.fire_spread") } returns toggleAllowed
            every { fixture.area.hasRoleFlag(fixture.landPlayer, Flags.SETTING_EDIT_LAND, null, false) } returns settingAllowed
            every { fixture.area.hasNaturalFlag(fixture.flag) } returns false

            val result = BukkitLandsUiGateway(fixture.integration).change(
                fixture.player,
                LandsUiContext(fixture.landId, LandsUiAccess.MEMBER, fixture.areaId),
                LandsUiChange.NaturalFlag("fire_spread", expected = false),
            )

            result shouldBe LandsUiChangeResult.DENIED
            verify(exactly = 0) { fixture.area.toggleNaturalFlag(fixture.flag) }
            verify(exactly = 0) { (fixture.area as Changeable).saveAndPublishToRedis() }
        }
    }

    "stale natural flag state is rejected without mutation" {
        val fixture = memberAreaFixture()
        every { fixture.area.hasNaturalFlag(fixture.flag) } returns false

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.MEMBER, fixture.areaId),
            LandsUiChange.NaturalFlag("fire_spread", expected = true),
        )

        result shouldBe LandsUiChangeResult.STALE
        verify(exactly = 0) { fixture.area.toggleNaturalFlag(fixture.flag) }
        verify(exactly = 0) { (fixture.area as Changeable).saveAndPublishToRedis() }
    }

    "natural flag toggle persists the selected area only" {
        val fixture = memberAreaFixture()
        var enabled = false
        every { fixture.flag.togglePermission } returns "lands.flag.fire_spread"
        every { fixture.flag.isDisplay } returns true
        every { fixture.flag.shouldDisplay(fixture.area, fixture.landPlayer) } returns true
        every { fixture.flag.isApplyInSubareas } returns true
        every { fixture.area.isDefault } returns false
        every { fixture.player.hasPermission("lands.flag.fire_spread") } returns true
        every { fixture.area.hasRoleFlag(fixture.landPlayer, Flags.SETTING_EDIT_LAND, null, false) } returns true
        every { fixture.area.hasNaturalFlag(fixture.flag) } answers { enabled }
        every { fixture.area.toggleNaturalFlag(fixture.flag) } answers { enabled = !enabled; true }
        every { (fixture.area as Changeable).saveAndPublishToRedis() } returns java.util.concurrent.CompletableFuture.completedFuture(null)

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.MEMBER, fixture.areaId),
            LandsUiChange.NaturalFlag("fire_spread", expected = false),
        )

        result shouldBe LandsUiChangeResult.APPLIED
        verify(exactly = 1) { fixture.area.toggleNaturalFlag(fixture.flag) }
        verify(exactly = 1) { (fixture.area as Changeable).saveAndPublishToRedis() }
        verify(exactly = 0) { fixture.land.saveAndPublishToRedis() }
    }

    "subarea setting permission cannot edit the land-wide entry message" {
        val fixture = memberAreaFixture()
        val mainArea = mockk<Area>()
        val mainAreaUlid = mockk<ULID>()
        every { fixture.land.defaultArea } returns mainArea
        every { fixture.land.allAreas } returns listOf(mainArea, fixture.area)
        every { mainArea.ulid } returns mainAreaUlid
        every { mainAreaUlid.toString() } returns "01KMAINAREA"
        every { mainArea.hasRoleFlag(fixture.landPlayer, Flags.SETTING_EDIT_LAND, null, false) } returns false
        every { fixture.area.isDefault } returns false
        every { fixture.area.hasRoleFlag(fixture.landPlayer, Flags.SETTING_EDIT_LAND, null, false) } returns true

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.MEMBER, fixture.areaId),
            LandsUiChange.Description("entry text"),
        )

        result shouldBe LandsUiChangeResult.DENIED
        verify(exactly = 0) { fixture.land.setTitleMessage(any()) }
        verify(exactly = 0) { fixture.land.saveAndPublishToRedis() }
    }

    "empty entry message clears the native title message" {
        val fixture = memberAreaFixture()
        every { fixture.land.defaultArea } returns fixture.area
        every { fixture.area.hasRoleFlag(fixture.landPlayer, Flags.SETTING_EDIT_LAND, null, false) } returns true
        every { fixture.land.saveAndPublishToRedis() } returns java.util.concurrent.CompletableFuture.completedFuture(null)

        val result = BukkitLandsUiGateway(fixture.integration).change(
            fixture.player,
            LandsUiContext(fixture.landId, LandsUiAccess.MEMBER, fixture.areaId),
            LandsUiChange.Description(""),
        )

        result shouldBe LandsUiChangeResult.APPLIED
        verify(exactly = 1) { fixture.land.setTitleMessage(null) }
        verify(exactly = 1) { fixture.land.saveAndPublishToRedis() }
    }
})

private data class AdminAreaFixture(
    val integration: LandsIntegration,
    val landPlayer: LandPlayer,
    val land: Land,
    val player: Player,
    val mainConfig: MainConfig,
    val landId: String,
    val areaId: String,
)

private fun adminFixture(): AdminAreaFixture {
    val actorId = UUID.randomUUID()
    val integration = mockk<LandsIntegration>()
    val landPlayer = mockk<LandPlayer>()
    val land = mockk<Land>()
    val area = mockk<Area>()
    val landUlid = mockk<ULID>()
    val areaUlid = mockk<ULID>()
    val player = mockk<Player>(relaxed = true)
    val landsConfig = mockk<Configuration>()
    val mainConfig = mockk<MainConfig>()
    val landId = "01KADMINLAND"
    val areaId = "01KADMINAREA"

    every { player.uniqueId } returns actorId
    every { player.hasPermission("lands.admin.command.edit") } returns true
    every { integration.getLandPlayer(actorId) } returns landPlayer
    every { integration.lands } returns listOf(land)
    every { integration.configuration } returns landsConfig
    every { landsConfig.mainConfig } returns mainConfig
    every { mainConfig.hasValue("general.edit-by-loc") } returns true
    every { mainConfig.getBoolean("general.edit-by-loc") } returns false
    every { land.exists() } returns true
    every { land.ulid } returns landUlid
    every { landUlid.toString() } returns landId
    every { land.allAreas } returns listOf(area)
    every { landPlayer.setEditLand(land) } just runs
    every { area.ulid } returns areaUlid
    every { areaUlid.toString() } returns areaId
    every { area.isDefault } returns false
    every { area.name } returns "Green House"

    return AdminAreaFixture(integration, landPlayer, land, player, mainConfig, landId, areaId)
}

private data class MemberAreaFixture(
    val integration: LandsIntegration,
    val landPlayer: LandPlayer,
    val land: Land,
    val area: Area,
    val flag: NaturalFlag,
    val player: Player,
    val landId: String,
    val areaId: String,
)

private fun memberAreaFixture(): MemberAreaFixture {
    val actorId = UUID.randomUUID()
    val integration = mockk<LandsIntegration>()
    val registry = mockk<FlagRegistry>()
    val landPlayer = mockk<LandPlayer>()
    val land = mockk<Land>(relaxed = true)
    val area = mockk<Area>(moreInterfaces = arrayOf(Changeable::class), relaxed = true)
    val flag = mockk<NaturalFlag>()
    val landUlid = mockk<ULID>()
    val areaUlid = mockk<ULID>()
    val player = mockk<Player>(relaxed = true)
    val landId = "01KMEMBERLAND"
    val areaId = "01KMEMBERAREA"

    every { player.uniqueId } returns actorId
    every { integration.getLandPlayer(actorId) } returns landPlayer
    every { integration.flagRegistry } returns registry
    every { registry.getNatural("fire_spread") } returns flag
    every { flag.isActiveInWar } returns false
    every { landPlayer.lands } returns setOf(land)
    every { land.exists() } returns true
    every { land.ulid } returns landUlid
    every { landUlid.toString() } returns landId
    every { land.isTrusted(actorId) } returns true
    every { land.allAreas } returns listOf(area)
    every { area.ulid } returns areaUlid
    every { areaUlid.toString() } returns areaId
    every { area.isDefault } returns true

    return MemberAreaFixture(integration, landPlayer, land, area, flag, player, landId, areaId)
}
