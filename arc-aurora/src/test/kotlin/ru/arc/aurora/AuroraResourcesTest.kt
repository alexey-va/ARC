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
        sky.size shouldBe 13
        val wave = sky.single { it.id == "sky_wave" }
        wave.skyOptions shouldBe AuroraSkyOptions(freshHeadRepair = false)
        wave.skyTracers shouldBe 1
        wave.points shouldBe 192
        wave.particle shouldBe org.bukkit.Particle.END_ROD
        sky.filter { it.skyRefreshTicks > 0 }.map { it.shape } shouldBe listOf(AuroraShape.CRYSTAL)
        sky.filter { it.shape == AuroraShape.CRYSTAL }.all {
            it.skyRefreshTicks == 40 && it.skyOptions.strokeDrift == 0.0 && it.skyOptions.strokeLift == 0.0
        } shouldBe true
        val tracks = sky.filter { it.shape.isTrack }
        tracks.map { it.shape }.toSet() shouldBe setOf(AuroraShape.TRACK_SPIRAL, AuroraShape.TRACK_PAIR, AuroraShape.TRACK_SCATTER)
        tracks.all { it.skyRefreshTicks == 0 && it.points / it.skyTracers <= 48 &&
            it.particle in setOf(org.bukkit.Particle.SOUL_FIRE_FLAME, org.bukkit.Particle.FLAME) } shouldBe true
        settings.skyPacketsPerViewer shouldBe 36
        settings.skyField.maxScenes shouldBe 9
        sky.all { it.updateTicks == 1 && it.minDistance >= 28 && it.radius <= 96 } shouldBe true
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
