package ru.arc.hooks.slimefun

import com.denizenscript.denizen.objects.LocationTag
import com.denizenscript.denizencore.objects.core.ElementTag
import com.denizenscript.denizencore.tags.ObjectTagProcessor
import com.denizenscript.denizencore.tags.TagRunnable
import org.bukkit.block.Block
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Optional Denizen location tags owned by the Slimefun hook.
 *
 * `<LocationTag.arc_is_slimefun_block>` returns a boolean.
 * `<LocationTag.arc_slimefun_block_id>` returns the stored Slimefun ID or null.
 */
internal object SlimefunDenizenTags {

    const val IS_SLIMEFUN_BLOCK = "arc_is_slimefun_block"
    const val SLIMEFUN_BLOCK_ID = "arc_slimefun_block_id"

    val names: Set<String> = setOf(IS_SLIMEFUN_BLOCK, SLIMEFUN_BLOCK_ID)

    private val registeredProcessors =
        Collections.newSetFromMap(IdentityHashMap<ObjectTagProcessor<LocationTag>, Boolean>())

    fun register(blockIdProvider: (Block) -> String?): Set<String> =
        register(LocationTag.tagProcessor, blockIdProvider)

    internal fun register(
        processor: ObjectTagProcessor<LocationTag>,
        blockIdProvider: (Block) -> String?,
    ): Set<String> {
        if (processor in registeredProcessors) return names

        processor.registerTag(
            ElementTag::class.java,
            IS_SLIMEFUN_BLOCK,
            TagRunnable.ObjectInterface { attribute, location ->
                val block = location.getBlockForTag(attribute)
                if (block == null) null else ElementTag(blockIdProvider(block) != null)
            },
        )
        processor.registerTag(
            ElementTag::class.java,
            SLIMEFUN_BLOCK_ID,
            TagRunnable.ObjectInterface { attribute, location ->
                val block = location.getBlockForTag(attribute)
                val slimefunId = block?.let(blockIdProvider)
                slimefunId?.let(::ElementTag)
            },
        )
        registeredProcessors += processor
        return names
    }
}
