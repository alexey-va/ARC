package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.instanced.arena.ArenaInstance
import com.magmaguy.elitemobs.config.powers.PowersConfig
import com.magmaguy.elitemobs.powers.meta.CustomSummonPower
import com.magmaguy.elitemobs.powers.scripts.enums.ActionType

/** A snapshot of the loaded native definitions; no files or shared boss levels are changed. */
internal data class ArenaLevelPlan(
    val baseline: Int,
    val maximum: Int,
    val cap: Int,
    val waves: Map<Int, List<WaveBoss>>,
    val bosses: Map<String, BossLevel>,
    val unsupported: String? = null,
) {
    val supported: Boolean get() = unsupported == null

    data class WaveBoss(val filename: String, val spawnPoint: String, val level: Int)
    data class BossLevel(val level: Int, val summons: Map<String, Boolean>)

    fun level(authored: Int, offset: Int): Int =
        if (offset == 0) authored else (authored.toLong() + offset).coerceAtMost(cap.toLong()).toInt()

    fun range(wave: Int, selected: Int): IntRange? {
        if (!supported) return null
        val levels = waves[wave].orEmpty().map { level(it.level, selected - baseline) }
        return if (levels.isEmpty()) null else levels.min()..levels.max()
    }

    companion object {
        fun inspect(
            arena: ArenaInstance,
            cap: Int,
            lookup: (String) -> CustomBossesConfigFields? = CustomBossesConfig::getCustomBoss,
        ): ArenaLevelPlan {
            val waves = linkedMapOf<Int, List<WaveBoss>>()
            val definitions = linkedMapOf<String, BossLevel>()
            fun unavailable(reason: String) = ArenaLevelPlan(0, 0, cap, waves, definitions, reason)
            try {
                for (wave in 1..arena.customArenasConfigFields.waveCount) {
                    val entries = arena.arenaWaves.getWaveEntities(wave).orEmpty()
                    if (entries.isEmpty()) return unavailable("Wave $wave is empty")
                    waves[wave] = entries.map { entry ->
                        require(!entry.isMythicMob) { "MythicMobs wave levels are not adjustable" }
                        val fields = requireNotNull(lookup(entry.bossfile)) { "Missing boss ${entry.bossfile}" }
                        require(fields.level > 0) { "Dynamic level in ${fields.filename}" }
                        WaveBoss(entry.bossfile, entry.spawnPointName, fields.level)
                    }
                }
                if (waves.isEmpty()) return unavailable("Arena has no waves")
                val pending = ArrayDeque(waves.values.flatten().map { it.filename })
                while (pending.isNotEmpty()) {
                    val filename = pending.removeFirst()
                    if (filename in definitions) continue
                    val fields = requireNotNull(lookup(filename)) { "Missing boss $filename" }
                    require(fields.level > 0) { "Dynamic level in $filename" }
                    val summonModes = linkedMapOf<String, MutableSet<Boolean>>()
                    fun summon(child: String, inherit: Boolean) {
                        require(child.isNotBlank()) { "Empty summon in $filename" }
                        summonModes.getOrPut(child) { linkedSetOf() }.add(inherit)
                    }
                    // Reuse the native parser, including its legacy summon declaration format.
                    fields.powers.orEmpty().filter(::isSummon).forEach { entry ->
                        val parsed = CustomSummonPower(entry, fields).customBossReinforcements
                        require(parsed.isNotEmpty()) { "Invalid summon in $filename" }
                        parsed.forEach { child ->
                            child.bossFileName?.let {
                                summon(it, child.inheritLevel && child.summonType != CustomSummonPower.SummonType.GLOBAL)
                            }
                        }
                    }
                    val scripts = fields.eliteScript.orEmpty().toMutableList()
                    fields.powers.orEmpty().filterNot(::isSummon).forEach { entry ->
                        val powerName = when (entry) {
                            is String -> entry.substringBefore(':')
                            is Map<*, *> -> entry.entries.firstOrNull { it.key.toString().equals("filename", true) }?.value?.toString()
                            else -> null
                        }
                        require(!powerName.isNullOrBlank()) { "Unrecognized power in $filename" }
                        val power = requireNotNull(PowersConfig.getPower(powerName)) { "Missing power $powerName" }
                        scripts += ArenaPowerCapabilities.scripts(power)
                    }
                    scripts.forEach { script ->
                        script.scriptActionsBlueprint.scriptActionsBlueprintList.forEach { action ->
                            if (action.actionType == ActionType.SUMMON_REINFORCEMENT) summon(action.sValue, false)
                        }
                    }
                    require(summonModes.values.none { it.size > 1 }) {
                        "A summon has both inherited and authored levels in $filename"
                    }
                    definitions[filename] = BossLevel(fields.level, summonModes.mapValues { it.value.single() })
                    pending.addAll(summonModes.keys)
                    fields.phases.orEmpty().forEach { pending.add(it.substringBefore(':').trim()) }
                }
                val baseline = waves.getValue(1).minOf { it.level }
                val highestWave = waves.values.flatten().maxOf { it.level }
                // Never turn a raised run into a reduction of an authored phase or child.
                require(definitions.values.none { it.level > cap }) { "An authored level exceeds the raised-mode cap" }
                return ArenaLevelPlan(baseline, baseline + (cap - highestWave).coerceAtLeast(0), cap, waves, definitions)
            } catch (failure: IllegalArgumentException) {
                return unavailable(failure.message ?: "Unsupported boss definition")
            }
        }

        private fun isSummon(entry: Any): Boolean = when (entry) {
            is String -> entry.substringBefore(':').let { it.equals("summon", true) || it.equals("summonable", true) }
            is Map<*, *> -> entry.keys.any { it.toString().equals("summonType", true) }
            else -> false
        }
    }
}

/** Main-thread lobby state. The native match remains the roster and wave owner. */
internal class ArenaLevelLobby(val plan: ArenaLevelPlan) {
    var selected = plan.baseline
        private set
    var generation = 0L
        private set
    var admitting = false
        private set
    var locked = false
        private set

    fun admit(level: Int, nativeAdmission: () -> Boolean): Boolean {
        if (admitting || level !in plan.baseline..plan.maximum || locked && level != selected) return false
        val first = !locked
        if (first) selected = level
        admitting = true
        return try {
            nativeAdmission().also { success ->
                if (success) locked = true
                else if (first) selected = plan.baseline
            }
        } catch (failure: Throwable) {
            if (first) selected = plan.baseline
            throw failure
        } finally {
            admitting = false
        }
    }

    fun observePlayer() {
        // An external native API admission has no selected level; it uses the original mode.
        if (!admitting) locked = true
    }

    fun reset() {
        generation++
        selected = plan.baseline
        locked = false
    }
}
