package ru.arc.dungeons.api

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class CompassStripTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()
    fun strip(yaw: Float, markers: List<CompassStrip.Marker> = emptyList()) =
        plain.serialize(CompassStrip.render(yaw, markers))

    "cardinals retain Bukkit yaw orientation and the shared strip width" {
        mapOf(0f to 'S', 90f to 'W', 180f to 'N', -90f to 'E').forEach { (yaw, cardinal) ->
            strip(yaw).length shouldBe CompassStrip.WIDTH + 2
            strip(yaw)[CompassStrip.WIDTH / 2 + 1] shouldBe cardinal
        }
    }

    "the Java-shaped target entry point projects a marker at its bearing" {
        val center = plain.serialize(CompassStrip.render(0f, 0.0, "✖", 0x9bd48d))
        center.length shouldBe CompassStrip.WIDTH + 2
        center[CompassStrip.WIDTH / 2 + 1] shouldBe '✖'

        val east = plain.serialize(CompassStrip.render(350f, 10.0, "✖", 0x9bd48d))
        east[CompassStrip.WIDTH / 2 + 21] shouldBe '✖'
    }

    "an offscreen selected target leaves a turn cue at the matching edge" {
        val left = plain.serialize(CompassStrip.render(0f, 250.0, "✖", 0x9bd48d))
        val right = plain.serialize(CompassStrip.render(0f, 120.0, "✖", 0x9bd48d))
        left[1] shouldBe '←'
        right[CompassStrip.WIDTH] shouldBe '→'
    }

    "invalid bearings are omitted and yaw wraps across revolutions" {
        val withoutMarker = plain.serialize(CompassStrip.render(0f, emptyList<CompassStrip.Marker>()))
        plain.serialize(CompassStrip.render(0f, Double.NaN, "✖", 0x9bd48d)) shouldBe withoutMarker
        strip(-10f) shouldBe strip(710f)
    }
})
