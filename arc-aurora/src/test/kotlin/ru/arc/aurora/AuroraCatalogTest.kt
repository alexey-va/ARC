package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.ServicePriority
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.ScheduledTask
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AuroraCatalogTest : FreeSpec({
    "showcase is a self-only arcaurora subcommand and fills missing profiles from the bundle" {
        withCatalog { fixture ->
            val plugin = fixture.plugin
            val admin = fixture.admin
            val target = fixture.target
            val commands = fixture.paper.server.commandMap
            plugin.getCommand("showcase") shouldBe null
            commands.getCommand("showcase") shouldBe null
            commands.getCommand("arcaurora:showcase") shouldBe null
            commands.getCommand("aaurora") shouldBe plugin.getCommand("arcaurora")
            fixture.command(target, "showcase")
            target.message() shouldBe "no-permission"
            fixture.complete(target, "show") shouldBe emptyList()
            fixture.command(fixture.paper.server.consoleSender, "showcase")
            fixture.complete(fixture.paper.server.consoleSender, "show") shouldBe emptyList()
            plugin.showcase(admin) shouldBe null
            fixture.complete(admin, "show") shouldBe listOf("showcase")
            fixture.complete(admin, "showcase", "particle_st") shouldBe listOf("PARTICLE_STAR", "PARTICLE_STELLAR_PRISM")

            fixture.command(admin, "preview self test-gallery fan")
            admin.message() shouldBe "preview-on"
            val preview = checkNotNull(plugin.preview(admin))
            fixture.command(admin, "showcase")
            admin.message() shouldBe "showcase-on"
            plugin.preview(admin) shouldBe null
            preview.previewTimer().isCancelled shouldBe true
            val first = checkNotNull(plugin.showcase(admin))
            first.profiles.map { it.id } shouldBe
                listOf("sky_stitch", "aurora_curtain", "invisible_hare", "paper_flotilla", "tumbleweed")
            first.expiresAt shouldBe 1_200L
            plugin.showcase(target) shouldBe null
            fixture.command(admin, "showcase ${target.name}")
            admin.message() shouldBe "showcase-effect-unknown"
            plugin.showcase(admin) shouldBe first

            fixture.command(admin, "showcase")
            admin.message() shouldBe "showcase-on"
            (plugin.showcase(admin) === first) shouldBe false
            fixture.command(admin, "showcase stop")
            admin.message() shouldBe "showcase-off"
            plugin.showcase(admin) shouldBe null
        }
    }

    "showcase selects active effects for three minutes and completes canonical effect names" {
        withCatalog { fixture ->
            val plugin = fixture.plugin
            val admin = fixture.admin
            val available = AuroraShape.active.filterNot { it.isIllustration }
            fixture.complete(admin, "SHOWCASE", "") shouldBe
                (available.map { it.showcaseId } + "stop").sorted()
            fixture.complete(admin, "showcase", "tRaCk_F") shouldBe listOf("TRACK_FLOCK", "TRACK_FLYBY")
            fixture.complete(admin, "showcase", "display_cl") shouldBe listOf("DISPLAY_CLOUD_FOX")
            fixture.complete(admin, "showcase", "lovecraft_") shouldBe listOf(
                "LOVECRAFT_AZATHOTH", "LOVECRAFT_BLINKING_EYE", "LOVECRAFT_CTHULHU", "LOVECRAFT_DAGON",
                "LOVECRAFT_ENDLESS_TENTACLES", "LOVECRAFT_HASTUR", "LOVECRAFT_NYARLATHOTEP",
                "LOVECRAFT_SHUB_NIGGURATH", "LOVECRAFT_WATCHING_EYES", "LOVECRAFT_YOG_SOTHOTH",
            )
            fixture.complete(admin, "showcase", "wh40_") shouldBe listOf(
                "WH40_IMPERIAL_AQUILA", "WH40_MECHANICUS_SIGIL", "WH40_ORK_KROOZER",
                "WH40_SOLAR_CORSAIR", "WH40_VOID_CATHEDRAL",
            )
            fixture.complete(admin, "showcase", "star_wars_") shouldBe listOf(
                "STAR_WARS_DEATH_STAR", "STAR_WARS_IMPERIAL_STAR_DESTROYER",
            )
            fixture.complete(admin, "showcase", "stop", "") shouldBe emptyList()
            fixture.complete(fixture.target, "showcase", "") shouldBe emptyList()
            fixture.complete(fixture.paper.server.consoleSender, "showcase", "") shouldBe emptyList()

            for (shape in available) {
                fixture.command(admin, "showcase ${shape.showcaseId.lowercase(Locale.ROOT)}")
                val rain = shape == AuroraShape.FROG_RAIN
                admin.message() shouldBe if (rain) "showcase-frog-rain-on" else "showcase-effect-on:${shape.showcaseId}"
                val showcase = checkNotNull(plugin.showcase(admin))
                showcase.profiles.map { it.shape } shouldBe List(AuroraShowcase.copyCount(shape)) { shape }
                showcase.expiresAt shouldBe 3_600L
            }
            fixture.command(admin, "showcase cthulhu")
            admin.message() shouldBe "showcase-effect-on:LOVECRAFT_CTHULHU"
            plugin.showcase(admin)!!.profiles.single().shape shouldBe AuroraShape.CTHULHU
            plugin.showcase(fixture.target) shouldBe null
            val selected = plugin.showcase(admin)
            for (retired in listOf(
                "jellyfish", "manta_ray", "astral_pendulum", "twin_samaras", "sky_phonograph",
                "kraken_drag", "rlyeh_rising", "dagon_tide", "night_gaunt", "colour_from_space", "shoggoth_escape", "silver_key",
                "sakura_idol", "sakura_idol_2d", "sakura_idol_map",
                "enderman_sky_theft", "sky_fishing", "frost_walker", "totem_heart", "ghast_portal",
                "storm_herd", "slime_anvil", "shattered_sword", "reverse_firework",
                "anime_sakura_idol", "anime_sakura_idol_2d", "anime_sakura_idol_map", "display_storm_herd",
            )) {
                fixture.complete(admin, "showcase", retired) shouldBe emptyList()
                fixture.command(admin, "showcase $retired")
                admin.message() shouldBe "showcase-effect-unknown"
                plugin.showcase(admin) shouldBe selected
            }
            fixture.command(admin, "showcase stop extra")
            admin.message() shouldBe "showcase-usage"
            plugin.showcase(admin) shouldBe selected
            fixture.command(admin, "SHOWCASE StOp")
            admin.message() shouldBe "showcase-off"
            plugin.showcase(admin) shouldBe null
        }
    }

    "both Ju Fufu variants remain hidden even with old showcase models on reload" {
        withCatalog { fixture ->
            val admin = fixture.admin
            val plugin = fixture.plugin
            val file = plugin.dataFolder.resolve("config.yml")
            YamlConfiguration.loadConfiguration(file).apply {
                set("showcase-illustrations.JU_FUFU.material", "PAPER")
                set("showcase-illustrations.JU_FUFU.custom-model-data", 175)
                set("showcase-illustrations.ANIME_JU_FUFU_2D.material", "PAPER")
                set("showcase-illustrations.ANIME_JU_FUFU_2D.custom-model-data", 173)
                save(file)
            }
            fixture.command(fixture.paper.server.consoleSender, "reload")
            checkNotNull(plugin.engine).settings.showcaseIllustrations shouldBe emptyMap()
            fixture.complete(admin, "showcase", "anime_") shouldBe emptyList()
            fixture.command(admin, "showcase LOVECRAFT_ENDLESS_TENTACLES")
            admin.message().contains("LOVECRAFT_ENDLESS_TENTACLES") shouldBe true
            val active = checkNotNull(plugin.showcase(admin))
            for (id in listOf("ANIME_JU_FUFU", "ANIME_JU_FUFU_2D", "JU_FUFU", "JU_FUFU_2D")) {
                fixture.command(admin, "showcase $id")
                admin.message().contains("Unknown effect.") shouldBe true
                plugin.showcase(admin) shouldBe active
            }
        }
    }

    "world change quit and reload clear a showcase owned by the old viewer or engine" {
        withCatalog { fixture ->
            val plugin = fixture.plugin
            val admin = fixture.admin
            fixture.command(admin, "showcase")
            (plugin.showcase(admin) != null) shouldBe true
            fixture.paper.callEvent(PlayerChangedWorldEvent(admin, admin.world))
            plugin.showcase(admin) shouldBe null

            fixture.command(admin, "showcase")
            (plugin.showcase(admin) != null) shouldBe true
            fixture.paper.callEvent(PlayerQuitEvent(admin, Component.empty()))
            plugin.showcase(admin) shouldBe null
            plugin.engine!!.join(admin)

            fixture.command(admin, "showcase")
            (plugin.showcase(admin) != null) shouldBe true
            val original = plugin.engine
            fixture.command(admin, "reload")
            (plugin.engine === original) shouldBe false
            plugin.showcase(admin) shouldBe null
        }
    }

    "reload waits for predecessor display cleanup and never activates after a failed drain" {
        for (success in listOf(true, false)) withCatalog { fixture ->
            val drain = CompletableFuture<Void>()
            fixture.plugin.engine!!.close()
            val previous = mockk<AuroraEngine>(relaxed = true)
            every { previous.closeAsync() } returns drain
            ArcAuroraPlugin::class.java.getDeclaredField("engine").apply { isAccessible = true }.set(fixture.plugin, previous)
            fixture.command(fixture.admin, "reload")
            fixture.plugin.engine shouldBe null
            fixture.paper.server.scheduler.performTicks(2)
            fixture.plugin.engine shouldBe null
            if (success) drain.complete(null) else drain.completeExceptionally(IllegalStateException("cleanup failed"))
            fixture.paper.server.scheduler.performTicks(2)
            (fixture.plugin.engine != null) shouldBe success
        }
    }

    "catalog commands and positional completion use the current presets behind the existing admin permission" {
        withCatalog { fixture ->
            val guest = fixture.paper.addPlayer("CatalogGuest")
            fixture.command(guest, "catalog")
            guest.message() shouldBe "no-permission"
            fixture.command(guest, "preview ${fixture.target.name} test-gallery fan")
            guest.message() shouldBe "no-permission"
            fixture.plugin.preview(fixture.target) shouldBe null
            fixture.complete(guest, "cat") shouldBe emptyList()
            fixture.complete(guest, "preview", "") shouldBe emptyList()

            fixture.command(fixture.admin, "catalog")
            fixture.admin.message() shouldBe "catalog-presets:test-gallery, treasure-hunt"
            fixture.command(fixture.admin, "catalog test-gallery")
            fixture.admin.message() shouldBe "catalog-profiles:test-gallery:coral, fan"
            fixture.command(fixture.admin, "catalog retired-gallery")
            fixture.admin.message() shouldBe "preset-not-found:retired-gallery"
            fixture.command(fixture.admin, "catalog missing")
            fixture.admin.message() shouldBe "preset-not-found:missing"
            fixture.command(fixture.admin, "catalog test-gallery extra")
            fixture.admin.message() shouldBe "usage"

            fixture.complete(fixture.admin, "cat") shouldBe listOf("catalog")
            fixture.complete(fixture.admin, "catalog", "test") shouldBe listOf("test-gallery")
            fixture.complete(fixture.admin, "catalog", "retired") shouldBe emptyList()
            fixture.complete(fixture.admin, "preview", "") shouldBe
                listOf("CatalogAdmin", "CatalogGuest", "CatalogTarget", "self")
            fixture.complete(fixture.paper.server.consoleSender, "preview", "self") shouldBe emptyList()
            fixture.complete(fixture.admin, "preview", "self", "test") shouldBe listOf("test-gallery")
            fixture.complete(fixture.admin, "preview", "self", "retired") shouldBe emptyList()
            fixture.complete(fixture.admin, "preview", "self", "test-gallery", "") shouldBe listOf("coral", "fan")
            fixture.complete(fixture.admin, "preview", "self", "missing", "") shouldBe emptyList()
            fixture.complete(fixture.admin, "preview", "self", "test-gallery", "fan", "") shouldBe emptyList()
        }
    }

    "explicit previews retain exactly the immutable selection and reject invalid replacements without disturbing it" {
        withCatalog { fixture ->
            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery fan")
            fixture.admin.message() shouldBe "preview-on"
            fixture.target.message() shouldBe "preview-on"
            val first = checkNotNull(fixture.plugin.preview(fixture.target))
            val selected = first.selectedProfiles()
            selected.map { it.id } shouldBe listOf("fan")
            (selected.single() === fixture.plugin.engine!!.settings.eventPresets.getValue("test-gallery")[0]) shouldBe true
            (fixture.plugin.previewSelection(fixture.target) === selected) shouldBe true
            shouldThrow<UnsupportedOperationException> { (selected as MutableList<AuroraProfile>).clear() }
            val firstTimer = first.previewTimer()

            fixture.command(fixture.admin, "preview ${fixture.target.name} missing")
            fixture.admin.message() shouldBe "preset-not-found:missing"
            (fixture.plugin.preview(fixture.target) === first) shouldBe true
            firstTimer.isCancelled shouldBe false
            fixture.command(fixture.admin, "preview ${fixture.target.name} retired-gallery")
            fixture.admin.message() shouldBe "preset-not-found:retired-gallery"
            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery old-glider")
            fixture.admin.message() shouldBe "profile-not-found:test-gallery:old-glider"
            (fixture.plugin.preview(fixture.target) === first) shouldBe true
            firstTimer.isCancelled shouldBe false
            fixture.command(fixture.admin, "preview Missing test-gallery fan")
            fixture.admin.message() shouldBe "player-not-found:Missing"
            (fixture.plugin.preview(fixture.target) === first) shouldBe true

            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery coral")
            val replacement = checkNotNull(fixture.plugin.preview(fixture.target))
            (replacement === first) shouldBe false
            firstTimer.isCancelled shouldBe true
            replacement.selectedProfiles().map { it.id } shouldBe listOf("coral")
            (fixture.plugin.previewSelection(fixture.target) === replacement.selectedProfiles()) shouldBe true
            fixture.command(fixture.admin, "preview self test-gallery")
            fixture.plugin.preview(fixture.admin)!!.selectedProfiles().map { it.id } shouldBe listOf("fan", "coral")
        }
    }

    "legacy preview commands still toggle treasure-hunt while explicit replacements own a fresh sixty second timer" {
        withCatalog { fixture ->
            fixture.command(fixture.admin, "preview")
            val self = checkNotNull(fixture.plugin.preview(fixture.admin))
            self.selectedProfiles().map { it.id } shouldBe listOf("legacy")
            fixture.command(fixture.admin, "preview")
            fixture.plugin.preview(fixture.admin) shouldBe null
            self.previewTimer().isCancelled shouldBe true

            fixture.command(fixture.admin, "preview ${fixture.target.name}")
            val legacy = checkNotNull(fixture.plugin.preview(fixture.target))
            legacy.selectedProfiles().map { it.id } shouldBe listOf("legacy")
            // This is command/timer coverage; render behavior has its own engine fixture.
            fixture.plugin.engine!!.quit(fixture.admin.uniqueId)
            fixture.plugin.engine!!.quit(fixture.target.uniqueId)
            fixture.paper.performTicks(600)
            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery fan")
            val replacement = checkNotNull(fixture.plugin.preview(fixture.target))
            legacy.previewTimer().isCancelled shouldBe true
            fixture.paper.performTicks(600)
            (fixture.plugin.preview(fixture.target) === replacement) shouldBe true
            fixture.paper.performTicks(599)
            (fixture.plugin.preview(fixture.target) === replacement) shouldBe true
            fixture.paper.performTicks(1)
            fixture.plugin.preview(fixture.target) shouldBe null
            replacement.previewTimer().isCancelled shouldBe true
        }
    }

    "reload quit and disable each clear the selected preview and cancel its owned timer" {
        withCatalog { fixture ->
            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery fan")
            val beforeReload = checkNotNull(fixture.plugin.preview(fixture.target))
            val engine = fixture.plugin.engine
            fixture.command(fixture.admin, "reload")
            (fixture.plugin.engine === engine) shouldBe false
            fixture.plugin.preview(fixture.target) shouldBe null
            beforeReload.previewTimer().isCancelled shouldBe true

            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery coral")
            val beforeQuit = checkNotNull(fixture.plugin.preview(fixture.target))
            fixture.paper.callEvent(PlayerQuitEvent(fixture.target, Component.empty()))
            fixture.plugin.preview(fixture.target) shouldBe null
            beforeQuit.previewTimer().isCancelled shouldBe true

            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery fan")
            val beforeDisable = checkNotNull(fixture.plugin.preview(fixture.target))
            fixture.paper.server.pluginManager.disablePlugin(fixture.plugin)
            fixture.plugin.preview(fixture.target) shouldBe null
            fixture.plugin.engine shouldBe null
            beforeDisable.previewTimer().isCancelled shouldBe true
        }
    }

    "automatic preview expiry owns a bounded drain timer and cannot stop a replacement" {
        for (replace in listOf(false, true)) withCatalog { fixture ->
            val settings = fixture.plugin.engine!!.settings
            fixture.plugin.engine!!.close()
            val engine = mockk<AuroraEngine>(relaxed = true)
            every { engine.settings } returns settings
            every { engine.closeAsync() } returns CompletableFuture.completedFuture(null)
            every { engine.beginPreviewDrain(fixture.target.uniqueId, any()) } returns 80L
            ArcAuroraPlugin::class.java.getDeclaredField("engine").apply { isAccessible = true }.set(fixture.plugin, engine)
            fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery fan")
            val preview = checkNotNull(fixture.plugin.preview(fixture.target))
            val admissionTimer = preview.previewTimer()
            fixture.paper.server.scheduler.performTicks(1199)
            (fixture.plugin.preview(fixture.target) === preview) shouldBe true
            verify(exactly = 0) { engine.beginPreviewDrain(any(), any()) }
            fixture.paper.server.scheduler.performTicks(1)
            verify(exactly = 1) { engine.beginPreviewDrain(fixture.target.uniqueId, match { it === preview.selectedProfiles() }) }
            (fixture.plugin.preview(fixture.target) === preview) shouldBe true
            admissionTimer.isCancelled shouldBe true
            val drainTimer = preview.previewTimer()
            (drainTimer === admissionTimer) shouldBe false
            fixture.paper.server.scheduler.performTicks(60)
            if (replace) {
                fixture.command(fixture.admin, "preview ${fixture.target.name} test-gallery coral")
                val replacement = checkNotNull(fixture.plugin.preview(fixture.target))
                drainTimer.isCancelled shouldBe true
                fixture.paper.server.scheduler.performTicks(20)
                (fixture.plugin.preview(fixture.target) === replacement) shouldBe true
                replacement.previewTimer().isCancelled shouldBe false
            } else {
                fixture.paper.server.scheduler.performTicks(19)
                (fixture.plugin.preview(fixture.target) === preview) shouldBe true
                fixture.paper.server.scheduler.performTicks(1)
                fixture.plugin.preview(fixture.target) shouldBe null
                drainTimer.isCancelled shouldBe true
            }
        }
    }
})

