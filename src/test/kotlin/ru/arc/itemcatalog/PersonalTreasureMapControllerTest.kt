package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.core.TestTaskScheduler
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.lands.LandsHook
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.hooks.worldguard.WGHook
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

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
            val staleMapPreview = org.bukkit.inventory.ItemStack(Material.FILLED_MAP).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("Снаряжение EliteMobs"))
                    meta.lore(listOf(Component.text("Тайник на Спавне")))
                }
            }
            val definition = testMapDefinition(owner.world.name)
            val voucher = PhysicalRewardVoucher.mark(
                staleMapPreview,
                PhysicalRewardVoucherIdentity(UUID.randomUUID(), spec.key, spec.fingerprint),
            )
            val controller = newController(plugin, scheduler, marker, spec, definition)
            var boundMap: org.bukkit.inventory.ItemStack? = null
            try {
                // The prize may move to another player before its first right-click activation.
                val target = definition.destinationFor(PhysicalRewardVoucher.identity(voucher)!!.id)
                visitor.world.loadChunk(0, 0)
                prepareSafeSurface(visitor.world, target)
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
                identity.mapViewWorld shouldBe visitor.world.uid.toString()
                val plain = PlainTextComponentSerializer.plainText()
                plain.serialize(bound.itemMeta!!.displayName()!!) shouldBe "Карта тайника"
                val boundLore = bound.itemMeta!!.lore().orEmpty().joinToString(" ") { plain.serialize(it) }
                boundLore.contains("EliteMobs") shouldBe false
                boundLore.contains("Спавн") shouldBe false
                boundLore.contains("Survival") shouldBe true
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

    "map views are reused per world and every managed renderer is removed on close" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapMultiworldViewTest")
                val playerA = paper.addPlayer("map-view-a")
                val playerB = paper.addPlayer("map-view-b")
                val otherWorld = MockBukkit.getMock()!!.addSimpleWorld("map-view-other-world")
                playerB.teleport(Location(otherWorld, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val definition = testMapDefinition(playerA.world.name)
                val createdViews = mutableListOf<MapView>()
                val marker = RecordingPersonalTreasureMapMarker()
                val controller = PersonalTreasureMapController(
                    plugin = plugin,
                    resolveSpec = { key -> spec.takeIf { it.key == key } },
                    resolveDefinition = { resolved -> definition.takeIf { resolved.key == spec.key } },
                    currentServer = { "spawn" },
                    scheduler = TestTaskScheduler(),
                    marker = marker,
                    mapViewFactory = { _ -> testMapView().also { createdViews += it } },
                    isUnclaimed = { true },
                )
                try {
                    fun voucher(id: UUID) = PhysicalRewardVoucher.mark(
                        spec.preview.clone(), PhysicalRewardVoucherIdentity(id, spec.key, spec.fingerprint),
                    )

                    val mapA = controller.bindToOwner(playerA, voucher(UUID.randomUUID())).shouldNotBeNull()
                    val mapB = controller.bindToOwner(playerB, voucher(UUID.randomUUID())).shouldNotBeNull()
                    controller.identity(mapA)?.mapViewWorld shouldBe playerA.world.uid.toString()
                    controller.identity(mapB)?.mapViewWorld shouldBe playerB.world.uid.toString()
                    createdViews.size shouldBe 2
                    val viewA = createdViews[0]
                    val viewB = createdViews[1]
                    viewA.getRenderers().size shouldBe 1
                    viewB.getRenderers().size shouldBe 1

                    repeat(2) {
                        controller.decorateMap(playerA, mapA).shouldNotBeNull()
                        controller.decorateMap(playerB, mapB).shouldNotBeNull()
                    }
                    createdViews.size shouldBe 2
                    viewA.getRenderers().size shouldBe 1
                    viewB.getRenderers().size shouldBe 1
                } finally {
                    controller.close()
                }
                createdViews.forEach { it.getRenderers().size shouldBe 0 }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Multi-world map-view lifecycle test aborted or failed", cause)
        }
    }

    "a late completion from an old request cannot cancel the player's replacement search" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapStaleSearchTest")
                val scheduler = TestTaskScheduler()
                val player = paper.addPlayer("map-stale-search")
                player.teleport(Location(player.world, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val definition = testSearchDefinition(player.world.name)
                val firstVoucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(), PhysicalRewardVoucherIdentity(UUID.randomUUID(), spec.key, spec.fingerprint),
                )
                val replacementVoucherId = UUID.randomUUID()
                val replacementVoucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(), PhysicalRewardVoucherIdentity(replacementVoucherId, spec.key, spec.fingerprint),
                )
                val firstFuture = CompletableFuture<Chunk?>()
                val replacementFuture = CompletableFuture<Chunk?>()
                val requested = mutableListOf<CompletableFuture<Chunk?>>()
                val controller = newController(
                    plugin, scheduler, RecordingPersonalTreasureMapMarker(), spec, definition,
                    asyncChunkLoader = { _, _, _ ->
                        val future = if (requested.isEmpty()) firstFuture else replacementFuture
                        requested += future
                        future
                    },
                    currentServer = { "survival" },
                )
                try {
                    player.inventory.setItemInMainHand(firstVoucher)
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    requested.size shouldBe 1

                    // Replacing the held voucher retires request A and starts request B for this player.
                    player.inventory.setItemInMainHand(replacementVoucher)
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    requested.size shouldBe 2
                    controller.guidance(player)?.searching shouldBe true

                    // A finishes after B has become current. Its stale callback must leave B registered.
                    firstFuture.complete(null)
                    scheduler.executeImmediate()
                    controller.identity(player.inventory.itemInMainHand)?.target shouldBe null
                    controller.guidance(player)?.searching shouldBe true

                    val candidate = personalTreasureMapCandidateOrder(
                        replacementVoucherId, 0, centerX = 0, centerZ = 0, radius = 32,
                    ).first()
                    prepareCandidateSurface(player.world, candidate.first, candidate.second)
                    val replacementChunk = player.world.getChunkAt(candidate.first shr 4, candidate.second shr 4)
                    replacementFuture.complete(replacementChunk)
                    scheduler.executeImmediate()

                    val completed = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    completed.voucherId shouldBe replacementVoucherId
                    completed.ownerId shouldBe player.uniqueId
                    completed.searchGeneration shouldBe 1
                    completed.target.shouldNotBeNull().let { target ->
                        target.world shouldBe player.world.name
                        target.x shouldBe candidate.first + 0.5
                        target.z shouldBe candidate.second + 0.5
                    }
                    controller.guidance(player)?.searching shouldBe false
                } finally {
                    controller.close()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Late personal-map search completion test aborted or failed", cause)
        }
    }

    "missing pre-generated chunks are skipped within the candidate cap without changing the voucher" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapUngeneratedChunkTest")
                val scheduler = TestTaskScheduler()
                val player = paper.addPlayer("map-ungenerated")
                player.teleport(Location(player.world, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val definition = testSearchDefinition(player.world.name)
                val voucherId = UUID.randomUUID()
                val voucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(), PhysicalRewardVoucherIdentity(voucherId, spec.key, spec.fingerprint),
                )
                val candidates = personalTreasureMapCandidateOrder(voucherId, 0, 0, 0, 32)
                val chunksBefore = candidates.map { (x, z) ->
                    (Pair(x shr 4, z shr 4)) to player.world.isChunkLoaded(x shr 4, z shr 4)
                }.toMap()
                var attempts = 0
                val controller = newController(
                    plugin, scheduler, RecordingPersonalTreasureMapMarker(), spec, definition,
                    asyncChunkLoader = { _, _, _ ->
                        attempts++
                        CompletableFuture.completedFuture(null)
                    },
                    currentServer = { "survival" },
                )
                try {
                    player.inventory.setItemInMainHand(voucher.clone())
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    repeat(100) { scheduler.executeImmediate() }

                    attempts shouldBe PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT
                    val identity = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    identity.voucherId shouldBe voucherId
                    identity.ownerId shouldBe player.uniqueId
                    identity.searchGeneration shouldBe 1
                    identity.target shouldBe null
                    PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id shouldBe voucherId
                    candidates.map { (x, z) ->
                        (Pair(x shr 4, z shr 4)) to player.world.isChunkLoaded(x shr 4, z shr 4)
                    }.toMap() shouldBe chunksBefore
                } finally {
                    controller.close()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Generated-only map chunk probe test aborted or failed", cause)
        }
    }

    "an async chunk probe expires and its late result cannot rewrite the retained voucher" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapSearchTimeoutTest")
                val scheduler = TestTaskScheduler()
                val player = paper.addPlayer("map-search-timeout")
                player.teleport(Location(player.world, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val definition = testSearchDefinition(player.world.name)
                val voucherId = UUID.randomUUID()
                val voucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(), PhysicalRewardVoucherIdentity(voucherId, spec.key, spec.fingerprint),
                )
                val chunkFuture = CompletableFuture<Chunk?>()
                val controller = newController(
                    plugin, scheduler, RecordingPersonalTreasureMapMarker(), spec, definition,
                    asyncChunkLoader = { _, _, _ -> chunkFuture },
                    currentServer = { "survival" },
                )
                try {
                    player.inventory.setItemInMainHand(voucher.clone())
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    scheduler.tick(20L * 30L)

                    val expired = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    expired.voucherId shouldBe voucherId
                    expired.searchGeneration shouldBe 1
                    expired.target shouldBe null
                    PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id shouldBe voucherId

                    chunkFuture.complete(null)
                    scheduler.executeImmediate()
                    controller.identity(player.inventory.itemInMainHand) shouldBe expired
                } finally {
                    controller.close()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Personal-map search timeout test aborted or failed", cause)
        }
    }

    "claim preflight rechecks server and location after movement without changing voucher identity" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("PersonalMapPreflightTest")
            val scheduler = TestTaskScheduler()
            val marker = RecordingPersonalTreasureMapMarker()
            val player = paper.addPlayer("map-preflight")
            val targetWorld = player.world
            val spec = testMapSpec()
            val definition = testSearchDefinition(player.world.name)
            val voucher = PhysicalRewardVoucher.mark(
                spec.preview.clone(),
                PhysicalRewardVoucherIdentity(UUID.randomUUID(), spec.key, spec.fingerprint),
            )
            val voucherIdentity = PhysicalRewardVoucher.identity(voucher).shouldNotBeNull()
            val firstCandidate = personalTreasureMapCandidateOrder(voucherIdentity.id, 0, 0, 0, 32).first()
            prepareCandidateSurface(player.world, firstCandidate.first, firstCandidate.second)
            var server = "survival"
            val controller = newController(
                plugin, scheduler, marker, spec, definition, currentServer = { server },
            )
            try {
                player.teleport(Location(targetWorld, 0.5, 64.0, 0.5))
                player.inventory.setItemInMainHand(voucher.clone())

                // First right-click selects a safe Survival target and never claims the reward.
                controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                    PersonalTreasureMapUseDecision.OPEN_MAP
                flushMapSearch(scheduler)
                val bound = player.inventory.itemInMainHand
                val activated = controller.identity(bound).shouldNotBeNull()
                activated.ownerId shouldBe player.uniqueId
                PhysicalRewardVoucher.identity(bound)?.id shouldBe voucherIdentity.id
                val target = activated.target.shouldNotBeNull()
                activated.searchGeneration shouldBe 1

                player.teleport(Location(targetWorld, target.x, target.y, target.z))
                // Backend changes do not reroute or consume the stored target.
                server = "spawn"
                controller.preflight(player, voucherIdentity, spec) shouldBe PersonalTreasureMapFailure.WRONG_SERVER
                controller.identity(player.inventory.itemInMainHand)?.target shouldBe target
                server = "survival"
                controller.preflight(player, voucherIdentity, spec) shouldBe null

                // A move during the durable-claim window is caught by the later preflight.
                player.teleport(Location(targetWorld, target.x + 20, target.y, target.z))
                controller.preflight(player, voucherIdentity, spec) shouldBe PersonalTreasureMapFailure.TOO_FAR
                controller.identity(player.inventory.itemInMainHand)?.target shouldBe target
                controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                    PersonalTreasureMapUseDecision.OPEN_MAP

                val otherWorld = MockBukkit.getMock()!!.addSimpleWorld("map-other-world")
                otherWorld.loadChunk(0, 0)
                player.teleport(Location(otherWorld, target.x, target.y, target.z))
                controller.preflight(player, voucherIdentity, spec) shouldBe PersonalTreasureMapFailure.WRONG_WORLD
                controller.identity(player.inventory.itemInMainHand)?.target shouldBe target

                player.teleport(Location(targetWorld, target.x, target.y, target.z))
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

    "target search fails closed when protection providers are absent" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapProtectionUnavailableTest")
                val scheduler = TestTaskScheduler()
                val marker = RecordingPersonalTreasureMapMarker()
                val player = paper.addPlayer("map-protection-unavailable")
                player.teleport(Location(player.world, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val definition = testSearchDefinition(player.world.name)
                val voucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(),
                    PhysicalRewardVoucherIdentity(UUID.randomUUID(), spec.key, spec.fingerprint),
                )
                val candidates = personalTreasureMapCandidateOrder(
                    PhysicalRewardVoucher.identity(voucher)!!.id, 0, 0, 0, 32,
                )
                candidates.forEach { (x, z) -> prepareCandidateSurface(player.world, x, z) }
                plugin.server.pluginManager.isPluginEnabled("Lands") shouldBe false
                plugin.server.pluginManager.isPluginEnabled("WorldGuard") shouldBe false

                val controller = PersonalTreasureMapController(
                    plugin = plugin,
                    resolveSpec = { key -> spec.takeIf { it.key == key } },
                    resolveDefinition = { resolved -> definition.takeIf { resolved.key == spec.key } },
                    currentServer = { "survival" },
                    scheduler = scheduler,
                    marker = marker,
                    mapViewFactory = { testMapView() },
                    asyncChunkLoader = { world, chunkX, chunkZ ->
                        CompletableFuture.completedFuture(
                            if (world.isChunkLoaded(chunkX, chunkZ)) world.getChunkAt(chunkX, chunkZ) else null,
                        )
                    },
                )
                try {
                    player.inventory.setItemInMainHand(voucher)
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    repeat(100) { scheduler.executeImmediate() }
                    val identity = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    identity.ownerId shouldBe player.uniqueId
                    identity.searchGeneration shouldBe 1
                    identity.target shouldBe null
                } finally {
                    controller.close()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Protection-unavailable map test aborted or failed", cause)
        }
    }

    "protection checks require Lands and skip WorldGuard only when it is absent" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("PersonalMapProtectionPolicyTest")
            val player = paper.addPlayer("map-protection-policy")
            val location = player.location
            val pluginManager = plugin.server.pluginManager
            val landsPlugin = paper.createSimplePlugin("Lands")
            val oldLandsHook = HookRegistry.landsHook
            val oldWorldGuardHook = HookRegistry.wgHook
            val landsHook = mockk<LandsHook>()
            val worldGuardHook = mockk<WGHook>()

            try {
                HookRegistry.landsHook = landsHook
                HookRegistry.wgHook = null
                every { landsHook.isUnclaimed(any()) } returns true

                pluginManager.getPlugin("Lands") shouldBe landsPlugin
                pluginManager.getPlugin("WorldGuard") shouldBe null
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe true

                pluginManager.disablePlugin(landsPlugin)
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null
                pluginManager.enablePlugin(landsPlugin)

                val worldGuardPlugin = paper.createSimplePlugin("WorldGuard")
                HookRegistry.wgHook = worldGuardHook
                every { worldGuardHook.isUnclaimed(any()) } returns true
                pluginManager.disablePlugin(worldGuardPlugin)
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null

                pluginManager.enablePlugin(worldGuardPlugin)
                HookRegistry.wgHook = null
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null

                HookRegistry.wgHook = worldGuardHook
                every { worldGuardHook.isUnclaimed(any()) } returns null
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null
                every { worldGuardHook.isUnclaimed(any()) } throws IllegalStateException("WG query failed")
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null

                HookRegistry.landsHook = null
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null
                HookRegistry.landsHook = landsHook
                every { landsHook.isUnclaimed(any()) } returns null
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null
                every { landsHook.isUnclaimed(any()) } throws IllegalStateException("Lands query failed")
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe null
                every { landsHook.isUnclaimed(any()) } returns false
                personalTreasureMapLocationUnclaimed(plugin, location) shouldBe false
            } finally {
                HookRegistry.landsHook = oldLandsHook
                HookRegistry.wgHook = oldWorldGuardHook
            }
        }
    }

    "legacy v2 map identity survives activation and upgrades to the current Survival target" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapLegacyIdentityTest")
                val scheduler = TestTaskScheduler()
                val marker = RecordingPersonalTreasureMapMarker()
                val player = paper.addPlayer("legacy-map-owner")
                player.teleport(Location(player.world, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val legacyDestinations = listOf(destination("world", 10.0, 64.0, 20.0, "Старый тайник"))
                val definitionId = "weekly_personal_cache"
                val oldPrizeKey = "frozen:legacy-map-prize"
                val definition = PersonalTreasureMapDefinition(
                    id = definitionId,
                    prizeSourceRef = "vanilla/weekly_map_cache",
                    destinations = legacyDestinations,
                    searchPolicy = PersonalTreasureMapSearchPolicy("survival", player.world.name, 32),
                    identityFingerprintOverride = PersonalTreasureMapDefinition.legacyFingerprint(
                        definitionId, oldPrizeKey, legacyDestinations,
                    ),
                )
                val voucherId = UUID.randomUUID()
                val voucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(), PhysicalRewardVoucherIdentity(voucherId, spec.key, spec.fingerprint),
                ).apply {
                    editMeta { meta ->
                        val data = meta.persistentDataContainer
                        data.set(PersonalTreasureMapIdentity.versionKey, PersistentDataType.STRING, "2")
                        data.set(PersonalTreasureMapIdentity.ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
                        data.set(PersonalTreasureMapIdentity.definitionKey, PersistentDataType.STRING, definitionId)
                        data.set(
                            PersonalTreasureMapIdentity.fingerprintKey,
                            PersistentDataType.STRING,
                            definition.fingerprint.sha256,
                        )
                        data.set(PersonalTreasureMapIdentity.destinationIndexKey, PersistentDataType.INTEGER, 0)
                        data.set(PersonalTreasureMapIdentity.searchGenerationKey, PersistentDataType.INTEGER, 0)
                    }
                }
                val firstCandidate = personalTreasureMapCandidateOrder(voucherId, 0, 0, 0, 32).first()
                prepareCandidateSurface(player.world, firstCandidate.first, firstCandidate.second)
                var probeRequests = 0
                val controller = newController(
                    plugin, scheduler, marker, spec, definition,
                    currentServer = { "survival" },
                    asyncChunkLoader = { world, chunkX, chunkZ ->
                        probeRequests++
                        CompletableFuture.completedFuture(
                            if (world.isChunkLoaded(chunkX, chunkZ)) world.getChunkAt(chunkX, chunkZ) else null,
                        )
                    },
                )
                try {
                    player.inventory.setItemInMainHand(voucher)
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    (probeRequests > 0) shouldBe true
                    flushMapSearch(scheduler)
                    val activated = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    activated.voucherId shouldBe voucherId
                    activated.ownerId shouldBe player.uniqueId
                    activated.definitionFingerprint shouldBe definition.fingerprint
                    activated.searchGeneration shouldBe 1
                    activated.target.shouldNotBeNull().server shouldBe "survival"
                    player.inventory.itemInMainHand.itemMeta!!.persistentDataContainer.get(
                        PersonalTreasureMapIdentity.versionKey, PersistentDataType.STRING,
                    ) shouldBe PersonalTreasureMapIdentity.VERSION

                    val target = activated.target.shouldNotBeNull()
                    player.teleport(Location(player.world, target.x, target.y, target.z))
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.CLAIM
                    controller.preflight(player, PhysicalRewardVoucher.identity(voucher)!!, spec) shouldBe null
                    controller.identity(player.inventory.itemInMainHand)?.target shouldBe target
                } finally {
                    controller.close()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Legacy map identity test aborted or failed", cause)
        }
    }

    "dynamic target is persisted, protected targets reroute without claiming, and later safe preflight keeps the voucher" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PersonalMapSafeTargetTest")
                val scheduler = TestTaskScheduler()
                val marker = RecordingPersonalTreasureMapMarker()
                val player = paper.addPlayer("map-safe-target")
                player.teleport(Location(player.world, 0.5, 64.0, 0.5))
                val spec = testMapSpec()
                val definition = testSearchDefinition(player.world.name)
                val voucherId = UUID.randomUUID()
                val voucher = PhysicalRewardVoucher.mark(
                    spec.preview.clone(),
                    PhysicalRewardVoucherIdentity(voucherId, spec.key, spec.fingerprint),
                )
                val firstCandidate = personalTreasureMapCandidateOrder(voucherId, 0, 0, 0, 32).first()
                prepareCandidateSurface(player.world, firstCandidate.first, firstCandidate.second)
                var protectedTarget: Pair<Int, Int>? = null
                var protectionResult: Boolean? = false
                val controller = newController(
                    plugin, scheduler, marker, spec, definition,
                    isUnclaimed = { location ->
                        val point = location.blockX to location.blockZ
                        if (point == protectedTarget) protectionResult else true
                    },
                    currentServer = { "survival" },
                )
                try {
                    player.inventory.setItemInMainHand(voucher.clone())
                    controller.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                        PersonalTreasureMapUseDecision.OPEN_MAP
                    flushMapSearch(scheduler)
                    val activated = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    activated.ownerId shouldBe player.uniqueId
                    activated.voucherId shouldBe voucherId
                    val firstTarget = activated.target.shouldNotBeNull()
                    activated.searchGeneration shouldBe 1

                    player.teleport(Location(player.world, firstTarget.x, firstTarget.y, firstTarget.z))
                    protectedTarget = floor(firstTarget.x).toInt() to floor(firstTarget.z).toInt()
                    val nextCenterX = floor(player.location.x).toInt()
                    val nextCenterZ = floor(player.location.z).toInt()
                    val secondCandidate = personalTreasureMapCandidateOrder(
                        voucherId, activated.searchGeneration, nextCenterX, nextCenterZ, 32,
                    ).first { (x, z) ->
                        val distanceSquared = (x + 0.5 - player.location.x) * (x + 0.5 - player.location.x) +
                            (z + 0.5 - player.location.z) * (z + 0.5 - player.location.z)
                        distanceSquared in 16.0 * 16.0..32.0 * 32.0
                    }
                    prepareCandidateSurface(player.world, secondCandidate.first, secondCandidate.second)

                    // This is the post-async-claim check: the old target became private, so no prize is applied.
                    controller.preflight(player, PhysicalRewardVoucher.identity(voucher)!!, spec) shouldBe
                        PersonalTreasureMapFailure.TARGET_CHANGED
                    flushMapSearch(scheduler)
                    val rerouted = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    rerouted.voucherId shouldBe voucherId
                    rerouted.ownerId shouldBe player.uniqueId
                    rerouted.searchGeneration shouldBe 2
                    val secondTarget = rerouted.target.shouldNotBeNull()
                    (secondTarget != firstTarget) shouldBe true

                    // An unavailable protection query preserves the target and releases the current claim.
                    protectedTarget = floor(secondTarget.x).toInt() to floor(secondTarget.z).toInt()
                    protectionResult = null
                    player.teleport(Location(player.world, secondTarget.x, secondTarget.y, secondTarget.z))
                    controller.preflight(player, PhysicalRewardVoucher.identity(voucher)!!, spec) shouldBe
                        PersonalTreasureMapFailure.SAFETY_UNAVAILABLE
                    val unchanged = controller.identity(player.inventory.itemInMainHand).shouldNotBeNull()
                    unchanged.target shouldBe secondTarget
                    unchanged.searchGeneration shouldBe 2

                    // Once the claim provider can confirm the same target is unclaimed, the same voucher can proceed.
                    protectionResult = true
                    controller.preflight(player, PhysicalRewardVoucher.identity(voucher)!!, spec) shouldBe null
                    PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id shouldBe voucherId
                } finally {
                    controller.close()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("Behavioral personal-map safe-target test aborted or failed", cause)
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
        val candidates = personalTreasureMapCandidateOrder(id, 0, 0, 0, 96)
        candidates.size shouldBe PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT
        candidates shouldBe personalTreasureMapCandidateOrder(id, 0, 0, 0, 96)
        candidates.all { (x, z) ->
            val distanceSquared = x.toDouble() * x + z.toDouble() * z
            distanceSquared in 16.0 * 16.0..96.0 * 96.0
        } shouldBe true

        val bounds = PersonalTreasureMapBounds(-9650, 9650, -9650, 9650)
        bounds.contains(-9650.0, 0.0) shouldBe true
        bounds.contains(9649.5, 0.0) shouldBe true
        bounds.contains(9650.0, 0.0) shouldBe false
        val expedition = personalTreasureMapCandidateOrder(
            id, 3, bounds, minDistance = 3000, originX = 0.5, originZ = 0.5,
        )
        expedition.size shouldBe PersonalTreasureMapSearchPolicy.CANDIDATE_LIMIT
        expedition.distinct().size shouldBe expedition.size
        expedition.all { (x, z) ->
            bounds.contains(x + 0.5, z + 0.5) &&
                (x + 0.5 - 0.5) * (x + 0.5 - 0.5) + (z + 0.5 - 0.5) * (z + 0.5 - 0.5) >= 3000.0 * 3000.0
        } shouldBe true
    }
})

