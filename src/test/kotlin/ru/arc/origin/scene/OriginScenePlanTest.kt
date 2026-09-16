package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class OriginScenePlanTest : FreeSpec({
    "bundled plan exposes independent forge and mount-yard scenes" {
        val plan = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-plan-test"))

        plan.world shouldBe "rc_origin_spawn"
        plan.scenes.map(OriginSceneDefinition::id) shouldContainAll listOf("forge", "mount-yard")
        plan.scene("forge").cycles.map(OriginSceneCycle::id) shouldContainAll
            listOf("master-anvil", "ledger-orders", "ore-inspection", "blade-practice", "furnace-check")
        plan.scene("mount-yard").cycles.map(OriginSceneCycle::id) shouldContainAll
            listOf("groom-wind", "inspect-buran", "inspect-ryzhik", "wind-paddock", "buran-paddock", "ryzhik-paddock")
    }

    "every cycle references declared actors anchors and route profiles" {
        val plan = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-plan-validation-test"))

        plan.scenes.forEach { scene ->
            scene.validate()
            scene.cycles.forEach { cycle ->
                cycle.actorIds.all(scene.actors::containsKey) shouldBe true
                cycle.steps.filterIsInstance<OriginSceneStep.Move>().all { it.anchor in scene.anchors } shouldBe true
                cycle.steps.filterIsInstance<OriginSceneStep.Move>().all { it.routeProfile in scene.routeProfiles } shouldBe true
            }
        }
    }

    "mount yard keeps multiple animals active without sharing one actor across simultaneous lanes" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-mount-test")).scene("mount-yard")
        val animalCycles = scene.cycles.filter { it.id.endsWith("-paddock") }

        animalCycles.size shouldBe 3
        animalCycles.flatMap(OriginSceneCycle::actorIds).toSet() shouldBe setOf(371, 422, 423)
        scene.maxConcurrentCycles shouldBe 3
    }
})
