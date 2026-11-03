package ru.arc.graves

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Called on ARC's background worker; never writes to Yamipa's shared player library. */
internal object GraveImageAssets {
    private val resources = listOf("graves/engraving/witness.png", "graves/engraving/candle.png")

    fun extract(dataDirectory: Path, resource: (String) -> InputStream?): List<Path> {
        val directory = dataDirectory.resolve("cache/grave-engraving-assets")
        Files.createDirectories(directory)
        return resources.map { name ->
            val bytes = requireNotNull(resource(name)) { "Missing bundled grave image $name" }.use { it.readBytes() }
            val image = requireNotNull(ImageIO.read(bytes.inputStream())) { "Invalid bundled grave image $name" }
            require(image.width in 1..2048 && image.height == image.width * 2) { "Grave image must have a 1:2 aspect ratio: $name" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val destination = directory.resolve("$digest.png")
            if (!Files.isRegularFile(destination) || !Files.readAllBytes(destination).contentEquals(bytes)) {
                val temporary = Files.createTempFile(directory, ".grave-image-", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally { Files.deleteIfExists(temporary) }
            }
            destination
        }
    }
}
