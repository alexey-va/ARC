package ru.arc.commandhide

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class CommandTreePrunerTest :
    FreeSpec({
        "keeps descendants untouched when only a nested path is blocked" {
            val root = RootCommandNode<Any>()
            val example = LiteralArgumentBuilder.literal<Any>("example").build()
            example.addChild(LiteralArgumentBuilder.literal<Any>("admin").build())
            example.addChild(LiteralArgumentBuilder.literal<Any>("public").build())
            root.addChild(example)

            CommandTreePruner.prune(root, treePolicy("example admin **"))

            root.getChild("example").shouldNotBeNull()
            example.getChild("admin").shouldNotBeNull()
            example.getChild("public").shouldNotBeNull()
        }

        "removes a fully blocked root and keeps its sibling" {
            val root = RootCommandNode<Any>()
            root.addChild(LiteralArgumentBuilder.literal<Any>("world").build())
            root.addChild(LiteralArgumentBuilder.literal<Any>("home").build())

            CommandTreePruner.prune(root, treePolicy("world **"))

            root.getChild("world").shouldBeNull()
            root.getChild("home").shouldNotBeNull()
        }

        "removes namespaced roots when namespace stripping is enabled" {
            val root = RootCommandNode<Any>()
            root.addChild(LiteralArgumentBuilder.literal<Any>("bukkit:plugins").build())
            root.addChild(LiteralArgumentBuilder.literal<Any>("help").build())

            CommandTreePruner.prune(root, treePolicy("plugins **"))

            root.getChild("bukkit:plugins").shouldBeNull()
            root.getChild("help").shouldNotBeNull()
        }

        "removes every namespaced root without blocking its ordinary alias" {
            val root = RootCommandNode<Any>()
            root.addChild(LiteralArgumentBuilder.literal<Any>("pwarp").build())
            root.addChild(LiteralArgumentBuilder.literal<Any>("playerwarps:pwarp").build())
            root.addChild(LiteralArgumentBuilder.literal<Any>("rediseconomy:pay").build())

            CommandTreePruner.prune(root, treePolicy())

            root.getChild("pwarp").shouldNotBeNull()
            root.getChild("playerwarps:pwarp").shouldBeNull()
            root.getChild("rediseconomy:pay").shouldBeNull()
        }

    })

private fun treePolicy(vararg patterns: String): CommandHidePolicy =
    CommandHidePolicy(
        patterns.map { CommandPattern.parse(it, stripCommandNamespace = true) },
        stripCommandNamespace = true,
        hideNamespacedRoots = true,
        blockedMessage = null,
    )