private fun newController(
    plugin: Plugin,
    scheduler: TestTaskScheduler,
    marker: RecordingPersonalTreasureMapMarker,
    spec: PhysicalRewardSpec,
    definition: PersonalTreasureMapDefinition,
    isUnclaimed: (Location) -> Boolean? = { true },
    currentServer: () -> String = { "spawn" },
    asyncChunkLoader: (World, Int, Int) -> CompletionStage<Chunk?> = { world, chunkX, chunkZ ->
        CompletableFuture.completedFuture(
            if (world.isChunkLoaded(chunkX, chunkZ)) world.getChunkAt(chunkX, chunkZ) else null,
        )
    },
) = PersonalTreasureMapController(
    plugin = plugin,
    resolveSpec = { key -> spec.takeIf { it.key == key } },
    resolveDefinition = { resolved -> definition.takeIf { resolved.key == spec.key } },
    currentServer = currentServer,
    scheduler = scheduler,
    marker = marker,
    mapViewFactory = { testMapView() },
    asyncChunkLoader = asyncChunkLoader,
    isUnclaimed = isUnclaimed,
)

private fun flushMapSearch(scheduler: TestTaskScheduler) {
    repeat(100) { scheduler.executeImmediate() }
}

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
    preview = org.bukkit.inventory.ItemStack(Material.FILLED_MAP).apply {
        editMeta { meta ->
            meta.displayName(Component.text("Карта тайника"))
            meta.lore(listOf(Component.text("Тайник на Survival")))
        }
    },
)

private fun testMapDefinition(world: String) = PersonalTreasureMapDefinition(
    id = "weekly_personal_cache",
    prizeSourceRef = "weekly/dungeon-case-loot-case",
    destinations = listOf(destination(world, 2.0, 64.0, 0.0, "Тайник на спавне")),
)

private fun testSearchDefinition(world: String) = PersonalTreasureMapDefinition(
    id = "weekly_personal_cache",
    prizeSourceRef = "vanilla/weekly_map_cache",
    destinations = emptyList(),
    searchPolicy = PersonalTreasureMapSearchPolicy("survival", world, 32),
)

private fun prepareSafeSurface(world: org.bukkit.World, destination: PersonalTreasureMapDestination) {
    prepareCandidateSurface(world, floor(destination.x).toInt(), floor(destination.z).toInt(), floor(destination.y).toInt() - 1)
}

private fun prepareCandidateSurface(world: org.bukkit.World, x: Int, z: Int, groundY: Int = 63) {
    world.loadChunk(x shr 4, z shr 4)
    world.getBlockAt(x, groundY, z).type = Material.STONE
}

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
