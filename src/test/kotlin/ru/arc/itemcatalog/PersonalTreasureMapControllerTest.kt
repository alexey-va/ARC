package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.entity.Player
import ru.arc.core.TestTaskScheduler
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PersonalTreasureMapControllerTest : StringSpec({
    "first activation after transfer binds its owner and never claims on that click" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("PersonalMapTest")
            val scheduler = TestTaskScheduler()
            val marker = RecordingPersonalTreasureMapMarker()
            val owner = paper.addPlayer("map-owner")
            val visitor = paper.addPlayer("map-visitor")
            val spec = testMapSpec()
            val definition = testMapDefinition(owner.world.name)
            val voucher = PhysicalRewardVoucher.mark(
                spec.preview.clone(),
                PhysicalRewardVoucherIdentity(UUID.randomUUID(), spec.key, spec.fingerprint),
            )
            val controller = newController(plugin, scheduler, marker, spec, definition)
            var boundMap: org.bukkit.inventory.ItemStack? = null
            try {
                // The prize may move to another player before its first right-click activation.
                val target = definition.destinationFor(PhysicalRewardVoucher.identity(voucher)!!.id)
                visitor.world.loadChunk(0, 0)
                visitor.inventory.setItemInMainHand(voucher.clone())
                visitor.teleport(Location(visitor.world, target.x + 2, target.y, target.z))
                controller.refreshHeldMap(visitor)
                controller.identity(visitor.inventory.itemInMainHand) shouldBe null
                controller.beforeUse(visitor, visitor.inventory.itemInMainHand) shouldBe PersonalTreasureMapUseDecision.OPEN_MAP
                val bound = visitor.inventory.itemInMainHand
                boundMap = bound.clone()
                val identity = controller.identity(bound).shouldNotBeNull()
                identity.ownerId shouldBe visitor.uniqueId
                identity.definitionFingerprint shouldBe definition.fingerprint
                identity.destinationIndex shouldBe definition.destinationIndex(identity.voucherId)
                identity.mapViewServer shouldBe "spawn"
                (identity.mapViewId != null) shouldBe true
                PhysicalRewardVoucher.identity(bound)?.id shouldBe identity.voucherId

                // Even at the cache, first activation only binds and opens the map.
                visitor.teleport(Location(visitor.world, target.x + 20, target.y, target.z))
                controller.beforeUse(visitor, visitor.inventory.itemInMainHand) shouldBe PersonalTreasureMapUseDecision.OPEN_MAP
                visitor.teleport(Location(visitor.world, target.x + 2, target.y, target.z))
                controller.beforeUse(visitor, visitor.inventory.itemInMainHand) shouldBe PersonalTreasureMapUseDecision.CLAIM
                marker.visibleTo shouldBe visitor.uniqueId

                owner.inventory.setItemInMainHand(bound.clone())
                controller.beforeUse(owner, owner.inventory.itemInMainHand) shouldBe PersonalTreasureMapUseDecision.REJECT
                controller.preflight(owner, PhysicalRewardVoucher.identity(bound)!!, spec) shouldBe PersonalTreasureMapFailure.WRONG_OWNER

                val partial = voucher.clone().apply {
                    editMeta { meta ->
                        meta.persistentDataContainer.set(
                            PersonalTreasureMapIdentity.ownerKey,
                            PersistentDataType.STRING,
                            owner.uniqueId.toString(),
                        )
                    }
                }
                controller.bindToOwner(visitor, partial) shouldBe null
                controller.beforeUse(visitor, partial) shouldBe PersonalTreasureMapUseDecision.REJECT
            } finally {
                controller.close()
            }
            marker.visibleTo shouldBe null

            val reboundMarker = RecordingPersonalTreasureMapMarker()
            val reloaded = newController(plugin, TestTaskScheduler(), reboundMarker, spec, definition)
            try {
                val frozenMap = reloaded.bindToOwner(visitor, boundMap!!).shouldNotBeNull()
                // The resolver passed to the new controller is the archived source; active config may differ.
                reloaded.identity(frozenMap)?.definitionFingerprint shouldBe definition.fingerprint
                PhysicalRewardVoucher.identity(frozenMap)?.key shouldBe spec.key
            } finally {
                reloaded.close()
            }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Behavioral personal-map test aborted or failed", cause)
        }
    }

    "claim preflight rechecks server and location after movement without changing voucher identity" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("PersonalMapPreflightTest")
            val scheduler = TestTaskScheduler()
            val marker = RecordingPersonalTreasureMapMarker()
            val player = paper.addPlayer("map-preflight")
            val spec = testMapSpec()
            val definition = testMapDefinition(player.world.name)
            val voucher = PhysicalRewardVoucher.mark(
                spec.preview.clone(),
                PhysicalRewardVoucherIdentity(UUID.randomUUID(), spec.key, spec.fingerprint),
            )
            val voucherIdentity = PhysicalRewardVoucher.identity(voucher).shouldNotBeNull()
            var server = "survival"
            val controller = newController(plugin, scheduler, marker, spec, definition) { server }
            val target = definition.destinationFor(voucherIdentity.id)
            try {
                player.world.loadChunk(0, 0)
                player.inventory.setItemInMainHand(voucher.clone())
                player.teleport(Location(player.world, target.x + 2, target.y, target.z))

                // First right-click only activates and binds the map, even on the wrong backend.
                controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                    PersonalTreasureMapUseDecision.OPEN_MAP
                val bound = player.inventory.itemInMainHand
                controller.identity(bound)?.ownerId shouldBe player.uniqueId
                PhysicalRewardVoucher.identity(bound)?.id shouldBe voucherIdentity.id
                controller.preflight(player, voucherIdentity, spec) shouldBe PersonalTreasureMapFailure.WRONG_SERVER

                // The same owner reaches the frozen destination on its configured backend.
                server = "spawn"
                controller.preflight(player, voucherIdentity, spec) shouldBe null

                // A move during the durable-claim window is caught by the later preflight.
                player.teleport(Location(player.world, target.x + 20, target.y, target.z))
                controller.preflight(player, voucherIdentity, spec) shouldBe PersonalTreasureMapFailure.TOO_FAR
                controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                    PersonalTreasureMapUseDecision.OPEN_MAP

                player.teleport(Location(player.world, target.x + 2, target.y, target.z))
                PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id shouldBe voucherIdentity.id
                controller.preflight(player, voucherIdentity, spec) shouldBe null
                controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                    PersonalTreasureMapUseDecision.CLAIM
                PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id shouldBe voucherIdentity.id
            } finally {
                controller.close()
            }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Behavioral personal-map test aborted or failed", cause)
        }
    }

    "destination selection is frozen and map cursor coordinates keep north up" {
        val destinations = listOf(
            destination("world", 10.0, 64.0, 20.0, "Сундук на спавне"),
            destination("world", -10.0, 64.0, -20.0, "Второй тайник"),
        )
        val id = UUID(0L, 7L)
        val first = PersonalTreasureMapDefinition("weekly.map", "weekly/dungeon-case-loot-case", destinations)
        val same = PersonalTreasureMapDefinition("weekly.map", "weekly/dungeon-case-loot-case", destinations.toList())
        val reordered = PersonalTreasureMapDefinition("weekly.map", "weekly/dungeon-case-loot-case", destinations.reversed())

        first.fingerprint shouldBe same.fingerprint
        (first.fingerprint != reordered.fingerprint) shouldBe true
        first.destinationFor(id) shouldBe first.destinations[first.destinationIndex(id)]
        personalTreasureMapPlayerCursorOffset(10.0, -10.0) shouldBe (20.toByte() to (-20).toByte())
        personalTreasureMapPlayerCursorOffset(10.0, 10.0) shouldBe (20.toByte() to 20.toByte())
    }
})

