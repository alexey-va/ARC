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
            listOf("master-anvil", "ledger-orders", "apprentice-engraving-jopa", "ore-inspection", "blade-practice", "furnace-check")
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
        val bran = scene.cycles.single { it.id == "blade-practice" }
        val yar = scene.cycles.single { it.id == "furnace-check" }

        luka.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::open) shouldContainExactly
            listOf(true, false, true, false)
        luka.steps.filterIsInstance<OriginSceneStep.Swing>().filter { it.feedbackSurface == "apprentice-work-surface" }
            .sumOf(OriginSceneStep.Swing::repetitions) shouldBe 22
        luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet() shouldBe
            setOf(
                "luka-blank",
                "luka-letter-x-left",
                "luka-letter-x-right",
                "luka-letter-u-left",
                "luka-letter-u-right",
                "luka-letter-short-i-left",
                "luka-letter-short-i-right",
                "luka-letter-short-i-diagonal",
                "luka-letter-short-i-breve",
            )
        luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().all {
            it.surface == "apprentice-work-surface" && it.origin == OriginScenePropOrigin.BOTTOM_CENTER
        } shouldBe true
        luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().count { it.rotationYDegrees != 0f } shouldBe 5
        val uStrokes = luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>()
            .filter { it.key.startsWith("luka-letter-u-") }
        uStrokes.size shouldBe 2
        uStrokes.single { it.key.endsWith("left") }.rotationYDegrees shouldBe 41f
        uStrokes.single { it.key.endsWith("right") }.rotationYDegrees shouldBe -32f
        luka.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map { it.key }.toSet() shouldBe
            luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet()
        val alternate = scene.cycles.single { it.id == "apprentice-engraving-jopa" }
        alternate.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet().size shouldBe 14
        alternate.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map { it.key }.toSet() shouldBe
            alternate.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet()
        savva.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::anchor).toSet() shouldBe
            setOf("ore-cart", "ore-chest")
        scene.actors.getValue(351).home.x shouldBe scene.anchors.getValue("ore-cart-stand").x
        scene.actors.getValue(351).home.z shouldBe scene.anchors.getValue("ore-cart-stand").z
        bran.yieldAnchor shouldBe "dummy"
        bran.yieldRange shouldBe 2.0
        bran.steps.filterIsInstance<OriginSceneStep.Swing>().sumOf(OriginSceneStep.Swing::repetitions) shouldBe 24
        yar.steps.filterIsInstance<OriginSceneStep.Equip>().map(OriginSceneStep.Equip::material) shouldContainExactly
            listOf("RAW_IRON", "MAGMA_BLOCK", "AIR", "IRON_INGOT", "AIR")
        yar.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::open) shouldContainExactly
            listOf(true, false, true, false)
        yar.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet() shouldBe
            setOf(
                "yar-mould",
                "yar-crucible",
                "yar-molten-cap",
                "yar-stream-a",
                "yar-stream-b",
                "yar-stream-c",
                "yar-casting",
            )
        yar.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().all {
            it.anchor == "quench-cauldron" && it.surface == null
        } shouldBe true
        yar.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map { it.key }.toSet() shouldBe
            yar.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet()
    }

    "Edgar showcase cycle crosses the forge with an assistant and a scoped workpiece" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-edgar-showcase-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "master-forging-showcase" }

        cycle.actorIds shouldBe setOf(349, 358)
        cycle.steps.filterIsInstance<OriginSceneStep.Move>().map(OriginSceneStep.Move::anchor).toSet() shouldContainAll
            setOf("stock-stand", "master-anvil-stand", "quench-stand")
        cycle.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet() shouldBe
            setOf("edgar-blade", "edgar-guard", "edgar-grip", "edgar-pommel")
        cycle.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().all {
            it.surface == "master-work-surface" && it.anchor == null && it.origin == OriginScenePropOrigin.BOTTOM_CENTER
        } shouldBe true
        cycle.steps.filterIsInstance<OriginSceneStep.Swing>().filter { it.feedbackSurface == "master-work-surface" }
            .sumOf(OriginSceneStep.Swing::repetitions) shouldBe 24
        cycle.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map(OriginSceneStep.RemoveDisplay::key).toSet() shouldBe
            setOf("edgar-blade", "edgar-guard", "edgar-grip", "edgar-pommel")
        cycle.steps.filterIsInstance<OriginSceneStep.LookAtSurface>().map(OriginSceneStep.LookAtSurface::surface).toSet() shouldBe
            setOf("master-work-surface")
    }
})