private fun withCatalog(block: (CatalogFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper -> block(CatalogFixture(paper)) }
    }
}

private class CatalogFixture(val paper: MockBukkitTestRuntime) {
    val plugin: ArcAuroraPlugin
    val admin: PlayerMock
    val target: PlayerMock

    init {
        val host = paper.createSimplePlugin("ArcCore")
        paper.createSimplePlugin("packetevents")
        paper.server.servicesManager.register(ArcVisualPacketBudget::class.java,
            mockk<ArcVisualPacketBudget>(relaxed = true), host, ServicePriority.Normal)
        plugin = paper.loadPlugin(ArcAuroraPlugin::class.java)
        plugin.isEnabled shouldBe true
        val file = plugin.dataFolder.resolve("config.yml")
        YamlConfiguration.loadConfiguration(file).apply {
            set("event-presets", null)
            set("night-sky.enabled", false)
            set("event-presets.treasure-hunt.legacy.sky-scene", true)
            set("event-presets.treasure-hunt.legacy.shape", "WAVE")
            set("event-presets.test-gallery.fan.sky-scene", true)
            set("event-presets.test-gallery.fan.shape", "MOON_FAN")
            set("event-presets.test-gallery.coral.sky-scene", true)
            set("event-presets.test-gallery.coral.shape", "SKY_CORAL")
            set("event-presets.test-gallery.old-glider.shape", "MANTA_RAY")
            set("event-presets.retired-gallery.old-swimmer.shape", "JELLYFISH")
            save(file)
        }
        command(paper.server.consoleSender, "reload")
        plugin.engine!!.settings.eventPresets.keys shouldBe setOf("treasure-hunt", "test-gallery")
        val templates = mapOf(
            "catalog-presets" to "catalog-presets:<presets>",
            "catalog-profiles" to "catalog-profiles:<preset>:<profiles>",
            "preset-not-found" to "preset-not-found:<preset>",
            "profile-not-found" to "profile-not-found:<preset>:<profile>",
            "player-not-found" to "player-not-found:<player>",
            "showcase-effect-on" to "showcase-effect-on:<effect>",
        )
        val catalog = object : LocaleCatalog {
            override fun scalar(path: String): String? = if (path == "prefix") "" else templates[path] ?: path
            override fun lines(path: String): List<String>? = null
        }
        ArcAuroraPlugin::class.java.getDeclaredField("messages").apply { isAccessible = true }.set(plugin,
            LocalizedMiniMessage(mapOf("ru" to catalog, "en" to catalog), { "en" }))
        admin = paper.addPlayer("CatalogAdmin").apply { isOp = true }
        target = paper.addPlayer("CatalogTarget")
    }

