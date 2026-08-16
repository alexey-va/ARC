package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import org.bukkit.entity.Player
import ru.arc.metrics.ProductPath

class ProductPathSubCommandTest :
    FreeSpec({
        "records exactly one bounded choice without accepting extra arguments" {
            val player = mockk<Player>(relaxed = true)
            val choices = mutableListOf<ProductPath>()
            val handler = ProductPathChoiceHandler({ _, path -> choices += path }, { _, _ -> }, {})

            handler.execute(player, arrayOf("engineer"))
            handler.execute(player, arrayOf("engineer", "unexpected"))

            choices shouldContainExactly listOf(ProductPath.ENGINEER)
        }

        "rejects unknown paths and completes only the three stable ids" {
            val player = mockk<Player>(relaxed = true)
            val choices = mutableListOf<ProductPath>()
            val handler = ProductPathChoiceHandler({ _, path -> choices += path }, { _, _ -> }, {})

            handler.execute(player, arrayOf("../../settler"))

            choices shouldContainExactly emptyList<ProductPath>()
            handler.complete(arrayOf("e")) shouldContainExactly listOf("engineer", "explorer")
        }
    })
