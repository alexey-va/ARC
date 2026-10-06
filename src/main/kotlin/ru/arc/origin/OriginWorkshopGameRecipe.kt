package ru.arc.origin

import org.bukkit.Material

/** A deliberate player input at a real station-local point. */
internal data class OriginWorkshopRecipeInteraction(
    val action: OriginWorkshopGameAction,
    val target: OriginWorkshopPoint,
    val control: String?,
    val nextStage: OriginWorkshopGameStage,
)

/** A machine-owned operation; player input is required before and after this stage. */
internal data class OriginWorkshopTimedStep(
    val nextStage: OriginWorkshopGameStage,
    val durationTicks: Long,
    val machine: String?,
    val target: OriginWorkshopPoint,
    val control: String?,
)

/** Data for the one shared game controller. Materials are visual packet props, never inventory items. */
internal data class OriginWorkshopGameRecipe(
    val role: OriginWorkshopTableRole,
    val productId: String,
    val title: String,
    val totalSteps: Int,
    val initialStage: OriginWorkshopGameStage,
    val resultAnchor: OriginWorkshopPoint,
    val interactions: Map<OriginWorkshopGameStage, OriginWorkshopRecipeInteraction>,
    val timedStages: Map<OriginWorkshopGameStage, OriginWorkshopTimedStep>,
    val materials: Map<OriginWorkshopGameAction, Material>,
    val partSizes: Map<OriginWorkshopGameAction, OriginWorkshopGamePartSize>,
) {
    init {
        require(productId.matches(Regex("[a-z0-9_:-]+")))
        require(title.isNotBlank() && totalSteps in 1..OriginWorkshopGameStage.TOTAL_STEPS)
        require(interactions.keys.intersect(timedStages.keys).isEmpty())
        require(materials.keys == partSizes.keys)
    }
}

