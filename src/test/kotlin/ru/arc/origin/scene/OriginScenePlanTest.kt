package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
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

    "forge workstations separate walk stands from gaze targets" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-facing-test")).scene("forge")

        scene.anchors.getValue("stock-stand").explicitPose shouldBe false
        scene.actors.getValue(354).home.explicitPose shouldBe true
        (scene.anchors.getValue("apprentice-stand").x == scene.anchors.getValue("apprentice-anvil").x &&
            scene.anchors.getValue("apprentice-stand").z == scene.anchors.getValue("apprentice-anvil").z) shouldBe false
    }

    "forge production cycles expose readable material transformations and container handoffs" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-production-test")).scene("forge")
        val luka = scene.cycles.single { it.id == "apprentice-engraving" }
        val savva = scene.cycles.single { it.id == "ore-inspection" }
        val yar = scene.cycles.single { it.id == "furnace-check" }

        luka.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::open) shouldContainExactly
            listOf(true, false, true, false)
        luka.steps.filterIsInstance<OriginSceneStep.Swing>().maxOf(OriginSceneStep.Swing::repetitions) shouldBe 18
        savva.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::anchor).toSet() shouldBe
            setOf("ore-cart", "ore-chest")
        yar.steps.filterIsInstance<OriginSceneStep.Equip>().map(OriginSceneStep.Equip::material) shouldContainExactly
            listOf("RAW_IRON", "MAGMA_BLOCK", "IRON_INGOT", "AIR")
        yar.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::open) shouldContainExactly
            listOf(true, false, true, false)
    }
})
