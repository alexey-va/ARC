package ru.arc.autobuild

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.buildertools.BuilderClipboard
import ru.arc.buildertools.BuilderClipboardBlock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class PlayerBuildBookLimitException : IllegalStateException("Player build-book limit reached")

data class PlayerBuildBookTemplate(
    val buildingId: String,
    val contentSha256: String,
    val schematicSha256: String,
    val blockCount: Int,
)

object PlayerBuildBookStore {
    fun create(
        creatorId: UUID,
        clipboard: BuilderClipboard,
    ): PlayerBuildBookTemplate {
        val checked = clipboard.validated(10_000)
        val root = resolvedSchematicsRoot()
        val fileName = fileName(creatorId, checked)
        val target = root.resolve(fileName)
        if (!Files.isRegularFile(target)) {
            enforceLimit(root, creatorId)
            writeAtomic(target, checked)
        }
        BuildingManager.addBuilding(Building(fileName))
        return PlayerBuildBookTemplate(
            buildingId = fileName,
            contentSha256 = contentSha256(checked),
            schematicSha256 = sha256(target),
            blockCount = checked.blocks.size,
        )
    }

    internal fun fileName(creatorId: UUID, clipboard: BuilderClipboard): String {
        val owner = creatorId.toString().replace("-", "")
        val hash = contentSha256(clipboard).take(20)
        return "player-$owner-$hash.schem"
    }

    internal fun contentSha256(clipboard: BuilderClipboard): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${clipboard.sizeX}:${clipboard.sizeY}:${clipboard.sizeZ}\n".toByteArray(StandardCharsets.UTF_8))
        clipboard.blocks.sortedWith(compareBy<ru.arc.buildertools.BuilderClipboardBlock> { it.dy }.thenBy { it.dx }.thenBy { it.dz })
            .forEach { block ->
                digest.update("${block.dx}:${block.dy}:${block.dz}:${block.blockData}\n".toByteArray(StandardCharsets.UTF_8))
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun schematicSha256(buildingId: String): String? = runCatching {
        require(buildingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))
        val target = resolvedSchematicsRoot().resolve(buildingId)
        require(target.parent == resolvedSchematicsRoot() && Files.isRegularFile(target))
        sha256(target)
    }.getOrNull()

    fun contentSha256(buildingId: String): String? = runCatching {
        require(buildingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))
        val building = BuildingManager.getBuilding(buildingId) ?: return@runCatching null
        val clipboard = building.clipboard
        val minimum = clipboard.minimumPoint
        val maximum = clipboard.maximumPoint
        val digest = MessageDigest.getInstance("SHA-256")
        val sizeX = maximum.x() - minimum.x() + 1
        val sizeY = maximum.y() - minimum.y() + 1
        val sizeZ = maximum.z() - minimum.z() + 1
        digest.update("$sizeX:$sizeY:$sizeZ\n".toByteArray(StandardCharsets.UTF_8))
        clipboard.region.asSequence()
            .mapNotNull { position ->
                val data = BukkitAdapter.adapt(clipboard.getFullBlock(position))
                if (data.material.isAir) null else BuilderClipboardBlock(
                    position.x() - minimum.x(),
                    position.y() - minimum.y(),
                    position.z() - minimum.z(),
                    data.asString,
                )
            }
            .sortedWith(compareBy<BuilderClipboardBlock> { it.dy }.thenBy { it.dx }.thenBy { it.dz })
            .forEach { block ->
                digest.update("${block.dx}:${block.dy}:${block.dz}:${block.blockData}\n".toByteArray(StandardCharsets.UTF_8))
            }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    private fun resolvedSchematicsRoot(): Path {
        val configured = ARC.instance.dataPath.resolve("schematics")
        Files.createDirectories(configured)
        return configured.toRealPath()
    }

    private fun enforceLimit(root: Path, creatorId: UUID) {
        val prefix = "player-${creatorId.toString().replace("-", "")}-"
        val count = Files.list(root).use { files ->
            files.filter { path ->
                Files.isRegularFile(path) && path.fileName.toString().startsWith(prefix) && path.fileName.toString().endsWith(".schem")
            }.count()
        }
        if (count >= BuildBookSettings.maxBooksPerPlayer) throw PlayerBuildBookLimitException()
    }

    private fun writeAtomic(target: Path, source: BuilderClipboard) {
        require(target.parent == resolvedSchematicsRoot()) { "Player build-book target escaped the schematic root" }
        val region = CuboidRegion(
            BlockVector3.ZERO,
            BlockVector3.at(source.sizeX - 1, source.sizeY - 1, source.sizeZ - 1),
        )
        val clipboard = BlockArrayClipboard(region).also { it.origin = BlockVector3.ZERO }
        source.blocks.forEach { block ->
            val data = Bukkit.createBlockData(block.blockData)
            clipboard.setBlock(BlockVector3.at(block.dx, block.dy, block.dz), BukkitAdapter.adapt(data))
        }

        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use { output ->
                BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output).use { writer -> writer.write(clipboard) }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
