package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Slab
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
