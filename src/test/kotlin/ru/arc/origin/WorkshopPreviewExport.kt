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
            progress: Double = 0.0, extras: List<Map<String, Any>> = emptyList(), finished: Boolean = false, hidden: Set<String> = emptySet()) {
            val pose = machine?.let { originWorkshopCraftMachinePose(it, progress, dimensions, tuning) }
            val pieces = originWorkshopTablePieces(role, 0, dimensions, tuning)
                .filter { it.kind == OriginWorkshopTablePieceKind.BLOCK && it.key !in hidden }
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
        fun item(key: String, model: String, center: OriginWorkshopPoint, ry: Double = 0.0) =
            cuboid(key, "arc_workshop:$model", center.x, center.y, center.z, 1.0, 1.0, 1.0, ry)
        fun board(holes: Int, center: OriginWorkshopPoint) = listOf(item("player-board",
            if (holes == 0) "board_cut" else "board_drilled_$holes", center))
        val names = mapOf(OriginWorkshopTableRole.CARPENTER to "Столяр", OriginWorkshopTableRole.UPHOLSTERER to "Обивщик",
            OriginWorkshopTableRole.ASSEMBLER to "Сборщик", OriginWorkshopTableRole.FINISHER to "Отделочник")
        OriginWorkshopTableRole.entries.forEach { state(it.key, "${names[it]} · станок", it) }
        val recipe = originWorkshopGameRecipe(OriginWorkshopTableRole.CARPENTER, "furnituresplus:white_wooden_chair",
            dimensions, tuning, OriginWorkshopPoint(-7.5, 0.405, 0.30), rules)
        for ((id, stage) in listOf("load-saw" to OriginWorkshopGameStage.CARRY_RAW_TO_SAW,
            "load-drill" to OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL, "assembly-slot" to OriginWorkshopGameStage.CARRY_BOARD_TO_JIG)) {
            val target = recipe.interactions.getValue(stage).target
            val marker = originWorkshopPlacementMarker().mapIndexed { i, p ->
                cuboid("target-$i", p.material.name, target.x + p.center.x, target.y + p.center.y, target.z + p.center.z,
                    p.size.x.toDouble(), p.size.y.toDouble(), p.size.z.toDouble()) + ("cue" to true)
            }
            state(id, "Столяр · ${stage.instruction}", OriginWorkshopTableRole.CARPENTER, extras = marker)
        }
        val cutAt = recipe.interactions.getValue(OriginWorkshopGameStage.PICK_SAWN_BOARD).target
        val offcuts = listOf(0.30, 0.58).mapIndexed { i, z ->
            item("offcut-$i", "board_offcut", OriginWorkshopPoint(cutAt.x - 0.60, cutAt.y - 0.13, cutAt.z + z))
        }
        state("raw-board", "Столяр · целая заготовка", OriginWorkshopTableRole.CARPENTER, extras = listOf(item("player-board", "board_raw", cutAt)))
        state("one-cut", "Столяр · первый распил", OriginWorkshopTableRole.CARPENTER, extras = listOf(item("player-board", "board_cut_once", cutAt)) + offcuts.take(1))
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
            item("shoulder-board", "board_raw", pose.center.copy(z = -2.2 + pose.center.z), Math.toDegrees(pose.rotationRadians)))
        state("shoulder", "Столяр · заготовка на плече (манекен)", OriginWorkshopTableRole.CARPENTER, extras = mannequin)
        state("press", "Обивщик · прижим в нижней точке", OriginWorkshopTableRole.UPHOLSTERER, "press", 0.65,
            listOf(cuboid("player-cloth", "RED_WOOL", tuning.pressCenterX, dimensions.height + 0.74, tuning.pressCenterZ, 0.64, 0.05, 0.46)))
        state("hammer", "Сборщик · удар молота", OriginWorkshopTableRole.ASSEMBLER, "anvil", 0.65,
            listOf(cuboid("player-tabletop", "SPRUCE_PLANKS", tuning.anvilCenterX, dimensions.height + 0.67, tuning.anvilCenterZ, 0.68, 0.06, 0.30)))
        state("panel", "Отделочник · панель на столе", OriginWorkshopTableRole.FINISHER, "finish", 0.25)
        val coating = listOf(-0.67, -0.45, -0.23).mapIndexed { i, z -> cuboid("coat-$i", "STRIPPED_DARK_OAK_WOOD",
            0.0, dimensions.height + 0.074, z, 0.18, 0.012, 0.22) }
        state("coated", "Отделочник · три полосы покрытия", OriginWorkshopTableRole.FINISHER, "finish", 0.25, coating)
        fun prop(key: String, geometry: List<OriginWorkshopWorkpiecePiece>, center: OriginWorkshopPoint,
            flip: Boolean = false): List<Map<String, Any>> = geometry.mapIndexed { i, piece ->
            val sign = if (flip) -1.0 else 1.0
            cuboid("$key-$i", piece.material.name, center.x + piece.center.x,
                center.y + sign * piece.center.y, center.z + sign * piece.center.z,
                piece.size.x.toDouble(), piece.size.y.toDouble(), piece.size.z.toDouble()) +
                ("rotationX" to if (flip) 180.0 else 0.0)
        }
        fun cue(recipe: OriginWorkshopGameRecipe, stage: OriginWorkshopGameStage) =
            prop("cue", originWorkshopPlacementMarker(recipe.interactions.getValue(stage).action), recipe.interactions.getValue(stage).target)
                .map { it + ("cue" to true) }
        fun recipeFor(role: OriginWorkshopTableRole) = originWorkshopGameRecipe(role, "preview:${role.key}",
            dimensions, tuning, OriginWorkshopPoint(-7.5, 0.405, 0.30), rules)
        val upholster = recipeFor(OriginWorkshopTableRole.UPHOLSTERER)
        val pressAt = upholster.interactions.getValue(OriginWorkshopGameStage.UPHOLSTER_PLACE_FABRIC).target
        state("press-cue", "Обивщик · нижняя ручка и первый шов", OriginWorkshopTableRole.UPHOLSTERER,
            extras = prop("cloth", originWorkshopUpholsteryPieces(2, 1), pressAt) + cue(upholster, OriginWorkshopGameStage.UPHOLSTER_START_PRESS_SECOND))
        val cushionAt = upholster.interactions.getValue(OriginWorkshopGameStage.UPHOLSTER_PLACE_COVER).target
        val hiddenCushion = setOf("upholsterer-cushion-cover", "upholsterer-cushion-padding")
        for (tucked in 0..2) state("cushion-$tucked", "Обивщик · набивка и края: $tucked/2", OriginWorkshopTableRole.UPHOLSTERER,
            extras = prop("cushion", originWorkshopUpholsteryPieces(2, 2, true, tucked, if (tucked == 2) 2 else 0), cushionAt),
            hidden = hiddenCushion)
        val assembler = recipeFor(OriginWorkshopTableRole.ASSEMBLER)
        val assemblyAt = assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_PLACE_ASSEMBLY_TOP).target
        val assembledLegs = listOf(OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG, OriginWorkshopGameStage.ASSEMBLER_PLACE_RIGHT_LEG).flatMap { stage ->
            prop(stage.name, originWorkshopAssemblerLegPieces(fastened = stage == OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG),
                assembler.interactions.getValue(stage).target)
        }
        state("joined-top", "Сборщик · соединения после двух ударов", OriginWorkshopTableRole.ASSEMBLER,
            extras = prop("top", originWorkshopJoinedTopPieces(2, 2), assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL).target))
        state("table-assembly", "Сборщик · столешница и обе ножки", OriginWorkshopTableRole.ASSEMBLER,
            extras = prop("top", originWorkshopJoinedTopPieces(2, 2), assemblyAt) + assembledLegs + cue(assembler, OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_RIGHT))
        val finisher = recipeFor(OriginWorkshopTableRole.FINISHER)
        state("rack-cue", "Отделочник · нижняя ручка сушилки", OriginWorkshopTableRole.FINISHER,
            extras = cue(finisher, OriginWorkshopGameStage.FINISHER_START_PANEL))
        val panelAt = OriginWorkshopPoint(0.0, dimensions.height + 0.04, -0.45)
        for (back in listOf(false, true)) for (coat in 0..3) state("finish-${if (back) "back" else "front"}-$coat",
            "Отделочник · ${if (back) "обратная" else "лицевая"} сторона, покрыто: $coat/3", OriginWorkshopTableRole.FINISHER,
            extras = prop("panel", originWorkshopFinishingPanelPieces(3, if (back) 3 else 0,
                if (back) 3 else coat, if (back) coat else 0), panelAt, back) + cue(finisher, when (coat) {
                    0 -> OriginWorkshopGameStage.FINISHER_COAT_NEAR
                    1 -> OriginWorkshopGameStage.FINISHER_COAT_CENTER
                    2 -> OriginWorkshopGameStage.FINISHER_COAT_FAR
                    else -> if (back) OriginWorkshopGameStage.FINISHER_HANG_PANEL else OriginWorkshopGameStage.FINISHER_FLIP_FOR_BACK_COAT
                }),
            hidden = setOf("finisher-drying-panel-center"))
        for (role in OriginWorkshopTableRole.entries) {
            val product = when (role) {
                OriginWorkshopTableRole.UPHOLSTERER -> "furnituresplus:red_wooden_sofa_single"
                OriginWorkshopTableRole.ASSEMBLER -> "furnituresplus:white_wooden_diningtable"
                else -> "furnituresplus:white_wooden_chair"
            }
            val at = originWorkshopResultAnchor(role, product, dimensions)
            state("result-${role.key}", "${names[role]} · готовая мебель на свободной площадке", role,
                extras = listOf(cuboid("finished-furniture", product, at.x, at.y, at.z, 1.0, 1.0, 1.0)),
                finished = role == OriginWorkshopTableRole.CARPENTER,
                hidden = if (role == OriginWorkshopTableRole.UPHOLSTERER) hiddenCushion else emptySet())
        }
        val output = Path.of(args.single())
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf("states" to states,
            "evidence" to "Production geometry, processing poses and filled cues; local yaw 0; exact vanilla and ItemsAdder face UVs. Finished furniture uses NONE, scale 0.65, native Y180 and analyzed tabletop contact. Finished anchors are checked against canonical machine assemblies; the carpenter fixture is hidden. No live world geometry is rendered. Workshop board native Y180 and compensation cancel.")))
        println("WORKSHOP_PREVIEW states=${states.size} output=$output")
    }
}
