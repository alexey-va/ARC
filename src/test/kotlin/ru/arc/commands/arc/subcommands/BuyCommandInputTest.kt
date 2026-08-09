package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class BuyCommandInputTest :
    FreeSpec({
        "parses a full item path and exact amount" {
            BuyCommandInput.parse(arrayOf("Blocks.12", "64"), 2304) shouldBe
                BuyParseResult.Valid(BuyRequest("Blocks.12", 64))
        }

        "joins section and nested item location" {
            BuyCommandInput.parse(arrayOf("Redstone", "pages.page1.items.1", "128"), 2304) shouldBe
                BuyParseResult.Valid(BuyRequest("Redstone.pages.page1.items.1", 128))
        }

        "rejects zero, overflow, and extra arguments" {
            BuyCommandInput.parse(arrayOf("Blocks.12", "0"), 2304) shouldBe BuyParseResult.InvalidAmount("0")
            BuyCommandInput.parse(arrayOf("Blocks.12", "2305"), 2304) shouldBe BuyParseResult.InvalidAmount("2305")
            BuyCommandInput.parse(arrayOf("Blocks.12", "999999999999"), 2304) shouldBe
                BuyParseResult.InvalidAmount("999999999999")
            BuyCommandInput.parse(arrayOf("a", "b", "1", "extra"), 2304) shouldBe BuyParseResult.InvalidSyntax
        }

        "completes sections, full paths, relative paths, and amounts" {
            val paths = listOf("Blocks.TNT", "Blocks.STONE", "Redstone.RAIL")

            BuyCommandInput.complete(arrayOf("Bl"), paths).orEmpty() shouldContainExactly listOf("Blocks")
            BuyCommandInput.complete(arrayOf("Blocks."), paths).orEmpty() shouldContainExactly
                listOf("Blocks.TNT", "Blocks.STONE")
            BuyCommandInput.complete(arrayOf("Blocks", "T"), paths).orEmpty() shouldContainExactly listOf("TNT")
            BuyCommandInput.complete(arrayOf("Blocks.TNT", "6"), paths).orEmpty() shouldContainExactly listOf("64")
            BuyCommandInput.complete(arrayOf("Redstone", "R"), paths).orEmpty() shouldContainExactly listOf("RAIL")
            BuyCommandInput.complete(arrayOf("Blocks.TNT", ""), paths, maxAmount = 32).orEmpty() shouldContainExactly
                listOf("1", "16", "32")
        }
    })
