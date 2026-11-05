package ru.arc.graves

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** Static geometry in metres, centred on the anchor block; its front faces local −Z. */
internal data class GraveModel(
    val id: String,
    val body: List<GraveModelPart>,
    val inscription: GraveInscription,
    val engraved: Boolean = false,
) {
    /** The same inscription bounds own its opaque support, including when an image is unavailable. */
    val parts: List<GraveModelPart> = body + inscription.backing
}

/** Lower corner plus dimensions. Optional XYZ rotations act around this cuboid's centre. */
internal data class GraveModelPart(
    val material: Material,
    val x: Double, val y: Double, val z: Double,
    val width: Float, val height: Float, val depth: Float,
    val rotationX: Float = 0f, val rotationY: Float = 0f, val rotationZ: Float = 0f,
)

/** Centre of a fixed inscription plane; rotationX tilts its local front upward. */
internal data class GraveInscription(
    val x: Double, val centerY: Double, val z: Double,
    val scale: Float,
    val lineWidth: Int,
    val color: Int,
    val maxHeight: Float = .28f,
    val rotationX: Float = 0f,
    val signatureScale: Float = .22f,
    val backingMaterial: Material = Material.SMOOTH_STONE,
    val backingDepth: Float = .028f,
) {
    val backing: GraveModelPart
        get() {
            val angle = Math.toRadians(rotationX.toDouble())
            val width = .025f * lineWidth * scale + 2 * MARGIN
            val height = maxHeight + 2 * MARGIN
            val inset = TEXT_GAP + backingDepth / 2
            return GraveModelPart(backingMaterial, x - width / 2,
                centerY - sin(angle) * inset - height / 2,
                z + cos(angle) * inset - backingDepth / 2,
                width, height, backingDepth, rotationX = rotationX)
        }

    fun placements(epitaph: String, ownerName: String? = null, includeSignature: Boolean = true): List<GraveInscriptionPlacement> {
        // Bedrock names may add a dot before their 16-character account name.
        val name = ownerName?.takeIf(String::isNotBlank)?.take(17) ?: "Игрок"
        val author = "— $name"
        val text = "«$epitaph»" + if (includeSignature) "\n$author" else ""
        // Never guess the height of an unknown legacy glyph: it could write over the surrounding model.
        if (DialogTextLayout.naturalWidth(Component.text(text)) == null) return emptyList()
        val width = if (includeSignature) maxOf(lineWidth, checkNotNull(DialogTextLayout.naturalWidth(Component.text(author)))) else lineWidth
        fun lineCount(value: String, wrapWidth: Int): Int? =
            (DialogTextLayout.layout(Component.text(value), TextAlignment.LEFT, wrapWidth + 8) as? TextLayoutResult.Aligned)?.lineCount
        val lines = lineCount(text, width) ?: return emptyList()
        var fittedScale = minOf(scale, lineWidth * scale / width, maxHeight / (.25f * lines))
        var combined = GraveInscriptionPlacement(text, centerY - .125 * lines * fittedScale, fittedScale, width)
        // Long custom text can use a wider wrap instead of shrinking into a narrow column.
        var candidateWidth = width + 1
        while (candidateWidth <= 1016 && lineWidth * scale / candidateWidth > fittedScale) {
            val candidateLines = lineCount(text, candidateWidth) ?: break
            val candidateScale = minOf(scale, lineWidth * scale / candidateWidth, maxHeight / (.25f * candidateLines))
            if (candidateScale > fittedScale) {
                fittedScale = candidateScale
                combined = GraveInscriptionPlacement(text, centerY - .125 * candidateLines * fittedScale,
                    fittedScale, candidateWidth)
            }
            candidateWidth++
        }
        if (width == lineWidth) return listOf(combined)

        // A long nickname must not shrink the quote on the narrow original plaques.
        val authorRows = mutableListOf<String>()
        var row = "— "
        name.forEach { character ->
            if ((DialogTextLayout.naturalWidth(Component.text(row + character)) ?: 0) > lineWidth) {
                authorRows += row
                row = ""
            }
            row += character
        }
        authorRows += row
        val authorScale = minOf(scale, signatureScale)
        val authorHeight = .25f * authorScale * authorRows.size
        val quoteHeight = maxHeight - authorHeight - .01f
        val quote = "«$epitaph»"
        val quoteLines = lineCount(quote, lineWidth) ?: return emptyList()
        val quoteScale = minOf(scale, quoteHeight / (.25f * quoteLines))
        if (quoteScale <= fittedScale) return listOf(combined)
        val bottom = centerY - maxHeight / 2
        return listOf(
            GraveInscriptionPlacement(quote, bottom + authorHeight + .01 + (quoteHeight - .25f * quoteLines * quoteScale) / 2, quoteScale, lineWidth),
            GraveInscriptionPlacement(authorRows.joinToString("\n"), bottom, authorScale, lineWidth),
        )
    }

    companion object {
        const val MARGIN = .02f
        const val TEXT_GAP = .008f
    }
}

