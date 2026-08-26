package ru.arc.buildertools

import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class BuilderPlanKind {
    FILL,
    PASTE,
    DECONSTRUCT,
    CROWN,
    UNDO,
}

enum class BuilderJournalPhase {
    PREPARED,
    APPLYING,
    COMMITTED,
    UNDONE,
}

enum class BuilderRecoveryAction {
    KEEP_BEFORE,
    RESTORE_BEFORE,
}

object BuilderRecoveryRules {
    fun action(
        phase: BuilderJournalPhase,
        currentBlockData: String,
        beforeBlockData: String,
        afterBlockData: String,
    ): BuilderRecoveryAction = when (phase) {
        BuilderJournalPhase.PREPARED -> {
            require(currentBlockData == beforeBlockData) {
                "Prepared builder-tools recovery found world drift"
            }
            BuilderRecoveryAction.KEEP_BEFORE
        }
        BuilderJournalPhase.APPLYING -> when (currentBlockData) {
            beforeBlockData -> BuilderRecoveryAction.KEEP_BEFORE
            afterBlockData -> BuilderRecoveryAction.RESTORE_BEFORE
            else -> error("Applying builder-tools recovery found ambiguous world state")
        }
        BuilderJournalPhase.COMMITTED,
        BuilderJournalPhase.UNDONE,
        -> error("Terminal builder-tools records do not require block recovery")
    }
}

enum class BuilderJournalReconciliation {
    TARGET_COMMITTED,
    PREDECESSOR_CONFIRMED,
    UNKNOWN,
}

object BuilderJournalTransitionRules {
    fun classify(
        expected: BuilderJournalRecord,
        target: BuilderJournalRecord,
        current: BuilderJournalRecord?,
    ): BuilderJournalReconciliation {
        require(expected.operationId == target.operationId && expected.playerId == target.playerId) {
            "Builder-tools journal transition identity mismatch"
        }
        require(expected.plan == target.plan && expected.inventoryBefore == target.inventoryBefore) {
            "Builder-tools journal transition changed immutable operation data"
        }
        require(target.updatedAtMillis >= expected.updatedAtMillis) {
            "Builder-tools journal transition moved time backwards"
        }
        require(
            (expected.phase == BuilderJournalPhase.PREPARED && target.phase == BuilderJournalPhase.APPLYING) ||
                (expected.phase == BuilderJournalPhase.APPLYING && target.phase == BuilderJournalPhase.COMMITTED) ||
                (expected.phase == BuilderJournalPhase.COMMITTED && target.phase == BuilderJournalPhase.UNDONE),
        ) { "Builder-tools journal phase transition is invalid" }
        return when (current) {
            target -> BuilderJournalReconciliation.TARGET_COMMITTED
            expected -> BuilderJournalReconciliation.PREDECESSOR_CONFIRMED
            else -> BuilderJournalReconciliation.UNKNOWN
        }
    }
}

data class BuilderBlockPos(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun validated(): BuilderBlockPos = apply {
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Builder-tools block position is outside the world coordinate bound"
        }
        require(y in -2_048..2_048) { "Builder-tools block position is outside the vertical safety bound" }
    }
}

data class BuilderBlockChange(
    val position: BuilderBlockPos,
    val beforeBlockData: String,
    val afterBlockData: String,
) {
    fun validated(): BuilderBlockChange = apply {
        position.validated()
        require(beforeBlockData.length in 3..512 && afterBlockData.length in 3..512) {
            "Builder-tools block data is outside its size bound"
        }
        require(beforeBlockData.startsWith("minecraft:") && afterBlockData.startsWith("minecraft:")) {
            "Builder-tools journal only accepts canonical vanilla block data"
        }
        require(beforeBlockData != afterBlockData) { "Builder-tools change must alter the block state" }
    }
}

