package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small eye-relative cast silhouettes; the display scene supplies the caster's yaw frame. */
internal fun staffPreparationParts(
    spell: StaffSpell,
    secondary: Boolean,
    age: Int,
    duration: Int = PREPARATION_TICKS,
): List<StaffDisplayPart> {
    if (age < 0 || duration <= 0 || age >= duration) return emptyList()
    if ((spell == StaffSpell.EMBER || spell == StaffSpell.ICICLE) && !secondary) return emptyList()
    if (spell == StaffSpell.INFERNO || spell == StaffSpell.GRAVITY) return emptyList()

    val pose = when (spell) {
        StaffSpell.CHAIN -> chainPreparation(age, secondary)
        StaffSpell.MARK -> markPreparation(age, secondary)
        StaffSpell.FROST -> frostPreparation(age, secondary)
        StaffSpell.FAULT -> faultPreparation(age, secondary)
        StaffSpell.LANCE -> lancePreparation(age, secondary)
        StaffSpell.NOVA -> novaPreparation(age, secondary)
        StaffSpell.LUNAR -> lunarPreparation(age, secondary)
        StaffSpell.GALE -> galePreparation(age, secondary)
        StaffSpell.TIDE -> tidePreparation(age, secondary)
        StaffSpell.REQUIEM -> requiemPreparation(age, secondary)
        StaffSpell.PRISM -> prismPreparation(age, secondary)
        StaffSpell.NECROMANCER -> necromancerPreparation(age, secondary)
        StaffSpell.OSSUARY -> ossuaryPreparation(age, secondary)
        StaffSpell.EMBER -> emberPreparation(age, secondary)
        StaffSpell.ICICLE -> iciclePreparation(age, secondary)
        StaffSpell.INFERNO, StaffSpell.GRAVITY -> emptyList()
    }
    val opening = 0.22 + 0.78 * preparationSmooth(age / 3.0)
    val fade = 0.001 + 0.999 * preparationSmooth((duration - age - 1) / 6.0)
    val envelope = (opening * fade).toFloat()
    return pose.map { part ->
        part.copy(
            center = Vector3f(part.center).add(0f, PREPARATION_VIEW_Y_OFFSET, PREPARATION_VIEW_Z_OFFSET),
            scale = Vector3f(part.scale).mul(envelope),
        )
    }
}

private fun chainPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val side = if (secondary) -1f else 1f
    val points = (0..6).map { index ->
        val offset = if (index % 2 == 0) -0.68f else 0.68f
        Vector3f(side * offset, 1.42f + index % 3 * 0.30f,
            (2.04f + (index % 2) * 0.18f + sin(age * 0.12 + index) * 0.04).toFloat())
    }
    for (index in 0 until points.lastIndex) add(preparationBeam(
        "chain-conductor-$index", if (index % 2 == 0) Material.LIGHTNING_ROD else Material.COPPER_BLOCK,
        points[index], points[index + 1], 0.12f,
    ))
    points.indices.filter { it % 2 == 0 }.forEach { index ->
        val point = points[index]
        add(preparationBox("chain-node-$index", Material.AMETHYST_BLOCK,
            point.x, point.y, point.z, 0.19f, 0.19f, 0.19f, roll = 0.35f))
    }
}

private fun markPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val bend = if (secondary) -1f else 1f
    val wobble = sin(age * 0.14).toFloat() * 0.08f
    add(preparationBox("mark-rift-core", Material.CRYING_OBSIDIAN,
        wobble, 1.84f, 2.25f, 0.18f, 0.90f, 0.16f))
    for (index in 0..4) {
        val side = if (index % 2 == 0) -1f else 1f
        val x = side * (0.31f + index / 2 * 0.20f)
        val y = 1.48f + index * 0.16f
        val alternateOffset = if (secondary) 0.12f else 0f
        val shard = preparationBox("mark-shard-$index",
            if (index % 2 == 0) Material.OBSIDIAN else Material.PURPLE_STAINED_GLASS,
            x + bend * wobble + alternateOffset, y, 2.12f + index % 2 * 0.10f,
            0.16f, 0.38f - index % 2 * 0.06f, 0.14f,
            roll = side * bend * 0.32f)
        add(shard)
    }
}

