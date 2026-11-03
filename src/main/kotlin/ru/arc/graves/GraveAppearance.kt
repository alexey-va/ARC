package ru.arc.graves

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import java.util.UUID

/** Static geometry in metres, centred on the anchor block; its front faces local −Z. */
internal data class GraveModel(
    val id: String,
    val parts: List<GraveModelPart>,
    val inscription: GraveInscription,
)

/** Lower corner plus dimensions. Optional XYZ rotations act around this cuboid's centre. */
internal data class GraveModelPart(
    val material: Material,
    val x: Double, val y: Double, val z: Double,
    val width: Float, val height: Float, val depth: Float,
    val rotationX: Float = 0f, val rotationY: Float = 0f, val rotationZ: Float = 0f,
)

/** Centre of the inscription area, fixed to the front face with a transparent background. */
internal data class GraveInscription(
    val x: Double, val centerY: Double, val z: Double,
    val scale: Float,
    val lineWidth: Int,
    val color: Int,
    val maxHeight: Float = .28f,
) {
    fun placement(epitaph: String): GraveInscriptionPlacement {
        val layout = DialogTextLayout.layout(Component.text(epitaph), TextAlignment.LEFT, lineWidth + 8)
        val lines = (layout as? TextLayoutResult.Aligned)?.lineCount ?: 3
        val fittedScale = minOf(scale, maxHeight / (.25f * lines))
        return GraveInscriptionPlacement(centerY - .125 * lines * fittedScale, fittedScale)
    }
}

internal data class GraveInscriptionPlacement(val bottomY: Double, val scale: Float)

internal object GraveModels {
    /** Yamipa's two north-facing map tiles cover x=[-.5,.5], y=[0,2]. */
    const val IMAGE_FRONT_Z = -.53125
    private const val FRONT_Z = -.5
    private const val TRIM_Z = -.552
    // The artwork leaves its lower quarter black for independently chosen last words.
    private val inscription = GraveInscription(0.0, .37, -.565, .34f, 92, 0xE8DFD2, .22f)
    // The mourning ribbon rests on the outer side, clear of the map and its front frame.
    private val mourningRibbon = listOf(
        GraveModelPart(Material.GRAY_WOOL, .499, 1.82, -.525, .020f, .080f, .400f, rotationX = 28f),
        GraveModelPart(Material.BLACK_WOOL, .514, 1.83, -.516, .012f, .060f, .382f, rotationX = 28f),
    )

