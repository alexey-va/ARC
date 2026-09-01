package ru.arc.parkour

data class ParkourCourseSnapshot(
    val id: String,
    val checkpoints: Int,
    val players: Int,
    val completed: Boolean,
)

data class ParkourCourseCard(
    val course: ParkourCourseSnapshot,
    val displayName: String,
    val sequence: Int,
)

data class ParkourCategorySnapshot(
    val definition: ParkourCategoryDefinition,
    val courses: List<ParkourCourseCard>,
)

object ArcParkourCatalog {
    fun group(
        courses: Collection<ParkourCourseSnapshot>,
        categories: List<ParkourCategoryDefinition>,
    ): List<ParkourCategorySnapshot> =
        categories.map { category ->
            val cards =
                courses.mapNotNull { course -> card(category, course) }
                    .sortedWith(compareBy<ParkourCourseCard> { it.sequence }.thenBy { it.course.id })
            ParkourCategorySnapshot(category, cards)
        }

    fun categoryFor(
        courseId: String,
        categories: List<ParkourCategoryDefinition>,
    ): ParkourCategoryDefinition? = categories.firstOrNull { match(it, courseId) != null }

    fun displayName(
        courseId: String,
        categories: List<ParkourCategoryDefinition>,
    ): String {
        val category = categoryFor(courseId, categories) ?: return courseId
        val number = checkNotNull(match(category, courseId))
        return category.courseName.replace("<number>", number.toString())
    }

    private fun card(
        category: ParkourCategoryDefinition,
        course: ParkourCourseSnapshot,
    ): ParkourCourseCard? {
        val sequence = match(category, course.id) ?: return null
        return ParkourCourseCard(course, category.courseName.replace("<number>", sequence.toString()), sequence)
    }

    private fun match(
        category: ParkourCategoryDefinition,
        courseId: String,
    ): Int? {
        val normalized = courseId.lowercase()
        for (prefix in category.prefixes) {
            if (!normalized.startsWith(prefix)) continue
            val suffix = normalized.removePrefix(prefix)
            val sequence = suffix.toIntOrNull() ?: continue
            if (sequence > 0 && prefix.length + suffix.length == normalized.length) return sequence
        }
        return null
    }
}

object ParkourJoinAllCommand {
    private val aliases = setOf("pa", "parkour", "pkr")

    fun matches(rawMessage: String): Boolean {
        val tokens = rawMessage.trim().removePrefix("/").split(Regex("\\s+")).filter(String::isNotEmpty)
        return tokens.size >= 2 && tokens[0].lowercase() in aliases && tokens[1].equals("joinall", ignoreCase = true)
    }
}