internal data class GraveInscriptionPlacement(val text: String, val bottomY: Double, val scale: Float, val lineWidth: Int)

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
            GraveModelPart(Material.CHISELED_STONE_BRICKS, -.09, .83, -.125, .18f, .18f, .035f),
            GraveModelPart(Material.MOSS_BLOCK, -.33, .23, -.28, .20f, .025f, .16f),
            GraveModelPart(Material.POPPY, -.36, .255, -.35, .26f, .26f, .26f),
            GraveModelPart(Material.COBBLED_DEEPSLATE, .18, .23, -.27, .13f, .065f, .12f),
        ),
        GraveInscription(0.0, .59, -.124, .34f, 60, 0x343638, .35f),
    )

    val obelisk = GraveModel(
        "obelisk",
        listOf(
            GraveModelPart(Material.SMOOTH_QUARTZ, -.38, .0, -.35, .76f, .11f, .70f),
            GraveModelPart(Material.QUARTZ_BRICKS, -.31, .11, -.28, .62f, .11f, .56f),
            GraveModelPart(Material.GOLD_BLOCK, -.25, .22, -.22, .50f, .018f, .44f),
            GraveModelPart(Material.QUARTZ_BLOCK, -.24, .238, -.19, .48f, .762f, .38f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.17, 1.0, -.14, .34f, .12f, .28f),
            GraveModelPart(Material.SMOOTH_QUARTZ, -.075, 1.08, -.10, .15f, .15f, .20f, rotationZ = 45f),
            GraveModelPart(Material.CHISELED_QUARTZ_BLOCK, -.115, .84, -.211, .23f, .14f, .021f),
        ),
        GraveInscription(0.0, .585, -.213, .28f, 60, 0x514D49, .38f, backingMaterial = Material.SMOOTH_QUARTZ),
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
        ),
        // The low front face is foreshortened from player height; keep the engraving on the clear lid.
        GraveInscription(.08, .565, -.100, .34f, 60, 0x171A1B, .47f, rotationX = 90f, signatureScale = .30f),
    )

    val timber = GraveModel(
        "timber",
        listOf(
            GraveModelPart(Material.MOSSY_COBBLESTONE, -.39, .0, -.38, .78f, .13f, .76f),
            GraveModelPart(Material.COARSE_DIRT, -.28, .13, -.27, .56f, .08f, .54f),
            GraveModelPart(Material.STRIPPED_SPRUCE_LOG, -.08, .21, -.075, .16f, .97f, .15f),
            GraveModelPart(Material.STRIPPED_SPRUCE_LOG, -.08, .51, -.09, .16f, .68f, .18f, rotationZ = 90f),
            GraveModelPart(Material.DARK_OAK_WOOD, -.025, .89, -.092, .05f, .05f, .017f),
            GraveModelPart(Material.COBBLESTONE, -.28, .21, -.24, .15f, .11f, .15f),
            GraveModelPart(Material.COBBLESTONE, .13, .21, .10, .14f, .085f, .14f),
            GraveModelPart(Material.DEAD_BUSH, .12, .21, -.26, .21f, .24f, .21f),
        ),
        GraveInscription(0.0, .52, -.138, .28f, 76, 0x3C2C20, .28f,
            backingMaterial = Material.STRIPPED_OAK_WOOD, backingDepth = .055f),
    )

    val broken = GraveModel(
        "broken",
        listOf(
            GraveModelPart(Material.BLACKSTONE, -.43, .0, -.35, .86f, .14f, .70f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, -.27, .14, -.15, .54f, .71f, .30f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, -.25, .82, -.14, .23f, .20f, .28f, rotationZ = -12f),
            GraveModelPart(Material.POLISHED_BLACKSTONE, .02, .82, -.14, .20f, .11f, .28f, rotationZ = 10f),
            GraveModelPart(Material.OXIDIZED_COPPER, -.29, .21, -.173, .58f, .035f, .346f),
            GraveModelPart(Material.OXIDIZED_COPPER, -.29, .26, .138, .035f, .34f, .035f),
            GraveModelPart(Material.BLACKSTONE, .22, .14, -.28, .16f, .11f, .13f, rotationY = 25f),
        ),
        GraveInscription(0.0, .55, -.181, .28f, 60, 0xDDD4C0, .36f, backingMaterial = Material.GRAY_CONCRETE),
    )

    /** Yamipa's two north-facing map tiles cover x=[-.5,.5], y=[0,2]. */
    const val IMAGE_FRONT_Z = -.53125
    private const val FRONT_Z = -.5
    private const val TRIM_Z = -.552
    // A solid plaque covers the artwork's empty lower quarter; it also survives missing map tiles.
    private val inscription = GraveInscription(0.0, .37, -.565, .34f, 92, 0xE8DFD2, .22f,
        backingMaterial = Material.BLACK_CONCRETE, backingDepth = .060f)
    // The mourning ribbon rests on the outer side, clear of the map and its front frame.
    private val mourningRibbon = listOf(
        GraveModelPart(Material.GRAY_WOOL, .499, 1.82, -.525, .020f, .080f, .400f, rotationX = 28f),
        GraveModelPart(Material.BLACK_WOOL, .514, 1.83, -.516, .012f, .060f, .382f, rotationX = 28f),
    )

    val engravedSlate = GraveModel(
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
        engraved = true,
    )

    val engravedObelisk = GraveModel(
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
        engraved = true,
    )

    val engravedCairn = GraveModel(
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
        engraved = true,
    )

    val engravedTimber = GraveModel(
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
        engraved = true,
    )

    val engravedBroken = GraveModel(
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
        engraved = true,
    )

    /** Both geometries share the existing style IDs and their configured weights. */
    val originals = listOf(slate, obelisk, cairn, timber, broken)
    val engraved = listOf(engravedSlate, engravedObelisk, engravedCairn, engravedTimber, engravedBroken)
    val all = originals + engraved
}