private fun frostPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val spread = if (secondary) 0.92f else 0.72f
    val motion = sin(age * 0.12).toFloat() * 0.06f
    for (index in 0..4) {
        val side = index - 2
        val tip = Vector3f(side * spread / 2f, if (secondary) 2.24f - kotlin.math.abs(side) * 0.11f
            else 1.92f + (2 - kotlin.math.abs(side)) * 0.13f, 2.20f + kotlin.math.abs(side) * 0.04f)
        val root = Vector3f(motion, 1.42f, 2.08f)
        add(preparationBeam("frost-fan-$index",
            if (index % 2 == 0) Material.BLUE_ICE else Material.PACKED_ICE, root, tip, 0.17f))
        add(preparationBox("frost-tip-$index", Material.SEA_LANTERN,
            tip.x, tip.y, tip.z, 0.15f, 0.22f, 0.15f,
            roll = (side * 0.10f + motion)))
    }
}

private fun faultPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val height = if (secondary) 0.48f else 0.34f
    val phase = sin(age * 0.10).toFloat() * 0.04f
    for (side in listOf(-1f, 1f)) {
        val inner = Vector3f(side * 1.12f, 1.36f + phase, 2.08f)
        val crown = Vector3f(side * 1.56f, 1.43f + height, 2.16f)
        val outer = Vector3f(side * 1.98f, 1.36f + phase, 2.23f)
        add(preparationBeam("fault-arch-inner-${side.toInt()}", Material.POLISHED_BASALT,
            inner, crown, 0.27f))
        add(preparationBeam("fault-arch-outer-${side.toInt()}", Material.DEEPSLATE_BRICKS,
            crown, outer, 0.27f))
        add(preparationBox("fault-foot-${side.toInt()}", Material.BLACKSTONE,
            inner.x, inner.y, inner.z, 0.34f, 0.22f, 0.34f, roll = side * 0.12f))
    }
    add(preparationBeam("fault-broken-keystone", Material.CRYING_OBSIDIAN,
        Vector3f(-0.31f, 1.57f, 2.17f), Vector3f(0.31f, 1.57f + phase, 2.17f), 0.19f))
}

private fun lancePreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val turn = (if (secondary) PI / 8.0 else 0.0) + age * 0.04
    val ring = (0 until 8).map { index ->
        val angle = index * PI / 4.0 + turn
        Vector3f((cos(angle) * 0.62).toFloat(), (1.82 + sin(angle) * 0.42).toFloat(), 2.22f)
    }
    for (index in ring.indices) add(preparationBeam("lance-lens-${index}",
        if (index % 2 == 0) Material.GOLD_BLOCK else Material.YELLOW_STAINED_GLASS,
        ring[index], ring[(index + 1) % ring.size], 0.10f))
    for (index in listOf(0, 2, 4, 6)) {
        add(preparationBeam("lance-ray-$index", Material.GLOWSTONE,
            Vector3f(0f, 1.82f, 2.20f), ring[index], 0.08f))
    }
    add(preparationBox("lance-lens-core", Material.GOLD_BLOCK,
        0f, 1.82f, 2.20f, 0.22f, 0.22f, 0.22f))
}

