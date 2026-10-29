package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import io.mockk.every
import io.mockk.mockk
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion

class AuroraResourcesTest : FreeSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec {
        val api = mockk<PacketEventsAPI<Any>>()
        every { api.settings } returns PacketEventsSettings()
        every { api.serverManager } returns ServerManager { ServerVersion.V_1_21_11 }
        PacketEvents.setAPI(api)
    }
    afterSpec { PacketEvents.setAPI(previousApi) }
    "bundled effects parse as a complete usable config and locales have the same keys" {
        fun resource(path: String) = YamlConfiguration().apply {
            loadFromString(checkNotNull(javaClass.classLoader.getResourceAsStream(path)).bufferedReader().use { it.readText() })
        }
        val settings = AuroraConfig.parse(resource("config.yml"))
        settings.permission shouldBe "arcaurora.view"
        settings.profiles.size shouldBe 25
        val sky = settings.eventPresets.getValue("treasure-hunt")
        sky.all { it.skyOptions == AuroraSkyOptions() } shouldBe true
        sky.map { it.skyTracers } shouldBe listOf(1, 3, 1, 1, 1)
        sky.map { it.points } shouldBe listOf(192, 192, 96, 160, 192)
        sky.all { it.updateTicks == 1 && it.minDistance >= 28 && it.radius <= 64 } shouldBe true
        settings.eventPresets["treasure-hunt"].orEmpty().any { it.skyScene && it.points > 24 } shouldBe true
        settings.ping shouldBe AuroraPingSettings(250, 180, 10, 20)
        (settings.profiles + settings.eventPresets.values.flatten()).forEach { profile ->
            ParticleTypes.getByName(profile.particle.key.toString()).name.toString() shouldBe profile.particle.key.toString()
        }
        resource("locales/ru.yml").getKeys(true) shouldBe resource("locales/en.yml").getKeys(true)
        val descriptor = resource("plugin.yml")
        descriptor.getBoolean("permissions.arcaurora.view.default") shouldBe false
        descriptor.getStringList("depend").toSet() shouldBe setOf("ArcCore", "packetevents")
    }
})
