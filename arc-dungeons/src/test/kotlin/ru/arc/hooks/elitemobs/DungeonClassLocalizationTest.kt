package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class DungeonClassLocalizationTest : FreeSpec({
    val directory = Files.createTempDirectory("arc-class-locale")
    val target = directory.resolve("elitemobs.yml")
    javaClass.classLoader.getResourceAsStream("modules/elitemobs.yml").use { source ->
        Files.copy(requireNotNull(source), target)
    }
    val config = ConfigManager.create(directory, "elitemobs.yml", "class-locale-${directory.fileName}")
    config.load()
    val locale = DungeonClassLocalization(config)

    "bundled Russian catalog names every form and describes native numerical effects" {
        val catalog = BuiltInClassContent.catalog()
        catalog.forms().size shouldBe 76
        catalog.forms().forEach { form ->
            locale.formName(form.id(), MISSING) shouldNotBe MISSING
            locale.passive(form.id(), MISSING, 1).also { passive ->
                passive.source shouldNotBe MISSING
                passive.contributionLevel shouldBe 1
                passive.effects.isNotEmpty() shouldBe true
                passive.effects.all { it.fraction.isFinite() && it.fraction != 0.0 } shouldBe true
                passive.effects.none { it.label.contains("__missing") } shouldBe true
            }
        }
        catalog.roots().forEach { root ->
            config.string("dungeon-qol.classes.catalog.${root.id()}.role", MISSING) shouldNotBe MISSING
        }
    }

    "Occultist 39 inherits growing parent contribution rather than saved parent level" {
        val lineage = BuiltInClassContent.catalog().lineageOf("occultist")
        val passives = lineage.passiveContributionLevels(39).map { (id, level) -> locale.passive(id, id, level) }
        passives.map { it.contributionLevel } shouldBe listOf(39, 9)
        passives[0].effects.single { it.label == "Наносимый урон" }.fraction shouldBe .015 + .00020 * 39
        passives[1].effects.single { it.label == "Наносимый урон" }.fraction shouldBe .02 + .00025 * 9
    }

    "incoming damage has the opposite benefit sign and conditional bonuses retain their trigger" {
        val paladin = locale.passive("paladin", "Паладин", 30)
        paladin.effects.single { it.label == "Получаемый урон" }.beneficial shouldBe true
        val bloodrager = locale.passive("bloodrager", "Кровавый яростник", 30)
        bloodrager.effects.single { it.label == "Получаемый урон (здоровье < 50%)" }.beneficial shouldBe false
        bloodrager.effects.single { it.label == "Наносимый урон (здоровье < 50%)" }.fraction shouldBe .06
    }

    "passive-only mechanics keep control resistance and party movement visible" {
        locale.passive("juggernaut", "Джаггернаут", 30).effects.any { it.label == "Сопротивление контролю" && it.fraction > 0 } shouldBe true
        locale.passive("pathfinder", "Следопыт", 30).effects.any { it.label.startsWith("Скорость группы рядом") && it.fraction > 0 } shouldBe true
    }

    "percent values have explicit signs and enough precision for inherited bonuses" {
        dungeonPassivePercent(.02225) shouldBe "+2,23%"
        dungeonPassivePercent(-.01) shouldBe "−1%"
        dungeonPassivePercent(.0001) shouldBe "+0,01%"
    }
}) {
    private companion object { const val MISSING = "__missing_translation__" }
}