internal data class GraveAppearanceSettings(
    val weights: Map<String, Double> = DEFAULT_WEIGHTS,
    val epitaphs: List<String> = DEFAULT_EPITAPHS,
    val engravedChance: Double = DEFAULT_ENGRAVED_CHANCE,
) {
    companion object {
        const val DEFAULT_ENGRAVED_CHANCE = .5
        val DEFAULT_WEIGHTS = linkedMapOf("slate" to 35.0, "obelisk" to 15.0, "cairn" to 20.0, "timber" to 20.0, "broken" to 10.0)
        val DEFAULT_EPITAPHS: List<String> = emptyList()
    }
}

internal data class GraveAppearance(val model: GraveModel, val epitaph: String, val yaw: Float)

/** Pure cosmetics: identical grave UUID and configuration produce the same scene after reconnects. */
internal object GraveAppearances {
    fun select(id: UUID, settings: GraveAppearanceSettings, engravingsAvailable: Boolean = true,
               choice: GraveAppearanceChoice? = null): GraveAppearance {
        val weights = GraveModels.originals.map { model -> settings.weights[model.id]?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
        val max = weights.maxOrNull()?.takeIf { it > 0.0 }
        val model = GraveModels.originals.firstOrNull { it.id == choice?.modelId } ?: if (max == null) GraveModels.slate else {
            // Normalization keeps even individually valid, very large configured weights finite.
            val normalized = weights.map { it / max }
            var remaining = fraction(id, "model") * normalized.sum()
            GraveModels.originals.indices.firstOrNull { index ->
                remaining -= normalized[index]
                remaining < 0.0
            }?.let(GraveModels.originals::get) ?: GraveModels.originals[weights.indexOfLast { it > 0.0 }]
        }
        val chance = settings.engravedChance.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            ?: GraveAppearanceSettings.DEFAULT_ENGRAVED_CHANCE
        val selected = if (engravingsAvailable && (choice?.engraved ?: (fraction(id, "engraving") < chance)))
            GraveModels.engraved.first { it.id == model.id } else model
        val epitaphs = settings.epitaphs.filter(String::isNotBlank).distinct()
        val epitaph = choice?.epitaph ?: if (epitaphs.isEmpty()) "" else epitaphs[(fraction(id, "epitaph") * epitaphs.size).toInt()]
        return GraveAppearance(selected, epitaph, (fraction(id, "front") * 4).toInt() * 90f)
    }

    private fun fraction(id: UUID, channel: String): Double =
        (UUID.nameUUIDFromBytes("arc:grave:$channel:$id".toByteArray(Charsets.UTF_8)).mostSignificantBits ushr 11).toDouble() / 9007199254740992.0
}
