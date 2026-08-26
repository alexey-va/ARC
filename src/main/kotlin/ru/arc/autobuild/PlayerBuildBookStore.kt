package ru.arc.autobuild

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.buildertools.BuilderClipboard
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class PlayerBuildBookLimitException : IllegalStateException("Player build-book limit reached")

object PlayerBuildBookStore {
    fun create(
        creatorId: UUID,
        clipboard: BuilderClipboard,
        title: String,
    ): BuildBookData {
        val checked = clipboard.validated(10_000)
        val root = resolvedSchematicsRoot()
        val fileName = fileName(creatorId, checked)
        val target = root.resolve(fileName)
        if (!Files.isRegularFile(target)) {
            enforceLimit(root, creatorId)
            writeAtomic(target, checked)
        }
        BuildingManager.addBuilding(Building(fileName))
        return BuildBookData(
            buildingId = fileName,
            title = title,
            playerCreated = true,
            creatorId = creatorId,
            blockCount = checked.blocks.size,
            cooldownSeconds = 0,
        ).validated()
    }

    internal fun fileName(creatorId: UUID, clipboard: BuilderClipboard): String {
        val owner = creatorId.toString().replace("-", "")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${clipboard.sizeX}:${clipboard.sizeY}:${clipboard.sizeZ}\n".toByteArray(StandardCharsets.UTF_8))
        clipboard.blocks.sortedWith(compareBy<ru.arc.buildertools.BuilderClipboardBlock> { it.dy }.thenBy { it.dx }.thenBy { it.dz })
            .forEach { block ->
                digest.update("${block.dx}:${block.dy}:${block.dz}:${block.blockData}\n".toByteArray(StandardCharsets.UTF_8))
            }
        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(20)
        return "player-$owner-$hash.schem"
    }

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
}
