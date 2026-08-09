package ru.arc.commandhide

import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode

/** Removes only subtrees covered by a pattern ending in `**`. */
internal object CommandTreePruner {
    fun prune(
        root: CommandNode<*>,
        policy: CommandHidePolicy,
    ) {
        if (policy.isEmpty) return
        pruneChildren(root, emptyList(), policy)
    }

    private fun pruneChildren(
        node: CommandNode<*>,
        path: List<CommandTreeToken>,
        policy: CommandHidePolicy,
    ) {
        val iterator = node.children.iterator()
        while (iterator.hasNext()) {
            val child = iterator.next()
            val treeToken =
                if (child is LiteralCommandNode<*>) {
                    CommandTreeToken.Literal(
                        normalizeTreeLiteral(
                            value = child.name,
                            root = path.isEmpty(),
                            stripCommandNamespace = policy.stripCommandNamespace,
                        ),
                    )
                } else {
                    CommandTreeToken.Argument
                }
            val childPath = path + treeToken
            val exactBlockedLeaf =
                child.children.isEmpty() &&
                    child.redirect == null &&
                    policy.blocksExactTreePath(childPath)
            if (policy.blocksSubtree(childPath) || exactBlockedLeaf) {
                // Brigadier 1.3.10 exposes the backing children values view. Removing from
                // this generated per-player tree keeps the blocked branch out of serialization.
                iterator.remove()
            } else {
                pruneChildren(child, childPath, policy)
            }
        }
    }
}
