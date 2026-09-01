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
        pruneChildren(root, ArrayList(4), policy)
    }

    private fun pruneChildren(
        node: CommandNode<*>,
        path: MutableList<CommandTreeToken>,
        policy: CommandHidePolicy,
    ) {
        val iterator = node.children.iterator()
        while (iterator.hasNext()) {
            val child = iterator.next()
            if (path.isEmpty() && policy.hidesRoot(child.name)) {
                iterator.remove()
                continue
            }
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
            path += treeToken
            val match = policy.matchTreePath(path)
            val exactBlockedLeaf =
                child.children.isEmpty() &&
                    child.redirect == null &&
                    match.blocksExactPath
            if (match.blocksSubtree || exactBlockedLeaf) {
                // Brigadier 1.3.10 exposes the backing children values view. Removing from
                // this generated per-player tree keeps the blocked branch out of serialization.
                iterator.remove()
            } else {
                pruneChildren(child, path, policy)
            }
            path.removeAt(path.lastIndex)
        }
    }
}
