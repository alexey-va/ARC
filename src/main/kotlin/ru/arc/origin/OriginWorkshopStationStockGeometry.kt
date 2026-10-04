package ru.arc.origin

import org.bukkit.Material

/** Sidecar stock rack: the front lane is outside z=-0.95; the floor centre is the live output slot. */
internal fun originWorkshopStationStockGeometry(role: OriginWorkshopTableRole): OriginWorkshopWarehouseGeometry {
    val blocks = buildList {
        fun block(key: String, material: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) {
            add(OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, w, h, d))
        }
        for (x in listOf(-1.25, 0.0, 1.25)) {
            block("pallet-runner-$x", Material.STRIPPED_SPRUCE_LOG, x, 0.04, 0.0, 0.12, 0.08, 1.80)
        }
        for ((index, z) in listOf(-0.60, 0.0, 0.60).withIndex()) {
            block("pallet-deck-$index", Material.SPRUCE_PLANKS, 0.0, 0.13, z, 2.90, 0.10, 0.58)
        }
        for (x in listOf(-1.38, 1.38)) for (z in listOf(-0.89, 0.89)) {
            block("rack-post-$x-$z", Material.STRIPPED_SPRUCE_LOG, x, 1.71, z, 0.12, 3.06, 0.12)
        }
        block("upper-shelf", Material.SPRUCE_PLANKS, 0.0, 1.81, 0.0, 2.64, 0.12, 1.84)
        block("shelf-front-rail", Material.DARK_OAK_PLANKS, 0.0, 1.76, -0.93, 2.64, 0.18, 0.04)
        block("shelf-back-rail", Material.DARK_OAK_PLANKS, 0.0, 1.76, 0.93, 2.64, 0.18, 0.04)
        block("rack-crown", Material.DARK_OAK_PLANKS, 0.0, 3.30, 0.89, 2.90, 0.12, 0.12)

        when (role) {
            OriginWorkshopTableRole.CARPENTER -> {
                // Bundled cut lumber behind the finished chair, with a narrow stack of seat blanks at its side.
                for (index in 0..5) {
                    block("cut-board-$index", if (index % 2 == 0) Material.OAK_PLANKS else Material.SPRUCE_PLANKS,
                        0.0, 0.225 + index * 0.095, 0.76, 2.40, 0.085, 0.22)
                }
                for (index in 0..3) block("seat-blank-$index", Material.BIRCH_PLANKS,
                    1.02, 0.24 + index * 0.13, -0.20, 0.46, 0.12, 0.66)
                for (x in listOf(-1.22, -0.92)) for (z in listOf(-0.36, 0.0)) {
                    block("chair-leg-$x-$z", Material.STRIPPED_OAK_LOG, x, 0.43, z, 0.07, 0.50, 0.07)
                }
                block("chair-seat-frame", Material.OAK_PLANKS, -1.07, 0.72, -0.18, 0.44, 0.08, 0.50)
            }
            OriginWorkshopTableRole.UPHOLSTERER -> {
                listOf(Material.RED_WOOL, Material.WHITE_WOOL, Material.BROWN_WOOL).forEachIndexed { index, material ->
                    block("fabric-bolt-$index", material, -0.78 + index * 0.78, 0.50, 0.76, 0.68, 0.64, 0.24)
                }
                for (index in 0..3) block("cushion-stack-$index", if (index % 2 == 0) Material.RED_WOOL else Material.WHITE_WOOL,
                    1.08, 0.27 + index * 0.18, -0.20, 0.42, 0.16, 0.64)
                block("padding-bale", Material.WHITE_WOOL, -1.06, 0.50, -0.16, 0.44, 0.64, 0.64)
                block("padding-strap", Material.BROWN_WOOL, -1.06, 0.825, -0.16, 0.09, 0.02, 0.66)
            }
            OriginWorkshopTableRole.ASSEMBLER -> {
                for (index in 0..4) block("table-top-blank-$index", Material.SPRUCE_PLANKS,
                    0.0, 0.24 + index * 0.13, 0.76, 2.40, 0.12, 0.24)
                for (x in listOf(-1.17, -0.94)) for (z in listOf(-0.33, -0.03)) {
                    block("joined-frame-leg-$x-$z", Material.STRIPPED_SPRUCE_LOG, x, 0.52, z, 0.07, 0.68, 0.07)
                }
                block("joined-frame-top", Material.OAK_PLANKS, -1.055, 0.90, -0.18, 0.42, 0.08, 0.48)
                for (index in 0..3) block("bracing-stock-$index", Material.DARK_OAK_PLANKS,
                    1.09, 0.23 + index * 0.11, -0.18, 0.38, 0.09, 0.68)
            }
            OriginWorkshopTableRole.FINISHER -> {
                for (index in 0..4) block("cured-panel-$index",
                    listOf(Material.BIRCH_PLANKS, Material.OAK_PLANKS, Material.SPRUCE_PLANKS)[index % 3],
                    -0.96 + index * 0.48, 0.68, 0.76, 0.36, 1.0, 0.10)
                for (index in 0..2) block("finished-trim-$index", Material.SPRUCE_PLANKS,
                    1.08, 0.23 + index * 0.12, -0.20, 0.42, 0.10, 0.66)
                block("coating-crate", Material.BARREL, -1.06, 0.44, -0.20, 0.42, 0.52, 0.62)
            }
        }
    }
    val product = when (role) {
        OriginWorkshopTableRole.CARPENTER, OriginWorkshopTableRole.FINISHER -> "furnituresplus:white_wooden_chair"
        OriginWorkshopTableRole.UPHOLSTERER -> "furnituresplus:red_wooden_sofa_single"
        OriginWorkshopTableRole.ASSEMBLER -> "furnituresplus:white_wooden_diningtable"
    }
    val items = buildList {
        add(OriginWorkshopWarehouseItem(product, -0.68, 1.87, 0.0, 0f, 1.0))
        add(OriginWorkshopWarehouseItem(product, 0.68, 1.87, 0.0, 0f, 1.0))
        // Other workers own this slot through their real carry-to-stock cycle.
        if (role == OriginWorkshopTableRole.FINISHER) {
            add(OriginWorkshopWarehouseItem(product, 0.0, 0.18, 0.0, 0f, 1.0))
        }
    }
    return OriginWorkshopWarehouseGeometry(blocks, items)
}
