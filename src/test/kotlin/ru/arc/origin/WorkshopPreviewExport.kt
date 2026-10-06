package ru.arc.origin

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/** Offline geometry receipt; uses production models/poses without a Bukkit world or spawned entities. */
object WorkshopPreviewExport {
    @JvmStatic
    fun main(args: Array<String>) {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val rules = OriginWorkshopGameRules()
        val states = mutableListOf<Map<String, Any>>()
        fun state(id: String, title: String, role: OriginWorkshopTableRole, machine: String? = null,
            progress: Double = 0.0, extras: List<Map<String, Any>> = emptyList(), finished: Boolean = false) {
            val pose = machine?.let { originWorkshopCraftMachinePose(it, progress, dimensions, tuning) }
            val pieces = originWorkshopTablePieces(role, 0, dimensions, tuning)
                .filter { it.kind == OriginWorkshopTablePieceKind.BLOCK }
                .filterNot { finished && it.key in originWorkshopFinishedAssemblyFixturePieceKeys(role) }
                .mapNotNull { piece ->
                    val motion = pose?.pieces?.get(piece.key)
                    val visible = motion?.visible ?: (piece.key !in ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES)
                    if (!visible) return@mapNotNull null
                    val offset = motion?.centerOffset ?: OriginWorkshopPoint(0.0, 0.0, 0.0)
                    val axis = motion?.rotationAxis
                    val restX = if (piece.key in setOf("assembler-hammer-arm", "assembler-hammer-head")) tuning.hammerRestAngleDegrees else 0.0
                    mapOf<String, Any>("key" to piece.key, "material" to piece.material.name,
                        "x" to piece.x + offset.x, "y" to piece.y + offset.y, "z" to piece.z + offset.z,
                        "width" to piece.width, "height" to piece.height * (motion?.scaleYFactor ?: 1.0), "depth" to piece.depth,
                        "rotationX" to if (axis == OriginWorkshopRotationAxis.X) motion.rotationDegrees else restX,
                        "rotationY" to if (axis == OriginWorkshopRotationAxis.Y) motion.rotationDegrees else 0.0,
                        "rotationZ" to if (axis == OriginWorkshopRotationAxis.Z) motion.rotationDegrees else 0.0)
                }
            states += mapOf("id" to id, "title" to title, "role" to role.key, "pieces" to pieces + extras)
        }
        fun cuboid(key: String, material: String, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
            ry: Double = 0.0) = mapOf<String, Any>("key" to key, "material" to material, "x" to x, "y" to y, "z" to z,
                "width" to w, "height" to h, "depth" to d, "rotationY" to ry)
        fun board(holes: Int, center: OriginWorkshopPoint) = originWorkshopBoardPieces(holes).mapIndexed { i, p ->
            cuboid("player-board-$i", p.material.name, center.x + p.center.x, center.y + p.center.y, center.z + p.center.z,
                p.size.x.toDouble(), p.size.y.toDouble(), p.size.z.toDouble())
        }
        val names = mapOf(OriginWorkshopTableRole.CARPENTER to "Столяр", OriginWorkshopTableRole.UPHOLSTERER to "Обивщик",
            OriginWorkshopTableRole.ASSEMBLER to "Сборщик", OriginWorkshopTableRole.FINISHER to "Отделочник")
        OriginWorkshopTableRole.entries.forEach { state(it.key, "${names[it]} · станок", it) }
        val recipe = originWorkshopGameRecipe(OriginWorkshopTableRole.CARPENTER, "furnituresplus:white_wooden_chair",
            dimensions, tuning, OriginWorkshopPoint(-7.5, 0.405, 0.30), rules)
        for ((id, stage) in listOf("load-saw" to OriginWorkshopGameStage.CARRY_RAW_TO_SAW,
            "load-drill" to OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL, "assembly-slot" to OriginWorkshopGameStage.CARRY_BOARD_TO_JIG)) {
            val target = recipe.interactions.getValue(stage).target
            val marker = originWorkshopPlacementMarker(false).mapIndexed { i, p ->
                cuboid("target-$i", p.material.name, target.x + p.center.x, target.y + p.center.y, target.z + p.center.z,
                    p.size.x.toDouble(), p.size.y.toDouble(), p.size.z.toDouble()) + ("highlight" to true)
            }
            state(id, "Столяр · ${stage.instruction}", OriginWorkshopTableRole.CARPENTER, extras = marker)
        }
        val cutAt = recipe.interactions.getValue(OriginWorkshopGameStage.PICK_SAWN_BOARD).target
        val offcuts = listOf(0.30, 0.58).mapIndexed { i, z ->
            cuboid("offcut-$i", "OAK_PLANKS", cutAt.x - 0.60, cutAt.y - 0.15, cutAt.z + z, 0.12, 0.08, 0.22)
        }
        state("two-cuts", "Столяр · два распила и обрезки", OriginWorkshopTableRole.CARPENTER, "saw", 1.0, board(0, cutAt) + offcuts)
        val drill = recipe.interactions.getValue(OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL).target
        for (holes in 1..3) state("holes-$holes", "Столяр · отверстий: $holes", OriginWorkshopTableRole.CARPENTER, "drill", 1.0,
            board(holes, drill.copy(x = drill.x - 0.23 * (holes - 1))) + offcuts)
        state("chair-clearance", "Столяр · место готового стула (без модели ItemsAdder)", OriginWorkshopTableRole.CARPENTER, finished = true)
        val pose = originWorkshopShoulderPose(0.0, false)
        val mannequin = listOf(
            cuboid("mannequin-head", "TERRACOTTA", 0.0, 1.65, -2.2, 0.50, 0.50, 0.50),
            cuboid("mannequin-body", "BLUE_TERRACOTTA", 0.0, 1.04, -2.2, 0.50, 0.72, 0.25),
            cuboid("mannequin-arm-right", "BLUE_TERRACOTTA", -0.36, 1.04, -2.2, 0.22, 0.72, 0.25),
            cuboid("mannequin-arm-left", "BLUE_TERRACOTTA", 0.36, 1.04, -2.2, 0.22, 0.72, 0.25),
            cuboid("mannequin-leg-right", "GRAY_TERRACOTTA", -0.13, 0.34, -2.2, 0.24, 0.68, 0.25),
            cuboid("mannequin-leg-left", "GRAY_TERRACOTTA", 0.13, 0.34, -2.2, 0.24, 0.68, 0.25),
            cuboid("shoulder-board", "OAK_PLANKS", pose.center.x, pose.center.y, -2.2 + pose.center.z, 1.0, 0.08, 0.22, Math.toDegrees(pose.rotationRadians)))
        state("shoulder", "Столяр · заготовка на плече (манекен)", OriginWorkshopTableRole.CARPENTER, extras = mannequin)
        state("press", "Обивщик · прижим в нижней точке", OriginWorkshopTableRole.UPHOLSTERER, "press", 0.65,
            listOf(cuboid("player-cloth", "RED_WOOL", tuning.pressCenterX, dimensions.height + 0.74, tuning.pressCenterZ, 0.64, 0.05, 0.46)))
        state("hammer", "Сборщик · удар молота", OriginWorkshopTableRole.ASSEMBLER, "anvil", 0.65,
            listOf(cuboid("player-tabletop", "SPRUCE_PLANKS", tuning.anvilCenterX, dimensions.height + 0.67, tuning.anvilCenterZ, 0.68, 0.06, 0.30)))
        state("panel", "Отделочник · панель на столе", OriginWorkshopTableRole.FINISHER, "finish", 0.25)
        val coating = listOf(-0.67, -0.45, -0.23).mapIndexed { i, z -> cuboid("coat-$i", "STRIPPED_BIRCH_WOOD",
            0.0, dimensions.height + 0.074, z, 0.18, 0.012, 0.22) }
        state("coated", "Отделочник · три полосы покрытия", OriginWorkshopTableRole.FINISHER, "finish", 0.25, coating)
        val output = Path.of(args.single())
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf("states" to states,
            "evidence" to "Production geometry and poses; local yaw 0; vanilla block assets; no world, NPCs or ItemsAdder item models.")))
        println("WORKSHOP_PREVIEW states=${states.size} output=$output")
    }
}
