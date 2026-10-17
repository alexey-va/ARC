package ru.arc.homemarkers

import com.google.gson.GsonBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class HomeMarkerSceneTest : StringSpec({
    "large loot marker stays above the saved floor and label clears its top" {
        val settings = HomeMarkerSettings()
        val shape = homeMarkerGeometry(settings.scale, true)
        shape.width shouldBe (1.6 plusOrMinus 0.00001)
        shape.height shouldBe (2.4 plusOrMinus 0.00001)
        (shape.itemY - settings.scale) shouldBe (0.05 plusOrMinus 0.00001)
        (shape.labelY - shape.floorY - shape.height) shouldBe (0.12 plusOrMinus 0.00001)
        shape.centerY shouldBe (1.25 plusOrMinus 0.00001)

        // Optional source-derived fixture for the offline model/texture preview; no runtime mutation.
        System.getenv("HOME_MARKER_PREVIEW_OUTPUT")?.let { destination ->
            val path = Path.of(destination)
            Files.createDirectories(path.parent)
            val marker = HomeMarker(HomeMarkerKind.HOME, "preview", UUID(0, 1), "Уютный дом", "world", 0.0, 0.0, 0.0)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "source" to "HomeMarkerSceneTest: actual homeMarkerGeometry + HomeMarkerSettings",
                "scale" to settings.scale,
                "labelScale" to settings.labelScale,
                "floorY" to shape.floorY,
                "height" to shape.height,
                "itemY" to shape.itemY,
                "labelY" to shape.labelY,
                "color" to settings.homeColor.asRGB(),
                "label" to PlainTextComponentSerializer.plainText().serialize(settings.label(marker)),
                "scope" to "Offline source geometry and resource textures; excludes native packets, client shading and interaction",
            )))
        }
    }

    "portable beacon fallback has a centered block origin instead of the loot model offset" {
        val shape = homeMarkerGeometry(1.6f, false)
        shape.height shouldBe (1.6 plusOrMinus 0.00001)
        (shape.itemY - shape.height / 2.0) shouldBe (0.05 plusOrMinus 0.00001)
        shape.centerY shouldBe shape.itemY
    }

    "private selection removes foreign owners and worlds and bounds nearest markers" {
        val owner = UUID(0, 1)
        val world = mockk<World> { every { name } returns "world" }
        val points = (1..70).map { index ->
            HomeMarker(HomeMarkerKind.HOME, index.toString(), owner, "Дом $index", "world", index * 0.1, 64.0, 0.0)
        }.reversed()
        val visible = nearbyHomeMarkers(owner, Location(world, 0.0, 64.0, 0.0), points + listOf(
            points.last().copy(id = "foreign", ownerId = UUID(0, 2)),
            points.last().copy(id = "other-world", world = "other"),
            points.last().copy(id = "far", x = 33.0),
        ), 32.0)
        visible.size shouldBe 64
        visible.first().id shouldBe "1"
        visible.last().id shouldBe "64"
        visible.all { it.ownerId == owner && it.world == "world" } shouldBe true
    }

    "home and warp names stay literal and source defaults do not require a resource pack" {
        val settings = HomeMarkerConfig(ConfigManager.empty()).snapshot()
        val marker = HomeMarker(HomeMarkerKind.HOME, "literal", UUID(0, 1), "<red>дом", "world", 0.0, 64.0, 0.0)
        settings.modelId shouldBe ""
        settings.refreshTicks shouldBe 100L
        PlainTextComponentSerializer.plainText().serialize(settings.label(marker)) shouldBe
            "Дом · <red>дом\nShift + ПКМ · Настройки"
        PlainTextComponentSerializer.plainText().serialize(settings.label(marker.copy(kind = HomeMarkerKind.WARP))) shouldBe
            "Варп · <red>дом\nShift + ПКМ · Настройки"
        shouldThrow<IllegalArgumentException> { settings.copy(scale = Float.NaN) }
        shouldThrow<IllegalArgumentException> { settings.copy(viewDistance = 0.0) }
    }
})
