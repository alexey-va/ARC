package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OriginWorkshopGameTest : FreeSpec({
    val rules = OriginWorkshopGameRules()

    "part must be picked and placed before the machine accepts hits" {
        val start = OriginWorkshopGameProgress(OriginWorkshopGameStage.PICK)
        originWorkshopPlace(start, 0, rules) shouldBe null
        val carrying = originWorkshopPick(start)
        carrying?.stage shouldBe OriginWorkshopGameStage.CARRY
        val clamping = originWorkshopPlace(carrying!!, 0, rules)
        clamping?.stage shouldBe OriginWorkshopGameStage.CLAMP
        clamping?.nextBeat shouldBe 62L
        originWorkshopHit(clamping!!, 61, rules).result shouldBe OriginWorkshopHitResult.EARLY
    }

    "clamp and finish require deliberate beat windows and cannot complete in under 45 seconds" {
        var progress = originWorkshopPlace(
            originWorkshopPick(OriginWorkshopGameProgress(OriginWorkshopGameStage.PICK))!!,
            0,
            rules,
        )!!
        val clampTimes = listOf(62L, 170L, 278L, 386L)
        clampTimes.forEachIndexed { index, at ->
            val hit = originWorkshopHit(progress, at, rules)
            hit.result shouldBe if (index == clampTimes.lastIndex) OriginWorkshopHitResult.CLAMPED else OriginWorkshopHitResult.HIT
            progress = hit.progress
        }
        progress.stage shouldBe OriginWorkshopGameStage.PAUSE
        originWorkshopAdvance(progress, 405, rules).stage shouldBe OriginWorkshopGameStage.PAUSE
        progress = originWorkshopAdvance(progress, 406, rules)
        progress.stage shouldBe OriginWorkshopGameStage.FINISH
        progress.nextBeat shouldBe 468L

        listOf(468L, 576L, 684L, 792L, 900L).forEachIndexed { index, at ->
            val hit = originWorkshopHit(progress, at, rules)
            hit.result shouldBe if (index == 4) OriginWorkshopHitResult.FINISHED else OriginWorkshopHitResult.HIT
            progress = hit.progress
        }
        progress.finishedAt shouldBe 900L
        progress.stage shouldBe OriginWorkshopGameStage.DISPLAY
        originWorkshopAdvance(progress, 959, rules).stage shouldBe OriginWorkshopGameStage.DISPLAY
        originWorkshopAdvance(progress, 960, rules).stage shouldBe OriginWorkshopGameStage.REWARDING
        rules.minimumRhythm shouldBe 900L
    }

    "a missed beat resets the next window instead of granting progress" {
        val progress = OriginWorkshopGameProgress(OriginWorkshopGameStage.CLAMP, nextBeat = 100L)
        val late = originWorkshopHit(progress, 117L, rules)
        late.result shouldBe OriginWorkshopHitResult.LATE
        late.progress.hits shouldBe 0
        late.progress.nextBeat shouldBe 225L
        val onNextBeat = originWorkshopHit(late.progress, 225L, rules)
        onNextBeat.result shouldBe OriginWorkshopHitResult.HIT
        onNextBeat.progress.hits shouldBe 1
    }

    "aim intersection respects forward direction, sphere miss, and 4.5 block reach" {
        val origin = OriginWorkshopVec3(0.0, 0.0, 0.0)
        val direction = OriginWorkshopVec3(0.0, 0.0, -1.0)
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -3.0), 0.25, 4.5) shouldBe 2.75
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -5.0), 0.25, 4.5) shouldBe null
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(1.0, 0.0, -3.0), 0.25, 4.5) shouldBe null
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, 3.0), 0.25, 4.5) shouldBe null
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -2.0), 0.25, 4.5001) shouldBe null
    }
})