private fun novaPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val twist = (if (secondary) -1f else 1f) * sin(age * 0.11).toFloat() * 0.10f
    add(preparationBeam("nova-stem", Material.MOSSY_COBBLESTONE,
        Vector3f(0f, 1.37f, 2.08f), Vector3f(twist, 2.28f, 2.20f), 0.15f))
    for (side in listOf(-1f, 1f)) for (level in 0..2) {
        val start = Vector3f(twist, 1.48f + level * 0.22f, 2.13f)
        val direction = side
        val spread = if (secondary) 0.40f + level * 0.08f else 0.48f + level * 0.12f
        val tip = Vector3f(direction * spread + twist,
            if (secondary) 1.79f + level * 0.13f else
                1.63f + level * 0.22f + if (level == 2) -0.10f else 0.08f, 2.20f)
        add(preparationBeam("nova-branch-${if (side < 0) "l" else "r"}-$level",
            Material.MOSS_BLOCK, start, tip, 0.10f))
        add(preparationBox("nova-leaf-${if (side < 0) "l" else "r"}-$level",
            if (level % 2 == 0) Material.MANGROVE_LEAVES else Material.OAK_LEAVES,
            tip.x, tip.y, tip.z, 0.33f, 0.18f, 0.18f, roll = direction * 0.42f))
    }
}

private fun lunarPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val moons = if (secondary) listOf(-0.28f, 0.28f) else listOf(0f)
    val direction = if (secondary) -1.0 else 1.0
    moons.forEachIndexed { moon, offset ->
        for (index in 0..6) {
            val angle = direction * (-PI * 0.72 + index * PI * 1.44 / 6.0) + age * 0.025
            val radius = if (secondary) 0.39 else 0.48
            val x = offset + cos(angle) * radius
            val y = 1.83 + sin(angle) * radius
            add(preparationBox("lunar-crescent-$moon-$index",
                if (index % 3 == 0) Material.AMETHYST_BLOCK else Material.QUARTZ_BLOCK,
                x.toFloat(), y.toFloat(), 2.18f, 0.17f, 0.30f, 0.16f,
                roll = (angle + PI / 2.0).toFloat()))
        }
    }
}

private fun galePreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val winding = if (secondary) -1.0 else 1.0
    for (index in 0 until 12) {
        val t = index / 11.0
        val angle = winding * (t * PI * 2.0 + age * 0.08)
        val radius = 0.24 + t * 0.48
        val x = cos(angle) * radius
        val y = 1.34 + t * 0.95
        val z = 2.18 + sin(angle) * radius * 0.42
        add(preparationBox("gale-vortex-$index",
            if (index % 3 == 0) Material.LIGHT_BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS,
            x.toFloat(), y.toFloat(), z.toFloat(), 0.18f, 0.17f, 0.32f,
            yaw = (angle + winding * PI / 2.0).toFloat(), roll = winding.toFloat() * 0.16f))
    }
}

private fun tidePreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val orbX = if (secondary) -0.48f else 0.48f
    val motion = sin(age * 0.12).toFloat() * 0.06f
    val crest = (0..4).map { index ->
        val t = index / 4.0
        Vector3f(((t - 0.5) * 2.35).toFloat(), (1.40 + sin(t * PI) * 0.34 + motion).toFloat(),
            2.08f + (index % 2) * 0.06f)
    }
    for (index in 0 until crest.lastIndex) add(preparationBeam("tide-crest-$index",
        if (index % 2 == 0) Material.PRISMARINE else Material.DARK_PRISMARINE,
        crest[index], crest[index + 1], 0.19f))
    for (index in 0 until 6) {
        val direction = if (secondary) -1.0 else 1.0
        val angle = index * PI / 3.0 + age * 0.035 * direction
        val point = Vector3f(orbX + (cos(angle) * 0.37).toFloat(),
            (1.95 + sin(angle) * 0.37).toFloat(), 2.28f)
        add(preparationBox("tide-hollow-orb-$index",
            if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.SEA_LANTERN,
            point.x, point.y, point.z, 0.14f, 0.23f, 0.14f, roll = (angle + PI / 2.0).toFloat()))
    }
}

