package ru.arc.aurora

import java.util.Random
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/** A bounded, detached view of a world lattice; viewer position selects cells, never their geometry. */
data class AuroraSkyFieldSettings(
    val cellSize: Double = 40.0,
    val jitter: Double = 0.3,
    val viewDistance: Double = 96.0,
    val cullHysteresis: Double = 16.0,
    val maxScenes: Int = 9,
    val cullCooldownTicks: Int = 80,
    val maxPerProfile: Int = 2,
)

internal data class AuroraSkyCell(val x: Int, val z: Int)
internal data class AuroraWorldCandidate(
    val cell: AuroraSkyCell, val profile: AuroraProfile, val epoch: Long,
    val seed: Long, val x: Double, val z: Double,
)

internal object AuroraWorldField {
    fun candidates(world: UUID, profiles: List<AuroraProfile>, x: Double, z: Double, age: Long,
        settings: AuroraSkyFieldSettings): List<AuroraWorldCandidate> {
        if (profiles.isEmpty()) return emptyList()
        val cx = floor(x / settings.cellSize).toInt()
        val cz = floor(z / settings.cellSize).toInt()
        val reach = ceil((settings.viewDistance + settings.cullHysteresis) / settings.cellSize).toInt() + 1
        val distance = settings.viewDistance + settings.cullHysteresis
        val result = ArrayList<AuroraWorldCandidate>()
        for (dx in -reach..reach) for (dz in -reach..reach) {
            val cell = AuroraSkyCell(cx + dx, cz + dz)
            val cellSeed = world.mostSignificantBits xor world.leastSignificantBits xor
                (cell.x.toLong() * 341873128712L) xor (cell.z.toLong() * 132897987541L)
            val profile = profiles[Random(cellSeed).nextInt(profiles.size)]
            val epoch = age / (profile.sceneDurationTicks + profile.spawnDelayTicks).coerceAtLeast(1)
            val seed = cellSeed xor (profile.id.hashCode().toLong() shl 32) xor (epoch * -7046029254386353131L)
            val random = Random(seed)
            val px = (cell.x + 0.5 + (random.nextDouble() * 2.0 - 1.0) * settings.jitter) * settings.cellSize
            val pz = (cell.z + 0.5 + (random.nextDouble() * 2.0 - 1.0) * settings.jitter) * settings.cellSize
            if ((px - x) * (px - x) + (pz - z) * (pz - z) <= distance * distance) {
                result += AuroraWorldCandidate(cell, profile, epoch, seed, px, pz)
            }
        }
        return result.sortedWith(compareBy<AuroraWorldCandidate> { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }
            .thenBy { it.cell.x }.thenBy { it.cell.z })
    }
}
