package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.config.Config
import java.nio.file.Files

class BuilderBookJourneyTest : FunSpec({
    test("status resolver follows the complete guided creation journey") {
        val cases = listOf(
            BuilderBookJourneySnapshot() to BuilderBookJourneyStage.START,
            BuilderBookJourneySnapshot(hasSelection = true) to BuilderBookJourneyStage.SELECTION,
            BuilderBookJourneySnapshot(hasSelection = true, hasClipboard = true) to BuilderBookJourneyStage.CLIPBOARD,
            BuilderBookJourneySnapshot(hasClipboard = true, draft = true) to BuilderBookJourneyStage.DRAFT,
            BuilderBookJourneySnapshot(draft = true, previewOpen = true) to BuilderBookJourneyStage.PREVIEW,
            BuilderBookJourneySnapshot(draft = true, previewOpen = true, hasQuote = true) to BuilderBookJourneyStage.QUOTE,
            BuilderBookJourneySnapshot(deliveryPending = true) to BuilderBookJourneyStage.DELIVERY,
            BuilderBookJourneySnapshot(active = true) to BuilderBookJourneyStage.ACTIVE,
        )
        cases.forEach { (snapshot, expected) -> BuilderBookJourney.resolve(snapshot) shouldBe expected }
    }

    test("unsafe or transitional states keep the most restrictive guidance") {
        BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                hasQuote = true,
                deliveryPending = true,
                auctionLocked = true,
                draft = true,
                previewOpen = true,
                active = true,
                hasClipboard = true,
                hasSelection = true,
            ),
        ) shouldBe BuilderBookJourneyStage.QUOTE
        BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                deliveryPending = true,
                auctionLocked = true,
                active = true,
            ),
        ) shouldBe BuilderBookJourneyStage.DELIVERY
        BuilderBookJourney.resolve(
            BuilderBookJourneySnapshot(
                auctionLocked = true,
                active = true,
            ),
        ) shouldBe BuilderBookJourneyStage.AUCTION_LOCKED
    }

    test("every journey stage owns one stable configured message path") {
        BuilderBookJourneyStage.entries.map(BuilderBookJourneyStage::messagePath) shouldContainExactly listOf(
            "book.status.start",
            "book.status.selection",
            "book.status.clipboard",
            "book.status.draft",
            "book.status.preview",
            "book.status.quote",
            "book.status.delivery",
            "book.status.active",
            "book.auction-locked",
        )
    }

    test("bundled locales preserve the complete seven-step builder guidance") {
        val config = Config(Files.createTempDirectory("arc-builder-journey-"), "modules/builder-tools.yml")
        val requiredCommands = listOf(
            "/builder wand",
            "/builder copy",
            "/builder book draft",
            "/builder book activate",
            "/builder book confirm",
            "/builder book copy",
            "/builder book sell",
            "/builder book status",
        )

        listOf("ru", "en").forEach { locale ->
            val guide = config.stringList("locales.$locale.book.guide").joinToString("\n")
            (1..7).forEach { step -> guide shouldContain "$step." }
            requiredCommands.forEach(guide::shouldContain)
        }

        val russianGuide = config.stringList("locales.ru.book.guide").joinToString("\n")
        russianGuide shouldContain "Контур виден постоянно"
        russianGuide shouldContain "бесплатный черновик"
        russianGuide shouldContain "смета без оплаты"
        config.string("locales.ru.book.status.active") shouldContain "Себестоимость копии"
        config.string("locales.en.book.status.active") shouldContain "Copy at stored cost"
    }
})
