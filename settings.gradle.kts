rootProject.name = "ARC"

val arcCoreDir = sequenceOf(
    file("../arc-core"),
    file("../../IdeaProjects/arc-core"),
).firstOrNull { it.resolve("settings.gradle.kts").isFile }
    ?: error(
        """
        arc-core not found. Clone https://github.com/alexey-va/arc-core:
          mcserver: ../arc-core submodule
          IdeaProjects: ~/IdeaProjects/arc-core
        """.trimIndent(),
    )

includeBuild(arcCoreDir)