/** Shared single-controller recipes for the four staffed furniture stations. */
internal fun originWorkshopGameRecipe(
    role: OriginWorkshopTableRole,
    productId: String,
    dimensions: OriginWorkshopTableDimensions,
    tuning: OriginWorkshopMachineTuning,
    stock: OriginWorkshopPoint,
    rules: OriginWorkshopGameRules,
    workpieceRenderer: OriginWorkshopWorkpieceRenderer = OriginWorkshopWorkpieceRenderer.MODEL,
): OriginWorkshopGameRecipe {
    fun point(x: Double, y: Double, z: Double) = OriginWorkshopPoint(x, y, z)
    fun interaction(
        stage: OriginWorkshopGameStage,
        action: OriginWorkshopGameAction,
        target: OriginWorkshopPoint,
        control: String?,
        next: OriginWorkshopGameStage,
    ) = stage to OriginWorkshopRecipeInteraction(action, target, control, next)
    fun timed(
        stage: OriginWorkshopGameStage,
        next: OriginWorkshopGameStage,
        duration: Long,
        machine: String?,
        target: OriginWorkshopPoint,
        control: String?,
    ) = stage to OriginWorkshopTimedStep(next, duration, machine, target, control)

    val h = dimensions.height
    val carpenterBoard = OriginWorkshopGamePartSize(1.0f, 0.08f, 0.22f)
    val leg = OriginWorkshopGamePartSize(0.14f, 0.52f, 0.14f)
    val interactions: Map<OriginWorkshopGameStage, OriginWorkshopRecipeInteraction>
    val timed: Map<OriginWorkshopGameStage, OriginWorkshopTimedStep>
    val materials: Map<OriginWorkshopGameAction, Material>
    val partSizes: Map<OriginWorkshopGameAction, OriginWorkshopGamePartSize>
    val totalSteps: Int
    val initialStage: OriginWorkshopGameStage
    val title: String
    val resultAnchor: OriginWorkshopPoint

    when (role) {
        OriginWorkshopTableRole.CARPENTER -> {
            val boardZ = tuning.sawPivotZ + 0.075
            val sawInput = point(tuning.sawFeedStartX, h + 0.17, boardZ)
            val sawOutput = point(tuning.sawFeedStartX + tuning.sawFeedDistance, h + 0.17, boardZ)
            val sawControl = point(-1.65, h + 0.34, -0.70)
            val drillHoleEdge = if (workpieceRenderer == OriginWorkshopWorkpieceRenderer.CUBES) 0.24 else 0.23
            val drillInput = point(drillHoleEdge, h + 0.14, -0.55)
            val drillCenter = point(0.0, h + 0.14, -0.55)
            val drillLast = point(-drillHoleEdge, h + 0.14, -0.55)
            val drillControl = point(0.30, h + 0.58, -0.42)
            val assembly = point(1.35, h + 0.035, -0.35)
            val leftLeg = point(1.30, h + 0.07, 0.50)
            val rightLeg = point(1.75, h + 0.07, 0.50)
            val clampLeft = point(0.95, h + 0.35, -0.65)
            val clampRight = point(1.95, h + 0.35, -0.65)
            interactions = listOf(
                interaction(OriginWorkshopGameStage.STOCK, OriginWorkshopGameAction.PICK_STOCK, stock, "stock", OriginWorkshopGameStage.CARRY_RAW_TO_SAW),
                interaction(OriginWorkshopGameStage.CARRY_RAW_TO_SAW, OriginWorkshopGameAction.PLACE_SAW, sawInput, null, OriginWorkshopGameStage.START_SAW),
                interaction(OriginWorkshopGameStage.START_SAW, OriginWorkshopGameAction.ACTIVATE_SAW, sawControl, "saw", OriginWorkshopGameStage.SAWING),
                interaction(OriginWorkshopGameStage.SAW_REPOSITION, OriginWorkshopGameAction.REPOSITION_SAW, sawOutput, null, OriginWorkshopGameStage.START_SAW_SECOND),
                interaction(OriginWorkshopGameStage.START_SAW_SECOND, OriginWorkshopGameAction.ACTIVATE_SAW, sawControl, "saw", OriginWorkshopGameStage.SAWING_SECOND),
                interaction(OriginWorkshopGameStage.PICK_SAWN_BOARD, OriginWorkshopGameAction.PICK_SAWN_BOARD, sawOutput, null, OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL),
                interaction(OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL, OriginWorkshopGameAction.PLACE_DRILL, drillInput, null, OriginWorkshopGameStage.START_DRILL),
                interaction(OriginWorkshopGameStage.START_DRILL, OriginWorkshopGameAction.ACTIVATE_DRILL, drillControl, "drill", OriginWorkshopGameStage.DRILLING),
                interaction(OriginWorkshopGameStage.DRILL_ALIGN_CENTER, OriginWorkshopGameAction.ALIGN_DRILL_CENTER, drillCenter, null, OriginWorkshopGameStage.START_DRILL_SECOND),
                interaction(OriginWorkshopGameStage.START_DRILL_SECOND, OriginWorkshopGameAction.ACTIVATE_DRILL, drillControl, "drill", OriginWorkshopGameStage.DRILLING_SECOND),
                interaction(OriginWorkshopGameStage.DRILL_ALIGN_LAST, OriginWorkshopGameAction.ALIGN_DRILL_LAST, drillLast, null, OriginWorkshopGameStage.START_DRILL_THIRD),
                interaction(OriginWorkshopGameStage.START_DRILL_THIRD, OriginWorkshopGameAction.ACTIVATE_DRILL, drillControl, "drill", OriginWorkshopGameStage.DRILLING_THIRD),
                interaction(OriginWorkshopGameStage.PICK_DRILLED_BOARD, OriginWorkshopGameAction.PICK_DRILLED_BOARD, drillLast, null, OriginWorkshopGameStage.CARRY_BOARD_TO_JIG),
                interaction(OriginWorkshopGameStage.CARRY_BOARD_TO_JIG, OriginWorkshopGameAction.PLACE_JIG, assembly, null, OriginWorkshopGameStage.LEG_LEFT),
                interaction(OriginWorkshopGameStage.LEG_LEFT, OriginWorkshopGameAction.PICK_LEFT_LEG, leftLeg, "leg-left", OriginWorkshopGameStage.CARRY_LEG_LEFT),
                interaction(OriginWorkshopGameStage.CARRY_LEG_LEFT, OriginWorkshopGameAction.PLACE_LEFT_LEG, point(1.13, h + 0.33, -0.35), null, OriginWorkshopGameStage.LEG_RIGHT),
                interaction(OriginWorkshopGameStage.LEG_RIGHT, OriginWorkshopGameAction.PICK_RIGHT_LEG, rightLeg, "leg-right", OriginWorkshopGameStage.CARRY_LEG_RIGHT),
                interaction(OriginWorkshopGameStage.CARRY_LEG_RIGHT, OriginWorkshopGameAction.PLACE_RIGHT_LEG, point(1.57, h + 0.33, -0.35), null, OriginWorkshopGameStage.CLAMP_LEFT),
                interaction(OriginWorkshopGameStage.CLAMP_LEFT, OriginWorkshopGameAction.TIGHTEN_LEFT, clampLeft, "clamp-left", OriginWorkshopGameStage.CLAMPING_LEFT),
                interaction(OriginWorkshopGameStage.CLAMP_RIGHT, OriginWorkshopGameAction.TIGHTEN_RIGHT, clampRight, "clamp-right", OriginWorkshopGameStage.CLAMPING_RIGHT),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.SAWING, OriginWorkshopGameStage.SAW_REPOSITION, rules.sawTicks, "saw", sawOutput, null),
                timed(OriginWorkshopGameStage.SAWING_SECOND, OriginWorkshopGameStage.PICK_SAWN_BOARD, rules.sawTicks, "saw", sawOutput, null),
                timed(OriginWorkshopGameStage.DRILLING, OriginWorkshopGameStage.DRILL_ALIGN_CENTER, rules.drillTicks, "drill", drillInput, null),
                timed(OriginWorkshopGameStage.DRILLING_SECOND, OriginWorkshopGameStage.DRILL_ALIGN_LAST, rules.drillTicks, "drill", drillCenter, null),
                timed(OriginWorkshopGameStage.DRILLING_THIRD, OriginWorkshopGameStage.PICK_DRILLED_BOARD, rules.drillTicks, "drill", drillLast, null),
                timed(OriginWorkshopGameStage.CLAMPING_LEFT, OriginWorkshopGameStage.CLAMP_RIGHT, rules.clampTicks, "clamp-left", clampLeft, null),
                timed(OriginWorkshopGameStage.CLAMPING_RIGHT, OriginWorkshopGameStage.FINISHING, rules.clampTicks, "clamp-right", clampRight, null),
                timed(OriginWorkshopGameStage.FINISHING, OriginWorkshopGameStage.REWARDING, rules.chairHoldTicks, null, assembly, null),
            ).toMap()
            materials = mapOf(
                OriginWorkshopGameAction.PICK_STOCK to Material.OAK_PLANKS,
                OriginWorkshopGameAction.PICK_LEFT_LEG to Material.STRIPPED_SPRUCE_LOG,
                OriginWorkshopGameAction.PICK_RIGHT_LEG to Material.STRIPPED_SPRUCE_LOG,
            )
            partSizes = mapOf(
                OriginWorkshopGameAction.PICK_STOCK to carpenterBoard,
                OriginWorkshopGameAction.PICK_LEFT_LEG to leg,
                OriginWorkshopGameAction.PICK_RIGHT_LEG to leg,
            )
            totalSteps = 21
            initialStage = OriginWorkshopGameStage.STOCK
            title = "Столярная мастерская · белый стул"
            resultAnchor = point(1.35, h + 0.325, -0.35)
        }
        OriginWorkshopTableRole.UPHOLSTERER -> {
            val press = point(tuning.pressCenterX, h + 0.74, tuning.pressCenterZ)
            val fabricBed = point(tuning.pressCenterX, h + 0.74, tuning.pressCenterZ)
            val cushion = point(0.96, h + 0.265, 0.38)
            interactions = listOf(
                interaction(OriginWorkshopGameStage.UPHOLSTER_FABRIC, OriginWorkshopGameAction.PICK_FABRIC, stock, "stock", OriginWorkshopGameStage.UPHOLSTER_PLACE_FABRIC),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PLACE_FABRIC, OriginWorkshopGameAction.PLACE_FABRIC, fabricBed, null, OriginWorkshopGameStage.UPHOLSTER_START_PRESS),
                interaction(OriginWorkshopGameStage.UPHOLSTER_START_PRESS, OriginWorkshopGameAction.ACTIVATE_PRESS, point(tuning.pressCenterX, h + 1.095, tuning.pressCenterZ), "press", OriginWorkshopGameStage.UPHOLSTER_PRESSING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PICK_COVER, OriginWorkshopGameAction.PICK_PRESSED_COVER, press, null, OriginWorkshopGameStage.UPHOLSTER_PLACE_COVER),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PLACE_COVER, OriginWorkshopGameAction.PLACE_CUSHION_COVER, cushion, null, OriginWorkshopGameStage.UPHOLSTER_FINISHING),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.UPHOLSTER_PRESSING, OriginWorkshopGameStage.UPHOLSTER_PICK_COVER, rules.pressTicks, "press", press, "press"),
                timed(OriginWorkshopGameStage.UPHOLSTER_FINISHING, OriginWorkshopGameStage.REWARDING, rules.finishingTicks, null, cushion, null),
            ).toMap()
            materials = mapOf(OriginWorkshopGameAction.PICK_FABRIC to Material.RED_WOOL)
            partSizes = mapOf(OriginWorkshopGameAction.PICK_FABRIC to OriginWorkshopGamePartSize(0.64f, 0.05f, 0.46f))
            totalSteps = 5
            initialStage = OriginWorkshopGameStage.UPHOLSTER_FABRIC
            title = "Обивочная мастерская · красный диван"
            resultAnchor = point(0.96, h + 0.48, 0.38)
        }
        OriginWorkshopTableRole.ASSEMBLER -> {
            val vise = point(tuning.viseCenterX, h + 0.10, tuning.viseCenterZ)
            val anvil = point(tuning.anvilCenterX, h + 0.67, tuning.anvilCenterZ)
            val hammer = point(
                tuning.anvilCenterX,
                h + tuning.hammerPivotYOffset - tuning.hammerArmLength * kotlin.math.sin(Math.toRadians(tuning.hammerRestAngleDegrees)),
                tuning.hammerPivotZ + tuning.hammerArmLength * kotlin.math.cos(Math.toRadians(tuning.hammerRestAngleDegrees)),
            )
            interactions = listOf(
                interaction(OriginWorkshopGameStage.ASSEMBLER_STOCK, OriginWorkshopGameAction.PICK_TABLETOP, stock, "stock", OriginWorkshopGameStage.ASSEMBLER_PLACE_VISE),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_VISE, OriginWorkshopGameAction.PLACE_VISE, vise, null, OriginWorkshopGameStage.ASSEMBLER_START_VISE),
                interaction(OriginWorkshopGameStage.ASSEMBLER_START_VISE, OriginWorkshopGameAction.TIGHTEN_VISE, point(tuning.viseCenterX - 0.70, h + 0.17, tuning.viseCenterZ), "vise", OriginWorkshopGameStage.ASSEMBLER_VISING),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PICK_VISED, OriginWorkshopGameAction.PICK_VISED_TABLETOP, vise, null, OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL, OriginWorkshopGameAction.PLACE_ANVIL, anvil, null, OriginWorkshopGameStage.ASSEMBLER_START_ANVIL),
                interaction(OriginWorkshopGameStage.ASSEMBLER_START_ANVIL, OriginWorkshopGameAction.ACTIVATE_ANVIL, hammer, "anvil", OriginWorkshopGameStage.ASSEMBLER_HAMMERING),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.ASSEMBLER_VISING, OriginWorkshopGameStage.ASSEMBLER_PICK_VISED, rules.viseTicks, "vise", vise, "vise"),
                timed(OriginWorkshopGameStage.ASSEMBLER_HAMMERING, OriginWorkshopGameStage.ASSEMBLER_FINISHING, rules.hammerTicks, "anvil", anvil, "anvil"),
                timed(OriginWorkshopGameStage.ASSEMBLER_FINISHING, OriginWorkshopGameStage.REWARDING, rules.finishingTicks, null, anvil, null),
            ).toMap()
            materials = mapOf(OriginWorkshopGameAction.PICK_TABLETOP to Material.SPRUCE_PLANKS)
            partSizes = mapOf(OriginWorkshopGameAction.PICK_TABLETOP to OriginWorkshopGamePartSize(0.68f, 0.06f, 0.30f))
            totalSteps = 5
            initialStage = OriginWorkshopGameStage.ASSEMBLER_STOCK
            title = "Сборочная мастерская · белый стол"
            resultAnchor = point(tuning.anvilCenterX, h + 0.98, tuning.anvilCenterZ)
        }
        OriginWorkshopTableRole.FINISHER -> {
            val panel = point(1.10, h + 0.52, -0.35)
            val workPanelCenter = point(0.0, h + 0.074, -0.45)
            interactions = listOf(
                interaction(OriginWorkshopGameStage.FINISHER_START_PANEL, OriginWorkshopGameAction.START_FINISH_PANEL, panel, "rack", OriginWorkshopGameStage.FINISHER_WITHDRAWING),
                interaction(OriginWorkshopGameStage.FINISHER_DIP_BRUSH, OriginWorkshopGameAction.DIP_FINISH_BRUSH, point(-1.15, h + 0.30, -0.14), "bath", OriginWorkshopGameStage.FINISHER_COAT_NEAR),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_NEAR, OriginWorkshopGameAction.COAT_PANEL_NEAR, point(0.0, h + 0.074, -0.67), null, OriginWorkshopGameStage.FINISHER_COAT_CENTER),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_CENTER, OriginWorkshopGameAction.COAT_PANEL_CENTER, workPanelCenter, null, OriginWorkshopGameStage.FINISHER_COAT_FAR),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_FAR, OriginWorkshopGameAction.COAT_PANEL_FAR, point(0.0, h + 0.074, -0.23), null, OriginWorkshopGameStage.FINISHER_DRYING),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.FINISHER_WITHDRAWING, OriginWorkshopGameStage.FINISHER_DIP_BRUSH, rules.finishingTicks, "finish", workPanelCenter, null),
                timed(OriginWorkshopGameStage.FINISHER_DRYING, OriginWorkshopGameStage.FINISHER_FINISHING, rules.finishingTicks, "finish", panel, null),
                timed(OriginWorkshopGameStage.FINISHER_FINISHING, OriginWorkshopGameStage.REWARDING, rules.chairHoldTicks, null, panel, null),
            ).toMap()
            materials = mapOf(OriginWorkshopGameAction.START_FINISH_PANEL to Material.OAK_PLANKS)
            partSizes = mapOf(OriginWorkshopGameAction.START_FINISH_PANEL to OriginWorkshopGamePartSize(0.18f, 0.06f, 0.68f))
            totalSteps = 5
            initialStage = OriginWorkshopGameStage.FINISHER_START_PANEL
            title = "Отделочная мастерская · защитное покрытие"
            resultAnchor = point(1.35, h + 0.325, -0.35)
        }
    }

    return OriginWorkshopGameRecipe(
        role, productId, title, totalSteps, initialStage, resultAnchor,
        interactions, timed, materials, partSizes,
    )
}