    fun command(sender: CommandSender, arguments: String) {
        paper.server.dispatchCommand(sender, "arcaurora $arguments") shouldBe true
    }

    fun complete(sender: CommandSender, vararg args: String): List<String> =
        checkNotNull(plugin.getCommand("arcaurora")).tabComplete(sender, "arcaurora", args)
}

private fun PlayerMock.message(): String =
    PlainTextComponentSerializer.plainText().serialize(checkNotNull(nextComponentMessage()))

private fun ArcAuroraPlugin.preview(player: Player): Any? =
    (ArcAuroraPlugin::class.java.getDeclaredField("previews").apply { isAccessible = true }.get(this) as Map<*, *>)[player.uniqueId]

private fun ArcAuroraPlugin.showcase(player: Player): AuroraShowcase? {
    val shows = AuroraEngine::class.java.getDeclaredField("showcases").apply { isAccessible = true }.get(engine) as Map<*, *>
    val show = shows[player.uniqueId] ?: return null
    return show.javaClass.getDeclaredField("panel").apply { isAccessible = true }.get(show) as AuroraShowcase
}

@Suppress("UNCHECKED_CAST")
private fun Any.selectedProfiles(): List<AuroraProfile> =
    javaClass.getDeclaredField("profiles").apply { isAccessible = true }.get(this) as List<AuroraProfile>

private fun Any.previewTimer(): ScheduledTask =
    javaClass.getDeclaredField("timer").apply { isAccessible = true }.get(this) as ScheduledTask

@Suppress("UNCHECKED_CAST")
private fun ArcAuroraPlugin.previewSelection(player: Player): List<AuroraProfile>? {
    val callback = AuroraEngine::class.java.getDeclaredField("previewProfiles").apply { isAccessible = true }
        .get(engine) as (UUID) -> List<AuroraProfile>?
    return callback(player.uniqueId)
}
