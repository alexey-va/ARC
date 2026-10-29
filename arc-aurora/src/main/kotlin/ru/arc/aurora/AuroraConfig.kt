package ru.arc.aurora

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.configuration.ConfigurationSection
import java.util.Collections

data class AuroraSettings(
    val permission: String,
    val intervalTicks: Int,
    val viewersPerTick: Int,
    val attemptsPerViewer: Int,
    val packetsPerViewer: Int,
    val nativeBudgetMicros: Int,
    val biomeAliases: Map<String, String>,
    val profiles: List<AuroraProfile>,
    val eventPresets: Map<String, List<AuroraProfile>>,
    val ping: AuroraPingSettings = AuroraPingSettings(),
    val skyPacketsPerViewer: Int = 36,
) {
    /** Exact, single-hop aliases; unknown biome keys remain usable without a registry lookup. */
    fun canonicalBiomeKey(raw: String): String = normalizeBiomeKey(raw).let { biomeAliases[it] ?: it }
}

data class AuroraPingSettings(
    val maxCurrentMs: Int = 250,
    val maxAverageMs: Int = 180,
    val windowSamples: Int = 10,
    val sampleIntervalTicks: Int = 20,
)

/** Main-thread-owned, constant-time rolling average. Empty or negative samples fail closed. */
class AuroraPingWindow(val capacity: Int) {
    init {
        require(capacity in 1..120) { "Ping window capacity must be in 1..120" }
    }

    private val samples = IntArray(capacity)
    private var cursor = 0
    private var count = 0
    private var sum = 0L
    private var invalid = 0

    val averageMs: Double
        get() = if (count == 0 || invalid > 0) Double.POSITIVE_INFINITY else sum.toDouble() / count

    /** A negative sample rejects admission until that sample rolls out of the fixed window. */
    fun sample(ping: Int) {
        if (count == capacity) {
            val previous = samples[cursor]
            if (previous < 0) invalid-- else sum -= previous
        } else {
            count++
        }
        samples[cursor] = ping
        if (ping < 0) invalid++ else sum += ping
        cursor = (cursor + 1) % capacity
    }

    fun admits(currentPing: Int, settings: AuroraPingSettings): Boolean =
        currentPing in 0..settings.maxCurrentMs && averageMs <= settings.maxAverageMs
}

enum class AuroraWeather { ANY, CLEAR, RAIN, THUNDER }

enum class AuroraShape { POINT, LINE, CUBE, RING, CIRCLE, SPHERE, WAVE }

data class AuroraProfile(
    val id: String,
    val priority: Int,
    val biomes: List<String>,
    val worlds: List<String>,
    val timeStart: Int,
    val timeEnd: Int,
    val weather: AuroraWeather,
    val minY: Int,
    val maxY: Int,
    val minSkyLight: Int,
    val maxSkyLight: Int,
    val underwater: Boolean,
    val radius: Double,
    val minDistance: Double,
    val verticalMin: Double,
    val verticalMax: Double,
    val shape: AuroraShape,
    val shapeSize: Double,
    val points: Int,
    val particle: Particle,
    val count: Int,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val speed: Double,
    val particleData: Any?,
    val skyScene: Boolean = false,
    val sceneDurationTicks: Int = 240,
    val motionPeriodTicks: Int = 400,
    val updateTicks: Int = 4,
    val spawnDelayTicks: Int = 60,
    val skyTracers: Int = 1,
    val skyRefreshTicks: Int = 0,
    val skyOptions: AuroraSkyOptions = AuroraSkyOptions(),
) {
    private val biomePatterns = biomes.map(::compileGlob)
    private val worldPatterns = worlds.map(::compileGlob)

    /**
     * Bounds are inclusive; a time range wrapping midnight uses either side of the boundary.
     * CLEAR means no storm; RAIN/THUNDER require a storm. `underwater=true` also permits water.
     * The caller applies [AuroraSettings.canonicalBiomeKey] before matching aliases.
     */
    fun matches(
        biomeKey: String,
        worldName: String,
        time: Long,
        storm: Boolean,
        thunder: Boolean,
        y: Double,
        skyLight: Int,
        underwater: Boolean,
    ): Boolean {
        if (!y.isFinite() || y < minY || y > maxY || skyLight !in minSkyLight..maxSkyLight) return false
        if (underwater && !this.underwater) return false
        if (biomePatterns.none { it.matches(normalizeBiomeKey(biomeKey)) } ||
            worldPatterns.none { it.matches(worldName) }) return false
        val dayTime = Math.floorMod(time, 24000L).toInt()
        val inTime = if (timeStart <= timeEnd) dayTime in timeStart..timeEnd
        else dayTime >= timeStart || dayTime <= timeEnd
        return inTime && when (weather) {
            AuroraWeather.ANY -> true
            AuroraWeather.CLEAR -> !storm
            AuroraWeather.RAIN -> storm && !thunder
            AuroraWeather.THUNDER -> storm && thunder
        }
    }
}

