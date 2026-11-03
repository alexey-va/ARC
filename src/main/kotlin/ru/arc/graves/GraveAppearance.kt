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
    val slate = GraveModel(
        "slate",
        listOf(
            GraveModelPart(Material.COBBLED_DEEPSLATE, -.43, .0, -.38, .86f, .13f, .76f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.37, .13, -.30, .74f, .10f, .60f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.34, .23, -.09, .68f, .64f, .25f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.34, .87, -.09, .12f, .10f, .25f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, .22, .87, -.09, .12f, .10f, .25f),
            GraveModelPart(Material.POLISHED_DEEPSLATE, -.22, .87, -.09, .44f, .20f, .25f),
            GraveModelPart(Material.SMOOTH_STONE, -.285, .40, -.116, .57f, .39f, .026f),
            GraveModelPart(Material.CHISELED_STONE_BRICKS, -.09, .83, -.125, .18f, .18f, .035f),
            GraveModelPart(Material.MOSS_BLOCK, -.33, .23, -.28, .20f, .025f, .16f),
            GraveModelPart(Material.POPPY, -.36, .255, -.35, .26f, .26f, .26f),
            GraveModelPart(Material.COBBLED_DEEPSLATE, .18, .23, -.27, .13f, .065f, .12f),
        ),
        GraveInscription(0.0, .59, -.124, .34f, 60, 0x343638),
    )

    val obelisk = GraveModel(
        "obelisk",
        listOf(
            GraveModelPart(Material.SMOOTH_QUARTZ, -.38, .0, -.35, .76f, .11f, .70f),
            GraveModelPart(Material.QUARTZ_BRICKS, -.31, .11, -.28, .62f, .11f, .56f),
            GraveModelPart(Material.GOLD_BLOCK, -.25, .22, -.22, .50f, .018f, .44f),
            GraveModelPart(Material.QUARTZ_BLOCK, -.23, .238, -.19, .46f, .762f, .38f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.17, 1.0, -.14, .34f, .12f, .28f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.075, 1.08, -.10, .15f, .15f, .20f, rotationZ = 45f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.215, .37, -.205, .43f, .43f, .015f),
            GraveModelPart(Material.CHISELED_QUARTZ_BLOCK, -.115, .84, -.211, .23f, .14f, .021f),
        ),
        GraveInscription(0.0, .585, -.213, .28f, 60, 0x514D49),
    )

    val cairn = GraveModel(
        "cairn",
        listOf(
            GraveModelPart(Material.STONE_BRICKS, -.47, .0, -.47, .94f, .10f, .94f),
            GraveModelPart(Material.MOSSY_COBBLESTONE, -.42, .10, -.42, .84f, .30f, .84f),
            GraveModelPart(Material.POLISHED_ANDESITE, -.45, .40, -.45, .90f, .09f, .90f),
            GraveModelPart(Material.STONE, -.37, .49, -.36, .74f, .055f, .72f),
            GraveModelPart(Material.MOSS_BLOCK, -.32, .545, .16, .35f, .025f, .15f),
            GraveModelPart(Material.MOSS_BLOCK, -.32, .545, -.04, .12f, .025f, .20f),
            GraveModelPart(Material.COBBLESTONE, .16, .545, .17, .13f, .06f, .13f),
            GraveModelPart(Material.SMOOTH_STONE, -.33, .105, -.439, .66f, .285f, .019f),
        ),
        GraveInscription(0.0, .2475, -.447, .32f, 76, 0x343638, .26f),
    )

    val timber = GraveModel(
        "timber",
        listOf(
            GraveModelPart(Material.MOSSY_COBBLESTONE, -.39, .0, -.38, .78f, .13f, .76f),
            GraveModelPart(Material.COARSE_DIRT, -.28, .13, -.27, .56f, .08f, .54f),
            GraveModelPart(Material.STRIPPED_SPRUCE_LOG, -.08, .21, -.075, .16f, .97f, .15f),
            GraveModelPart(Material.STRIPPED_SPRUCE_LOG, -.08, .51, -.09, .16f, .68f, .18f, rotationZ = 90f),
            GraveModelPart(Material.STRIPPED_OAK_WOOD, -.30, .36, -.13, .60f, .32f, .055f),
            GraveModelPart(Material.DARK_OAK_WOOD, -.025, .89, -.092, .05f, .05f, .017f),
            GraveModelPart(Material.COBBLESTONE, -.28, .21, -.24, .15f, .11f, .15f),
            GraveModelPart(Material.COBBLESTONE, .13, .21, .10, .14f, .085f, .14f),
            GraveModelPart(Material.DEAD_BUSH, .12, .21, -.26, .21f, .24f, .21f),
        ),
        GraveInscription(0.0, .52, -.138, .28f, 76, 0x3C2C20, .24f),
    )

    val broken = GraveModel(
        "broken",
        listOf(
            GraveModelPart(Material.BLACKSTONE, -.43, .0, -.35, .86f, .14f, .70f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, -.27, .14, -.15, .54f, .71f, .30f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, -.25, .82, -.14, .23f, .20f, .28f, rotationZ = -12f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, .02, .82, -.14, .20f, .11f, .28f, rotationZ = 10f),
            GraveModelPart(Material.SMOOTH_BASALT, -.225, .35, -.173, .45f, .40f, .023f),
            GraveModelPart(Material.OXIDIZED_COPPER, -.29, .21, -.173, .58f, .035f, .346f),
            GraveModelPart(Material.OXIDIZED_COPPER, -.29, .26, .138, .035f, .34f, .035f),
            GraveModelPart(Material.BLACKSTONE, .22, .14, -.28, .16f, .11f, .13f, rotationY = 25f),
        ),
        GraveInscription(0.0, .55, -.181, .28f, 60, 0xDDD4C0),
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
