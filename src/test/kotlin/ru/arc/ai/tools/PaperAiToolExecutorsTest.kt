package ru.arc.ai.tools

import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase

class PaperAiToolExecutorsTest :
    KotestTestBase({
        describe("inventory item display names") {
            it("serializes the current Adventure component API as plain text") {
                val stack = ItemStack(Material.DIAMOND)
                stack.editMeta { meta -> meta.displayName(Component.text("Reward")) }

                PaperAiToolExecutors.itemDisplayName(stack) shouldBe "Reward"
            }

            it("omits items without a custom display name") {
                PaperAiToolExecutors.itemDisplayName(ItemStack(Material.DIAMOND)) shouldBe null
            }
        }
    })