/** One exact ItemStack prototype encoded with Paper's native codec plus a bounded quantity. */
data class BuilderItemAmount(
    val itemBase64: String,
    val materialKey: String,
    val amount: Int,
) {
    fun validated(): BuilderItemAmount = apply {
        require(itemBase64.length in 4..1_500_000) { "Builder-tools item payload is outside its size bound" }
        require(materialKey.matches(Regex("minecraft:[a-z0-9_./-]{1,96}"))) {
            "Builder-tools material key is invalid"
        }
        require(amount in 1..1_000_000) { "Builder-tools item quantity is outside its safety bound" }
    }
}

data class BuilderPlan(
    val id: UUID,
    val playerId: UUID,
    val kind: BuilderPlanKind,
    val changes: List<BuilderBlockChange>,
    val costs: List<BuilderItemAmount>,
    val rewards: List<BuilderItemAmount>,
    val toolFingerprintBase64: String? = null,
    val toolDamage: Int = 0,
    val sourceRecordId: UUID? = null,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    fun validated(maxChanges: Int = ABSOLUTE_MAX_CHANGES): BuilderPlan = apply {
        require(changes.size in 1..maxChanges.coerceAtMost(ABSOLUTE_MAX_CHANGES)) {
            "Builder-tools plan change count is outside its safety bound"
        }
        require(changes.map { it.position }.toSet().size == changes.size) {
            "Builder-tools plan contains duplicate block positions"
        }
        changes.forEach(BuilderBlockChange::validated)
        require(costs.size + rewards.size <= 4_096) { "Builder-tools item entry count is outside its safety bound" }
        require((costs + rewards).sumOf { it.itemBase64.length.toLong() } <= 16_000_000L) {
            "Builder-tools item payload total is outside its safety bound"
        }
        (costs + rewards).forEach(BuilderItemAmount::validated)
        require(costs.sumOf { it.amount.toLong() } <= ABSOLUTE_MAX_ITEMS) {
            "Builder-tools plan cost is outside its safety bound"
        }
        require(rewards.sumOf { it.amount.toLong() } <= ABSOLUTE_MAX_ITEMS) {
            "Builder-tools plan reward is outside its safety bound"
        }
        require(toolDamage in 0..ABSOLUTE_MAX_CHANGES) { "Builder-tools tool damage is outside its safety bound" }
        require((toolDamage == 0) == (toolFingerprintBase64 == null)) {
            "Builder-tools tool fingerprint and damage must be present together"
        }
        toolFingerprintBase64?.let {
            require(it.length in 4..1_500_000) { "Builder-tools tool fingerprint is outside its size bound" }
        }
        require(createdAtMillis > 0L && expiresAtMillis > createdAtMillis) {
            "Builder-tools plan lifetime is invalid"
        }
        require(kind == BuilderPlanKind.UNDO || sourceRecordId == null) {
            "Only an undo plan may reference a source operation"
        }
        require(kind != BuilderPlanKind.UNDO || sourceRecordId != null) {
            "An undo plan must reference its source operation"
        }
    }

    companion object {
        const val ABSOLUTE_MAX_CHANGES = 10_000
        const val ABSOLUTE_MAX_ITEMS = 2_000_000L
    }
}

