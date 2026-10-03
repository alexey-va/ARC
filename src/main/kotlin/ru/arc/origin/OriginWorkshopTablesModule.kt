package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.nio.file.Path
import java.util.logging.Level

internal data class OriginWorkshopTableDimensions(
    val width: Double,
    val depth: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && width in 4.0..5.2) { "workshop-table width must be within 4.0..5.2 blocks" }
        require(depth.isFinite() && depth in 1.8..2.3) { "workshop-table depth must be within 1.8..2.3 blocks" }
        require(height.isFinite() && height in 0.9..1.2) { "workshop-table height must be within 0.9..1.2 blocks" }
    }

    companion object {
        val DEFAULT = OriginWorkshopTableDimensions(width = 4.8, depth = 2.05, height = 1.08)
    }
}

internal enum class OriginWorkshopTableRole(val key: String) {
    CARPENTER("carpenter"),
    UPHOLSTERER("upholsterer"),
    ASSEMBLER("assembler"),
    FINISHER("finisher");

    companion object {
        fun parse(value: String): OriginWorkshopTableRole =
            entries.firstOrNull { it.key == value.trim().lowercase() }
                ?: error("Unknown Origin workshop table role '$value'")
    }
}

internal data class OriginWorkshopTableDefinition(
    val id: String,
    val x: Double,
    val floorY: Double,
    val z: Double,
    val yaw: Int,
    val role: OriginWorkshopTableRole,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))) { "Invalid workshop table id '$id'" }
        require(x.isFinite() && x in -30_000_000.0..30_000_000.0) { "workshop table '$id' has invalid x" }
        require(floorY.isFinite() && floorY in -64.0..320.0) { "workshop table '$id' has invalid floor-y" }
        require(z.isFinite() && z in -30_000_000.0..30_000_000.0) { "workshop table '$id' has invalid z" }
        require(yaw in setOf(0, 90, 180, 270)) { "workshop table '$id' yaw must be 0, 90, 180 or 270" }
    }
}

internal data class OriginWorkshopTablesSettings(
    val enabled: Boolean,
    val world: String,
    val dimensions: OriginWorkshopTableDimensions,
    val tables: List<OriginWorkshopTableDefinition>,
    val warehouse: OriginWorkshopWarehouseAnchor?,
) {
    companion object {
        private const val RESOURCE = "origin-workshop-tables.yml"
        private const val ROOT = "origin-workshop-tables"

        fun load(dataPath: Path): OriginWorkshopTablesSettings {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            val ids = source.stringList("$ROOT.table-ids").map(String::trim)
            require(ids.size <= MAX_TABLES) { "$ROOT.table-ids supports at most $MAX_TABLES tables" }
            require(ids.distinct().size == ids.size) { "$ROOT.table-ids contains duplicate ids" }

            val dimensions = OriginWorkshopTableDimensions(
                width = source.real("$ROOT.dimensions.width", OriginWorkshopTableDimensions.DEFAULT.width),
                depth = source.real("$ROOT.dimensions.depth", OriginWorkshopTableDimensions.DEFAULT.depth),
                height = source.real("$ROOT.dimensions.height", OriginWorkshopTableDimensions.DEFAULT.height),
            )
            val tables = ids.map { id ->
                val path = "$ROOT.tables.$id"
                OriginWorkshopTableDefinition(
                    id = id,
                    x = source.doubleOrNull("$path.x") ?: error("$path.x is required"),
                    floorY = source.doubleOrNull("$path.floor-y") ?: error("$path.floor-y is required"),
                    z = source.doubleOrNull("$path.z") ?: error("$path.z is required"),
                    yaw = source.intOrNull("$path.yaw") ?: error("$path.yaw is required"),
                    role = OriginWorkshopTableRole.parse(source.string("$path.role")),
                )
            }
            require(tables.map { listOf(it.x, it.floorY, it.z) }.distinct().size == tables.size) {
                "$ROOT contains tables with duplicate centers"
            }
            val enabled = source.bool("$ROOT.enabled", false)
            require(!enabled || tables.size == MAX_TABLES) {
                "$ROOT must configure exactly $MAX_TABLES tables when enabled"
            }
            val world = source.string("$ROOT.world", "rc_origin_spawn").trim()
            require(world.isNotEmpty()) { "$ROOT.world must not be blank" }
            val warehousePath = "$ROOT.warehouse"
            val warehouse = if (source.bool("$warehousePath.enabled", false)) {
                OriginWorkshopWarehouseAnchor(
                    source.doubleOrNull("$warehousePath.x") ?: error("$warehousePath.x is required"),
                    source.doubleOrNull("$warehousePath.floor-y") ?: error("$warehousePath.floor-y is required"),
                    source.doubleOrNull("$warehousePath.z") ?: error("$warehousePath.z is required"),
                )
            } else null
            return OriginWorkshopTablesSettings(enabled, world, dimensions, tables, warehouse)
        }

        private const val MAX_TABLES = 4
    }
}

