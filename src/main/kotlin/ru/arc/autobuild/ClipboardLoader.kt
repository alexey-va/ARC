package ru.arc.autobuild

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import java.io.File
import java.io.FileInputStream

/**
 * Interface for loading WorldEdit clipboards.
 * Allows mocking in tests where WorldEdit platform isn't available.
 */
interface ClipboardLoader {
    fun load(file: File): Clipboard
}

/**
 * Default implementation using WorldEdit's ClipboardFormats.
 */
class WorldEditClipboardLoader : ClipboardLoader {
    override fun load(file: File): Clipboard {
        require(file.exists()) { "Schematic file not found: ${file.name}" }

        val format = ClipboardFormats.findByFile(file)
            ?: throw IllegalArgumentException("Unknown schematic format: ${file.name}")

        return format.getReader(FileInputStream(file)).use { it.read() }
    }
}

/**
 * Registry for clipboard loader - can be replaced in tests.
 */
object ClipboardLoaders {
    var loader: ClipboardLoader = WorldEditClipboardLoader()

    fun load(file: File): Clipboard = loader.load(file)

    fun reset() {
        loader = WorldEditClipboardLoader()
    }
}


