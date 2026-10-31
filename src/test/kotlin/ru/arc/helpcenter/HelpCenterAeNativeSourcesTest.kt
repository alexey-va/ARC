package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.PluginCommand
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin

class HelpCenterAeNativeSourcesTest : StringSpec({
    "native dust range follows exact AE exclusive upper bound without consuming random state" {
        HelpCenterAeNativeSources.nativeDustRange(byteArrayOf(1, 15)) shouldBe (1..14)
        HelpCenterAeNativeSources.nativeDustRange(byteArrayOf(3, 3)) shouldBe (3..3)
    }

    "native mob chance gates every configured item without normalizing counts or weights" {
        val config = YamlConfiguration()
        config.set("mobs.ZOMBIE.chance", 25.0)
        config.set("mobs.ZOMBIE.items", listOf("whitescroll:2", "blackscroll:1"))
        HelpCenterAeNativeSources.nativeMobFacts("whitescroll", config).single().values shouldBe
            mapOf("mob" to "ZOMBIE", "chance" to "25", "amount" to "2")
        HelpCenterAeNativeSources.nativeMobFacts("blackscroll", config).single().values["chance"] shouldBe "25"

        config.set("mobs.ZOMBIE.chance", 2.5)
        HelpCenterAeNativeSources.nativeMobFacts("whitescroll", config).single().values["chance"] shouldBe "2,5"
        config.set("mobs.ZOMBIE.chance", 0.0)
        HelpCenterAeNativeSources.nativeMobFacts("whitescroll", config) shouldBe emptyList()
    }

    "tinkerer source follows the loaded book reward replacement flag" {
        val config = YamlConfiguration()
        config.set("general.give-item-instead-of-dust.enabled", false)
        HelpCenterAeNativeSources.tinkererFacts(config).single().values shouldBe mapOf("input" to "1", "output" to "1")
        config.set("general.give-item-instead-of-dust.enabled", true)
        HelpCenterAeNativeSources.tinkererFacts(config) shouldBe emptyList()
    }

    "gkit commands are reported only when the bare ae alias belongs to the bound AE plugin" {
        val ae = mockk<Plugin>()
        val other = mockk<Plugin>()
        val command = mockk<PluginCommand>()
        every { command.plugin } returns ae
        HelpCenterAeNativeSources.gkitCommandResolvesTo(ae, command) shouldBe true
        HelpCenterAeNativeSources.gkitCommandResolvesTo(other, command) shouldBe false
        HelpCenterAeNativeSources.gkitCommandResolvesTo(ae, null) shouldBe false
    }

    "native kit source follows enabled content and only accepts installed giveitem selectors" {
        val config = YamlConfiguration()
        config.set("gkits-enabled", true)
        config.set("kits.Paladin.permission", "gkit.paladin")
        config.set("kits.Paladin.delay", 259200)
        config.set("kits.Paladin.content.1.command", "ae giveitem %player% slotincreaser 1 ULTIMATE")
        config.set("kits.Paladin.content.2.command", "ae giveitem %player% randomizationscroll 1 ULTIMATE")
        val groups = setOf("ULTIMATE", "LEGENDARY")
        HelpCenterAeNativeSources.nativeGkitFacts("slotincreaser", config, groups).single().values shouldBe
            mapOf("kit" to "Paladin", "slot" to "1", "amount" to "1", "chance" to "100", "cooldown" to "3d")
        config.set("kits.Paladin.content.3.command", "ae giveitem %player% slotincreaser 2 ULTIMATE")
        config.set("kits.Paladin.content.3.chance", 10)
        config.set("kits.Paladin.content.4.command", "ae giveitem %player% slotincreaser 1 ULTIMATE")
        config.set("kits.Paladin.content.4.chance", 0)
        config.set("kits.Paladin.content.5.command", "ae giveitem %player% slotincreaser 1 ULTIMATE")
        config.set("kits.Paladin.content.5.chance", "invalid")
        val slots = HelpCenterAeNativeSources.nativeGkitFacts("slotincreaser", config, groups)
        slots.size shouldBe 2
        slots.last().values shouldBe mapOf("kit" to "Paladin", "slot" to "3", "amount" to "2", "chance" to "10", "cooldown" to "3d")
        config.set("kits.Paladin.content.3.chance", 150)
        HelpCenterAeNativeSources.nativeGkitFacts("slotincreaser", config, groups).last().values["chance"] shouldBe "100"
        config.set("kits.Paladin.content.6.command", "ae giveitem %player% slotincreaser 1 NONEXISTENT")
        HelpCenterAeNativeSources.nativeGkitFacts("slotincreaser", config, groups).size shouldBe 2
        config.set("kits.Paladin.content.7.command", "ae giveitem %player% itemnametag 1")
        HelpCenterAeNativeSources.nativeGkitFacts("itemnametag", config, groups) shouldBe emptyList()
        config.set("kits.Paladin.content.8.command", "ae giveitem %player% renametag 1")
        HelpCenterAeNativeSources.nativeGkitFacts("itemnametag", config, groups).size shouldBe 1
        config.set("kits.Paladin.content.9.command", "ae giveitem %player% soulgem 1")
        HelpCenterAeNativeSources.nativeGkitFacts("soulgem", config, groups) shouldBe emptyList()
        config.set("kits.Paladin.content.9.command", "ae giveitem %player% soulgem 1 250")
        HelpCenterAeNativeSources.nativeGkitFacts("soulgem", config, groups).size shouldBe 1
        config.set("kits.Paladin.content.10.command", "ae giveitem %player% orb 1 weapon 5 60")
        HelpCenterAeNativeSources.nativeGkitFacts("orb_weapon", config, groups).size shouldBe 1
        config.set("kits.Paladin.content.10.command", "ae giveitem %player% orb 1 weapon")
        HelpCenterAeNativeSources.nativeGkitFacts("orb_weapon", config, groups) shouldBe emptyList()
        HelpCenterAeNativeSources.nativeGkitFacts("randomizer", config, groups) shouldBe emptyList()
        config.set("gkits-enabled", false)
        HelpCenterAeNativeSources.nativeGkitFacts("slotincreaser", config, groups) shouldBe emptyList()
    }
})
