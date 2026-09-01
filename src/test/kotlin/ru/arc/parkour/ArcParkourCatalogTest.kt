package ru.arc.parkour

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class ArcParkourCatalogTest : StringSpec({
    "catalog groups ready courses by authored prefix and numerical order" {
        val categories = listOf(category("easy", 10, "easy", "Лёгкая <number>"), category("medium", 20, "med", "Средняя <number>"))
        val grouped =
            ArcParkourCatalog.group(
                listOf(
                    ParkourCourseSnapshot("easy10", 8, 0, false),
                    ParkourCourseSnapshot("med2", 6, 1, true),
                    ParkourCourseSnapshot("easy2", 4, 2, true),
                    ParkourCourseSnapshot("overworld1", 3, 0, false),
                ),
                categories,
            )

        grouped.map { it.definition.id } shouldContainExactly listOf("easy", "medium")
        grouped[0].courses.map { it.course.id } shouldContainExactly listOf("easy2", "easy10")
        grouped[0].courses.map(ParkourCourseCard::displayName) shouldContainExactly listOf("Лёгкая 2", "Лёгкая 10")
        grouped[1].courses.single().course.completed shouldBe true
    }

    "catalog accepts only a positive numeric suffix and keeps unknown ids out" {
        val category = category("easy", 10, "easy", "Лёгкая <number>")
        val grouped =
            ArcParkourCatalog.group(
                listOf(
                    ParkourCourseSnapshot("easy0", 1, 0, false),
                    ParkourCourseSnapshot("easy-1", 1, 0, false),
                    ParkourCourseSnapshot("easy1extra", 1, 0, false),
                    ParkourCourseSnapshot("EASY3", 1, 0, false),
                ),
                listOf(category),
            )

        grouped.single().courses.map { it.course.id } shouldContainExactly listOf("EASY3")
        ArcParkourCatalog.displayName("unknown", listOf(category)) shouldBe "unknown"
    }

    "joinall parser recognizes Parkour aliases and filtered forms only" {
        ParkourJoinAllCommand.matches("/pa joinall") shouldBe true
        ParkourJoinAllCommand.matches("  /PARKOUR JOINALL completed name_asc ") shouldBe true
        ParkourJoinAllCommand.matches("pkr joinall world") shouldBe true
        ParkourJoinAllCommand.matches("/pa join easy1") shouldBe false
        ParkourJoinAllCommand.matches("/parkour joinalligator") shouldBe false
        ParkourJoinAllCommand.matches("/other joinall") shouldBe false
    }

    "finish time keeps the HUD stable across short and long runs" {
        formatParkourMillis(-1) shouldBe "00:00.000"
        formatParkourMillis(61_234) shouldBe "01:01.234"
        formatParkourMillis(3_661_007) shouldBe "61:01.007"
    }
})

private fun category(
    id: String,
    order: Int,
    prefix: String,
    courseName: String,
) = ParkourCategoryDefinition(
    id = id,
    order = order,
    prefixes = listOf(prefix),
    name = id,
    courseName = courseName,
    courseDisplay = "<#92bed8><bold><course>",
    icon = Material.LEATHER_BOOTS,
    courseIcon = Material.STONE_PRESSURE_PLATE,
    display = id,
    description = listOf(id),
)
