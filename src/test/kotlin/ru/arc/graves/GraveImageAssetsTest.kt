package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class GraveImageAssetsTest : FunSpec({
    test("private content-addressed assets repair corruption and distinguish changed images") {
        val directory = Files.createTempDirectory("grave-assets-test")
        try {
            fun png(color: Int): ByteArray {
                val image = BufferedImage(1, 2, BufferedImage.TYPE_INT_RGB)
                image.setRGB(0, 0, color)
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            var witness = png(0x010203)
            val candle = png(0x040500)
            fun resource(name: String) = ByteArrayInputStream(if (name.endsWith("witness.png")) witness else candle)
            val first = GraveImageAssets.extract(directory, ::resource)
            Files.readAllBytes(first[0]).toList() shouldBe witness.toList()
            Files.readAllBytes(first[1]).toList() shouldBe candle.toList()
            Files.write(first[0], byteArrayOf(9))
            GraveImageAssets.extract(directory, ::resource) shouldBe first
            Files.readAllBytes(first[0]).toList() shouldBe witness.toList()
            witness = png(0x080700)
            val second = GraveImageAssets.extract(directory, ::resource)
            (first[0] == second[0]) shouldBe false
            second[1] shouldBe first[1]
            Files.readAllBytes(second[0]).toList() shouldBe witness.toList()
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
})