private fun newController(
    plugin: Plugin,
    scheduler: TestTaskScheduler,
    marker: RecordingPersonalTreasureMapMarker,
    spec: PhysicalRewardSpec,
    definition: PersonalTreasureMapDefinition,
    currentServer: () -> String = { "spawn" },
) = PersonalTreasureMapController(
    plugin = plugin,
    resolveSpec = { key -> spec.takeIf { it.key == key } },
    resolveDefinition = { resolved -> definition.takeIf { resolved.key == spec.key } },
    currentServer = currentServer,
    scheduler = scheduler,
    marker = marker,
    mapViewFactory = { testMapView() },
)

private val nextTestMapViewId = AtomicInteger(1_000_000)

private fun testMapView(): MapView {
    val viewId = nextTestMapViewId.getAndIncrement()
    val renderers = mutableListOf<MapRenderer>()
    return mockk<MapView> {
        every { id } returns viewId
        every { getRenderers() } answers { renderers }
        every { addRenderer(any()) } answers {
            renderers.add(firstArg())
            Unit
        }
        every { removeRenderer(any()) } answers { renderers.remove(firstArg()) }
        every { setScale(any()) } just runs
        every { setTrackingPosition(any()) } just runs
        every { setUnlimitedTracking(any()) } just runs
        every { setLocked(any()) } just runs
    }
}

private fun testMapSpec() = PhysicalRewardSpec(
    key = "weekly/map/archive-1",
    fingerprint = OneTimeUseFingerprint.sha256Fields("weekly/map/archive-1", "source-recipe-v1"),
    preview = org.bukkit.inventory.ItemStack(Material.FILLED_MAP),
)

private fun testMapDefinition(world: String) = PersonalTreasureMapDefinition(
    id = "weekly_personal_cache",
    prizeSourceRef = "weekly/dungeon-case-loot-case",
    destinations = listOf(destination(world, 2.0, 64.0, 0.0, "Тайник на спавне")),
)

private fun destination(world: String, x: Double, y: Double, z: Double, hint: String) =
    PersonalTreasureMapDestination("spawn", world, x, y, z, hint)

private class RecordingPersonalTreasureMapMarker : PersonalTreasureMapMarker {
    var visibleTo: UUID? = null
    val cleared = mutableListOf<UUID>()

    override fun show(player: Player, destination: PersonalTreasureMapDestination) {
        visibleTo = player.uniqueId
    }

    override fun clear(playerId: UUID) {
        if (visibleTo == playerId) visibleTo = null
        cleared += playerId
    }

    override fun close() = Unit
}
