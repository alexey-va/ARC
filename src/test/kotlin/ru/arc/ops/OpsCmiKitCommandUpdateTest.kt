package ru.arc.ops

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIPlayerInventory.CMIInventorySlot
import com.Zrips.CMI.Modules.Kits.Kit
import com.Zrips.CMI.Modules.Kits.KitsManager
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class OpsCmiKitCommandUpdateTest : StringSpec({
    "commands-only preview and update preserve native kit content and reject invalid patches before mutation" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("CMI")
            val cmi = mockk<CMI>()
            val manager = mockk<KitsManager>()
            // A strict native mock allows only the commands setter; every other mutation fails.
            val kit = mockk<Kit>()
            val item = ItemStack(Material.WOODEN_SWORD).apply {
                editMeta { it.persistentDataContainer.set(NamespacedKey("arc", "opaque"), PersistentDataType.STRING, "keep") }
            }
            val items = MutableList<ItemStack?>(36) { null }.apply { this[27] = item }
            val icon = ItemStack(Material.CHEST)
            val iconOff = ItemStack(Material.BARRIER)
            val helmet = ItemStack(Material.LEATHER_HELMET)
            val originals = listOf(item, icon, iconOff, helmet).map(ItemStack::clone)
            val builder = "asConsole! builder systembook [playerName] random @starter"
            val oldCommands = listOf("asConsole! lands admin player [playerName] give claimblock 0 1 false", builder)
            val commands = listOf("asConsole! arc claimblock give [playerName] 1", builder)
            var nativeCommands = oldCommands
            every { cmi.kitsManager } returns manager
            every { manager.getKit("start", true) } returns kit
            every { manager.getKit("missing", true) } returns null
            every { manager.safeSave() } returns Unit
            every { kit.configName } returns "start"
            every { kit.commandName } returns "start"
            every { kit.displayName } returns "Стартовый"
            every { kit.isEnabled } returns true
            every { kit.delay } returns 1800L
            every { kit.icon } returns icon
            every { kit.iconOff } returns iconOff
            every { kit.items } returns items
            every { kit.getExtraItem(any<CMIInventorySlot>()) } returns null
            every { kit.getExtraItem(CMIInventorySlot.Helmet) } returns helmet
            every { kit.commands } answers { nativeCommands }
            every { kit.commands = any() } answers { nativeCommands = firstArg() }
            every { kit.conditions } returns emptyList()
            every { kit.description } returns emptyList()
            every { kit.cost } returns 0.0
            every { kit.expCost } returns 0
            every { kit.group } returns ""
            every { kit.weight } returns 0
            every { kit.slot } returns null
            every { kit.page } returns null
            every { kit.maxUsages } returns -1
            every { kit.isShowDespiteUsage } returns false
            every { kit.isShowDespiteWeight } returns false
            every { kit.isDropItems } returns true
            mockkStatic(CMI::class)
            try {
                every { CMI.getInstance() } returns cmi
                val body = JsonParser.parseString("""{"commandsOnly":true,"commands":["${commands[0]}","${commands[1]}"]}""").asJsonObject
                val previewBody = body.deepCopy().apply { addProperty("name", "start") }
                @Suppress("UNCHECKED_CAST")
                val before = (OpsCmiKitHandlers.listKits("start")["kits"] as List<Map<String, Any?>>).single()
                val preview = OpsCmiKitHandlers.preview("start", previewBody)
                preview - setOf("source", "updatedFields", "preview", "persisted") shouldBe before + ("commands" to commands)
                preview["updatedFields"] shouldBe listOf("commands")
                preview["preview"] shouldBe true
                preview["persisted"] shouldBe false
                previewBody.get("name").asString shouldBe "start"
                nativeCommands shouldBe oldCommands
                verify(exactly = 0) { kit.commands = any() }
                verify(exactly = 0) { manager.safeSave() }

                val invalid = listOf(
                    """{"commandsOnly":false,"commands":[]}""",
                    """{"commandsOnly":"true","commands":[]}""",
                    """{"commandsOnly":1,"commands":[]}""",
                    """{"commandsOnly":null,"commands":[]}""",
                    """{"commandsOnly":true}""",
                    """{"commandsOnly":true,"commands":null}""",
                    """{"commandsOnly":true,"commands":"command"}""",
                    """{"commandsOnly":true,"commands":[1]}""",
                    """{"commandsOnly":true,"commands":[" "]}""",
                    """{"commandsOnly":true,"commands":[],"delay":0}""",
                    """{"commandsOnly":true,"commands":[],"name":"other"}""",
                    """{"commandsOnly":true,"commands":[],"name":1}""",
                    """{"commandsOnly":true,"commands":[],"name":true}""",
                    """{"commandsOnly":true,"commands":[],"name":null}""",
                    """{"commandsOnly":true,"commands":[],"name":{}}""",
                    """{"commandsOnly":true,"commands":["${"x".repeat(513)}"]}""",
                    """{"commandsOnly":true,"commands":[${List(65) { "\"command\"" }.joinToString(",")}]}""",
                )
                invalid.forEach { json ->
                    val patch = JsonParser.parseString(json).asJsonObject
                    shouldThrow<IllegalArgumentException> { OpsCmiKitHandlers.preview("start", patch) }
                    shouldThrow<IllegalArgumentException> { OpsCmiKitHandlers.upsert("start", patch) }
                }
                shouldThrow<IllegalArgumentException> { OpsCmiKitHandlers.upsert("bad-name", body) }
                shouldThrow<NoSuchElementException> { OpsCmiKitHandlers.preview("missing", body) }
                shouldThrow<NoSuchElementException> { OpsCmiKitHandlers.upsert("missing", body) }
                shouldThrow<IllegalArgumentException> {
                    OpsCmiKitHandlers.upsert("start", JsonParser.parseString("""{"commands":[]}""").asJsonObject)
                }.message shouldBe "display is required"
                val empty = JsonParser.parseString("""{"commandsOnly":true,"commands":[]}""").asJsonObject
                OpsCmiKitHandlers.preview("start", empty)["commands"] shouldBe emptyList<String>()
                every { kit.icon } returns null
                OpsCmiKitHandlers.preview("start", body)["icon"] shouldBe mapOf("empty" to true)
                every { kit.icon } returns icon
                nativeCommands shouldBe oldCommands
                verify(exactly = 0) { kit.commands = any() }
                verify(exactly = 0) { manager.safeSave() }

                val updated = OpsCmiKitHandlers.upsert("start", previewBody)
                updated["created"] shouldBe false
                updated["saved"] shouldBe true
                updated["updatedFields"] shouldBe listOf("commands")
                updated["kit"] shouldBe before + ("commands" to commands)
                previewBody.get("name").asString shouldBe "start"
                nativeCommands shouldBe commands
                verify(exactly = 1) { kit.commands = commands }
                verify(exactly = 1) { manager.safeSave() }
                listOf(item, kit.icon, kit.iconOff, kit.getExtraItem(CMIInventorySlot.Helmet)) shouldBe originals
                (kit.items === items) shouldBe true
            } finally {
                unmockkStatic(CMI::class)
            }
        }
    }
})
