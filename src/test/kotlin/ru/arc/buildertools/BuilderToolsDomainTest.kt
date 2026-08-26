package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Slab
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.mockito.kotlin.mock
import ru.arc.config.Config
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID

class BuilderToolsDomainTest : FunSpec({
    val worldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val playerId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    test("bundled config is disabled while a runtime override may opt in survival") {
        val temporaryDirectory = Files.createTempDirectory("arc-builder-tools-config-")
        val base = Config(temporaryDirectory, "modules/builder-tools.yml")
        BuilderToolsConfig(base).enabled shouldBe false

        val override = Config(temporaryDirectory, "modules/builder-tools-runtime.yml")
        override.setBoolean("enabled", true)
        override.setStringList("allowed-worlds", listOf("world"))
        val configured = BuilderToolsConfig(base, override).validated()
        configured.enabled shouldBe true
        configured.allowedWorlds shouldBe setOf("world")
        configured.shopMaxQuotedMaterials shouldBe 64
        configured.allowsWorld("WORLD") shouldBe true

        override.setStringList("allowed-worlds", listOf("*"))
        val wildcard = BuilderToolsConfig(base, override).validated()
        wildcard.allowsWorld("rc_survival_nether") shouldBe true

        override.setStringList("allowed-worlds", listOf("*", "world"))
        shouldThrow<IllegalArgumentException> { BuilderToolsConfig(base, override).validated() }
    }

    test("plugin descriptor exposes only the unified builder command root") {
        val pluginDescriptor = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml"))
            .bufferedReader()
            .use { it.readText() }

        pluginDescriptor.contains("\n  builder:\n") shouldBe true
        pluginDescriptor.contains("\n  deconstruction:\n") shouldBe false
        pluginDescriptor.contains("\n  crown:\n") shouldBe false
        pluginDescriptor.contains("aliases: [buildtools]") shouldBe false
    }

    test("permission policy accepts only canonical feature nodes") {
        fun permissions(vararg nodes: String): (String) -> Boolean = nodes.toSet()::contains

        BuilderPermissionPolicy.canUse(BuilderFeature.FILL, permissions("arc.builder.tools.fill")) shouldBe true
        BuilderPermissionPolicy.canUse(BuilderFeature.COPY, permissions("arc.builder.tools.copy")) shouldBe true
        BuilderPermissionPolicy.canUse(BuilderFeature.PASTE, permissions("arc.buildertools.paste")) shouldBe false
        BuilderPermissionPolicy.canUse(BuilderFeature.CROWN, permissions("arc.crown")) shouldBe false
        BuilderPermissionPolicy.canUseAny(permissions("arc.builder.tools.deconstruct")) shouldBe true
        BuilderPermissionPolicy.canUseAny(permissions()) shouldBe false
    }

    test("permission policy applies canonical selection and hourly tiers under absolute bounds") {
        fun permissions(vararg nodes: String): (String) -> Boolean = nodes.toSet()::contains

        BuilderPermissionPolicy.maximumAxis(permissions("arc.builder.tools.selection.size.100"), 48) shouldBe 48
        BuilderPermissionPolicy.maximumAxis(permissions("arc.builder.tools.selection.size.40"), 48) shouldBe 40
        BuilderPermissionPolicy.maximumAxis(permissions(), 48) shouldBe 20
        BuilderPermissionPolicy.hourlyChanges(permissions("arc.builder.tools.hourly.150000"), 20_000) shouldBe 150_000
        BuilderPermissionPolicy.hourlyChanges(permissions("arc.builder.tools.hourly.50000"), 20_000) shouldBe 50_000
        BuilderPermissionPolicy.hourlyChanges(permissions(), 20_000) shouldBe 20_000
    }

    test("builder tool exchange transforms one owned item without minting its base material") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("ToolOwner")
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD, 3))
            val replacement = ItemStack(Material.ECHO_SHARD).also { item ->
                item.editMeta { meta -> meta.setCustomModelData(1) }
            }

            BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, replacement) shouldBe
                BuilderOwnedToolExchangeResult.REPLACED
            player.inventory.contents.filterNotNull().sumOf { item ->
                if (item.type == Material.ECHO_SHARD) item.amount else 0
            } shouldBe 3
            player.inventory.contents.filterNotNull().count(replacement::isSimilar) shouldBe 1
        }
    }

    test("builder tool exchange rejects custom inputs and a full split inventory without mutation") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("ToolGuard")
            val customInput = ItemStack(Material.ECHO_SHARD).also { item ->
                item.editMeta { meta -> meta.setCustomModelData(9) }
            }
            player.inventory.setItemInMainHand(customInput)
            val replacement = ItemStack(Material.ECHO_SHARD).also { item ->
                item.editMeta { meta -> meta.setCustomModelData(1) }
            }
            BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, replacement) shouldBe
                BuilderOwnedToolExchangeResult.WRONG_ITEM
            player.inventory.itemInMainHand shouldBe customInput

            player.inventory.contents.indices.forEach { index -> player.inventory.setItem(index, ItemStack(Material.STONE)) }
            player.inventory.setItemInMainHand(ItemStack(Material.ECHO_SHARD, 2))
            BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, replacement) shouldBe
                BuilderOwnedToolExchangeResult.INVENTORY_FULL
            player.inventory.itemInMainHand.amount shouldBe 2
        }
    }

    test("inventory procurement computes only exact deficits and simulates delivery before construction") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("ShopBuilder")
            player.inventory.addItem(ItemStack(Material.STONE, 20))
            val costs = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 64), ItemStack(Material.OAK_PLANKS, 16)))

            BuilderInventory.missingCosts(player, costs).map { it.materialKey to it.amount } shouldBe listOf(
                "minecraft:stone" to 44,
                "minecraft:oak_planks" to 16,
            )
            BuilderInventory.canApply(player, costs, emptyList(), null, 0) shouldBe false
            BuilderInventory.canApplyAfterReceiving(
                player,
                BuilderInventory.missingCosts(player, costs),
                costs,
                emptyList(),
                null,
                0,
            ) shouldBe true
            costs.all { BuilderInventory.plainMaterial(it) != null } shouldBe true
        }
    }

    test("block safety admits ordinary structure blocks and rejects technical or unstable blocks") {
        MockBukkitTestRuntime.open().use { paper ->
            val safety = BuilderBlockSafety(mock<Plugin>(), setOf("AIR", "SHORT_GRASS"))
            safety.isSafeMaterial(Material.STONE) shouldBe true
            safety.isSafeMaterial(Material.OAK_STAIRS) shouldBe true
            safety.isSafeMaterial(Material.OAK_LEAVES) shouldBe true
            safety.isSafeMaterial(Material.TNT) shouldBe false
            safety.isSafeMaterial(Material.SAND) shouldBe false
            safety.isSafeMaterial(Material.REDSTONE_TORCH) shouldBe false
            safety.isSafeMaterial(Material.CHEST) shouldBe false

            val waterlogged = paper.server.createBlockData(Material.OAK_STAIRS) as Waterlogged
            waterlogged.isWaterlogged = true
            safety.isSafePlacement(waterlogged) shouldBe false

            val doubleSlab = paper.server.createBlockData(Material.OAK_SLAB) as Slab
            doubleSlab.type = Slab.Type.DOUBLE
            BuilderPlacementCost.item(doubleSlab).amount shouldBe 2
        }
    }

    test("selection has overflow-safe inclusive dimensions and deterministic order") {
        val selection = BuilderSelection(
            BuilderBlockPos(worldId, 3, 7, -2),
            BuilderBlockPos(worldId, 1, 6, 1),
        ).validated(maxAxis = 8, maxScanVolume = 100)

        selection.sizeX shouldBe 3
        selection.sizeY shouldBe 2
        selection.sizeZ shouldBe 4
        selection.volume shouldBe 24L
        selection.positionsTopDown().first() shouldBe BuilderBlockPos(worldId, 1, 7, -2)
        selection.positionsBottomUp().first() shouldBe BuilderBlockPos(worldId, 1, 6, -2)
    }

    test("selection rejects axis and scan-volume abuse independently") {
        val selection = BuilderSelection(
            BuilderBlockPos(worldId, 0, 0, 0),
            BuilderBlockPos(worldId, 20, 20, 20),
        )
        shouldThrow<IllegalArgumentException> { selection.validated(maxAxis = 20, maxScanVolume = 100_000) }
        shouldThrow<IllegalArgumentException> { selection.validated(maxAxis = 32, maxScanVolume = 9_000) }
    }

    test("crown geometry is deterministic bounded and seed-sensitive") {
        val first = BuilderCrownGeometry.offsets(radius = 5, seed = 42L)
        val repeated = BuilderCrownGeometry.offsets(radius = 5, seed = 42L)
        val other = BuilderCrownGeometry.offsets(radius = 5, seed = 43L)

        first shouldBe repeated
        first shouldNotBe other
        first.isNotEmpty() shouldBe true
        first.all { (x, y, z) -> x in -5..5 && y in -5..5 && z in -5..5 } shouldBe true
        first.size shouldBe first.toSet().size
    }

    test("crown palette parser preserves exact bounded weights") {
        BuilderCrownPaletteParser.parse("oak_leaves90%,birch_leaves10%") shouldBe listOf(
            BuilderCrownPaletteEntry("oak_leaves", 90),
            BuilderCrownPaletteEntry("birch_leaves", 10),
        )
        BuilderCrownPaletteParser.parse("oak_leaves,birch_leaves") shouldBe listOf(
            BuilderCrownPaletteEntry("oak_leaves", 1),
            BuilderCrownPaletteEntry("birch_leaves", 1),
        )
        shouldThrow<IllegalArgumentException> { BuilderCrownPaletteParser.parse("oak_leaves80%,birch_leaves10%") }
        shouldThrow<IllegalArgumentException> { BuilderCrownPaletteParser.parse("oak_leaves,oak_leaves") }
        shouldThrow<IllegalArgumentException> { BuilderCrownPaletteParser.parse("oak_leaves50%,birch_leaves") }
    }

    test("crown palette assignment is deterministic and approximately weighted") {
        val settings = BuilderCrownSettings(
            palette = listOf(
                BuilderCrownPaletteEntry("oak_leaves", 80),
                BuilderCrownPaletteEntry("birch_leaves", 20),
            ),
        ).validated()
        val first = (0 until 2_000).map { index -> settings.materialAt(index, index % 17, index % 31, 99L) }
        val repeated = (0 until 2_000).map { index -> settings.materialAt(index, index % 17, index % 31, 99L) }
        first shouldBe repeated
        (first.count { it == "oak_leaves" } in 1_500..1_700) shouldBe true
    }

    test("crown settings produce distinct bounded shapes and monotonic density") {
        val wide = BuilderCrownGeometry.offsets(BuilderCrownSettings(shape = BuilderCrownShape.WIDE), 73L)
        val tall = BuilderCrownGeometry.offsets(BuilderCrownSettings(shape = BuilderCrownShape.TALL), 73L)
        val wideX = wide.maxOf { kotlin.math.abs(it.first) }
        val wideY = wide.maxOf { kotlin.math.abs(it.second) }
        val tallX = tall.maxOf { kotlin.math.abs(it.first) }
        val tallY = tall.maxOf { kotlin.math.abs(it.second) }
        (wideX > wideY) shouldBe true
        (tallY > tallX) shouldBe true

        val airy = BuilderCrownGeometry.offsets(BuilderCrownSettings(density = BuilderCrownDensity.AIRY), 73L)
        val natural = BuilderCrownGeometry.offsets(BuilderCrownSettings(density = BuilderCrownDensity.NATURAL), 73L)
        val dense = BuilderCrownGeometry.offsets(BuilderCrownSettings(density = BuilderCrownDensity.DENSE), 73L)
        (airy.size <= natural.size) shouldBe true
        (natural.size <= dense.size) shouldBe true
        dense.all { (x, y, z) -> x in -6..6 && y in -6..6 && z in -6..6 } shouldBe true
    }

    test("crown sessions keep a stable preview seed and advance only on reroll") {
        val sessions = BuilderCrownSessions()
        val center = BuilderBlockPos(worldId, 12, 80, -7)
        val settings = BuilderCrownSettings(shape = BuilderCrownShape.ROUND)
        sessions.update(playerId, settings)
        val first = sessions.seed(playerId, center, settings, reroll = false)
        sessions.seed(playerId, center, settings, reroll = false) shouldBe first
        sessions.seed(playerId, center, settings, reroll = true) shouldNotBe first
        sessions.clear(playerId)
        sessions.settings(playerId) shouldBe BuilderCrownSettings()
    }

    test("journal enforces plan identity phase and undo linkage") {
        val now = 1_800_000_000_000L
        val operationId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val change = BuilderBlockChange(
            BuilderBlockPos(worldId, 4, 70, 8),
            "minecraft:stone",
            "minecraft:air",
        )
        val plan = BuilderPlan(
            id = operationId,
            playerId = playerId,
            kind = BuilderPlanKind.DECONSTRUCT,
            changes = listOf(change),
            costs = emptyList(),
            rewards = emptyList(),
            createdAtMillis = now,
            expiresAtMillis = now + 30_000,
        ).validated()
        val prepared = BuilderJournalRecord(
            operationId = operationId,
            playerId = playerId,
            playerName = "Builder_1",
            phase = BuilderJournalPhase.PREPARED,
            plan = plan,
            inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
            createdAtMillis = now,
            updatedAtMillis = now,
        ).validated()

        val applying = prepared.copy(phase = BuilderJournalPhase.APPLYING, updatedAtMillis = now + 1)
        val committed = applying.copy(
            phase = BuilderJournalPhase.COMMITTED,
            updatedAtMillis = now + 2,
            committedAtMillis = now + 2,
        )
        BuilderJournalTransitionRules.classify(applying, committed, committed) shouldBe
            BuilderJournalReconciliation.TARGET_COMMITTED
        BuilderJournalTransitionRules.classify(applying, committed, applying) shouldBe
            BuilderJournalReconciliation.PREDECESSOR_CONFIRMED
        BuilderJournalTransitionRules.classify(applying, committed, null) shouldBe
            BuilderJournalReconciliation.UNKNOWN

        shouldThrow<IllegalArgumentException> { plan.copy(kind = BuilderPlanKind.UNDO).validated() }
        shouldThrow<IllegalArgumentException> {
            BuilderJournalRecord(
                operationId = operationId,
                playerId = playerId,
                playerName = "Builder_1",
                phase = BuilderJournalPhase.COMMITTED,
                plan = plan,
                inventoryBefore = PaperPlayerStateEnvelope(payloadBase64 = "AAAA", sha256 = "a".repeat(64)),
                createdAtMillis = now,
                updatedAtMillis = now,
                committedAtMillis = null,
            ).validated()
        }
    }

    test("recovery distinguishes never-applied and possibly-applied phases") {
        BuilderRecoveryRules.action(
            BuilderJournalPhase.PREPARED,
            "minecraft:stone",
            "minecraft:stone",
            "minecraft:air",
        ) shouldBe BuilderRecoveryAction.KEEP_BEFORE
        shouldThrow<IllegalArgumentException> {
            BuilderRecoveryRules.action(
                BuilderJournalPhase.PREPARED,
                "minecraft:air",
                "minecraft:stone",
                "minecraft:air",
            )
        }
        BuilderRecoveryRules.action(
            BuilderJournalPhase.APPLYING,
            "minecraft:air",
            "minecraft:stone",
            "minecraft:air",
        ) shouldBe BuilderRecoveryAction.RESTORE_BEFORE
        shouldThrow<IllegalStateException> {
            BuilderRecoveryRules.action(
                BuilderJournalPhase.APPLYING,
                "minecraft:dirt",
                "minecraft:stone",
                "minecraft:air",
            )
        }
    }

    test("plan rejects duplicate positions and non-vanilla block data") {
        val now = 1_800_000_000_000L
        val position = BuilderBlockPos(worldId, 1, 64, 1)
        val duplicate = listOf(
            BuilderBlockChange(position, "minecraft:stone", "minecraft:air"),
            BuilderBlockChange(position, "minecraft:dirt", "minecraft:air"),
        )
        shouldThrow<IllegalArgumentException> {
            BuilderPlan(UUID.randomUUID(), playerId, BuilderPlanKind.FILL, duplicate, emptyList(), emptyList(), createdAtMillis = now, expiresAtMillis = now + 1_000).validated()
        }
        shouldThrow<IllegalArgumentException> {
            BuilderBlockChange(position, "itemsadder:custom", "minecraft:air").validated()
        }
    }
})
