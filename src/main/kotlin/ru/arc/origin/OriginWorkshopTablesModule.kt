package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
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
        require(width.isFinite() && width in 2.2..3.2) { "workshop-table width must be within 2.2..3.2 blocks" }
        require(depth.isFinite() && depth in 0.9..1.6) { "workshop-table depth must be within 0.9..1.6 blocks" }
        require(height.isFinite() && height in 0.9..1.2) { "workshop-table height must be within 0.9..1.2 blocks" }
    }

    companion object {
        val DEFAULT = OriginWorkshopTableDimensions(width = 2.8, depth = 1.25, height = 1.04)
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
            return OriginWorkshopTablesSettings(enabled, world, dimensions, tables)
        }

        private const val MAX_TABLES = 4
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
)

private data class WorkshopItemProp(
    val material: Material,
    val xRatio: Double,
    val zRatio: Double,
    val scale: Double,
)

/** Thirteen block cuboids plus two role props form one compact static table. */
internal fun originWorkshopTablePieces(
    role: OriginWorkshopTableRole,
    yaw: Int,
    dimensions: OriginWorkshopTableDimensions = OriginWorkshopTableDimensions.DEFAULT,
): List<OriginWorkshopTablePiece> {
    require(yaw in setOf(0, 90, 180, 270)) { "workshop table yaw must be 0, 90, 180 or 270" }
    val topThickness = 0.14
    val legSize = 0.11
    val legX = dimensions.width / 2.0 - 0.13
    val legZ = dimensions.depth / 2.0 - 0.13
    val legHeight = dimensions.height - topThickness + 0.02
    val pieces = buildList {
        add(blockPiece("top", Material.SPRUCE_PLANKS, 0.0, dimensions.height - topThickness / 2, 0.0,
            dimensions.width, topThickness, dimensions.depth))

        val edgeHeight = 0.09
        val edgeY = dimensions.height - edgeHeight / 2
        val edge = 0.07
        for (side in listOf(-1.0, 1.0)) {
            add(blockPiece("long-edge-$side", Material.DARK_OAK_PLANKS, 0.0, edgeY,
                side * (dimensions.depth / 2.0 - edge / 2.0), dimensions.width, edgeHeight, edge))
            add(blockPiece("end-edge-$side", Material.DARK_OAK_PLANKS,
                side * (dimensions.width / 2.0 - edge / 2.0), edgeY, 0.0,
                edge, edgeHeight, dimensions.depth - edge * 2))
        }

        for (x in listOf(-legX, legX)) for (z in listOf(-legZ, legZ)) {
            add(blockPiece("leg-$x-$z", Material.STRIPPED_SPRUCE_LOG, x, legHeight / 2.0, z,
                legSize, legHeight, legSize))
        }

        val braceThickness = 0.055
        val braceY = legHeight * 0.72
        for (z in listOf(-legZ, legZ)) {
            add(blockPiece("long-brace-$z", Material.POLISHED_BLACKSTONE, 0.0, braceY, z,
                dimensions.width - legSize, braceThickness, braceThickness))
        }
        for (x in listOf(-legX, legX)) {
            add(blockPiece("end-brace-$x", Material.POLISHED_BLACKSTONE, x, braceY, 0.0,
                braceThickness, braceThickness, dimensions.depth - legSize))
        }
    }

    val worldPieces = pieces.map { piece -> rotatePiece(piece, yaw) }.toMutableList()
    roleProps(role).forEachIndexed { index, prop ->
        val local = OriginWorkshopTablePiece(
            key = "${role.key}-prop-$index",
            material = prop.material,
            kind = OriginWorkshopTablePieceKind.ITEM,
            x = prop.xRatio * dimensions.width,
            y = dimensions.height + 0.04,
            z = prop.zRatio * dimensions.depth,
            width = prop.scale,
            height = prop.scale,
            depth = prop.scale,
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
        WorkshopItemProp(Material.IRON_AXE, -0.18, -0.04, 0.28),
        WorkshopItemProp(Material.STICK, 0.12, 0.12, 0.22),
    )
    OriginWorkshopTableRole.UPHOLSTERER -> listOf(
        WorkshopItemProp(Material.BROWN_CARPET, -0.18, -0.04, 0.34),
        WorkshopItemProp(Material.SHEARS, 0.13, 0.12, 0.25),
    )
    OriginWorkshopTableRole.ASSEMBLER -> listOf(
        WorkshopItemProp(Material.COPPER_INGOT, -0.18, -0.04, 0.25),
        WorkshopItemProp(Material.REDSTONE, 0.13, 0.12, 0.25),
    )
    OriginWorkshopTableRole.FINISHER -> listOf(
        WorkshopItemProp(Material.HONEY_BOTTLE, -0.18, -0.04, 0.25),
        WorkshopItemProp(Material.GLASS_BOTTLE, 0.13, 0.12, 0.23),
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
                        val display = owner.spawnBlock(location, piece.material.createBlockData())
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
                        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                        display.transformation = cuboidTransform(piece.width, piece.height, piece.depth)
                    }
                }
            }
            clearScene()
            handles += created
            ARC.instance.logger.info(
                "Origin workshop tables loaded: tables=${settings.tables.size} client-only displays=${created.size}",
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
}
