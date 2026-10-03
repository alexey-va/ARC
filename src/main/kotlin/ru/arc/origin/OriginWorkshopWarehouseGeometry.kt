package ru.arc.origin

import org.bukkit.Material

/** Static furniture's Y is the shelf top; the display owner applies the model's anchor offset. */
internal data class OriginWorkshopWarehouseItem(
    val itemId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val scale: Double,
)

internal data class OriginWorkshopWarehouseGeometry(
    val blocks: List<OriginWorkshopTablePiece>,
    val items: List<OriginWorkshopWarehouseItem>,
)

/** Local coordinates use a 10 by 3 footprint, with the long rack on the north (-Z) wall. */
internal fun originWorkshopWarehouseGeometry(): OriginWorkshopWarehouseGeometry {
    val blocks = buildList {
        val rackPosts = listOf(-4.85, -2.0, 2.0, 4.85)
        rackPosts.forEachIndexed { index, x ->
            add(warehouseBlock("rack-post-$index", Material.STRIPPED_SPRUCE_LOG, x, 1.63, -1.43,
                0.14, 3.10, 0.14))
        }
        for ((key, y) in listOf("lower" to 0.38, "crown" to 2.88)) {
            add(warehouseBlock("rack-back-rail-$key", Material.DARK_OAK_PLANKS, 0.0, y, -1.30,
                9.70, 0.12, 0.12))
        }

        val bays = listOf(
            -3.425 to 2.71,
            0.0 to 3.86,
            3.425 to 2.71,
        )
        for ((tier, shelfY) in listOf("lower" to 0.26, "upper" to 1.98)) {
            bays.forEachIndexed { bay, (x, width) ->
                add(warehouseBlock("shelf-$tier-$bay", Material.SPRUCE_PLANKS, x, shelfY, -0.90,
                    width, 0.12, 1.20))
                add(warehouseBlock("shelf-support-$tier-$bay", Material.DARK_OAK_PLANKS, x, shelfY - 0.10, -1.42,
                    width, 0.08, 0.10))
                add(warehouseBlock("shelf-lip-$tier-$bay", Material.DARK_OAK_PLANKS, x, shelfY + 0.10, -0.325,
                    width, 0.08, 0.05))
            }
        }

        // The live Denizen products occupy these three front pallets.
        val palletCenters = listOf(-2.5, 0.0, 3.0)
        palletCenters.forEachIndexed { pallet, x ->
            for (runnerX in listOf(-0.65, 0.0, 0.65)) {
                add(warehouseBlock("pallet-$pallet-runner-$runnerX", Material.STRIPPED_SPRUCE_LOG,
                    x + runnerX, 0.04, 0.4, 0.12, 0.08, 1.30))
            }
            for ((board, offset) in listOf(-0.45, 0.0, 0.45).withIndex()) {
                add(warehouseBlock("pallet-$pallet-deck-$board", Material.SPRUCE_PLANKS,
                    x, 0.13, 0.4 + offset, 1.80, 0.10, 0.40))
            }
        }
    }

    val items = listOf(
        OriginWorkshopWarehouseItem("furnituresplus:white_wooden_chair", -4.05, 0.32, -0.90, 0f, 1.0),
        OriginWorkshopWarehouseItem("furnituresplus:red_wooden_sofa_single", -1.25, 0.32, -0.90, 0f, 1.0),
        OriginWorkshopWarehouseItem("furnituresplus:white_wooden_chair", 1.30, 0.32, -0.90, 0f, 1.0),
        OriginWorkshopWarehouseItem("furnituresplus:white_wooden_diningtable", -4.05, 2.04, -0.90, 0f, 1.0),
        OriginWorkshopWarehouseItem("furnituresplus:red_wooden_sofa_single", -1.25, 2.04, -0.90, 0f, 1.0),
        OriginWorkshopWarehouseItem("furnituresplus:red_wooden_sofa_single", 4.15, 2.04, -0.90, 0f, 1.0),
    )
    return OriginWorkshopWarehouseGeometry(blocks, items)
}

private fun warehouseBlock(
    key: String,
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    width: Double,
    height: Double,
    depth: Double,
) = OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, width, height, depth)
