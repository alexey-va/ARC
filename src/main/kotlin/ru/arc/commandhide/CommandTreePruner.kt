package ru.arc.commandhide

import com.mojang.brigadier.tree.CommandNode

/** Removes blocked roots from Paper's generated per-player command tree. */
internal object CommandTreePruner {
    fun prune(
        root: CommandNode<*>,
        policy: CommandHidePolicy,
    ) {
        if (policy.isEmpty) return
        // Paper can retain dispatcher-owned descendants while flattening dead redirects.
        // Mutating below this generated root can therefore race another async tree build.
        val iterator = root.children.iterator()
        while (iterator.hasNext()) {
            val child = iterator.next()
            if (policy.hidesRoot(child.name)) {
                iterator.remove()
            }
        }
    }
}
