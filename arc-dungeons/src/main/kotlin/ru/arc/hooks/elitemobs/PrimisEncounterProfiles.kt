package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import java.security.MessageDigest
import kotlin.math.floor

internal sealed interface PrimisRoom {
    fun contains(x: Double, y: Double, z: Double): Boolean

    data class Interior(val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double) : PrimisRoom {
        override fun contains(x: Double, y: Double, z: Double) =
            x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ
    }

    data class LeashedSide(val x: Double, val y: Double, val z: Double, val radius: Double, val minZ: Double) : PrimisRoom {
        override fun contains(x: Double, y: Double, z: Double) = z >= minZ &&
            (x - this.x) * (x - this.x) + (y - this.y) * (y - this.y) + (z - this.z) * (z - this.z) <= radius * radius
    }
}

internal data class PrimisEncounterProfile(
    val baseFile: String,
    val phaseFile: String,
    val spawn: String,
    val room: PrimisRoom,
    val closureMaterials: Set<Material>,
    val closureCount: Int,
    val controlFingerprint: String,
) {
    val world = spawn.substringBefore(',')
    val spawnCoordinates = spawn.split(',').drop(1).map(String::toDouble)

    fun matchesSpawn(raw: String?): Boolean {
        val parts = raw?.substringBefore(':')?.split(',') ?: return false
        return parts.size == 6 && parts.first() == world && parts.drop(1).map(String::toDoubleOrNull) == spawnCoordinates
    }
}

internal data class PrimisPassageCell(val x: Int, val y: Int, val z: Int, val baseline: BlockData) {
    val box = BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
}

internal data class PrimisPreparedEncounter(
    val profile: PrimisEncounterProfile,
    val base: CustomBossesConfigFields?,
    val passages: List<PrimisPassageCell>,
    val failure: String? = null,
) {
    val ready get() = failure == null && passages.size == profile.closureCount
}

/** Explicit pack-v19 admission volumes; these are authored door/cage planes, not discovered block bounds. */
internal fun primisEncounterProfiles() = listOf(
    PrimisEncounterProfile("primis_ice_mage.yml", "primis_ice_mage_p2.yml",
        "em_primis,1095.5,38.5,573.5,0.0,0.0", PrimisRoom.Interior(1086.0, 36.0, 564.0, 1104.0, 43.0, 583.0),
        setOf(Material.DARK_OAK_FENCE_GATE), 13, "aae1c28e6ae6002e3581183516a90000e74714e80d17da75ffa671d46ea09ab9"),
    PrimisEncounterProfile("primis_crystaline_summoner_p1.yml", "primis_crystaline_summoner_p2.yml",
        "em_primis,491.5,55.5,368.5,0.0,0.0", PrimisRoom.Interior(476.0, 53.0, 358.0, 497.0, 64.0, 379.0),
        setOf(Material.GREEN_STAINED_GLASS, Material.GREEN_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS,
            Material.LIME_STAINED_GLASS_PANE, Material.TINTED_GLASS), 474,
        "b72cc8428f35342cc334acd8d4f65e5283cf76e6b3fe4bb20807b9a768c74007"),
    PrimisEncounterProfile("primis_mountain_pass_guardian_p1.yml", "primis_mountain_pass_guardian_p2.yml",
        "em_primis,1264.5,50.5,600.5,0.0,0.0", PrimisRoom.LeashedSide(1264.5, 50.5, 600.5, 35.0, 574.0),
        setOf(Material.BARRIER), 19, "d95e13b5eb172d1c4bd964d9f8e2d122cc43f72eba6484b954cf2f74b06e8a3f"),
)

internal fun primisControlFingerprint(baseFile: String, base: List<String>, phaseFile: String, phase: List<String>): String =
    MessageDigest.getInstance("SHA-256").digest((listOf(baseFile) + base + phaseFile + phase)
        .joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Read only already-loaded native configuration. A changed pack disables its profile, never other dungeons. */
internal fun preparePrimisEncounters(
    profiles: List<PrimisEncounterProfile> = primisEncounterProfiles(),
    config: (String) -> CustomBossesConfigFields? = CustomBossesConfig::getCustomBoss,
): List<PrimisPreparedEncounter> = profiles.map { profile ->
    val base = config(profile.baseFile)
    runCatching {
        requireNotNull(base) { "missing base configuration" }
        val phase = requireNotNull(config(profile.phaseFile)) { "missing phase configuration" }
        require(base.phases.orEmpty().map { it.substringBefore(':') } == listOf(profile.phaseFile)) { "phase list changed" }
        require(phase.phaseSpawnLocation.isNullOrBlank()) { "phase destination changed" }
        val original = base.onSpawnBlockStates.orEmpty()
        val closed = phase.onSpawnBlockStates.orEmpty()
        require(primisControlFingerprint(profile.baseFile, original, profile.phaseFile, closed) == profile.controlFingerprint) {
            "block controls differ from the verified pack"
        }
        fun cells(states: List<String>): Map<List<Int>, String> = buildMap {
            for (entry in states) {
                val parts = entry.split('/', limit = 2)
                require(parts.size == 2) { "invalid block control" }
                val relative = parts.first().split(',').map(String::toDouble)
                require(relative.size == 3 && relative.all(Double::isFinite)) { "invalid block position" }
                val key = relative.mapIndexed { i, coordinate -> floor(profile.spawnCoordinates[i] + coordinate).toInt() }
                // Native TransitiveBossBlock applies the authored list in order, including repeated cells.
                put(key, parts.last())
            }
        }
        val baseline = cells(original)
        val passages = cells(closed).filter { (key, data) -> Material.matchMaterial(data.substringBefore('[')) in profile.closureMaterials &&
            data != baseline[key] }.map { (key, _) ->
            PrimisPassageCell(key[0], key[1], key[2], Bukkit.createBlockData(requireNotNull(baseline[key]) { "missing passage baseline" }))
        }
        require(passages.size == profile.closureCount) { "closure count changed: ${passages.size}" }
        PrimisPreparedEncounter(profile, base, passages)
    }.getOrElse { PrimisPreparedEncounter(profile, base, emptyList(), it.message ?: it.javaClass.simpleName) }
}
