package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import kotlin.math.abs

class OriginWorkshopGameRecipeTest : FreeSpec({
    "all four recipes require their declared inputs and timed work to reach rewarding" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val rules = OriginWorkshopGameRules(sawTicks = 4, drillTicks = 3, clampTicks = 2, chairHoldTicks = 2,
            pressTicks = 4, viseTicks = 3, hammerTicks = 4, finishingTicks = 2)
        val stock = OriginWorkshopPoint(-7.5, 0.44, 0.30)
        val expectedInputs = mapOf(
            OriginWorkshopTableRole.CARPENTER to listOf(
                OriginWorkshopGameAction.PICK_STOCK,
                OriginWorkshopGameAction.PLACE_SAW,
                OriginWorkshopGameAction.ACTIVATE_SAW,
                OriginWorkshopGameAction.REPOSITION_SAW,
                OriginWorkshopGameAction.ACTIVATE_SAW,
                OriginWorkshopGameAction.PICK_SAWN_BOARD,
                OriginWorkshopGameAction.PLACE_DRILL,
                OriginWorkshopGameAction.ACTIVATE_DRILL,
                OriginWorkshopGameAction.ALIGN_DRILL_CENTER,
                OriginWorkshopGameAction.ACTIVATE_DRILL,
                OriginWorkshopGameAction.ALIGN_DRILL_LAST,
                OriginWorkshopGameAction.ACTIVATE_DRILL,
                OriginWorkshopGameAction.PICK_DRILLED_BOARD,
                OriginWorkshopGameAction.PLACE_JIG,
                OriginWorkshopGameAction.PICK_LEFT_LEG,
                OriginWorkshopGameAction.PLACE_LEFT_LEG,
                OriginWorkshopGameAction.PICK_RIGHT_LEG,
                OriginWorkshopGameAction.PLACE_RIGHT_LEG,
                OriginWorkshopGameAction.TIGHTEN_LEFT,
                OriginWorkshopGameAction.TIGHTEN_RIGHT,
            ),
            OriginWorkshopTableRole.UPHOLSTERER to listOf(
                OriginWorkshopGameAction.PICK_FABRIC,
                OriginWorkshopGameAction.PLACE_FABRIC,
                OriginWorkshopGameAction.STRETCH_FABRIC_LEFT,
                OriginWorkshopGameAction.STRETCH_FABRIC_RIGHT,
                OriginWorkshopGameAction.ACTIVATE_PRESS,
                OriginWorkshopGameAction.TURN_FABRIC,
                OriginWorkshopGameAction.ACTIVATE_PRESS,
                OriginWorkshopGameAction.PICK_PRESSED_COVER,
                OriginWorkshopGameAction.PLACE_CUSHION_COVER,
                OriginWorkshopGameAction.PICK_PADDING,
                OriginWorkshopGameAction.PLACE_PADDING,
                OriginWorkshopGameAction.TUCK_PADDING_NEAR,
                OriginWorkshopGameAction.TUCK_PADDING_FAR,
                OriginWorkshopGameAction.FASTEN_COVER_LEFT,
                OriginWorkshopGameAction.FASTEN_COVER_RIGHT,
            ),
            OriginWorkshopTableRole.ASSEMBLER to listOf(
                OriginWorkshopGameAction.PICK_TABLETOP,
                OriginWorkshopGameAction.PLACE_VISE,
                OriginWorkshopGameAction.TIGHTEN_VISE,
                OriginWorkshopGameAction.ROTATE_TABLETOP,
                OriginWorkshopGameAction.TIGHTEN_VISE,
                OriginWorkshopGameAction.RELEASE_VISE,
                OriginWorkshopGameAction.PICK_VISED_TABLETOP,
                OriginWorkshopGameAction.PLACE_ANVIL,
                OriginWorkshopGameAction.ACTIVATE_ANVIL,
                OriginWorkshopGameAction.ALIGN_JOIN_SECOND,
                OriginWorkshopGameAction.ACTIVATE_ANVIL,
                OriginWorkshopGameAction.PICK_JOINED_TABLETOP,
                OriginWorkshopGameAction.PLACE_ASSEMBLY_TOP,
                OriginWorkshopGameAction.PICK_LEFT_LEG,
                OriginWorkshopGameAction.PLACE_LEFT_LEG,
                OriginWorkshopGameAction.PICK_RIGHT_LEG,
                OriginWorkshopGameAction.PLACE_RIGHT_LEG,
                OriginWorkshopGameAction.TIGHTEN_LEFT,
                OriginWorkshopGameAction.TIGHTEN_RIGHT,
            ),
            OriginWorkshopTableRole.FINISHER to listOf(
                OriginWorkshopGameAction.START_FINISH_PANEL,
                OriginWorkshopGameAction.PICK_ABRASIVE,
                OriginWorkshopGameAction.SAND_PANEL_NEAR,
                OriginWorkshopGameAction.SAND_PANEL_CENTER,
                OriginWorkshopGameAction.SAND_PANEL_FAR,
                OriginWorkshopGameAction.FLIP_PANEL,
                OriginWorkshopGameAction.SAND_PANEL_NEAR,
                OriginWorkshopGameAction.SAND_PANEL_CENTER,
                OriginWorkshopGameAction.SAND_PANEL_FAR,
                OriginWorkshopGameAction.DIP_FINISH_BRUSH,
                OriginWorkshopGameAction.COAT_PANEL_NEAR,
                OriginWorkshopGameAction.COAT_PANEL_CENTER,
                OriginWorkshopGameAction.COAT_PANEL_FAR,
                OriginWorkshopGameAction.FLIP_PANEL,
                OriginWorkshopGameAction.DIP_FINISH_BRUSH,
                OriginWorkshopGameAction.COAT_PANEL_NEAR,
                OriginWorkshopGameAction.COAT_PANEL_CENTER,
                OriginWorkshopGameAction.COAT_PANEL_FAR,
                OriginWorkshopGameAction.HANG_FINISHED_PANEL,
            ),
        )
        val expectedSteps = mapOf(
            OriginWorkshopTableRole.CARPENTER to 21,
            OriginWorkshopTableRole.UPHOLSTERER to 15,
            OriginWorkshopTableRole.ASSEMBLER to 19,
            OriginWorkshopTableRole.FINISHER to 19,
        )
        val expectedTimedStages = mapOf(
            OriginWorkshopTableRole.CARPENTER to listOf(
                OriginWorkshopGameStage.SAWING,
                OriginWorkshopGameStage.SAWING_SECOND,
                OriginWorkshopGameStage.DRILLING,
                OriginWorkshopGameStage.DRILLING_SECOND,
                OriginWorkshopGameStage.DRILLING_THIRD,
                OriginWorkshopGameStage.CLAMPING_LEFT,
                OriginWorkshopGameStage.CLAMPING_RIGHT,
                OriginWorkshopGameStage.FINISHING,
            ),
            OriginWorkshopTableRole.UPHOLSTERER to listOf(
                OriginWorkshopGameStage.UPHOLSTER_PRESSING,
                OriginWorkshopGameStage.UPHOLSTER_PRESSING_SECOND,
                OriginWorkshopGameStage.UPHOLSTER_FINISHING,
            ),
            OriginWorkshopTableRole.ASSEMBLER to listOf(
                OriginWorkshopGameStage.ASSEMBLER_VISING,
                OriginWorkshopGameStage.ASSEMBLER_VISING_SECOND,
                OriginWorkshopGameStage.ASSEMBLER_HAMMERING,
                OriginWorkshopGameStage.ASSEMBLER_HAMMERING_SECOND,
                OriginWorkshopGameStage.ASSEMBLER_CLAMPING_LEFT,
                OriginWorkshopGameStage.ASSEMBLER_CLAMPING_RIGHT,
                OriginWorkshopGameStage.ASSEMBLER_FINISHING,
            ),
            OriginWorkshopTableRole.FINISHER to listOf(
                OriginWorkshopGameStage.FINISHER_WITHDRAWING,
                OriginWorkshopGameStage.FINISHER_DRYING,
                OriginWorkshopGameStage.FINISHER_FINISHING,
            ),
        )

        for (role in OriginWorkshopTableRole.entries) {
            val recipe = originWorkshopGameRecipe(role, "test:${role.key}", dimensions, tuning, stock, rules)
            var progress = OriginWorkshopGameProgress(recipe.initialStage)
            val visited = mutableListOf(progress.stage)
            val inputs = mutableListOf<Pair<OriginWorkshopGameStage, OriginWorkshopGameAction>>()

            repeat(64) {
                if (progress.stage == OriginWorkshopGameStage.REWARDING) return@repeat
                val interaction = recipe.interactions[progress.stage]
                if (interaction != null) {
                    for (wrong in OriginWorkshopGameAction.entries.filter { it != interaction.action }) {
                        originWorkshopTransition(progress, wrong, progress.stageStartedAt + 1, recipe) shouldBe null
                    }
                    inputs += progress.stage to interaction.action
                    progress = requireNotNull(
                        originWorkshopTransition(progress, interaction.action, progress.stageStartedAt + 1, recipe),
                    )
                } else {
                    val timed = requireNotNull(recipe.timedStages[progress.stage]) {
                        "${recipe.role} has no interaction or timed transition at ${progress.stage}"
                    }
                    for (action in OriginWorkshopGameAction.entries) {
                        originWorkshopTransition(progress, action, progress.stageStartedAt + timed.durationTicks, recipe) shouldBe null
                    }
                    originWorkshopAdvance(progress, progress.stageStartedAt + timed.durationTicks - 1, recipe) shouldBe progress
                    progress = originWorkshopAdvance(progress, progress.stageStartedAt + timed.durationTicks, recipe)
                }
                visited += progress.stage
            }

            progress.stage shouldBe OriginWorkshopGameStage.REWARDING
            visited.distinct().size shouldBe visited.size
            inputs.map { it.second } shouldBe expectedInputs.getValue(role)
            recipe.totalSteps shouldBe expectedSteps.getValue(role)
            recipe.timedStages.keys.toList() shouldBe expectedTimedStages.getValue(role)
            recipe.materials.keys shouldBe recipe.partSizes.keys
            recipe.partSizes.values.all { it.x > 0f && it.y > 0f && it.z > 0f } shouldBe true
            when (role) {
                OriginWorkshopTableRole.CARPENTER -> {
                    inputs.count { it.second == OriginWorkshopGameAction.ACTIVATE_SAW } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.REPOSITION_SAW } shouldBe 1
                    inputs.filter { it.second == OriginWorkshopGameAction.ACTIVATE_DRILL }
                        .map { it.first } shouldBe listOf(
                        OriginWorkshopGameStage.START_DRILL,
                        OriginWorkshopGameStage.START_DRILL_SECOND,
                        OriginWorkshopGameStage.START_DRILL_THIRD,
                    )
                    inputs.map { it.second }.count { it == OriginWorkshopGameAction.ALIGN_DRILL_CENTER } shouldBe 1
                    inputs.map { it.second }.count { it == OriginWorkshopGameAction.ALIGN_DRILL_LAST } shouldBe 1
                }
                OriginWorkshopTableRole.UPHOLSTERER -> {
                    inputs.count { it.second == OriginWorkshopGameAction.ACTIVATE_PRESS } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.FASTEN_COVER_LEFT } shouldBe 1
                    inputs.count { it.second == OriginWorkshopGameAction.FASTEN_COVER_RIGHT } shouldBe 1
                }
                OriginWorkshopTableRole.ASSEMBLER -> {
                    inputs.count { it.second == OriginWorkshopGameAction.TIGHTEN_VISE } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.ACTIVATE_ANVIL } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.PICK_LEFT_LEG } shouldBe 1
                    inputs.count { it.second == OriginWorkshopGameAction.PICK_RIGHT_LEG } shouldBe 1
                }
                OriginWorkshopTableRole.FINISHER -> {
                    inputs.count { it.second == OriginWorkshopGameAction.FLIP_PANEL } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.DIP_FINISH_BRUSH } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.COAT_PANEL_NEAR } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.COAT_PANEL_CENTER } shouldBe 2
                    inputs.count { it.second == OriginWorkshopGameAction.COAT_PANEL_FAR } shouldBe 2
                }
            }
        }
    }

    "recipe timed machine identifiers are supported and assembler leg fastening stays local" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val rules = OriginWorkshopGameRules(clampTicks = 7)
        val stock = OriginWorkshopPoint(-7.5, 0.44, 0.30)

        for (role in OriginWorkshopTableRole.entries) {
            val recipe = originWorkshopGameRecipe(role, "test:${role.key}", dimensions, tuning, stock, rules)
            recipe.timedStages.values.mapNotNull { it.machine }.forEach { machine ->
                // This is the same closed dispatcher called by animateCraftMachine at runtime.
                originWorkshopCraftMachinePose(machine, 0.5, dimensions, tuning)
            }
        }

        val assembler = originWorkshopGameRecipe(
            OriginWorkshopTableRole.ASSEMBLER, "test:assembler", dimensions, tuning, stock, rules,
        )
        val leftClamp = assembler.timedStages.getValue(OriginWorkshopGameStage.ASSEMBLER_CLAMPING_LEFT)
        val rightClamp = assembler.timedStages.getValue(OriginWorkshopGameStage.ASSEMBLER_CLAMPING_RIGHT)
        leftClamp.machine shouldBe null
        rightClamp.machine shouldBe null
        leftClamp.durationTicks shouldBe rules.clampTicks
        rightClamp.durationTicks shouldBe rules.clampTicks
        leftClamp.target shouldBe OriginWorkshopPoint(-0.24, dimensions.height + 0.50, -0.45)
        rightClamp.target shouldBe OriginWorkshopPoint(0.24, dimensions.height + 0.50, -0.45)
        assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_LEFT).control shouldBe null
        assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_RIGHT).control shouldBe null

        val leg = originWorkshopAssemblerLegPieces(fastened = false).single()
        val fastenedLeg = originWorkshopAssemblerLegPieces(fastened = true)
        fastenedLeg.size shouldBe 2
        fastenedLeg.first() shouldBe leg
        val pin = fastenedLeg.last()
        pin.material shouldBe Material.IRON_BLOCK
        pin.center shouldBe OriginWorkshopPoint(0.0, 0.24, -0.0875)
        pin.size shouldBe OriginWorkshopGamePartSize(0.035f, 0.035f, 0.035f)
    }

    "noncarpenter workflows use the agreed work surfaces and deliberately supplied materials" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val stock = OriginWorkshopPoint(-7.5, 0.44, 0.30)
        val recipes = OriginWorkshopTableRole.entries.associateWith { role ->
            originWorkshopGameRecipe(role, "test:${role.key}", dimensions, tuning, stock, OriginWorkshopGameRules())
        }

        fun target(role: OriginWorkshopTableRole, stage: OriginWorkshopGameStage) =
            recipes.getValue(role).interactions.getValue(stage).target

        for (role in OriginWorkshopTableRole.entries) {
            recipes.getValue(role).productId shouldBe "test:${role.key}"
        }

        val pressHandle = OriginWorkshopPoint(tuning.pressCenterX - 0.46, dimensions.height + 0.42, tuning.pressCenterZ - 0.45)
        target(OriginWorkshopTableRole.UPHOLSTERER, OriginWorkshopGameStage.UPHOLSTER_START_PRESS) shouldBe pressHandle
        target(OriginWorkshopTableRole.UPHOLSTERER, OriginWorkshopGameStage.UPHOLSTER_START_PRESS_SECOND) shouldBe pressHandle
        target(OriginWorkshopTableRole.UPHOLSTERER, OriginWorkshopGameStage.UPHOLSTER_PICK_PADDING) shouldBe
            OriginWorkshopPoint(stock.x + 0.62, stock.y, stock.z)
        recipes.getValue(OriginWorkshopTableRole.UPHOLSTERER).materials[OriginWorkshopGameAction.PICK_PADDING] shouldBe Material.WHITE_WOOL

        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_PLACE_ASSEMBLY_TOP) shouldBe
            OriginWorkshopPoint(0.0, dimensions.height + 0.55, -0.45)
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG) shouldBe
            OriginWorkshopPoint(-0.24, dimensions.height + 0.26, -0.45)
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_PLACE_RIGHT_LEG) shouldBe
            OriginWorkshopPoint(0.24, dimensions.height + 0.26, -0.45)
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_PICK_LEFT_LEG) shouldBe
            OriginWorkshopPoint(stock.x - 0.55, 0.32, stock.z - 0.55)
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_PICK_RIGHT_LEG) shouldBe
            OriginWorkshopPoint(stock.x + 0.55, 0.32, stock.z - 0.55)
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_START_ANVIL) shouldBe
            OriginWorkshopPoint(
                tuning.anvilCenterX,
                dimensions.height + tuning.hammerPivotYOffset - tuning.hammerArmLength * kotlin.math.sin(Math.toRadians(tuning.hammerRestAngleDegrees)),
                tuning.hammerPivotZ + tuning.hammerArmLength * kotlin.math.cos(Math.toRadians(tuning.hammerRestAngleDegrees)),
            )
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_ALIGN_SECOND).x shouldBe
            tuning.anvilCenterX + 0.18
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_ALIGN_SECOND).y shouldBe
            dimensions.height + 0.67
        target(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameStage.ASSEMBLER_ALIGN_SECOND).z shouldBe tuning.anvilCenterZ
        recipes.getValue(OriginWorkshopTableRole.ASSEMBLER).materials[OriginWorkshopGameAction.PICK_LEFT_LEG] shouldBe
            Material.STRIPPED_SPRUCE_LOG
        recipes.getValue(OriginWorkshopTableRole.ASSEMBLER).materials[OriginWorkshopGameAction.PICK_RIGHT_LEG] shouldBe
            Material.STRIPPED_SPRUCE_LOG

        val finish = recipes.getValue(OriginWorkshopTableRole.FINISHER)
        target(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameStage.FINISHER_START_PANEL) shouldBe
            OriginWorkshopPoint(1.10, dimensions.height + 0.14, -0.42)
        target(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameStage.FINISHER_HANG_PANEL) shouldBe
            OriginWorkshopPoint(1.10, dimensions.height + 0.14, -0.42)
        target(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameStage.FINISHER_SAND_NEAR) shouldBe
            OriginWorkshopPoint(0.0, dimensions.height + 0.074, -0.67)
        target(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameStage.FINISHER_SAND_CENTER) shouldBe
            OriginWorkshopPoint(0.0, dimensions.height + 0.074, -0.45)
        target(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameStage.FINISHER_SAND_FAR) shouldBe
            OriginWorkshopPoint(0.0, dimensions.height + 0.074, -0.23)
        finish.materials[OriginWorkshopGameAction.PICK_ABRASIVE] shouldBe Material.SANDSTONE
        recipes.getValue(OriginWorkshopTableRole.UPHOLSTERER).resultAnchor shouldBe
            OriginWorkshopPoint(0.0, dimensions.height + 0.325, 0.08)
        recipes.getValue(OriginWorkshopTableRole.ASSEMBLER).resultAnchor shouldBe
            OriginWorkshopPoint(0.0, dimensions.height + 0.3371875, -0.45)
        finish.resultAnchor shouldBe OriginWorkshopPoint(0.0, dimensions.height + 0.325, -0.45)
        recipes.getValue(OriginWorkshopTableRole.CARPENTER).totalSteps shouldBe 21
    }

    "recipes use supplied dimensions, machine tuning, and each staffed role's stock anchor" {
        val dimensions = OriginWorkshopTableDimensions(width = 4.6, depth = 2.15, height = 1.12)
        val tuning = OriginWorkshopMachineTuning(
            sawPivotZ = -0.45,
            sawFeedStartX = -1.65,
            sawFeedDistance = 1.15,
            viseCenterX = -0.60,
            viseCenterZ = 0.35,
            anvilCenterX = 1.35,
            anvilCenterZ = 0.20,
            pressCenterX = -0.90,
            pressCenterZ = 0.18,
        )
        val stocks = mapOf(
            OriginWorkshopTableRole.CARPENTER to OriginWorkshopPoint(-7.2, 0.44, 0.3),
            OriginWorkshopTableRole.UPHOLSTERER to OriginWorkshopPoint(7.1, 0.52, -0.4),
            OriginWorkshopTableRole.ASSEMBLER to OriginWorkshopPoint(-6.1, 0.38, 0.7),
            OriginWorkshopTableRole.FINISHER to OriginWorkshopPoint(6.2, 0.46, -0.8),
        )
        val recipes = OriginWorkshopTableRole.entries.associateWith { role ->
            originWorkshopGameRecipe(role, "test:${role.key}", dimensions, tuning, stocks.getValue(role), OriginWorkshopGameRules())
        }

        fun interaction(role: OriginWorkshopTableRole, action: OriginWorkshopGameAction) =
            recipes.getValue(role).interactions.values.first { it.action == action }

        val carpenterSawInput = interaction(OriginWorkshopTableRole.CARPENTER, OriginWorkshopGameAction.PLACE_SAW).target
        carpenterSawInput shouldBe OriginWorkshopPoint(tuning.sawFeedStartX, dimensions.height + 0.17, tuning.sawPivotZ + 0.075)
        recipes.getValue(OriginWorkshopTableRole.CARPENTER).timedStages.getValue(OriginWorkshopGameStage.SAWING).target shouldBe
            OriginWorkshopPoint(tuning.sawFeedStartX + tuning.sawFeedDistance, dimensions.height + 0.17, tuning.sawPivotZ + 0.075)

        interaction(OriginWorkshopTableRole.UPHOLSTERER, OriginWorkshopGameAction.ACTIVATE_PRESS).target shouldBe
            OriginWorkshopPoint(tuning.pressCenterX - 0.46, dimensions.height + 0.42, tuning.pressCenterZ - 0.45)
        interaction(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameAction.PLACE_VISE).target shouldBe
            OriginWorkshopPoint(tuning.viseCenterX, dimensions.height + 0.10, tuning.viseCenterZ)
        interaction(OriginWorkshopTableRole.ASSEMBLER, OriginWorkshopGameAction.PLACE_ANVIL).target shouldBe
            OriginWorkshopPoint(tuning.anvilCenterX, dimensions.height + 0.67, tuning.anvilCenterZ)

        val stockActions = mapOf(
            OriginWorkshopTableRole.CARPENTER to OriginWorkshopGameAction.PICK_STOCK,
            OriginWorkshopTableRole.UPHOLSTERER to OriginWorkshopGameAction.PICK_FABRIC,
            OriginWorkshopTableRole.ASSEMBLER to OriginWorkshopGameAction.PICK_TABLETOP,
        )
        for ((role, action) in stockActions) interaction(role, action).target shouldBe stocks.getValue(role)
        recipes.getValue(OriginWorkshopTableRole.FINISHER).interactions.values.any {
            it.action == OriginWorkshopGameAction.PICK_STOCK || it.action == OriginWorkshopGameAction.START_FINISH_PANEL && it.target == stocks.getValue(OriginWorkshopTableRole.FINISHER)
        } shouldBe false

        recipes.getValue(OriginWorkshopTableRole.CARPENTER).resultAnchor.y shouldBe dimensions.height + 0.325
        recipes.getValue(OriginWorkshopTableRole.UPHOLSTERER).resultAnchor.y shouldBe dimensions.height + 0.325
        recipes.getValue(OriginWorkshopTableRole.ASSEMBLER).resultAnchor.y shouldBe dimensions.height + 0.3371875
        recipes.getValue(OriginWorkshopTableRole.FINISHER).resultAnchor.y shouldBe dimensions.height + 0.325
        abs(interaction(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameAction.START_FINISH_PANEL).target.y - (dimensions.height + 0.14)) shouldBe 0.0
    }

    "each renderer puts the processed board hole under the fixed drill spindle" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val rules = OriginWorkshopGameRules()
        val spindle = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, 0, dimensions, tuning)
            .single { it.key == "carpenter-drill-bit" }
        val drillStages = listOf(
            OriginWorkshopGameStage.DRILLING,
            OriginWorkshopGameStage.DRILLING_SECOND,
            OriginWorkshopGameStage.DRILLING_THIRD,
        )

        for ((renderer, edgeHole) in listOf(
            OriginWorkshopWorkpieceRenderer.MODEL to 0.23,
            OriginWorkshopWorkpieceRenderer.CUBES to 0.24,
        )) {
            val recipe = originWorkshopGameRecipe(
                OriginWorkshopTableRole.CARPENTER, "test:drill-${renderer.configValue}", dimensions, tuning,
                OriginWorkshopPoint(0.0, 0.0, 0.0), rules, renderer,
            )
            val holeCenters = listOf(-edgeHole, 0.0, edgeHole)
            val boardCenters = drillStages.map { stage ->
                val target = originWorkshopDrillSettleTarget(recipe, stage)
                (target != null) shouldBe true
                target!!
            }

            boardCenters.zip(holeCenters).forEach { (boardCenter, holeX) ->
                (abs(boardCenter.x + holeX - spindle.x) < 1e-9) shouldBe true
                boardCenter.z shouldBe spindle.z
            }
            recipe.interactions.getValue(OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL).target.x shouldBe edgeHole
            recipe.interactions.getValue(OriginWorkshopGameStage.DRILL_ALIGN_CENTER).target.x shouldBe 0.0
            recipe.interactions.getValue(OriginWorkshopGameStage.DRILL_ALIGN_LAST).target.x shouldBe -edgeHole
        }
    }

    "the saw feed crosses the full raw cube board and the blade clears both feed endpoints" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val recipe = originWorkshopGameRecipe(
            OriginWorkshopTableRole.CARPENTER, "test:saw-cubes", dimensions, tuning,
            OriginWorkshopPoint(0.0, 0.0, 0.0), OriginWorkshopGameRules(), OriginWorkshopWorkpieceRenderer.CUBES,
        )
        val input = recipe.interactions.getValue(OriginWorkshopGameStage.CARRY_RAW_TO_SAW).target
        val output = recipe.timedStages.getValue(OriginWorkshopGameStage.SAWING).target
        val raw = originWorkshopCoarseBoardPieces(OriginWorkshopBoardModel.RAW)
        val halfLength = raw.maxOf { it.center.x + it.size.x / 2.0 }
        val bladeAtInput = tuning.sawPivotX - input.x
        val bladeAtOutput = tuning.sawPivotX - output.x

        raw.size shouldBe 39
        (bladeAtInput > halfLength) shouldBe true
        (bladeAtOutput < -halfLength) shouldBe true
        (output.x - input.x > 2 * halfLength) shouldBe true
    }
})
