package ru.arc.commandhide

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class CommandTreePrunerTest :
    FreeSpec({
        "removes a blocked literal subtree and keeps its sibling" {
            val root = RootCommandNode<Any>()
            val example = LiteralArgumentBuilder.literal<Any>("example").build()
            example.addChild(LiteralArgumentBuilder.literal<Any>("admin").build())
            example.addChild(LiteralArgumentBuilder.literal<Any>("public").build())
            root.addChild(example)

            CommandTreePruner.prune(root, treePolicy("example admin **"))

            root.getChild("example").shouldNotBeNull()
            example.getChild("admin").shouldBeNull()
            example.getChild("public").shouldNotBeNull()
        }

        "removes namespaced roots when namespace stripping is enabled" {
            val root = RootCommandNode<Any>()
            root.addChild(LiteralArgumentBuilder.literal<Any>("bukkit:plugins").build())
            root.addChild(LiteralArgumentBuilder.literal<Any>("help").build())

            CommandTreePruner.prune(root, treePolicy("plugins **"))

            root.getChild("bukkit:plugins").shouldBeNull()
            root.getChild("help").shouldNotBeNull()
        }

        "single wildcard can remove an argument subtree" {
            val root = RootCommandNode<Any>()
            val example = LiteralArgumentBuilder.literal<Any>("example").build()
            val target =
                RequiredArgumentBuilder
                    .argument<Any, String>("target", StringArgumentType.word())
                    .build()
            target.addChild(LiteralArgumentBuilder.literal<Any>("reload").build())
            example.addChild(target)
            root.addChild(example)

            CommandTreePruner.prune(root, treePolicy("example * **"))

            example.getChild("target").shouldBeNull()
        }

        "removes an exact blocked leaf" {
            val root = RootCommandNode<Any>()
            val example = LiteralArgumentBuilder.literal<Any>("example").build()
            example.addChild(LiteralArgumentBuilder.literal<Any>("admin").build())
            root.addChild(example)

            CommandTreePruner.prune(root, treePolicy("example admin"))

            example.getChild("admin").shouldBeNull()
        }

        "keeps an exact blocked node when it has allowed descendants" {
            val root = RootCommandNode<Any>()
            val example = LiteralArgumentBuilder.literal<Any>("example").build()
            val admin = LiteralArgumentBuilder.literal<Any>("admin").build()
            admin.addChild(LiteralArgumentBuilder.literal<Any>("status").build())
            example.addChild(admin)
            root.addChild(example)

            CommandTreePruner.prune(root, treePolicy("example admin"))

            example.getChild("admin").shouldNotBeNull()
        }
    })

private fun treePolicy(vararg patterns: String): CommandHidePolicy =
    CommandHidePolicy(
        patterns.map { CommandPattern.parse(it, stripCommandNamespace = true) },
        stripCommandNamespace = true,
        blockedMessage = null,
    )