internal data class OriginWorkshopWarehouseAnchor(val x: Double, val floorY: Double, val z: Double) {
    init {
        require(x.isFinite() && x in -30_000_000.0..30_000_000.0) { "warehouse x is invalid" }
        require(floorY.isFinite() && floorY in -64.0..320.0) { "warehouse floor-y is invalid" }
        require(z.isFinite() && z in -30_000_000.0..30_000_000.0) { "warehouse z is invalid" }
    }
}

internal enum class OriginWorkshopTablePieceKind { BLOCK, ITEM }

/** A piece center and size relative to its table center and floor. */
internal data class OriginWorkshopTablePiece(
    val key: String,
    val material: Material,
    val kind: OriginWorkshopTablePieceKind,
    val x: Double,
    val y: Double,
    val z: Double,
    val width: Double,
    val height: Double,
    val depth: Double,
    val flat: Boolean = false,
)

private data class WorkshopItemProp(
    val material: Material,
    val xRatio: Double,
    val zRatio: Double,
    val scale: Double,
    val flat: Boolean,
)

/** Large workbench geometry with separated trim, storage and recognizable role equipment. */
internal fun originWorkshopTablePieces(
    role: OriginWorkshopTableRole,
    yaw: Int,
    dimensions: OriginWorkshopTableDimensions = OriginWorkshopTableDimensions.DEFAULT,
): List<OriginWorkshopTablePiece> {
    require(yaw in setOf(0, 90, 180, 270)) { "workshop table yaw must be 0, 90, 180 or 270" }
    val topThickness = 0.18
    val border = 0.10
    val legSize = 0.22
    val legX = dimensions.width / 2.0 - 0.42
    val legZ = dimensions.depth / 2.0 - 0.32
    val legHeight = dimensions.height - topThickness + 0.02
    val pieces = buildList {
        val topY = dimensions.height - topThickness / 2
        add(blockPiece("top", Material.SPRUCE_PLANKS, 0.0, topY, 0.0,
            dimensions.width - border * 2, topThickness, dimensions.depth - border * 2))
        for (side in listOf(-1.0, 1.0)) {
            add(blockPiece("long-edge-$side", Material.DARK_OAK_PLANKS, 0.0, topY,
                side * (dimensions.depth / 2.0 - border / 2.0), dimensions.width, topThickness, border))
            add(blockPiece("end-edge-$side", Material.DARK_OAK_PLANKS,
                side * (dimensions.width / 2.0 - border / 2.0), topY, 0.0,
                border, topThickness, dimensions.depth - border * 2))
        }

        for (x in listOf(-legX, legX)) for (z in listOf(-legZ, legZ)) {
            add(blockPiece("leg-$x-$z", Material.STRIPPED_SPRUCE_LOG, x, legHeight / 2.0, z,
                legSize, legHeight, legSize))
        }

        val braceThickness = 0.08
        val braceY = legHeight * 0.74
        for (z in listOf(-legZ, legZ)) {
            add(blockPiece("long-brace-$z", Material.POLISHED_BLACKSTONE, 0.0, braceY, z,
                dimensions.width - legSize * 2, braceThickness, braceThickness))
        }
        for (x in listOf(-legX, legX)) {
            add(blockPiece("end-brace-$x", Material.POLISHED_BLACKSTONE, x, braceY, 0.0,
                braceThickness, braceThickness, dimensions.depth - legSize * 2))
        }

        val cabinetWidth = 0.84
        val cabinetDepth = 0.72
        val cabinetBottom = 0.14
        val cabinetHeight = dimensions.height - topThickness - cabinetBottom
        val drawerFaceHeight = 0.19
        val cabinetZ = -0.10
        for (x in listOf(-1.15, 1.15)) {
            add(blockPiece("drawer-cabinet-$x", Material.DARK_OAK_PLANKS, x,
                cabinetBottom + cabinetHeight / 2.0, cabinetZ,
                cabinetWidth, cabinetHeight, cabinetDepth))
            val faceZ = cabinetZ - cabinetDepth / 2.0 - 0.025
            for (faceY in listOf(cabinetBottom + 0.20, cabinetBottom + 0.49)) {
                add(blockPiece("drawer-face-$x-$faceY", Material.SPRUCE_PLANKS, x, faceY, faceZ,
                    cabinetWidth - 0.12, drawerFaceHeight, 0.05))
                add(blockPiece("drawer-handle-$x-$faceY", Material.IRON_BLOCK, x, faceY, faceZ - 0.045,
                    0.18, 0.045, 0.045))
            }
        }

        add(blockPiece("lower-stock-shelf", Material.DARK_OAK_PLANKS, 0.0, 0.22, 0.36,
            1.55, 0.10, 0.78))
        listOf(
            Triple(Material.SPRUCE_PLANKS, 0.28, 0.18),
            Triple(Material.OAK_PLANKS, 0.40, 0.39),
            Triple(Material.BIRCH_PLANKS, 0.52, 0.60),
        ).forEachIndexed { index, (material, y, z) ->
            add(blockPiece("stock-board-$index", material, 0.0, y, z, 1.38, 0.07, 0.16))
        }

        val boardZ = dimensions.depth / 2.0 - 0.30
        add(blockPiece("back-tool-board", Material.DARK_OAK_PLANKS, 0.0, dimensions.height + 0.39, boardZ,
            2.46, 0.78, 0.12))
        for (x in listOf(-1.30, 1.30)) {
            add(blockPiece("rack-post-$x", Material.STRIPPED_SPRUCE_LOG, x, dimensions.height + 0.48,
                dimensions.depth / 2.0 - 0.12, 0.14, 0.96, 0.14))
        }
        add(blockPiece("back-rack-shelf", Material.SPRUCE_PLANKS, 0.0, dimensions.height + 0.96,
            dimensions.depth / 2.0 - 0.18, 2.75, 0.12, 0.25))
    }

    val machine = when (role) {
        OriginWorkshopTableRole.CARPENTER -> listOf(
            blockPiece("carpenter-stonecutter", Material.STONECUTTER, -1.15, dimensions.height + 0.35, -0.14,
                0.72, 0.70, 0.72),
        )
        OriginWorkshopTableRole.UPHOLSTERER -> listOf(
            blockPiece("upholsterer-loom", Material.LOOM, -1.15, dimensions.height + 0.36, -0.14,
                0.68, 0.72, 0.62),
        )
        OriginWorkshopTableRole.ASSEMBLER -> listOf(
            blockPiece("assembler-anvil", Material.ANVIL, -1.15, dimensions.height + 0.31, -0.14,
                0.76, 0.62, 0.70),
            blockPiece("assembler-clamp-left", Material.IRON_BLOCK, -0.52, dimensions.height + 0.18, -0.16,
                0.14, 0.36, 0.14),
            blockPiece("assembler-clamp-right", Material.IRON_BLOCK, -0.24, dimensions.height + 0.18, -0.16,
                0.14, 0.36, 0.14),
        )
        OriginWorkshopTableRole.FINISHER -> listOf(
            blockPiece("finisher-paint-bath", Material.WATER_CAULDRON, -1.15, dimensions.height + 0.30, -0.14,
                0.72, 0.60, 0.72),
        )
    }
    val workpiece = when (role) {
        OriginWorkshopTableRole.CARPENTER -> blockPiece(
            "carpenter-board-sample", Material.SPRUCE_PLANKS,
            0.29 * dimensions.width, dimensions.height + 0.035, -0.10 * dimensions.depth,
            0.65, 0.06, 0.30,
        )
        OriginWorkshopTableRole.UPHOLSTERER -> blockPiece(
            "upholsterer-cloth-roll", Material.RED_WOOL,
            0.29 * dimensions.width, dimensions.height + 0.085, -0.10 * dimensions.depth,
            0.65, 0.16, 0.35,
        )
        OriginWorkshopTableRole.ASSEMBLER -> blockPiece(
            "assembler-board-sample", Material.SPRUCE_PLANKS,
            0.29 * dimensions.width, dimensions.height + 0.035, -0.10 * dimensions.depth,
            0.65, 0.06, 0.30,
        )
        OriginWorkshopTableRole.FINISHER -> blockPiece(
            "finisher-finished-board", Material.SPRUCE_PLANKS,
            0.29 * dimensions.width, dimensions.height + 0.035, -0.10 * dimensions.depth,
            0.65, 0.06, 0.30,
        )
    }
    val worldPieces = (pieces + machine + workpiece).map { piece -> rotatePiece(piece, yaw) }.toMutableList()
    roleProps(role).forEachIndexed { index, prop ->
        val local = OriginWorkshopTablePiece(
            key = "${role.key}-prop-$index",
            material = prop.material,
            kind = OriginWorkshopTablePieceKind.ITEM,
            x = prop.xRatio * dimensions.width,
            y = dimensions.height + prop.scale / 32.0 + 0.01,
            z = prop.zRatio * dimensions.depth,
            width = prop.scale,
            height = prop.scale,
            depth = prop.scale,
            flat = prop.flat,
        )
        worldPieces += rotatePiece(local, yaw)
    }
    return worldPieces
}