class AuroraConfigException(val path: String, detail: String) : IllegalArgumentException("$path: $detail")

/**
 * Parses one detached candidate, without file IO or world/biome registry access. A bad field throws
 * [AuroraConfigException]; the runtime must only publish the returned complete settings snapshot.
 * Profiles are mappings keyed by ID, including `event-presets.<preset>.<profile>`.
 */
object AuroraConfig {
    private val particlesByKey = Particle.entries.associateBy { it.key.toString() }
    private val biomeKey = Regex("[a-z0-9._-]+:[a-z0-9/._-]+")
    private val biomeGlob = Regex("[a-z0-9._*-]+:[a-z0-9/._*-]+")
    private val permissionNode = Regex("[a-z0-9._-]+")
    private val rgb = Regex("#[0-9a-fA-F]{6}")

    fun parse(root: ConfigurationSection): AuroraSettings {
        val source = Fields(root, root.currentPath.orEmpty())
        val permission = source.string("permission", "arcaurora.view")
        source.check("permission", permissionNode.matches(permission), "expected a nonblank lowercase permission node")
        val skyDefaults = skyOptions(source.section("sky-defaults"), AuroraSkyOptions())
        var totalProfiles = 0
        fun profiles(fields: Fields?): List<AuroraProfile> {
            if (fields == null) return emptyList()
            totalProfiles += fields.keys.size
            fields.check("", totalProfiles <= 128, "at most 128 profiles are allowed across profiles and event-presets")
            return fields.keys.map { id -> profile(id, fields.requiredSection(id), skyDefaults) }.immutable()
        }
        val ordinaryProfiles = profiles(source.section("profiles"))
        val events = source.section("event-presets")?.let { presets ->
            presets.keys.associateWith { profiles(presets.requiredSection(it)) }
        }.orEmpty().immutable()
        val aliases = source.section("biome-aliases")?.let { aliasFields ->
            buildMap {
                for (raw in aliasFields.leafKeys) {
                    val from = normalizeBiomeKey(raw)
                    val to = normalizeBiomeKey(aliasFields.string(raw))
                    aliasFields.check(raw, biomeKey.matches(from) && biomeKey.matches(to), "expected exact biome keys without wildcards")
                    aliasFields.check(raw, !containsKey(from), "duplicate normalized biome alias '$from'")
                    put(from, to)
                }
            }
        }.orEmpty().immutable()
        val ping = source.section("ping")
        return AuroraSettings(
            permission = permission,
            intervalTicks = source.int("interval-ticks", 10, 1..1200),
            viewersPerTick = source.int("viewers-per-tick", 8, 1..128),
            attemptsPerViewer = source.int("attempts-per-viewer", 8, 1..64),
            packetsPerViewer = source.int("packets-per-viewer", 12, 1..128),
            nativeBudgetMicros = source.int("native-budget-micros", 1500, 1..10000),
            biomeAliases = aliases,
            profiles = ordinaryProfiles,
            eventPresets = events,
            ping = AuroraPingSettings(
                maxCurrentMs = ping?.int("max-current-ms", 250, 1..10000) ?: 250,
                maxAverageMs = ping?.int("max-average-ms", 180, 1..10000) ?: 180,
                windowSamples = ping?.int("window-samples", 10, 1..120) ?: 10,
                sampleIntervalTicks = ping?.int("sample-interval-ticks", 20, 1..1200) ?: 20,
            ),
            skyPacketsPerViewer = source.int("sky-packets-per-viewer", 36, 1..128),
        )
    }

