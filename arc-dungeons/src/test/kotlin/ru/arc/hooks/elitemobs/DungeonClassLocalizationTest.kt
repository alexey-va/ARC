package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class DungeonClassLocalizationTest : FreeSpec({
    "bundled Russian catalog covers every EliteMobs class form" {
        val directory = Files.createTempDirectory("arc-class-locale")
        val target = directory.resolve("elitemobs.yml")
        javaClass.classLoader.getResourceAsStream("modules/elitemobs.yml").use { source ->
            Files.copy(requireNotNull(source), target)
        }
        val config = ConfigManager.create(directory, "elitemobs.yml", "class-locale-${directory.fileName}")
        config.load()
        val locale = DungeonClassLocalization(config)
        val catalog = BuiltInClassContent.catalog()
        catalog.forms().size shouldBe 76

        catalog.forms().forEach { form ->
            locale.formName(form.id(), MISSING) shouldNotBe MISSING
            locale.ability(form.id(), "signature", MISSING, MISSING).also {
                it.name shouldNotBe MISSING
                it.description shouldNotBe MISSING
            }
            locale.ability(form.id(), "utility", MISSING, MISSING).also {
                it.name shouldNotBe MISSING
                it.description shouldNotBe MISSING
            }
            locale.passive(form.id(), MISSING, MISSING).also {
                it.source shouldNotBe MISSING
                it.description shouldNotBe MISSING
            }
        }
        catalog.roots().forEach { root ->
            config.string("dungeon-qol.classes.catalog.${root.id()}.role", MISSING) shouldNotBe MISSING
            locale.ability(root.id(), "mobility", MISSING, MISSING).also {
                it.name shouldNotBe MISSING
                it.description shouldNotBe MISSING
            }
        }
    }
}) {
    private companion object {
        const val MISSING = "__missing_translation__"
    }
}