private fun requiemPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val tilt = (if (secondary) -1f else 1f) * (0.08f + sin(age * 0.08).toFloat() * 0.05f)
    add(preparationBox("requiem-bell", Material.BELL,
        if (secondary) 0.14f else 0f, 1.91f, 2.23f, 0.42f, 0.50f, 0.34f, roll = tilt))
    add(preparationBeam("requiem-handle", Material.BONE_BLOCK,
        Vector3f(0f, 2.14f, 2.22f), Vector3f(0f, 2.31f, 2.22f), 0.10f))
    for (side in listOf(-1f, 1f)) {
        add(preparationBeam("requiem-bone-${side.toInt()}", Material.BONE_BLOCK,
            Vector3f(side * 0.28f, 1.80f, 2.18f), Vector3f(side * 0.50f, 2.12f, 2.20f), 0.11f))
    }
    add(preparationBox("requiem-clapper", Material.SOUL_SAND,
        0f, 1.56f, 2.24f, 0.15f, 0.18f, 0.15f))
}

private fun prismPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val phase = (if (secondary) 0.09 else 0.0) + age * 0.006
    fun point(x: Double, y: Double, z: Float): Vector3f {
        val centerZ = 2.20
        val dx = x * cos(phase) - (z - centerZ) * sin(phase)
        val dz = x * sin(phase) + (z - centerZ) * cos(phase)
        return Vector3f(dx.toFloat(), y.toFloat(), (centerZ + dz).toFloat())
    }
    val front = listOf(point(0.0, 2.25, 2.03f), point(-0.62, 1.48, 2.03f), point(0.62, 1.48, 2.03f))
    val back = front.map { Vector3f(it).add(0f, 0f, 0.34f) }
    val materials = listOf(Material.AMETHYST_BLOCK, Material.CYAN_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS)
    for (index in 0..2) {
        add(preparationBeam("prism-front-$index", materials[index], front[index], front[(index + 1) % 3], 0.11f))
        add(preparationBeam("prism-back-$index", materials[(index + 1) % 3], back[index], back[(index + 1) % 3], 0.09f))
        add(preparationBeam("prism-edge-$index", Material.YELLOW_STAINED_GLASS,
            front[index], back[index], 0.08f))
    }
    for (index in 0..2) {
        val tip = front[index]
        val outward = Vector3f(tip.x * 1.48f, tip.y.coerceAtLeast(1.45f), 2.46f)
        add(preparationBeam("prism-ray-$index", Material.SEA_LANTERN, tip, outward, 0.08f))
    }
}

private fun necromancerPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val horn = if (secondary) -1f else 1f
    val sway = sin(age * 0.10).toFloat() * 0.04f
    add(preparationBox("necromancer-skull", Material.BONE_BLOCK,
        sway, 1.72f, 2.22f, 0.47f, 0.38f, 0.28f))
    for (side in listOf(-1f, 1f)) {
        add(preparationBox("necromancer-eye-${side.toInt()}", Material.BLACK_CONCRETE,
            sway + side * 0.13f, 1.77f, 2.06f, 0.09f, 0.08f, 0.06f))
        val base = Vector3f(sway + side * 0.20f, 1.91f, 2.21f)
        val tip = Vector3f(sway + side * 0.48f, 2.25f + horn * side * 0.08f, 2.25f)
        add(preparationBeam("necromancer-horn-${side.toInt()}", Material.BONE_BLOCK,
            base, tip, 0.15f))
    }
    for (index in 0..2) {
        val side = index - 1
        add(preparationBox("necromancer-crown-$index", Material.POLISHED_BLACKSTONE,
            side * 0.34f + sway, 2.13f + (1 - kotlin.math.abs(side)) * 0.10f,
            2.23f, 0.19f, 0.28f, 0.18f, roll = side * horn * 0.20f))
    }
}