    private fun profile(id: String, source: Fields, skyDefaults: AuroraSkyOptions): AuroraProfile {
        source.check("", id.isNotBlank(), "profile ID must not be blank")
        val biomes = source.strings("biomes", listOf("*")).map { raw ->
            normalizeBiomeKey(raw).also {
                source.check("biomes", raw == "*" || biomeGlob.matches(it), "invalid biome glob '$raw'; only '*' is a wildcard")
            }
        }.immutable()
        val worlds = source.strings("worlds", listOf("*")).immutable()
        val skyScene = source.boolean("sky-scene", false)
        val skyOptions = skyOptions(source.section("sky-options"), skyDefaults)
        val shapeSize = source.double("shape-size", 1.0, if (skyScene) 0.1..32.0 else 0.1..8.0)
        source.check("shape-size", !skyScene || shapeSize * skyOptions.scaleMax <= 32.0,
            "shape-size multiplied by sky-options.scale-max must not exceed 32")
        val minY = source.int("min-y", -2048, -2048..2048)
        val maxY = source.int("max-y", 2048, -2048..2048)
        source.check("max-y", maxY >= minY, "must be at least min-y")
        val minSky = source.int("min-sky-light", 0, 0..15)
        val maxSky = source.int("max-sky-light", 15, 0..15)
        source.check("max-sky-light", maxSky >= minSky, "must be at least min-sky-light")
        val radius = source.double("radius", 12.0, if (skyScene) 2.0..96.0 else 2.0..32.0)
        val minDistance = source.double("min-distance", 2.0, if (skyScene) 2.0..96.0 else 2.0..32.0)
        source.check("min-distance", minDistance <= radius, "must not exceed radius")
        val verticalMin = source.double("vertical-min", -2.0, if (skyScene) -32.0..64.0 else -32.0..32.0)
        val verticalMax = source.double("vertical-max", 6.0, if (skyScene) -32.0..64.0 else -32.0..32.0)
        source.check("vertical-max", verticalMax >= verticalMin, "must be at least vertical-min")
        val particleKey = normalizeBiomeKey(source.string("particle", "minecraft:end_rod"))
        val particle = particlesByKey[particleKey] ?: source.fail("particle", "unknown particle '$particleKey'")
        val color = source.color("dust-color")
        val toColor = source.color("dust-to-color")
        val dustSize = source.double("dust-size", 1.0, 0.1..2.0).toFloat()
        val particleData = when (particle) {
            Particle.DUST -> Particle.DustOptions(color ?: source.fail("dust-color", "required for $particleKey"), dustSize)
            Particle.DUST_COLOR_TRANSITION -> {
                val from = color ?: source.fail("dust-color", "required for $particleKey")
                Particle.DustTransition(from, toColor ?: from, dustSize)
            }
            else -> {
                source.check("particle", particle.dataType == Void::class.java,
                    "unsupported particle data type ${particle.dataType.simpleName}; use a no-data or dust particle")
                null
            }
        }
        return AuroraProfile(
            id = id,
            priority = source.int("priority", 0, Int.MIN_VALUE..Int.MAX_VALUE),
            biomes = biomes,
            worlds = worlds,
            timeStart = source.int("time-start", 0, 0..23999),
            timeEnd = source.int("time-end", 23999, 0..23999),
            weather = source.enum("weather", AuroraWeather.ANY),
            minY = minY,
            maxY = maxY,
            minSkyLight = minSky,
            maxSkyLight = maxSky,
            underwater = source.boolean("underwater", false),
            radius = radius,
            minDistance = minDistance,
            verticalMin = verticalMin,
            verticalMax = verticalMax,
            shape = source.enum("shape", AuroraShape.POINT),
            shapeSize = shapeSize,
            points = if (skyScene) source.int("sky-points", source.int("points", 1, 1..192), 1..192)
                else source.int("points", 1, 1..24),
            particle = particle,
            count = source.int("count", 1, 0..16),
            offsetX = source.double("offset-x", 0.0, 0.0..2.0),
            offsetY = source.double("offset-y", 0.0, 0.0..2.0),
            offsetZ = source.double("offset-z", 0.0, 0.0..2.0),
            speed = source.double("speed", 0.0, 0.0..1.0),
            particleData = particleData,
            skyScene = skyScene,
            sceneDurationTicks = source.int("scene-duration-ticks", 240, 40..1200),
            motionPeriodTicks = source.int("motion-period-ticks", 400, 40..2400),
            updateTicks = source.int("update-ticks", 4, 1..200),
            spawnDelayTicks = source.int("spawn-delay-ticks", 60, 0..400),
            skyTracers = source.int("sky-tracers", 1, 1..4),
            skyRefreshTicks = source.int("sky-refresh-ticks", 0, 0..200),
            skyOptions = skyOptions,
        )
    }

