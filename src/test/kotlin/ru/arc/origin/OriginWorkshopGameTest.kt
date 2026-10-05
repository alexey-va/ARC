package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OriginWorkshopGameTest : FreeSpec({
    val rules = OriginWorkshopGameRules()

    "one chair needs stock, saw, drill, both legs and both clamps in that order" {
        var now = 0L
        var progress = OriginWorkshopGameProgress(OriginWorkshopGameStage.STOCK)
        fun act(action: OriginWorkshopGameAction, expected: OriginWorkshopGameStage) {
            val previous = progress
            progress = originWorkshopTransition(progress, action, ++now)!!
            progress.stage shouldBe expected
            originWorkshopTransition(progress, action, now) shouldBe null
            (progress != previous) shouldBe true
        }
        fun finishOperation(ticks: Long, expected: OriginWorkshopGameStage) {
            originWorkshopAdvance(progress, now + ticks - 1, rules) shouldBe progress
            now += ticks
            progress = originWorkshopAdvance(progress, now, rules)
            progress.stage shouldBe expected
        }
        act(OriginWorkshopGameAction.PICK_STOCK, OriginWorkshopGameStage.CARRY_RAW_TO_SAW)
        act(OriginWorkshopGameAction.PLACE_SAW, OriginWorkshopGameStage.START_SAW)
        act(OriginWorkshopGameAction.ACTIVATE_SAW, OriginWorkshopGameStage.SAWING)
        finishOperation(rules.sawTicks, OriginWorkshopGameStage.PICK_SAWN_BOARD)
        act(OriginWorkshopGameAction.PICK_SAWN_BOARD, OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL)
        act(OriginWorkshopGameAction.PLACE_DRILL, OriginWorkshopGameStage.START_DRILL)
        act(OriginWorkshopGameAction.ACTIVATE_DRILL, OriginWorkshopGameStage.DRILLING)
        finishOperation(rules.drillTicks, OriginWorkshopGameStage.PICK_DRILLED_BOARD)
        act(OriginWorkshopGameAction.PICK_DRILLED_BOARD, OriginWorkshopGameStage.CARRY_BOARD_TO_JIG)
        act(OriginWorkshopGameAction.PLACE_JIG, OriginWorkshopGameStage.LEG_LEFT)
        act(OriginWorkshopGameAction.PICK_LEFT_LEG, OriginWorkshopGameStage.CARRY_LEG_LEFT)
        act(OriginWorkshopGameAction.PLACE_LEFT_LEG, OriginWorkshopGameStage.LEG_RIGHT)
        act(OriginWorkshopGameAction.PICK_RIGHT_LEG, OriginWorkshopGameStage.CARRY_LEG_RIGHT)
        act(OriginWorkshopGameAction.PLACE_RIGHT_LEG, OriginWorkshopGameStage.CLAMP_LEFT)
        act(OriginWorkshopGameAction.TIGHTEN_LEFT, OriginWorkshopGameStage.CLAMPING_LEFT)
        finishOperation(rules.clampTicks, OriginWorkshopGameStage.CLAMP_RIGHT)
        act(OriginWorkshopGameAction.TIGHTEN_RIGHT, OriginWorkshopGameStage.CLAMPING_RIGHT)
        finishOperation(rules.clampTicks, OriginWorkshopGameStage.FINISHING)
        finishOperation(rules.chairHoldTicks, OriginWorkshopGameStage.REWARDING)
        originWorkshopAdvance(progress, now + 10_000, rules) shouldBe progress
    }

    "waiting never skips a required pickup and extra clicks never skip processing" {
        for (stage in listOf(OriginWorkshopGameStage.STOCK, OriginWorkshopGameStage.PICK_SAWN_BOARD,
            OriginWorkshopGameStage.PICK_DRILLED_BOARD, OriginWorkshopGameStage.LEG_LEFT,
            OriginWorkshopGameStage.LEG_RIGHT, OriginWorkshopGameStage.CLAMP_LEFT)) {
            val progress = OriginWorkshopGameProgress(stage)
            originWorkshopAdvance(progress, 10_000, rules) shouldBe progress
        }
        for (stage in listOf(OriginWorkshopGameStage.SAWING, OriginWorkshopGameStage.DRILLING,
            OriginWorkshopGameStage.CLAMPING_LEFT, OriginWorkshopGameStage.CLAMPING_RIGHT,
            OriginWorkshopGameStage.FINISHING, OriginWorkshopGameStage.REWARDING)) {
            OriginWorkshopGameAction.entries.forEach { action ->
                originWorkshopTransition(OriginWorkshopGameProgress(stage), action, 1) shouldBe null
            }
        }
        originWorkshopTransition(OriginWorkshopGameProgress(OriginWorkshopGameStage.STOCK),
            OriginWorkshopGameAction.PLACE_JIG, 1) shouldBe null
        originWorkshopTransition(OriginWorkshopGameProgress(OriginWorkshopGameStage.LEG_LEFT),
            OriginWorkshopGameAction.TIGHTEN_LEFT, 1) shouldBe null
    }

    "a delayed animation tick finishes only its current operation without resetting or skipping the handoff" {
        val sawing = OriginWorkshopGameProgress(OriginWorkshopGameStage.SAWING, 100)
        val ready = originWorkshopAdvance(sawing, 900, rules)
        ready.stage shouldBe OriginWorkshopGameStage.PICK_SAWN_BOARD
        ready.stageStartedAt shouldBe 900L
        originWorkshopAdvance(ready, 2000, rules) shouldBe ready
    }

    "paired arm and interact packets count once but the next deliberate click is accepted" {
        val window = 120_000_000L
        originWorkshopIsDuplicateClick(null, 1_000, window) shouldBe false
        originWorkshopIsDuplicateClick(1_000, 1_000, window) shouldBe true
        originWorkshopIsDuplicateClick(1_000, 1_000 + window, window) shouldBe true
        originWorkshopIsDuplicateClick(1_000, 1_001 + window, window) shouldBe false
    }

    "stock trip stays in range while sneak, departure and expiry cancel the session" {
        fun reason(online: Boolean = true, sameWorld: Boolean = true, sneaking: Boolean = false,
            distance: Double = 7.5 * 7.5, elapsed: Long = 1) = originWorkshopCancelReason(
            online, sameWorld, sneaking, distance, elapsed, rules.timeout, 144.0)
        reason() shouldBe null
        reason(distance = 144.0) shouldBe null
        reason(distance = 144.001) shouldBe OriginWorkshopCancelReason.TOO_FAR
        reason(distance = Double.NaN) shouldBe OriginWorkshopCancelReason.TOO_FAR
        reason(sneaking = true) shouldBe OriginWorkshopCancelReason.SNEAKING
        reason(sameWorld = false) shouldBe OriginWorkshopCancelReason.WORLD_CHANGED
        reason(online = false) shouldBe OriginWorkshopCancelReason.OFFLINE
        reason(elapsed = rules.timeout) shouldBe OriginWorkshopCancelReason.TIMEOUT
    }

    "aim intersection rejects misses, backward targets, invalid coordinates and overreach" {
        val origin = OriginWorkshopVec3(0.0, 0.0, 0.0)
        val direction = OriginWorkshopVec3(0.0, 0.0, -1.0)
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -3.0), 0.25, 4.5) shouldBe 2.75
        for (target in listOf(OriginWorkshopVec3(0.0, 0.0, -5.0),
            OriginWorkshopVec3(1.0, 0.0, -3.0), OriginWorkshopVec3(0.0, 0.0, 3.0),
            OriginWorkshopVec3(Double.NaN, 0.0, -2.0))) {
            originWorkshopRayHit(origin, direction, target, 0.25, 4.5) shouldBe null
        }
        originWorkshopRayHit(origin, origin, OriginWorkshopVec3(0.0, 0.0, -2.0), 0.25, 4.5) shouldBe null
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -2.0), 0.25, 4.5001) shouldBe null
    }

    "whole bench can be selected from front, side and above without extending reach" {
        val bench = originWorkshopStartInteractionAabb(OriginWorkshopTableDimensions.DEFAULT)
        fun hit(origin: OriginWorkshopVec3, direction: OriginWorkshopVec3, reach: Double = 4.5) =
            originWorkshopRayAabbHit(origin, direction, bench, reach)
        (hit(OriginWorkshopVec3(0.0, 1.2, -3.025), OriginWorkshopVec3(0.0, 0.0, 4.0))!! in 1.999..2.001) shouldBe true
        (hit(OriginWorkshopVec3(-4.4, 1.2, 0.0), OriginWorkshopVec3(1.0, 0.0, 0.0))!! in 1.999..2.001) shouldBe true
        (hit(OriginWorkshopVec3(0.0, 3.63, 0.0), OriginWorkshopVec3(0.0, -1.0, 0.0))!! in 1.999..2.001) shouldBe true
        hit(OriginWorkshopVec3(0.0, 1.2, 0.0), OriginWorkshopVec3(0.0, 0.0, -1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -6.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(3.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 2.0, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, -1.0)) shouldBe null
        hit(OriginWorkshopVec3(Double.NaN, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 0.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0), 4.5001) shouldBe null
    }

})
