package ru.arc.gui

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.arc.config.Config

/**
 * Tests for GuiDefaults configuration.
 */
class GuiDefaultsTest :
    DescribeSpec({

        beforeEach {
            GuiDefaults.reset()
        }

        afterEach {
            GuiDefaults.reset()
        }

        describe("without config") {

            it("should return hardcoded default for background material") {
                GuiDefaults.Background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
            }

            it("should return hardcoded default for background model data") {
                GuiDefaults.Background.modelData shouldBe 11000
            }

            it("should return hardcoded default for content material") {
                GuiDefaults.Background.contentMaterial shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
            }

            it("should return hardcoded default for back button") {
                GuiDefaults.BackButton.material shouldBe Material.BLUE_STAINED_GLASS_PANE
                GuiDefaults.BackButton.modelData shouldBe 11013
                GuiDefaults.BackButton.defaultDisplay shouldBe "<gray>« Назад"
                GuiDefaults.BackButton.defaultCommand shouldBe "menu"
            }

            it("should return hardcoded default for prev button") {
                GuiDefaults.PrevButton.material shouldBe Material.BLUE_STAINED_GLASS_PANE
                GuiDefaults.PrevButton.modelData shouldBe 11009
                GuiDefaults.PrevButton.defaultDisplay shouldBe "<gray>« Предыдущая"
            }

            it("should return hardcoded default for next button") {
                GuiDefaults.NextButton.material shouldBe Material.BLUE_STAINED_GLASS_PANE
                GuiDefaults.NextButton.modelData shouldBe 11008
                GuiDefaults.NextButton.defaultDisplay shouldBe "<gray>Следующая »"
            }

            it("should return hardcoded default for confirm button") {
                GuiDefaults.ConfirmButton.material shouldBe Material.LIME_STAINED_GLASS_PANE
                GuiDefaults.ConfirmButton.modelData shouldBe 0
            }

            it("should return hardcoded default for cancel button") {
                GuiDefaults.CancelButton.material shouldBe Material.RED_STAINED_GLASS_PANE
                GuiDefaults.CancelButton.modelData shouldBe 0
            }

            it("should return hardcoded default for slots") {
                GuiDefaults.Slots.back shouldBe 0
                GuiDefaults.Slots.prev shouldBe 3
                GuiDefaults.Slots.next shouldBe 5
                GuiDefaults.Slots.confirm shouldBe 2
                GuiDefaults.Slots.cancel shouldBe 6
            }

            it("should return hardcoded default for messages") {
                GuiDefaults.Messages.cooldown shouldBe "<red>Подождите..."
                GuiDefaults.Messages.error shouldBe "<red>Ошибка"
                GuiDefaults.Messages.noPermission shouldBe "<red>Нет прав"
            }
        }

        describe("with config") {
            val emptyDefaults = emptyMap<String, Any>()

            it("should use config value for background material") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("background") } returns true
                        every { map("background", emptyDefaults) } returns mapOf("material" to "BLACK_STAINED_GLASS_PANE")
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)

                GuiDefaults.Background.material shouldBe Material.BLACK_STAINED_GLASS_PANE
            }

            it("should use config value for model data") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("background") } returns true
                        every { map("background", emptyDefaults) } returns mapOf("customModelData" to 99999)
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)

                GuiDefaults.Background.modelData shouldBe 99999
            }

            it("should use config value for button material") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("buttons.back") } returns true
                        every { map("buttons.back", emptyDefaults) } returns mapOf("material" to "DIAMOND_BLOCK")
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)

                GuiDefaults.BackButton.material shouldBe Material.DIAMOND_BLOCK
            }

            it("should use config value for button display") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("buttons.back") } returns true
                        every { map("buttons.back", emptyDefaults) } returns mapOf("display" to "<gold>Go Back!")
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)

                GuiDefaults.BackButton.defaultDisplay shouldBe "<gold>Go Back!"
            }

            it("should use config value for slots") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                        every { integer("slots.back", any()) } returns 8
                        every { integer("slots.prev", any()) } returns 2
                        every { integer("slots.next", any()) } returns 6
                    }

                GuiDefaults.init(config)

                GuiDefaults.Slots.back shouldBe 8
                GuiDefaults.Slots.prev shouldBe 2
                GuiDefaults.Slots.next shouldBe 6
            }

            it("should fall back to default for invalid material") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("background") } returns true
                        every { map("background", emptyDefaults) } returns mapOf("material" to "INVALID_MATERIAL_NAME")
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)

                GuiDefaults.Background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
            }

            it("should fall back to default for blank material") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("background") } returns true
                        every { map("background", emptyDefaults) } returns mapOf("material" to "   ")
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)

                GuiDefaults.Background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
            }
        }

        describe("reset") {

            it("should reset to hardcoded defaults after reset") {
                val config =
                    mockk<Config> {
                        every { exists(any<String>()) } returns false
                        every { exists("background") } returns true
                        every { map("background", emptyMap<String, Any>()) } returns mapOf("material" to "DIAMOND_BLOCK")
                        every { string(any<String>(), any()) } returns ""
                        every { integer(any<String>(), any()) } answers { secondArg() }
                    }

                GuiDefaults.init(config)
                GuiDefaults.Background.material shouldBe Material.DIAMOND_BLOCK

                GuiDefaults.reset()

                GuiDefaults.Background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
            }
        }
    })