private fun blockPiece(
    key: String,
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    width: Double,
    height: Double,
    depth: Double,
) = OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, width, height, depth)

private fun rotatePiece(piece: OriginWorkshopTablePiece, yaw: Int): OriginWorkshopTablePiece {
    val (x, z) = when (yaw) {
        0 -> piece.x to piece.z
        90 -> -piece.z to piece.x
        180 -> -piece.x to -piece.z
        else -> piece.z to -piece.x
    }
    val swapsAxes = yaw == 90 || yaw == 270
    return piece.copy(
        x = x,
        z = z,
        width = if (swapsAxes) piece.depth else piece.width,
        depth = if (swapsAxes) piece.width else piece.depth,
    )
}

private fun roleProps(role: OriginWorkshopTableRole): List<WorkshopItemProp> = when (role) {
    OriginWorkshopTableRole.CARPENTER -> listOf(
        WorkshopItemProp(Material.IRON_AXE, 0.05, -0.10, 0.62, flat = true),
    )
    OriginWorkshopTableRole.UPHOLSTERER -> listOf(
        WorkshopItemProp(Material.SHEARS, 0.05, -0.10, 0.60, flat = true),
    )
    OriginWorkshopTableRole.ASSEMBLER -> listOf(
        WorkshopItemProp(Material.IRON_INGOT, 0.05, -0.10, 0.58, flat = true),
    )
    OriginWorkshopTableRole.FINISHER -> listOf(
        WorkshopItemProp(Material.BRUSH, 0.05, -0.10, 0.62, flat = true),
    )
}

