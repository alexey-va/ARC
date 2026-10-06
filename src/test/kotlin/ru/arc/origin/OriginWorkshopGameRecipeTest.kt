package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopGameRecipeTest : FreeSpec({
    "all four recipes require their declared inputs and timed work to reach rewarding" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val rules = OriginWorkshopGameRules(sawTicks = 4, drillTicks = 3, clampTicks = 2, chairHoldTicks = 2,
            pressTicks = 4, viseTicks = 3, hammerTicks = 4, finishingTicks = 2)
        val stock = OriginWorkshopPoint(-7.5, 0.44, 0.30)

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
                OriginWorkshopTableRole.UPHOLSTERER,
                OriginWorkshopTableRole.ASSEMBLER,
                OriginWorkshopTableRole.FINISHER,
                -> Unit
            }
        }
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
            recipes.getValue(role).interactions.values.single { it.action == action }

        val carpenterSawInput = interaction(OriginWorkshopTableRole.CARPENTER, OriginWorkshopGameAction.PLACE_SAW).target
        carpenterSawInput shouldBe OriginWorkshopPoint(tuning.sawFeedStartX, dimensions.height + 0.17, tuning.sawPivotZ + 0.075)
        recipes.getValue(OriginWorkshopTableRole.CARPENTER).timedStages.getValue(OriginWorkshopGameStage.SAWING).target shouldBe
            OriginWorkshopPoint(tuning.sawFeedStartX + tuning.sawFeedDistance, dimensions.height + 0.17, tuning.sawPivotZ + 0.075)

        interaction(OriginWorkshopTableRole.UPHOLSTERER, OriginWorkshopGameAction.ACTIVATE_PRESS).target shouldBe
            OriginWorkshopPoint(tuning.pressCenterX, dimensions.height + 1.095, tuning.pressCenterZ)
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
        recipes.getValue(OriginWorkshopTableRole.UPHOLSTERER).resultAnchor.y shouldBe dimensions.height + 0.48
        recipes.getValue(OriginWorkshopTableRole.ASSEMBLER).resultAnchor.y shouldBe dimensions.height + 0.98
        recipes.getValue(OriginWorkshopTableRole.FINISHER).resultAnchor.y shouldBe dimensions.height + 0.325
        abs(interaction(OriginWorkshopTableRole.FINISHER, OriginWorkshopGameAction.START_FINISH_PANEL).target.y - (dimensions.height + 0.52)) shouldBe 0.0
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