    val slate = GraveModel(
        "slate",
        listOf(
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.49, .01, FRONT_Z, .98f, 1.98f, .34f),
            GraveModelPart(Material.COBBLED_DEEPSLATE, -.72, .0, -.70, 1.44f, .20f, 1.15f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.62, .20, -.515, 1.24f, .16f, .945f),
            GraveModelPart(Material.DEEPSLATE_TILES, -.5, .09, TRIM_Z, .045f, 1.74f, .08f),
            GraveModelPart(Material.DEEPSLATE_TILES, .455, .09, TRIM_Z, .045f, 1.74f, .08f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.5, 1.94, TRIM_Z, 1f, .06f, .39f),
            GraveModelPart(Material.CHISELED_DEEPSLATE, -.5, 1.83, TRIM_Z, .08f, .11f, .10f),
            GraveModelPart(Material.CHISELED_DEEPSLATE, .42, 1.83, TRIM_Z, .08f, .11f, .10f),
        ) + mourningRibbon,
        inscription,
    )

    val obelisk = GraveModel(
        "obelisk",
        listOf(
            GraveModelPart(Material.QUARTZ_BLOCK, -.49, .01, FRONT_Z, .98f, 1.98f, .38f),
            GraveModelPart(Material.QUARTZ_BRICKS, -.70, .0, -.69, 1.40f, .18f, 1.14f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.60, .18, -.515, 1.20f, .18f, .92f),
            GraveModelPart(Material.GOLD_BLOCK, -.69, .15, -.697, 1.38f, .018f, .045f),
            GraveModelPart(Material.QUARTZ_PILLAR, -.5, .103, TRIM_Z, .055f, 1.837f, .12f),
            GraveModelPart(Material.QUARTZ_PILLAR, .445, .103, TRIM_Z, .055f, 1.837f, .12f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.5, 1.94, TRIM_Z, 1f, .06f, .43f),
            GraveModelPart(Material.GOLD_BLOCK, -.445, 1.925, TRIM_Z, .89f, .015f, .055f),
        ) + mourningRibbon,
        inscription,
    )

    val cairn = GraveModel(
        "cairn",
        listOf(
            GraveModelPart(Material.STONE, -.49, .01, FRONT_Z, .98f, 1.98f, .44f),
            GraveModelPart(Material.MOSSY_COBBLESTONE, -.74, .0, -.70, 1.48f, .20f, 1.20f),
            GraveModelPart(Material.STONE_BRICKS, -.63, .20, -.51, 1.26f, .18f, 1.00f),
            GraveModelPart(Material.MOSSY_STONE_BRICKS, -.5, .10, TRIM_Z, .065f, .60f, .16f),
            GraveModelPart(Material.COBBLESTONE, -.5, .70, TRIM_Z, .045f, .70f, .11f),
            GraveModelPart(Material.MOSSY_STONE_BRICKS, -.5, 1.40, TRIM_Z, .065f, .54f, .16f),
            GraveModelPart(Material.COBBLESTONE, .445, .10, TRIM_Z, .055f, 1.84f, .13f),
            GraveModelPart(Material.STONE_BRICKS, -.5, 1.94, TRIM_Z, 1f, .06f, .49f),
            GraveModelPart(Material.MOSS_BLOCK, -.49, 1.87, -.558, .065f, .07f, .10f),
            GraveModelPart(Material.MOSS_BLOCK, .435, .10, -.558, .055f, .16f, .07f),
        ) + mourningRibbon,
        inscription,
    )

    val timber = GraveModel(
        "timber",
        listOf(
            GraveModelPart(Material.SMOOTH_SANDSTONE, -.49, .01, FRONT_Z, .98f, 1.98f, .38f),
            GraveModelPart(Material.RED_SANDSTONE, -.71, .0, -.68, 1.42f, .18f, 1.10f),
            GraveModelPart(Material.CUT_SANDSTONE, -.61, .18, -.51, 1.22f, .18f, .90f),
            GraveModelPart(Material.CUT_SANDSTONE, -.5, .10, TRIM_Z, .06f, 1.84f, .11f),
            GraveModelPart(Material.CUT_SANDSTONE, .44, .10, TRIM_Z, .06f, 1.84f, .11f),
            GraveModelPart(Material.SMOOTH_SANDSTONE, -.5, 1.94, TRIM_Z, 1f, .06f, .43f),
            GraveModelPart(Material.CHISELED_SANDSTONE, -.44, 1.88, TRIM_Z, .14f, .06f, .08f),
            GraveModelPart(Material.CHISELED_SANDSTONE, .30, 1.88, TRIM_Z, .14f, .06f, .08f),
        ) + mourningRibbon,
        inscription,
    )

    val broken = GraveModel(
        "broken",
        listOf(
            GraveModelPart(Material.POLISHED_BLACKSTONE, -.49, .01, FRONT_Z, .98f, 1.98f, .34f),
            GraveModelPart(Material.BLACKSTONE, -.73, .0, -.69, 1.46f, .20f, 1.15f),
            GraveModelPart(Material.POLISHED_BLACKSTONE_BRICKS, -.60, .20, -.51, 1.20f, .14f, .94f),
            GraveModelPart(Material.OXIDIZED_COPPER, -.71, .165, -.697, 1.42f, .018f, .035f),
            GraveModelPart(Material.POLISHED_BLACKSTONE_BRICKS, -.5, .114, TRIM_Z, .05f, 1.826f, .11f),
            GraveModelPart(Material.OXIDIZED_COPPER, .465, .114, TRIM_Z, .035f, 1.18f, .065f),
            GraveModelPart(Material.BLACKSTONE, .45, 1.36, TRIM_Z, .05f, .58f, .10f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, -.5, 1.94, TRIM_Z, .46f, .06f, .39f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, .04, 1.94, TRIM_Z, .46f, .04f, .36f),
        ) + mourningRibbon,
        inscription,
    )

    val all = listOf(slate, obelisk, cairn, timber, broken)
}

internal data class GraveAppearanceSettings(
    val weights: Map<String, Double> = DEFAULT_WEIGHTS,
    val epitaphs: List<String> = DEFAULT_EPITAPHS,
) {
    companion object {
        val DEFAULT_WEIGHTS = linkedMapOf("slate" to 35.0, "obelisk" to 15.0, "cairn" to 20.0, "timber" to 20.0, "broken" to 10.0)
        val DEFAULT_EPITAPHS: List<String> = emptyList()
    }
}

internal data class GraveAppearance(val model: GraveModel, val epitaph: String, val yaw: Float)

/** Pure cosmetics: identical grave UUID and configuration produce the same scene after reconnects. */
internal object GraveAppearances {
    fun select(id: UUID, settings: GraveAppearanceSettings): GraveAppearance {
        val weights = GraveModels.all.map { model -> settings.weights[model.id]?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
        val max = weights.maxOrNull()?.takeIf { it > 0.0 }
        val model = if (max == null) GraveModels.slate else {
            // Normalization keeps even individually valid, very large configured weights finite.
            val normalized = weights.map { it / max }
            var remaining = fraction(id, "model") * normalized.sum()
            GraveModels.all.indices.firstOrNull { index ->
                remaining -= normalized[index]
                remaining < 0.0
            }?.let(GraveModels.all::get) ?: GraveModels.all[weights.indexOfLast { it > 0.0 }]
        }
        val epitaphs = settings.epitaphs.filter(String::isNotBlank)
        val epitaph = if (epitaphs.isEmpty()) "" else epitaphs[(fraction(id, "epitaph") * epitaphs.size).toInt()]
        return GraveAppearance(model, epitaph, (fraction(id, "front") * 4).toInt() * 90f)
    }

    private fun fraction(id: UUID, channel: String): Double =
        (UUID.nameUUIDFromBytes("arc:grave:$channel:$id".toByteArray(Charsets.UTF_8)).mostSignificantBits ushr 11).toDouble() / 9007199254740992.0
}