data class BuilderJournalRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val operationId: UUID,
    val playerId: UUID,
    val playerName: String,
    val phase: BuilderJournalPhase,
    val plan: BuilderPlan,
    val inventoryBefore: PaperPlayerStateEnvelope,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val committedAtMillis: Long? = null,
) {
    fun validated(maxChanges: Int = BuilderPlan.ABSOLUTE_MAX_CHANGES): BuilderJournalRecord = apply {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported builder-tools journal schema" }
        require(operationId == plan.id && playerId == plan.playerId) { "Builder-tools journal identity mismatch" }
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "Builder-tools player name is invalid" }
        plan.validated(maxChanges)
        require(inventoryBefore.payloadBase64.length in 4..24_000_000) {
            "Builder-tools inventory payload is outside its safety bound"
        }
        require(inventoryBefore.sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Builder-tools inventory checksum is invalid"
        }
        require(createdAtMillis == plan.createdAtMillis && updatedAtMillis >= createdAtMillis) {
            "Builder-tools journal timestamps are invalid"
        }
        require((phase == BuilderJournalPhase.COMMITTED || phase == BuilderJournalPhase.UNDONE) == (committedAtMillis != null)) {
            "Builder-tools committed timestamp does not match its phase"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class BuilderSelection(
    val first: BuilderBlockPos,
    val second: BuilderBlockPos,
) {
    init {
        require(first.worldId == second.worldId) { "Builder-tools selection cannot cross worlds" }
    }

    val worldId: UUID get() = first.worldId
    val minX: Int get() = min(first.x, second.x)
    val maxX: Int get() = max(first.x, second.x)
    val minY: Int get() = min(first.y, second.y)
    val maxY: Int get() = max(first.y, second.y)
    val minZ: Int get() = min(first.z, second.z)
    val maxZ: Int get() = max(first.z, second.z)
    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1
    val volume: Long get() = Math.multiplyExact(Math.multiplyExact(sizeX.toLong(), sizeY.toLong()), sizeZ.toLong())

    fun positionsTopDown(): Sequence<BuilderBlockPos> = sequence {
        for (y in maxY downTo minY) {
            for (x in minX..maxX) {
                for (z in minZ..maxZ) yield(BuilderBlockPos(worldId, x, y, z))
            }
        }
    }

    fun positionsBottomUp(): Sequence<BuilderBlockPos> = sequence {
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                for (z in minZ..maxZ) yield(BuilderBlockPos(worldId, x, y, z))
            }
        }
    }

    fun validated(maxAxis: Int, maxScanVolume: Long): BuilderSelection = apply {
        first.validated()
        second.validated()
        require(maxOf(sizeX, sizeY, sizeZ) <= maxAxis) { "selection-axis" }
        require(volume <= maxScanVolume) { "selection-volume" }
    }
}

data class BuilderClipboardBlock(
    val dx: Int,
    val dy: Int,
    val dz: Int,
    val blockData: String,
)

data class BuilderClipboard(
    val blocks: List<BuilderClipboardBlock>,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    fun validated(maxBlocks: Int): BuilderClipboard = apply {
        require(blocks.size in 1..maxBlocks) { "clipboard-size" }
        require(blocks.map { Triple(it.dx, it.dy, it.dz) }.toSet().size == blocks.size) { "clipboard-duplicates" }
        blocks.forEach {
            require(abs(it.dx) <= 256 && abs(it.dy) <= 256 && abs(it.dz) <= 256) { "clipboard-offset" }
            require(it.blockData.length in 3..512 && it.blockData.startsWith("minecraft:")) { "clipboard-block-data" }
        }
        require(createdAtMillis > 0L && expiresAtMillis > createdAtMillis) { "clipboard-lifetime" }
    }
}

object BuilderCrownGeometry {
    /** Deterministic organic ellipsoid. It changes no world state and is stable across restarts. */
    fun offsets(radius: Int, seed: Long): List<Triple<Int, Int, Int>> {
        require(radius in 3..12) { "Crown radius must be between 3 and 12" }
        val verticalRadius = radius * 0.82
        val result = ArrayList<Triple<Int, Int, Int>>()
        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val nx = x / (radius + 0.35)
                    val ny = (y + radius * 0.08) / verticalRadius
                    val nz = z / (radius + 0.35)
                    val distance = nx * nx + ny * ny + nz * nz
                    val jitter = (stableNoise(x, y, z, seed) - 0.5) * 0.22
                    if (distance <= 1.0 + jitter) result += Triple(x, y, z)
                }
            }
        }
        return result.sortedWith(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.first }.thenBy { it.third })
    }

    private fun stableNoise(x: Int, y: Int, z: Int, seed: Long): Double {
        var value = seed xor (x.toLong() * -7046029254386353131L)
        value = value xor (y.toLong() * -4658895280553007687L)
        value = value xor (z.toLong() * -7723592293110705685L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return (value ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
