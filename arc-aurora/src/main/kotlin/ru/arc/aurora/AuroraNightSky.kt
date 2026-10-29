package ru.arc.aurora

import org.bukkit.World

/** Automatic preset selection uses world time; it never changes the world's clock. */
data class AuroraNightSkySettings(
    val enabled: Boolean = false,
    val preset: String = "treasure-hunt",
    val timeStart: Int = 13000,
    val timeEnd: Int = 23000,
    val worlds: List<String> = listOf("*"),
    val environments: List<World.Environment> = listOf(World.Environment.NORMAL),
) {
    private val worldPatterns = worlds.map(::compileGlob)

    fun matches(worldName: String, environment: World.Environment, time: Long): Boolean {
        if (!enabled || environment !in environments || worldPatterns.none { it.matches(worldName) }) return false
        val dayTime = Math.floorMod(time, 24000L).toInt()
        return if (timeStart <= timeEnd) dayTime in timeStart..timeEnd
            else dayTime >= timeStart || dayTime <= timeEnd
    }
}
