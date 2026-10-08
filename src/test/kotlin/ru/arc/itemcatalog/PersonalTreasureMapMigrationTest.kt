package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class PersonalTreasureMapMigrationTest : StringSpec({
    "legacy map keeps its bearer address while resolving the active Survival prize and presentation" {
        MockBukkitTestRuntime.open().use { paper ->
            val poolId = "weekly-map-migration-${UUID.randomUUID()}"
            val pool = TreasurePool(poolId, treasures = listOf(Treasure.Item(ItemStack(Material.DIAMOND), id = "rare_find")))
            var poolLookups = 0
            mockkObject(Treasures)
            every { Treasures.getPool(poolId) } answers {
                poolLookups++
                pool
            }
            val child = RewardCatalogEntry(
                id = "weekly_map_cache",
                name = "Находка из тайника",
                description = emptyList(),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.Treasure(poolId, "rare_find"),
                icon = CatalogIconStyle(Material.DIAMOND.name),
            )
            val map = RewardCatalogEntry(
                id = "weekly_personal_map",
                name = "Карта тайника",
                description = listOf("<#e8dfd2>Тайник на Survival в обычном мире."),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.PersonalMap(
                    "rewards", child.id, PersonalTreasureMapSearchPolicy("survival", "world", 96),
                ),
                icon = CatalogIconStyle(Material.FILLED_MAP.name),
            )
            val settings = RewardCatalogSettings(
                enabled = true,
                title = "Награды",
                categories = listOf(
                    RewardCatalogCategory("rewards", "Награды", emptyList(), CatalogIconStyle(Material.CHEST.name), listOf(child, map)),
                ),
                messages = RewardCatalogMessages.DEFAULT,
            )
            val root = Files.createTempDirectory("arc-legacy-map-migration")
            try {
                val archive = FrozenPhysicalRewards(root)
                val rewards = CatalogPhysicalRewards(settings, frozen = archive)
                val currentSnapshots = rewards.captureInteractiveArchives().shouldNotBeNull()
                rewards.persistInteractiveArchives(currentSnapshots) shouldBe true
                val poolLookupsAfterWarmup = poolLookups

                // This is the old archived EliteMobs case recipe and old Spawn preview from already issued maps.
                val oldPrize = archive.prepare(
                    "dungeon-case:loot_case",
                    FrozenPhysicalRecipe(
                        type = "dungeon-case",
                        dungeonCaseId = "loot_case",
                        dungeonCaseDefinition = "legacy-elitemobs-case-recipe",
                    ),
                    ItemStack(Material.PAPER),
                ).shouldNotBeNull()
                val oldDestinations = listOf(
                    PersonalTreasureMapDestination("spawn", "world", 10.0, 64.0, 20.0, "Старый тайник на Спавне"),
                )
                val oldMapPreview = ItemStack(Material.FILLED_MAP).apply {
                    editMeta { meta ->
                        meta.displayName(net.kyori.adventure.text.Component.text("Снаряжение EliteMobs"))
                        meta.lore(listOf(net.kyori.adventure.text.Component.text("Получить на Спавне")))
                    }
                }
                val oldMap = archive.prepare(
                    "personal-map:legacy-weekly-map",
                    FrozenPhysicalRecipe(
                        type = "personal-map",
                        mapId = map.id,
                        mapPrizeKey = oldPrize.sourceKey,
                        mapPrizeFingerprint = oldPrize.providerFingerprint,
                        mapDestinations = oldDestinations,
                    ),
                    oldMapPreview,
                ).shouldNotBeNull()

                val mapSpec = rewards.resolve(oldMap.sourceKey).shouldNotBeNull()
                val mapDefinition = rewards.personalMapDefinition(mapSpec).shouldNotBeNull()
                repeat(3) {
                    rewards.resolve(oldMap.sourceKey).shouldNotBeNull()
                    rewards.personalMapDefinition(mapSpec).shouldNotBeNull()
                }
                poolLookups shouldBe poolLookupsAfterWarmup
                mapSpec.key shouldBe oldMap.sourceKey
                mapSpec.fingerprint.sha256 shouldBe oldMap.providerFingerprint
                mapDefinition.searchPolicy shouldBe PersonalTreasureMapSearchPolicy("survival", "world", 96)
                mapDefinition.fingerprint shouldBe PersonalTreasureMapDefinition.legacyFingerprint(
                    map.id, oldPrize.sourceKey, oldDestinations,
                )
                val voucherId = UUID.randomUUID()
                mapDefinition.destinationIndex(voucherId) shouldBe Math.floorMod(voucherId.hashCode(), oldDestinations.size)

                val plain = PlainTextComponentSerializer.plainText()
                val visibleName = plain.serialize(mapSpec.preview.itemMeta!!.displayName()!!)
                val visibleLore = mapSpec.preview.itemMeta!!.lore().orEmpty().joinToString(" ") { plain.serialize(it) }
                visibleName shouldBe "Карта тайника"
                visibleLore.contains("EliteMobs") shouldBe false
                visibleLore.contains("Спавн") shouldBe false
                visibleLore.contains("Survival") shouldBe true

                val player = paper.addPlayer("legacy-map-owner")
                rewards.canRedeem(player, mapSpec) shouldBe null
                val result = rewards.redeem(player, mapSpec, UUID.randomUUID()).join()
                result shouldBe PhysicalRewardOutcome.Applied
                poolLookups shouldBe poolLookupsAfterWarmup
                player.inventory.contents.any { it?.type == Material.DIAMOND } shouldBe true
            } finally {
                unmockkObject(Treasures)
                root.toFile().deleteRecursively()
            }
        }
    }

    "issued v3 map migrates its validated old-world target and redeems the active prize once" {
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val poolId = "weekly-map-v3-migration-${UUID.randomUUID()}"
                val pool = TreasurePool(poolId, treasures = listOf(Treasure.Item(ItemStack(Material.DIAMOND), id = "rare_find")))
                mockkObject(Treasures)
                every { Treasures.getPool(poolId) } returns pool
                val child = RewardCatalogEntry(
                    id = "weekly_map_cache",
                    name = "Находка из тайника",
                    description = emptyList(),
                    rarity = null,
                    requires = emptyList(),
                    source = RewardCatalogSource.Treasure(poolId, "rare_find"),
                    icon = CatalogIconStyle(Material.DIAMOND.name),
                )
                val activePolicy = PersonalTreasureMapSearchPolicy("survival", "survival", 96)
                val map = RewardCatalogEntry(
                    id = "weekly_personal_map",
                    name = "Карта тайника",
                    description = listOf("<#e8dfd2>Тайник на Survival в Новых биомах."),
                    rarity = null,
                    requires = emptyList(),
                    source = RewardCatalogSource.PersonalMap("rewards", child.id, activePolicy),
                    icon = CatalogIconStyle(Material.FILLED_MAP.name),
                )
                val settings = RewardCatalogSettings(
                    enabled = true,
                    title = "Награды",
                    categories = listOf(
                        RewardCatalogCategory(
                            "rewards", "Награды", emptyList(), CatalogIconStyle(Material.CHEST.name), listOf(child, map),
                        ),
                    ),
                    messages = RewardCatalogMessages.DEFAULT,
                )
                val root = Files.createTempDirectory("arc-v3-map-route-migration")
                val scheduler = TestTaskScheduler()
                val plugin = paper.createSimplePlugin("PersonalMapV3MigrationTest")
                val searchWorld = MockBukkit.getMock()!!.addSimpleWorld("survival")
                val oldWorld = paper.server.getWorld("world") ?: MockBukkit.getMock()!!.addSimpleWorld("world")
                val player = paper.addPlayer("v3-map-owner")
                val foreignPlayer = paper.addPlayer("v3-map-foreign")
                val oldTarget = PersonalTreasureMapDestination(
                    "survival", "world", 100.5, 65.0, 100.5, PersonalTreasureMapSearchPolicy.TARGET_HINT,
                )
                try {
                    val archive = FrozenPhysicalRewards(root)
                    val rewards = CatalogPhysicalRewards(settings, frozen = archive)
                    val snapshots = rewards.captureInteractiveArchives().shouldNotBeNull()
                    rewards.persistInteractiveArchives(snapshots) shouldBe true

                    val oldPrizeStack = ItemStack(Material.GOLD_INGOT)
                    val oldPrize = archive.prepare(
                        "treasure:legacy_weekly_map_prize",
                        FrozenPhysicalRecipe(
                            type = "treasure",
                            treasure = FrozenTreasureNode(
                                id = "legacy_find",
                                type = "item",
                                weight = 1,
                                minInt = 1,
                                maxInt = 1,
                                stack = Base64.getEncoder().encodeToString(oldPrizeStack.serializeAsBytes()),
                            ),
                        ),
                        oldPrizeStack,
                    ).shouldNotBeNull()
                    val oldPolicy = PersonalTreasureMapSearchPolicy("survival", "world", 96)
                    val oldMapKey = rewards.key(
                        map.copy(source = RewardCatalogSource.PersonalMap("rewards", child.id, oldPolicy)),
                    )
                    val oldMap = archive.prepare(
                        oldMapKey,
                        FrozenPhysicalRecipe(
                            type = "personal-map",
                            mapId = map.id,
                            mapPrizeKey = oldPrize.sourceKey,
                            mapPrizeFingerprint = oldPrize.providerFingerprint,
                            mapSearchServer = oldPolicy.server,
                            mapSearchWorld = oldPolicy.world,
                            mapSearchRadius = oldPolicy.radius,
                        ),
                        ItemStack(Material.FILLED_MAP),
                    ).shouldNotBeNull()
                    val mapSpec = rewards.resolve(oldMap.sourceKey).shouldNotBeNull()
                    val migratedDefinition = rewards.personalMapDefinition(mapSpec).shouldNotBeNull()
                    val originalMapFingerprint = PersonalTreasureMapDefinition(
                        map.id, oldPrize.sourceKey, emptyList(), searchPolicy = oldPolicy,
                    ).fingerprint
                    val unrelatedMap = archive.prepare(
                        "personal-map:unrelated-old-route",
                        FrozenPhysicalRecipe(
                            type = "personal-map",
                            mapId = "unrelated_personal_map",
                            mapPrizeKey = oldPrize.sourceKey,
                            mapPrizeFingerprint = oldPrize.providerFingerprint,
                            mapSearchServer = oldPolicy.server,
                            mapSearchWorld = oldPolicy.world,
                            mapSearchRadius = oldPolicy.radius,
                        ),
                        ItemStack(Material.FILLED_MAP),
                    ).shouldNotBeNull()
                    val unrelatedSpec = rewards.resolve(unrelatedMap.sourceKey).shouldNotBeNull()
                    val unrelatedDefinition = rewards.personalMapDefinition(unrelatedSpec).shouldNotBeNull()

                    val ledger = SingleUseMapLedger()
                    val maps = PersonalTreasureMapController(
                        plugin = plugin,
                        resolveSpec = rewards::resolve,
                        resolveDefinition = rewards::personalMapDefinition,
                        currentServer = { "survival" },
                        scheduler = scheduler,
                        marker = RecordingMigrationMarker(),
                        mapViewFactory = { migrationTestMapView() },
                        isUnclaimed = { true },
                    )
                    val physical = PhysicalRewardController(
                        plugin = plugin,
                        ledger = ledger,
                        resolve = rewards::resolve,
                        canRedeem = { claimant, spec ->
                            val voucher = PhysicalRewardVoucher.identity(claimant.inventory.itemInMainHand)
                            if (voucher == null) "Map preflight failed: ${PersonalTreasureMapFailure.INVALID_OR_STALE}"
                            else maps.preflight(claimant, voucher, spec)?.let { "Map preflight failed: $it" }
                                ?: rewards.canRedeem(claimant, spec)
                        },
                        redeem = rewards::redeem,
                        scope = "survival",
                        beforeUse = { claimant, _, _ ->
                            maps.beforeUse(claimant, claimant.inventory.itemInMainHand) in setOf(
                                PersonalTreasureMapUseDecision.NOT_A_PERSONAL_MAP,
                                PersonalTreasureMapUseDecision.CLAIM,
                            )
                        },
                    )
                    try {
                        migratedDefinition.searchPolicy shouldBe activePolicy
                        migratedDefinition.fingerprint shouldBe originalMapFingerprint
                        unrelatedDefinition.searchPolicy shouldBe oldPolicy

                        val voucher = physical.createStack(oldMap.sourceKey).shouldNotBeNull()
                        val voucherIdentity = PhysicalRewardVoucher.identity(voucher).shouldNotBeNull()
                        val oldGeneration = 7
                        val boundOldMap = voucher.clone().apply {
                            editMeta { meta ->
                                val data = meta.persistentDataContainer
                                data.set(PersonalTreasureMapIdentity.versionKey, PersistentDataType.STRING, "3")
                                data.set(PersonalTreasureMapIdentity.ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
                                data.set(PersonalTreasureMapIdentity.definitionKey, PersistentDataType.STRING, map.id)
                                data.set(
                                    PersonalTreasureMapIdentity.fingerprintKey,
                                    PersistentDataType.STRING,
                                    originalMapFingerprint.sha256,
                                )
                                data.set(PersonalTreasureMapIdentity.destinationIndexKey, PersistentDataType.INTEGER, 0)
                                data.set(PersonalTreasureMapIdentity.searchGenerationKey, PersistentDataType.INTEGER, oldGeneration)
                                data.set(PersonalTreasureMapIdentity.targetServerKey, PersistentDataType.STRING, oldTarget.server)
                                data.set(PersonalTreasureMapIdentity.targetWorldKey, PersistentDataType.STRING, oldTarget.world)
                                data.set(PersonalTreasureMapIdentity.targetXKey, PersistentDataType.DOUBLE, oldTarget.x)
                                data.set(PersonalTreasureMapIdentity.targetYKey, PersistentDataType.DOUBLE, oldTarget.y)
                                data.set(PersonalTreasureMapIdentity.targetZKey, PersistentDataType.DOUBLE, oldTarget.z)
                            }
                        }
                        val originalIdentity = PersonalTreasureMapIdentity.read(boundOldMap).shouldNotBeNull()
                        player.inventory.setItemInMainHand(boundOldMap.clone())

                        player.teleport(Location(oldWorld, 0.5, 64.0, 0.5))
                        maps.beforeUse(player, player.inventory.itemInMainHand) shouldBe
                            PersonalTreasureMapUseDecision.OPEN_MAP
                        val oldWorldMigrated = PersonalTreasureMapIdentity.read(player.inventory.itemInMainHand)
                            .shouldNotBeNull()
                        oldWorldMigrated.target shouldBe null
                        oldWorldMigrated.voucherId shouldBe originalIdentity.voucherId
                        oldWorldMigrated.ownerId shouldBe originalIdentity.ownerId
                        oldWorldMigrated.definitionFingerprint shouldBe originalIdentity.definitionFingerprint
                        oldWorldMigrated.searchGeneration shouldBe oldGeneration
                        PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe voucherIdentity
                        val oldWorldGuidance = maps.guidance(player).shouldNotBeNull()
                        oldWorldGuidance.onDestinationServer shouldBe true
                        oldWorldGuidance.onDestinationWorld shouldBe false
                        oldWorldGuidance.targetSelected shouldBe false
                        oldWorldGuidance.ownerBound shouldBe true

                        foreignPlayer.teleport(Location(oldWorld, 0.5, 64.0, 0.5))
                        foreignPlayer.inventory.setItemInMainHand(boundOldMap.clone())
                        maps.beforeUse(foreignPlayer, foreignPlayer.inventory.itemInMainHand) shouldBe
                            PersonalTreasureMapUseDecision.REJECT
                        PersonalTreasureMapIdentity.read(foreignPlayer.inventory.itemInMainHand)?.target shouldBe oldTarget

                        val forgedTargetMap = boundOldMap.clone().apply {
                            editMeta { meta ->
                                meta.persistentDataContainer.set(
                                    PersonalTreasureMapIdentity.targetWorldKey,
                                    PersistentDataType.STRING,
                                    "otherworld",
                                )
                            }
                        }
                        player.inventory.setItemInMainHand(forgedTargetMap)
                        maps.beforeUse(player, forgedTargetMap) shouldBe PersonalTreasureMapUseDecision.REJECT
                        PersonalTreasureMapIdentity.read(player.inventory.itemInMainHand)?.target?.world shouldBe "otherworld"
                        player.inventory.setItemInMainHand(boundOldMap.clone())
                        player.teleport(Location(searchWorld, 0.5, 64.0, 0.5))
                        val (candidateX, candidateZ) = personalTreasureMapCandidateOrder(
                            voucherIdentity.id, oldGeneration, 0, 0, activePolicy.radius,
                        ).first()
                        searchWorld.loadChunk(candidateX shr 4, candidateZ shr 4)
                        searchWorld.getBlockAt(candidateX, 63, candidateZ).type = Material.STONE
                        physical.register()
                        Tasks.withScheduler(scheduler) {
                            paper.callEvent(interact(player))
                            flush(scheduler)
                            ledger.requests.size shouldBe 0

                            val migrated = PersonalTreasureMapIdentity.read(player.inventory.itemInMainHand).shouldNotBeNull()
                            migrated.voucherId shouldBe originalIdentity.voucherId
                            migrated.ownerId shouldBe originalIdentity.ownerId
                            migrated.definitionFingerprint shouldBe originalIdentity.definitionFingerprint
                            migrated.searchGeneration shouldBe (oldGeneration + 1)
                            migrated.target.shouldNotBeNull().world shouldBe "survival"
                            PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe voucherIdentity
                            val replay = player.inventory.itemInMainHand.clone()

                            val target = migrated.target.shouldNotBeNull()
                            player.teleport(Location(searchWorld, target.x, target.y, target.z))
                            paper.callEvent(interact(player))
                            flush(scheduler)
                            ledger.requests.size shouldBe 1
                            ledger.commits shouldBe 1
                            player.inventory.itemInMainHand.type shouldBe Material.AIR
                            player.inventory.storageContents.filterNotNull().sumOf { stack ->
                                if (stack.type == Material.DIAMOND) stack.amount else 0
                            } shouldBe 1
                            player.inventory.storageContents.filterNotNull().sumOf { stack ->
                                if (stack.type == Material.GOLD_INGOT) stack.amount else 0
                            } shouldBe 0

                            player.inventory.setItemInMainHand(replay)
                            paper.callEvent(interact(player))
                            flush(scheduler)
                            ledger.requests.size shouldBe 2
                            ledger.requests.map { it.identity }.distinct() shouldBe listOf(
                                OneTimeUseIdentity(voucherIdentity.id, voucherIdentity.fingerprint),
                            )
                            ledger.commits shouldBe 1
                            player.inventory.storageContents.filterNotNull().sumOf { stack ->
                                if (stack.type == Material.DIAMOND) stack.amount else 0
                            } shouldBe 1
                        }
                    } finally {
                        physical.close()
                        maps.close()
                    }
                } finally {
                    unmockkObject(Treasures)
                    root.toFile().deleteRecursively()
                }
            }
        } catch (cause: Throwable) {
            throw AssertionError("v3 weekly-map migration test aborted or failed", cause)
        }
    }
})

