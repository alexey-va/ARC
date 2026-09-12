package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.isRegularFile

class RewardCatalogDeploymentCompatibilityTest : StringSpec({
    "tracked runtime reward catalogues supplied by the deploy helper parse with this ARC source" {
        val paths =
            System.getProperty(RUNTIME_CATALOG_PATHS_PROPERTY)
                .orEmpty()
                .split(File.pathSeparatorChar)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(Path::of)

        paths.forEach { source ->
            source.isRegularFile() shouldBe true
            val root = Files.createTempDirectory("arc-runtime-reward-catalog")
            try {
                val target = root.resolve("modules/reward-catalog.yml")
                Files.createDirectories(target.parent)
                Files.copy(source, target)
                RewardCatalogModuleConfig.load(root).snapshot()
            } finally {
                Files.walk(root).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
})

private const val RUNTIME_CATALOG_PATHS_PROPERTY = "arc.rewardCatalogPaths"
