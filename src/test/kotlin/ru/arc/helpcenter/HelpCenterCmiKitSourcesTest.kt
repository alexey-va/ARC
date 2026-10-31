package ru.arc.helpcenter

import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.ops.BundleDefinition
import ru.arc.ops.BundleEntry
import ru.arc.ops.ItemDefinition

class HelpCenterCmiKitSourcesTest : StringSpec({
    "only the owned CMI arc give grammar is read" {
        HelpCenterCmiKitSources.arcGive("asConsole! arc give [playerName] lootbox_bundle 1") shouldBe ("lootbox_bundle" to 1)
        HelpCenterCmiKitSources.arcGive("asConsole! arc give [playerName] ae_lootbox 2") shouldBe ("ae_lootbox" to 2)
        HelpCenterCmiKitSources.arcGive("asConsole! arc give someone_else ae_lootbox 2") shouldBe null
        HelpCenterCmiKitSources.arcGive("asConsole! arc give [playerName] ae_lootbox 2;op") shouldBe null
        HelpCenterCmiKitSources.arcGive("arc give [playerName] ae_lootbox 2") shouldBe null
    }

    "enabled kits reflect current preset and bundle quantities without claiming player permissions" {
        val box = ItemDefinition("ae_lootbox", null,
            JsonParser.parseString("""{"customData":{"arc:treasure_key":"ae"}}""").asJsonObject)
        val bundle = BundleDefinition("lootbox_bundle", null, listOf(BundleEntry("ae_lootbox", 1)))
        val snapshot = HelpCenterEnchantmentSourceSnapshot(emptyList(), listOf(box, bundle), null)
        val kits = listOf(
            HelpCenterCmiKit("tokens", true, 86400, listOf("asConsole! arc give [playerName] lootbox_bundle 1")),
            HelpCenterCmiKit("aelootbox", true, 86400, listOf("asConsole! arc give [playerName] ae_lootbox 2")),
            HelpCenterCmiKit("disabled", false, 0, listOf("asConsole! arc give [playerName] ae_lootbox 10")),
        )
        val facts = HelpCenterCmiKitSources.describe("ae_lootbox", snapshot, kits)
        facts.map { it.values["kit"] } shouldBe listOf("tokens", "aelootbox")
        facts.map { it.values["amount"] } shouldBe listOf("1", "2")
        facts.map { it.values["cooldown"] } shouldBe listOf("1d", "1d")
        HelpCenterCmiKitSources.describe("ae_lootbox", snapshot, kits.map { it.copy(enabled = false) }) shouldBe emptyList()
    }
})