/** Recipe-backed transitions used by the shared runtime and deterministic role-chain tests. */
internal fun originWorkshopTransition(
    progress: OriginWorkshopGameProgress,
    action: OriginWorkshopGameAction,
    now: Long,
    recipe: OriginWorkshopGameRecipe,
): OriginWorkshopGameProgress? {
    val interaction = recipe.interactions[progress.stage] ?: return null
    if (interaction.action != action) return null
    return OriginWorkshopGameProgress(interaction.nextStage, now)
}

internal fun originWorkshopAdvance(
    progress: OriginWorkshopGameProgress,
    now: Long,
    recipe: OriginWorkshopGameRecipe,
): OriginWorkshopGameProgress {
    val timed = recipe.timedStages[progress.stage] ?: return progress
    if (now - progress.stageStartedAt < timed.durationTicks) return progress
    return OriginWorkshopGameProgress(timed.nextStage, now)
}

/** A drilled board settles at the center of the hole just processed, directly under the fixed spindle. */
internal fun originWorkshopDrillSettleTarget(
    recipe: OriginWorkshopGameRecipe,
    stage: OriginWorkshopGameStage,
): OriginWorkshopPoint? {
    if (recipe.role != OriginWorkshopTableRole.CARPENTER) return null
    return when (stage) {
        OriginWorkshopGameStage.DRILLING,
        OriginWorkshopGameStage.DRILLING_SECOND,
        OriginWorkshopGameStage.DRILLING_THIRD,
        -> recipe.timedStages[stage]?.target
        else -> null
    }
}
