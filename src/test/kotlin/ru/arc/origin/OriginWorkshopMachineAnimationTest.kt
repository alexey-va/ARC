package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class OriginWorkshopMachineAnimationTest : FreeSpec({
    val dimensions = OriginWorkshopTableDimensions.DEFAULT
    val tuning = OriginWorkshopMachineTuning()

    "continuous drives own model parts separately from work and close their loop without a jump" {
        val mechanisms = mapOf(
            OriginWorkshopTableRole.CARPENTER to listOf(OriginWorkshopMechanism.SAW),
            OriginWorkshopTableRole.UPHOLSTERER to listOf(OriginWorkshopMechanism.PRESS),
            OriginWorkshopTableRole.ASSEMBLER to listOf(OriginWorkshopMechanism.VISE, OriginWorkshopMechanism.ANVIL),
            OriginWorkshopTableRole.FINISHER to listOf(OriginWorkshopMechanism.FINISH),
        )
        for (role in OriginWorkshopTableRole.entries) {
            val geometry = originWorkshopTablePieces(role, 0).associateBy { it.key }
            val start = originWorkshopDrivePose(role, 0.0, dimensions, tuning)
            start.pieces.isNotEmpty() shouldBe true
            val workKeys = mechanisms.getValue(role).flatMap { originWorkshopMachinePose(it, 0.5, 0.65, dimensions, tuning).pieces.keys }.toSet()
            start.pieces.keys.intersect(workKeys) shouldBe emptySet()
            for (phase in listOf(0.0, 0.125, 0.25, 0.5, 0.75, 1.0)) {
                val pose = originWorkshopDrivePose(role, phase, dimensions, tuning)
                pose.pieces.keys shouldBe start.pieces.keys
                geometry.keys.containsAll(pose.pieces.keys + workKeys) shouldBe true
                pose.contact shouldBe null
                for ((key, motion) in pose.pieces) {
                    val piece = geometry.getValue(key)
                    val offset = motion.centerOffset
                    listOf(offset.x, offset.y, offset.z, motion.rotationDegrees).all(Double::isFinite) shouldBe true
                    val angle = Math.toRadians(motion.rotationDegrees)
                    val verticalSize = when (motion.rotationAxis) {
                        OriginWorkshopRotationAxis.Z -> abs(sin(angle)) * piece.width + abs(cos(angle)) * piece.height
                        OriginWorkshopRotationAxis.X -> abs(sin(angle)) * piece.depth + abs(cos(angle)) * piece.height
                        else -> piece.height
                    }
                    (piece.y + offset.y - verticalSize / 2.0 > dimensions.height) shouldBe true
                    if (phase == 1.0) {
                        (abs(offset.x) + abs(offset.y) + abs(offset.z) < 1e-9) shouldBe true
                        (abs(motion.rotationDegrees % 360.0) < 1e-9) shouldBe true
                    }
                }
            }
        }
    }

    "sewing needle meets fabric and the saw feed rollers stay above their board" {
        val sewing = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0).associateBy { it.key }
        val needle = sewing.getValue("upholsterer-drive-needle")
        val cloth = sewing.getValue("upholsterer-sewing-fabric")
        val stroke = originWorkshopDrivePose(OriginWorkshopTableRole.UPHOLSTERER, 0.25, dimensions, tuning)
        val needleBottom = needle.y + stroke.pieces.getValue(needle.key).centerOffset.y - needle.height / 2.0
        (abs(needleBottom - cloth.y - cloth.height / 2.0) < 1e-9) shouldBe true
        val fabricRoller = sewing.getValue("upholsterer-drive-fabric-roller-0")
        (fabricRoller.y - kotlin.math.hypot(fabricRoller.width, fabricRoller.height) / 2.0 >= cloth.y + cloth.height / 2.0) shouldBe true
        val saw = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, 0).associateBy { it.key }
        val board = saw.getValue("carpenter-board-in-feed")
        val roller = saw.getValue("carpenter-drive-feed-roller-0")
        (roller.y - kotlin.math.hypot(roller.width, roller.height) / 2.0 >= board.y + board.height / 2.0) shouldBe true
    }

    "finisher withdraws a rack panel before laying it on the table and returns to the same slot" {
        val panel = originWorkshopTablePieces(OriginWorkshopTableRole.FINISHER, 0)
            .single { it.key == "finisher-drying-panel-center" }
        for (progress in listOf(0.0, 1.0)) {
            val motion = originWorkshopMachinePose(OriginWorkshopMechanism.FINISH, progress, 0.0, dimensions, tuning)
                .pieces.getValue(panel.key)
            (abs(motion.centerOffset.x) + abs(motion.centerOffset.y) + abs(motion.centerOffset.z) < 1e-9) shouldBe true
            (abs(motion.rotationDegrees) < 1e-9) shouldBe true
        }
        val withdrawn = originWorkshopMachinePose(OriginWorkshopMechanism.FINISH, 0.10, 0.0, dimensions, tuning)
            .pieces.getValue(panel.key)
        (abs(withdrawn.centerOffset.x) < 1e-9) shouldBe true
        (withdrawn.centerOffset.z < -0.4) shouldBe true
        val flat = originWorkshopMachinePose(OriginWorkshopMechanism.FINISH, 0.5, 0.0, dimensions, tuning)
            .pieces.getValue(panel.key)
        (abs(panel.y + flat.centerOffset.y - panel.depth / 2.0 - dimensions.height - 0.01) < 1e-9) shouldBe true
        flat.rotationDegrees shouldBe -90.0
    }

    "saw feed advances along the blade plane and rotating rows keep their radius" {
        val poses = listOf(0.25, 0.5).map { progress ->
            originWorkshopMachinePose(OriginWorkshopMechanism.SAW, progress, 0.0, dimensions, tuning)
        }
        for (pose in poses) for (index in 0..8) {
            val baselineY = (index - 4) * 0.095
            val offset = pose.pieces.getValue("carpenter-saw-blade-row-$index").centerOffset
            val radius = kotlin.math.hypot(offset.x, baselineY + offset.y)
            (abs(radius - abs(baselineY)) < 1e-9) shouldBe true
        }

        val centers = listOf(0.0, 0.5, 1.0).map { progress ->
            tuning.sawFeedStartX + originWorkshopMachinePose(
                OriginWorkshopMechanism.SAW, progress, 0.0, dimensions, tuning,
            ).pieces.getValue("carpenter-board-in-feed").centerOffset.x
        }
        (centers[0] < centers[1] && centers[1] < centers[2]) shouldBe true
        val contact = poses.first().contact!!
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, 0, dimensions, tuning)
        val board = pieces.single { it.key == "carpenter-board-in-feed" }
        val lowestBlade = pieces.filter { it.key.startsWith("carpenter-saw-blade-row-") }
            .minOf { it.y - it.height / 2.0 }
        (contact.y in (lowestBlade..(board.y + board.height / 2.0))) shouldBe true
    }

    "vise starts and ends open, then closes both jaws onto the board" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, 0, dimensions, tuning)
            .associateBy { it.key }
        val board = pieces.getValue("assembler-board-sample")
        val left = pieces.getValue("assembler-clamp-left")
        val right = pieces.getValue("assembler-clamp-right")

        for (progress in listOf(0.0, 1.0)) {
            val pose = originWorkshopMachinePose(OriginWorkshopMechanism.VISE, progress, 0.0, dimensions, tuning)
            pose.pieces.getValue("assembler-clamp-left").centerOffset.x shouldBe 0.0
            pose.pieces.getValue("assembler-clamp-right").centerOffset.x shouldBe 0.0
        }
        val closed = originWorkshopMachinePose(OriginWorkshopMechanism.VISE, 0.5, 0.0, dimensions, tuning)
        val leftContact = left.x + closed.pieces.getValue("assembler-clamp-left").centerOffset.x + left.width / 2.0
        val rightContact = right.x + closed.pieces.getValue("assembler-clamp-right").centerOffset.x - right.width / 2.0
        (abs(leftContact - (board.x - board.width / 2.0)) < 1e-9) shouldBe true
        (abs(rightContact - (board.x + board.width / 2.0)) < 1e-9) shouldBe true
        closed.pieces.getValue("assembler-board-sample").visible shouldBe true
    }

    "hammer head reaches the workpiece on the contact stroke and returns to its hinge rest" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, 0, dimensions, tuning)
            .associateBy { it.key }
        val head = pieces.getValue("assembler-hammer-head")
        val workpiece = pieces.getValue("assembler-anvil-workpiece")
        val strike = originWorkshopMachinePose(OriginWorkshopMechanism.ANVIL, 0.5, 0.65, dimensions, tuning)
        val angle = Math.toRadians(strike.pieces.getValue("assembler-hammer-head").rotationDegrees)
        val headCenterY = tuning.hammerPivotYOffset - tuning.hammerArmLength * sin(angle)
        val rotatedHalfHeight = (
            head.height * abs(cos(angle)) + head.depth * abs(sin(angle))
        ) / 2.0
        val workpieceTop = workpiece.y + workpiece.height / 2.0
        (abs(dimensions.height + headCenterY - rotatedHalfHeight - workpieceTop) < 0.003) shouldBe true
        (abs(strike.contact!!.y - workpieceTop) < 1e-9) shouldBe true

        for (stroke in listOf(0.0, 1.0)) {
            val rest = originWorkshopMachinePose(OriginWorkshopMechanism.ANVIL, 0.5, stroke, dimensions, tuning)
            val hammer = rest.pieces.getValue("assembler-hammer-head")
            hammer.centerOffset shouldBe OriginWorkshopPoint(0.0, 0.0, 0.0)
            hammer.rotationDegrees shouldBe tuning.hammerRestAngleDegrees
        }
    }

    "press platen touches compressed cloth and releases at stroke ends" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0, dimensions, tuning)
            .associateBy { it.key }
        val platen = pieces.getValue("upholsterer-press-platen")
        val cloth = pieces.getValue("upholsterer-press-cloth")
        val press = originWorkshopMachinePose(OriginWorkshopMechanism.PRESS, 0.5, 0.65, dimensions, tuning)
        val platenBottom = platen.y + press.pieces.getValue("upholsterer-press-platen").centerOffset.y - platen.height / 2.0
        val clothMotion = press.pieces.getValue("upholsterer-press-cloth")
        val clothCenter = cloth.y + clothMotion.centerOffset.y
        val clothTop = clothCenter + cloth.height * clothMotion.scaleYFactor / 2.0
        (abs(platenBottom - clothTop) < 1e-9) shouldBe true
        (abs(press.contact!!.y - clothTop) < 1e-9) shouldBe true

        for (stroke in listOf(0.0, 1.0)) {
            val rest = originWorkshopMachinePose(OriginWorkshopMechanism.PRESS, 0.5, stroke, dimensions, tuning)
            (abs(rest.pieces.getValue("upholsterer-press-platen").centerOffset.y) < 1e-9) shouldBe true
            rest.pieces.getValue("upholsterer-press-cloth").scaleYFactor shouldBe 1.0
        }
    }

    "idle reset keeps transient material hidden and NONE has no moving pose" {
        ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES shouldBe setOf(
            "carpenter-board-in-feed",
            "assembler-board-sample",
            "assembler-anvil-workpiece",
            "upholsterer-press-cloth",
        )
        val idle = originWorkshopMachinePose(OriginWorkshopMechanism.NONE, 0.0, 0.0, dimensions, tuning)
        idle.pieces shouldBe emptyMap()
        idle.contact shouldBe null
        WORKSHOP_MACHINE_INTERPOLATION_TICKS shouldBe 2
    }
})
