package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.nio.file.Files

class MountGuiItemsCurrencyTest : TestBase() {
    @Test
    fun `token price lore renders runtime premium glyph through currency placeholder`() {
        val bundled = bundledMountConfigForCurrency()
        val mount = checkNotNull(bundled.catalog().all.first { it.currency == "tokens" && it.price(1) != null })
        val glyph = "\uE516"
        val runtimeConfig = mockk<MountModuleConfig> {
            every { guiText(any(), any()) } answers {
                when (firstArg<String>()) {
                    "common.price-tokens" -> "<#8c8c8c>Цена: <#ffacd5><price> <currency>"
                    "currencies.tokens" -> "<white><bold:false>$glyph</bold></white>"
                    else -> secondArg()
                }
            }
            every { guiLines(any(), any()) } answers { secondArg() }
            every { guiStyle(any()) } returns MountGuiItemStyle()
        }

        val item = MountGuiItems({ runtimeConfig }, mockk(relaxed = true))
            .mountIcon(mount, MountProfile(level = 0, glowOwned = false, glowDisabled = false))
        val lore = checkNotNull(item.itemMeta?.lore())
            .map(PlainTextComponentSerializer.plainText()::serialize)

        lore.any { it.startsWith("Цена:") && glyph in it } shouldBe true
        lore.none { "жетонов" in it } shouldBe true
    }

    @Test
    fun `bundled currency remains portable fallback and exposes separate upgrade footer rows`() {
        val config = bundledMountConfigForCurrency()

        config.guiText("common.price-tokens", "") shouldBe
            "<color:#8c8c8c>Цена: <color:#ffacd5><price> <currency>"
        config.guiText("currencies.tokens", "<missing>") shouldBe "<color:#ffacd5>жетонов"
        config.guiText("list.mount-upgrades-level-footer", "") shouldBe
            "<color:#92bed8>ЛКМ<color:#e6fff3> — повышение уровня"
        config.guiText("list.mount-upgrades-ability-footer", "") shouldBe
            "<color:#ff9f0f>ПКМ<color:#e6fff3> — покупка улучшений"
    }
}

private fun bundledMountConfigForCurrency(): MountModuleConfig {
    val dataPath = Files.createTempDirectory("arc-mounts-currency-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(MountGuiItemsCurrencyTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath)
}