private fun interact(player: org.bukkit.entity.Player): PlayerInteractEvent =
    PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND)

private fun flush(scheduler: TestTaskScheduler) {
    repeat(12) { scheduler.executeImmediate() }
}

private val nextMigrationMapViewId = AtomicInteger(1_000_000)

private fun migrationTestMapView(): MapView {
    val viewId = nextMigrationMapViewId.getAndIncrement()
    val renderers = mutableListOf<MapRenderer>()
    return mockk {
        every { id } returns viewId
        every { getRenderers() } answers { renderers }
        every { addRenderer(any()) } answers { renderers.add(firstArg()); Unit }
        every { removeRenderer(any()) } answers { renderers.remove(firstArg()) }
        every { setScale(any()) } just runs
        every { setTrackingPosition(any()) } just runs
        every { setUnlimitedTracking(any()) } just runs
        every { setLocked(any()) } just runs
    }
}

private class RecordingMigrationMarker : PersonalTreasureMapMarker {
    override fun show(player: org.bukkit.entity.Player, destination: PersonalTreasureMapDestination) = Unit
    override fun clear(playerId: UUID) = Unit
    override fun close() = Unit
}

private class SingleUseMapLedger : OneTimeUseLedger {
    val requests = mutableListOf<OneTimeUseClaimRequest>()
    var commits = 0
    private val claimed = mutableSetOf<OneTimeUseIdentity>()

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        requests += request
        val result = if (claimed.add(request.identity)) {
            OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, true))
        } else OneTimeUseClaimResult.AlreadyConsumed
        return CompletableFuture.completedFuture(result)
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        commits++
        return CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> =
        CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> =
        CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)

    override fun close() = Unit
}