private fun ossuaryPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    add(preparationBeam("ossuary-spine", Material.BONE_BLOCK,
        Vector3f(0f, 1.35f, 2.20f), Vector3f(0f, 2.30f, 2.20f), 0.12f))
    for (level in 0..4) {
        val y = 1.48f + level * 0.16f
        val flare = if (secondary) 0.12f else 0f
        for (side in listOf(-1f, 1f)) {
            val root = Vector3f(0f, y, 2.20f)
            val tip = Vector3f(side * (0.54f + level * 0.055f + flare),
                y + if (secondary) 0.24f else 0.17f, 2.18f + level * 0.025f)
            add(preparationBeam("ossuary-rib-${level}-${side.toInt()}", Material.BONE_BLOCK,
                root, tip, 0.095f))
        }
    }
}

private fun emberPreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val winding = if (secondary) -1.0 else 1.0
    add(preparationBox("ember-meteor-core", Material.MAGMA_BLOCK,
        0f, 1.83f, 2.22f, 0.34f, 0.34f, 0.34f, yaw = age * 0.08f))
    for (index in 0 until 7) {
        val angle = index * PI * 2.0 / 7.0 + winding * age * 0.13
        val radius = 0.43 + (index % 2) * 0.08
        val x = cos(angle) * radius
        val y = 1.83 + sin(angle) * radius * 0.72
        val z = 2.22 + sin(angle * 1.7) * 0.18
        add(preparationBox("ember-orbit-$index",
            when (index % 3) {
                0 -> Material.ORANGE_CONCRETE
                1 -> Material.RED_CONCRETE
                else -> Material.GLOWSTONE
            }, x.toFloat(), y.toFloat(), z.toFloat(), 0.19f, 0.24f, 0.19f,
            yaw = angle.toFloat(), roll = (angle * 0.4).toFloat()))
    }
}

private fun iciclePreparation(age: Int, secondary: Boolean): List<StaffDisplayPart> = buildList {
    val spread = if (secondary) 0.88f else 0.72f
    val lift = sin(age * 0.12).toFloat() * 0.04f
    for (side in listOf(-1f, 1f)) {
        val root = Vector3f(side * spread, 1.36f, 2.14f)
        val tip = Vector3f(side * (spread - 0.16f), 2.29f + lift, 2.20f)
        add(preparationBeam("icicle-gate-post-${side.toInt()}", Material.PACKED_ICE, root, tip, 0.20f))
        add(preparationBox("icicle-gate-foot-${side.toInt()}", Material.BLUE_ICE,
            root.x, root.y, root.z, 0.28f, 0.22f, 0.22f))
    }
    for (index in 0..2) {
        val x = (index - 1) * spread * 0.58f
        val y = 2.00f + if (index == 1) 0.12f else 0f
        add(preparationBox("icicle-gate-crown-$index", Material.SEA_LANTERN,
            x, y + lift, 2.18f, 0.16f, 0.33f, 0.16f,
            roll = (index - 1) * if (secondary) 0.35f else -0.20f))
    }
}

private fun preparationBox(
    identity: String,
    material: Material,
    x: Float,
    y: Float,
    z: Float,
    width: Float,
    height: Float,
    depth: Float,
    yaw: Float = 0f,
    pitch: Float = 0f,
    roll: Float = 0f,
) = StaffDisplayPart(material, Vector3f(x, y, z), Vector3f(width, height, depth),
    Quaternionf().rotateY(yaw).rotateX(pitch).rotateZ(roll), identity = "preparation-$identity")

private fun preparationBeam(
    identity: String,
    material: Material,
    start: Vector3f,
    end: Vector3f,
    thickness: Float,
): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val length = delta.length().coerceAtLeast(0.10f)
    val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize())
    return StaffDisplayPart(material, Vector3f(start).add(end).mul(0.5f),
        Vector3f(thickness, thickness, length), rotation, identity = "preparation-$identity")
}

private fun preparationSmooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

private const val PREPARATION_TICKS = 10
private const val PREPARATION_VIEW_Y_OFFSET = -0.9f
private const val PREPARATION_VIEW_Z_OFFSET = 1.3f
