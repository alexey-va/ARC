package ru.arc.autobuild

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.block.BaseBlock
import ru.arc.ARC
import java.io.File

/**
 * Represents a building schematic that can be placed in the world.
 * Wraps a WorldEdit clipboard with rotation support.
 */
class Building(val fileName: String) {

    private var _clipboard: Clipboard? = null

    val clipboard: Clipboard
        get() = _clipboard ?: loadClipboard().also { _clipboard = it }

    val isLoaded: Boolean get() = _clipboard != null

    val volume: Long get() = clipboard.region.volume

    /**
     * Loads the schematic file from the schematics folder.
     * @throws IllegalArgumentException if file doesn't exist or can't be read
     */
    fun loadClipboard(): Clipboard {
        val file = schematicFile
        return ClipboardLoaders.load(file).also { _clipboard = it }
    }

    private val schematicFile: File
        get() {
            val folder = File(ARC.plugin.dataFolder, "schematics").also { it.mkdirs() }
            return File(folder, fileName)
        }

    /**
     * Gets a block at the given coordinates with rotation applied.
     */
    fun getBlock(coords: BlockVector3, rotation: Int): BaseBlock {
        val normalizedRotation = rotation.mod(360)
        val rotated = coords.rotate(-normalizedRotation)
        return clipboard.getFullBlock(rotated.add(clipboard.origin))
    }

    /**
     * Gets the first corner of the schematic relative to origin, with rotation.
     */
    fun getCorner1(rotation: Int): BlockVector3 {
        val center = clipboard.origin
        return clipboard.minimumPoint.subtract(center).rotate(rotation)
    }

    /**
     * Gets the second corner of the schematic relative to origin, with rotation.
     */
    fun getCorner2(rotation: Int): BlockVector3 {
        val center = clipboard.origin
        return clipboard.maximumPoint.subtract(center).rotate(rotation)
    }

    override fun toString() = "Building($fileName)"

    companion object {
        /**
         * Extension function to rotate a BlockVector3 around the Y axis.
         * @param degrees rotation in degrees (0, 90, 180, 270)
         */
        private fun BlockVector3.rotate(degrees: Int): BlockVector3 = when (degrees.mod(360)) {
            90 -> BlockVector3.at(-z(), y(), x())
            180 -> BlockVector3.at(-x(), y(), -z())
            270 -> BlockVector3.at(z(), y(), -x())
            else -> this // 0 or invalid
        }
    }
}