internal object OriginWorkshopTablesModule : PluginModule {
    override val name = "OriginWorkshopTables"
    override val priority = 24

    private var displays: PaperPacketDisplays? = null
    private val handles = mutableListOf<PacketDisplay>()

    override fun init() = reload()

    override fun reload() {
        val settings = try {
            OriginWorkshopTablesSettings.load(ARC.instance.dataPath)
        } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop tables config rejected; keeping current scene", failure)
            return
        }
        if (!settings.enabled || settings.tables.isEmpty()) {
            clearScene()
            return
        }
        val world = Bukkit.getWorld(settings.world)
        if (world == null) {
            ARC.instance.logger.warning("Origin workshop tables skipped: world '${settings.world}' is not loaded; keeping current scene")
            return
        }

        val owner = displays ?: try {
            PaperPacketDisplays(ARC.instance).also { displays = it }
        } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop tables could not open the packet display owner", failure)
            return
        }
        val created = mutableListOf<PacketDisplay>()
        try {
            settings.tables.forEach { table ->
                originWorkshopTablePieces(table.role, table.yaw, settings.dimensions).forEach { piece ->
                    if (piece.kind == OriginWorkshopTablePieceKind.BLOCK) {
                        val location = Location(
                            world,
                            table.x + piece.x - piece.width / 2.0,
                            table.floorY + piece.y - piece.height / 2.0,
                            table.z + piece.z - piece.depth / 2.0,
                        )
                        val block = piece.material.createBlockData()
                        if (block is Directional) {
                            val front = when (table.yaw) {
                                90 -> BlockFace.EAST
                                180 -> BlockFace.SOUTH
                                270 -> BlockFace.WEST
                                else -> BlockFace.NORTH
                            }
                            if (front in block.faces) block.facing = front
                        }
                        val display = owner.spawnBlock(location, block)
                        created += display
                        display.transformation = cuboidTransform(piece.width, piece.height, piece.depth)
                    } else {
                        val location = Location(
                            world,
                            table.x + piece.x,
                            table.floorY + piece.y,
                            table.z + piece.z,
                            table.yaw.toFloat(),
                            0f,
                        )
                        val display = owner.spawnItem(location, ItemStack(piece.material))
                        created += display
                        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                        display.transformation = itemTransform(piece.width, piece.flat)
                    }
                }
            }
            settings.warehouse?.let { anchor ->
                val warehouse = originWorkshopWarehouseGeometry()
                warehouse.blocks.forEach { piece ->
                    val display = owner.spawnBlock(
                        Location(world, anchor.x + piece.x - piece.width / 2.0,
                            anchor.floorY + piece.y - piece.height / 2.0,
                            anchor.z + piece.z - piece.depth / 2.0),
                        piece.material.createBlockData(),
                    )
                    created += display
                    display.transformation = cuboidTransform(piece.width, piece.height, piece.depth)
                }
                warehouse.items.forEach { piece ->
                    val item = CustomStack.getInstance(piece.itemId)?.itemStack?.clone()
                        ?: error("Warehouse furniture '${piece.itemId}' is missing from ItemsAdder")
                    val modelBottom = if (piece.itemId == "furnituresplus:white_wooden_diningtable") 0.51875 else 0.5
                    val display = owner.spawnItem(
                        Location(world, anchor.x + piece.x,
                            anchor.floorY + piece.y + modelBottom * piece.scale,
                            anchor.z + piece.z, piece.yaw, 0f),
                        item,
                    )
                    created += display
                    display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    display.transformation = itemTransform(piece.scale, flat = false)
                }
            }
            clearScene()
            handles += created
            ARC.instance.logger.info(
                "Origin workshop tables loaded: tables=${settings.tables.size} warehouse=${settings.warehouse != null} client-only displays=${created.size}",
            )
        } catch (failure: Exception) {
            created.forEach(PacketDisplay::remove)
            ARC.instance.logger.log(Level.WARNING, "Origin workshop tables could not be constructed; keeping current scene", failure)
        }
    }

    override fun shutdown() {
        clearScene()
        displays?.close()
        displays = null
    }

    private fun clearScene() {
        handles.forEach(PacketDisplay::remove)
        handles.clear()
    }

    private fun cuboidTransform(width: Double, height: Double, depth: Double) = Transformation(
        Vector3f(0f, 0f, 0f),
        Quaternionf(),
        Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
        Quaternionf(),
    )

    private fun itemTransform(scale: Double, flat: Boolean) = Transformation(
        Vector3f(0f, 0f, 0f),
        if (flat) Quaternionf().rotationX((Math.PI / 2.0).toFloat()) else Quaternionf(),
        Vector3f(scale.toFloat(), scale.toFloat(), scale.toFloat()),
        Quaternionf(),
    )
}
