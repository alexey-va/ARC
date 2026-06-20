package ru.arc.listeners

import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.util.Logging.error
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class IAEvents : Listener {

    private val BOOTS = "items=leather_boots"
    private val REPLACE_BOOTS = "items=leather_boots iron_boots golden_boots chainmail_boots diamond_boots netherite_boots"
    private val LEGGINGS = "items=leather_leggings"
    private val REPLACE_LEGGINGS = "items=leather_leggings iron_leggings golden_leggings chainmail_leggings diamond_leggings netherite_leggings"
    private val CHESTPLATE = "items=leather_chestplate"
    private val REPLACE_CHESTPLATE = "items=leather_chestplate iron_chestplate golden_chestplate chainmail_chestplate diamond_chestplate netherite_chestplate"
    private val HELMET = "items=leather_helmet"
    private val REPLACE_HELMET = "items=leather_helmet iron_helmet golden_helmet chainmail_helmet diamond_helmet netherite_helmet"
    private val layer1key = "texture.leather_layer_1"
    private val layer2key = "texture.leather_layer_2"
    private val allLayers1Keys = listOf(
        "texture.iron_layer_1", "texture.gold_layer_1", "texture.chainmail_layer_1",
        "texture.diamond_layer_1", "texture.netherite_layer_1",
    )
    private val allLayers2Keys = listOf(
        "texture.iron_layer_2", "texture.gold_layer_2", "texture.chainmail_layer_2",
        "texture.diamond_layer_2", "texture.netherite_layer_2",
    )

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRpCompress(event: ItemsAdderPackCompressedEvent) {
        val path = ARC.instance.dataFolder.toPath()
        val emHookConfig = ConfigManager.of(path, "ia-hooks.yml")
        val pathToZip = emHookConfig.string("path-to-zip", "ItemsAdder/output/generated.zip")
        val zipPath = path.parent.resolve(pathToZip)
        if (!Files.exists(zipPath)) return

        val tempDir = Files.createTempDirectory("ia-hooks-temp")
        unzip(zipPath, tempDir)

        Files.walk(tempDir).use { walk ->
            walk.filter { it.toString().endsWith(".properties") }
                .filter { it.toString().contains("ia_generated") }
                .forEach { p ->
                    try {
                        replaceLineInFile(p, BOOTS, REPLACE_BOOTS)
                        replaceLineInFile(p, LEGGINGS, REPLACE_LEGGINGS)
                        replaceLineInFile(p, CHESTPLATE, REPLACE_CHESTPLATE)
                        replaceLineInFile(p, HELMET, REPLACE_HELMET)
                    } catch (e: Exception) {
                        error("Error processing property file: {}", p, e)
                    }
                }
        }

        zip(tempDir, zipPath.toString())
    }

    private fun unzip(zipPath: java.nio.file.Path, destDir: java.nio.file.Path) {
        val zipUri = URI.create("jar:file:" + zipPath.toUri().path)
        FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { zipFs ->
            val root = zipFs.getPath("/")
            Files.walk(root).use { walk ->
                walk.forEach { p ->
                    try {
                        val dest = destDir.resolve(root.relativize(p).toString())
                        if (Files.isDirectory(p)) Files.createDirectories(dest)
                        else Files.copy(p, dest)
                    } catch (e: Exception) {
                        error("Error unzipping file: {}", p, e)
                    }
                }
            }
        }
    }

    private fun zip(sourceDir: java.nio.file.Path, zipFilePath: String) {
        val zipPath = Paths.get(zipFilePath)
        Files.deleteIfExists(zipPath)
        val zipUri = URI.create("jar:file:" + zipPath.toUri().path)
        FileSystems.newFileSystem(zipUri, mapOf("create" to "true")).use { zipFs ->
            Files.walk(sourceDir).use { walk ->
                walk.filter { !Files.isDirectory(it) }.forEach { path ->
                    val destPath = zipFs.getPath("/" + sourceDir.relativize(path))
                    try {
                        Files.createDirectories(destPath.parent)
                        Files.copy(path, destPath, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: Exception) {
                        error("Error zipping file: {}", path, e)
                    }
                }
            }
        }
    }

    private fun replaceLineInFile(path: java.nio.file.Path, target: String, replace: String) {
        if (updateBaseArmor(path)) return
        val list = Files.readAllLines(path).toMutableList()
        var found = false
        var layer1: String? = null
        var layer2: String? = null

        for (i in list.indices) {
            val line = list[i]
            if (line.contains(layer1key)) layer1 = line.split("=")[1]
            if (line.contains(layer2key)) layer2 = line.split("=")[1]
            if (line.contains(target)) {
                list[i] = line.replace(target, replace)
                found = true
            }
        }
        if (found && layer1 != null && layer2 != null) {
            allLayers1Keys.forEach { key -> list.add("$key=$layer1") }
            allLayers2Keys.forEach { key -> list.add("$key=$layer2") }
        }
        Files.write(path, list)
    }

    private fun updateBaseArmor(path: java.nio.file.Path): Boolean {
        if (!path.toString().contains("base_leather")) return false
        val parent = path.parent
        val pluginFolder = ARC.instance.dataFolder.toPath()
        val iaData = pluginFolder.resolve("ia_data")
        if (!Files.exists(iaData)) Files.createDirectories(iaData)

        Files.walk(iaData)
            .filter { it.toString().endsWith(".png") }
            .filter { it.fileName.toString().startsWith("leather_layer") }
            .forEach { p ->
                try {
                    Files.copy(p, parent.resolve(p.fileName), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    error("Error copying file: {}", p, e)
                }
            }

        val fileContent = """
            argType=armor
            items=leather_helmet leather_chestplate leather_leggings leather_boots
            texture.leather_layer_1_overlay=leather_layer_1_overlay.png
            texture.leather_layer_2_overlay=leather_layer_2_overlay.png
            texture.leather_layer_1=leather_layer_1.png
            texture.leather_layer_2=leather_layer_2.png
        """.trimIndent() + "\n"
        Files.write(path, fileContent.toByteArray())
        return true
    }
}
