package ru.arc.autobuild

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BaseBlock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Mock ClipboardLoader for tests that don't have WorldEdit platform available.
 */
class MockClipboardLoader : ClipboardLoader {

    private val clipboards = mutableMapOf<String, Clipboard>()

    override fun load(file: File): Clipboard {
        // Check file exists just like real loader
        require(file.exists()) { "Schematic file not found: ${file.name}" }
        return clipboards.getOrPut(file.name) { createMockClipboard(file.name) }
    }

    /**
     * Creates a mock clipboard with reasonable defaults.
     */
    private fun createMockClipboard(fileName: String): Clipboard {
        val clipboard = mock<Clipboard>()

        val origin = BlockVector3.at(0, 0, 0)
        val min = BlockVector3.at(-5, 0, -5)
        val max = BlockVector3.at(5, 10, 5)
        val region = CuboidRegion(min, max)

        whenever(clipboard.origin).thenReturn(origin)
        whenever(clipboard.minimumPoint).thenReturn(min)
        whenever(clipboard.maximumPoint).thenReturn(max)
        whenever(clipboard.region).thenReturn(region)
        whenever(clipboard.dimensions).thenReturn(BlockVector3.at(11, 11, 11))

        // Return air block by default
        val airBlock = mock<BaseBlock>()
        whenever(clipboard.getFullBlock(any())).thenReturn(airBlock)

        return clipboard
    }

    /**
     * Pre-configure a clipboard for a specific file.
     */
    fun setClipboard(fileName: String, clipboard: Clipboard) {
        clipboards[fileName] = clipboard
    }

    fun clear() {
        clipboards.clear()
    }
}