    private fun skyOptions(source: Fields?, defaults: AuroraSkyOptions): AuroraSkyOptions {
        if (source == null) return defaults
        val options = AuroraSkyOptions(
            fanDegrees = source.double("fan-degrees", defaults.fanDegrees, 0.0..360.0),
            angularGapDegrees = source.double("angular-gap-degrees", defaults.angularGapDegrees, 0.0..90.0),
            anchorCandidates = source.int("anchor-candidates", defaults.anchorCandidates, 1..32),
            terrainClearance = source.double("terrain-clearance", defaults.terrainClearance, 1.0..32.0),
            scaleMin = source.double("scale-min", defaults.scaleMin, 0.1..2.0),
            scaleMax = source.double("scale-max", defaults.scaleMax, 0.1..2.0),
            rollRadians = source.double("roll-radians", defaults.rollRadians, 0.0..Math.PI),
            waveAmplitude = source.double("wave-amplitude", defaults.waveAmplitude, 0.0..1.0),
            waveCycles = source.double("wave-cycles", defaults.waveCycles, 0.1..8.0),
            waveSlope = source.double("wave-slope", defaults.waveSlope, -1.0..1.0),
            waveDepth = source.double("wave-depth", defaults.waveDepth, 0.0..1.0),
            strokeDrift = source.double("stroke-drift", defaults.strokeDrift, -1.0..1.0),
            strokeLift = source.double("stroke-lift", defaults.strokeLift, -1.0..1.0),
            heldDrift = source.double("held-drift", defaults.heldDrift, -0.25..0.25),
            heldLift = source.double("held-lift", defaults.heldLift, -0.25..0.25),
            exitTicks = source.int("exit-ticks", defaults.exitTicks, 0..200),
            freshHeadRepair = source.boolean("fresh-head-repair", defaults.freshHeadRepair),
            freshHeadMaxAgeTicks = source.int("fresh-head-max-age-ticks", defaults.freshHeadMaxAgeTicks, 1..4),
        )
        source.check("scale-max", options.scaleMax >= options.scaleMin, "must be at least scale-min")
        return options
    }

    private class Fields(private val section: ConfigurationSection, private val prefix: String) {
        val keys: Set<String> get() = section.getKeys(false)
        // Bukkit expands dotted YAML keys into sections; recover complete biome keys at leaves.
        val leafKeys: List<String> get() = section.getKeys(true).filter { key ->
            val value = section.get(key)
            value !is ConfigurationSection || value.getKeys(false).isEmpty()
        }
        private fun path(name: String) = listOf(prefix, name).filter(String::isNotEmpty).joinToString(".")
        fun fail(name: String, message: String): Nothing = throw AuroraConfigException(path(name), message)
        fun check(name: String, valid: Boolean, message: String) {
            if (!valid) fail(name, message)
        }
        fun section(name: String): Fields? {
            val raw = section.get(name) ?: return null
            return Fields(raw as? ConfigurationSection ?: fail(name, "expected a mapping"), path(name))
        }
        fun requiredSection(name: String) = section(name) ?: fail(name, "expected a mapping")
        fun string(name: String, default: String? = null): String {
            val raw = section.get(name) ?: return default ?: fail(name, "expected a string")
            return raw as? String ?: fail(name, "expected a string")
        }
        fun boolean(name: String, default: Boolean): Boolean {
            val raw = section.get(name) ?: return default
            return raw as? Boolean ?: fail(name, "expected true or false")
        }
        fun int(name: String, default: Int, range: IntRange): Int {
            val raw = section.get(name) ?: return default
            val number = (raw as? Number)?.toDouble() ?: fail(name, "expected an integer in $range")
            check(name, number.isFinite() && number % 1.0 == 0.0 && number >= range.first && number <= range.last,
                "expected an integer in $range")
            return number.toInt()
        }
        fun double(name: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
            val raw = section.get(name) ?: return default
            val number = (raw as? Number)?.toDouble() ?: fail(name, "expected a finite number in $range")
            check(name, number.isFinite() && number in range, "expected a finite number in $range")
            return number
        }
        fun strings(name: String, default: List<String>): List<String> {
            val raw = section.get(name) ?: return default
            val list = raw as? List<*> ?: fail(name, "expected a list of strings")
            check(name, list.isNotEmpty(), "expected at least one selector")
            return list.mapIndexed { index, value ->
                val text = value as? String ?: fail("$name[$index]", "expected a string")
                check("$name[$index]", text.isNotBlank(), "selector must not be blank")
                text
            }.distinct()
        }
        inline fun <reified T : Enum<T>> enum(name: String, default: T): T {
            val value = string(name, default.name)
            return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: fail(name, "expected one of ${enumValues<T>().joinToString { it.name }}")
        }
        fun color(name: String): Color? {
            if (section.get(name) == null) return null
            val value = string(name)
            check(name, rgb.matches(value), "expected '#RRGGBB'")
            return Color.fromRGB(value.drop(1).toInt(16))
        }
    }
}

private fun normalizeBiomeKey(raw: String): String = if (raw == "*" || ':' in raw) raw else "minecraft:$raw"

private fun compileGlob(glob: String): Regex = Regex(glob.split('*').joinToString(".*", transform = Regex::escape))

private fun <T> List<T>.immutable(): List<T> = Collections.unmodifiableList(toList())

private fun <K, V> Map<K, V>.immutable(): Map<K, V> = Collections.unmodifiableMap(toMap())
