package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Stable kite-shaped plates: glass carries the surface, solid bevels carry the silhouette. */
internal fun staffDefenseParts(
    spell: StaffSpell,
    age: Int,
    duration: Int,
    allAround: Boolean,
    strength: Double = 1.0,
    impactAge: Int = 100,
    endingAge: Int? = null,
): List<StaffDisplayPart> {
    val (glass, rim, accent) = when (spell) {
        StaffSpell.EMBER, StaffSpell.INFERNO -> Triple(Material.ORANGE_STAINED_GLASS, Material.GOLD_BLOCK, Material.OCHRE_FROGLIGHT)
        StaffSpell.FROST, StaffSpell.ICICLE, StaffSpell.TIDE -> Triple(Material.LIGHT_BLUE_STAINED_GLASS, Material.PACKED_ICE, Material.SEA_LANTERN)
        StaffSpell.NOVA -> Triple(Material.LIME_STAINED_GLASS, Material.MOSS_BLOCK, Material.VERDANT_FROGLIGHT)
        StaffSpell.FAULT, StaffSpell.GRAVITY -> Triple(Material.PURPLE_STAINED_GLASS, Material.OBSIDIAN, Material.AMETHYST_BLOCK)
        StaffSpell.OSSUARY, StaffSpell.NECROMANCER -> Triple(Material.WHITE_STAINED_GLASS, Material.BONE_BLOCK, Material.SOUL_SOIL)
        StaffSpell.LANCE -> Triple(Material.YELLOW_STAINED_GLASS, Material.GOLD_BLOCK, Material.OCHRE_FROGLIGHT)
        StaffSpell.MARK, StaffSpell.REQUIEM, StaffSpell.LUNAR, StaffSpell.PRISM -> Triple(Material.MAGENTA_STAINED_GLASS, Material.AMETHYST_BLOCK, Material.PEARLESCENT_FROGLIGHT)
        else -> Triple(Material.CYAN_STAINED_GLASS, Material.PRISMARINE_BRICKS, Material.SEA_LANTERN)
    }
    fun ease(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    val opening = .62 + .38 * ease(age / 4.0)
    val ending = endingAge?.let { 1.0 - ease(it / 6.0) }
        ?: ease((duration - age) / 6.0)
    val impact = if (impactAge in 0..6) 1.0 - ease(impactAge / 6.0) else 0.0
    val size = (opening * ending).coerceAtLeast(.001).toFloat()
    val points = listOf(-.65 to .82, .65 to .82, .84 to .43, .62 to -.37,
        0.0 to -.98, -.62 to -.37, -.84 to .43)
    val parts = mutableListOf<StaffDisplayPart>()
    val count = if (allAround) 4 else 1
    repeat(count) { plate ->
        val angle = if (allAround) PI / 4 + plate * PI / 2 else 0.0
        val rotation = Quaternionf().rotationY(angle.toFloat())
        val plateScale = if (allAround) .70f else 1f
        val radius = if (allAround) 1.72 else 1.85
        val recoil = impact * .12
        val center = Vector3f((sin(angle) * (radius - recoil)).toFloat(), 1.12f,
            (cos(angle) * (radius - recoil)).toFloat())
        fun add(id: String, material: Material, x: Double, y: Double, z: Double,
            width: Double, height: Double, depth: Double, roll: Double = 0.0) {
            val local = Vector3f(x.toFloat(), y.toFloat(), z.toFloat()).mul(plateScale * size)
            val localRotation = Quaternionf().rotationZ(roll.toFloat())
            parts += StaffDisplayPart(material, rotation.transform(local).add(center),
                Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()).mul(plateScale * size),
                Quaternionf(rotation).mul(localRotation), identity = "defense-$plate-$id")
        }
        // Separated bevels avoid overlapping outward faces at their angled joints.
        points.indices.forEach { i ->
            val (x1, y1) = points[i]
            val (x2, y2) = points[(i + 1) % points.size]
            val material = if (impact > .3 || i == 0 && strength > .5) accent else rim
            add("rim-$i", material, (x1 + x2) / 2, (y1 + y2) / 2, .015,
                hypot(x2 - x1, y2 - y1) - .18, .15, .20, atan2(y2 - y1, x2 - x1))
        }
        // Non-overlapping panes give a substantial transparent face without an opaque reticle cover.
        add("pane-top", glass, 0.0, .43, -.025, 1.32, .70, .065)
        add("pane-mid", glass, 0.0, -.185, -.025, 1.03, .49, .065)
        add("pane-tip", glass, 0.0, -.625, -.025, .42, .35, .065)
        if (!allAround) {
            add("boss", accent, 0.0, -.27, .16, .25, .25, .13, PI / 4)
            add("keel", rim, 0.0, -.64, .11, .09, .31, .10)
        }
    }
    return parts
}
