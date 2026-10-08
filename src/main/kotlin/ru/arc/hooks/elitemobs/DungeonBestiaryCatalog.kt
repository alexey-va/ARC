package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.ClassLootSettingsConfig
import com.magmaguy.elitemobs.config.ItemSettingsConfig
import com.magmaguy.elitemobs.config.ProceduralItemGenerationSettingsConfig
import com.magmaguy.elitemobs.config.SpecialItemSystemsConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.config.powers.PowersConfig
import com.magmaguy.elitemobs.config.powers.PowersConfigFields
import com.magmaguy.elitemobs.config.translations.TranslationsConfig
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.items.customloottable.*
import com.magmaguy.elitemobs.mobconstructor.BossType
import com.magmaguy.elitemobs.powers.scripts.caching.EliteScriptBlueprint
import com.magmaguy.elitemobs.powers.scripts.caching.ScriptActionBlueprint
import com.magmaguy.elitemobs.powers.scripts.enums.ActionType
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.pow

internal data class BestiaryFact(val name: String, val description: String)

internal data class BestiaryMob(
    val id: String,
    val name: String,
    val kind: String,
    val level: String,
    val abilities: List<BestiaryFact>,
    val loot: List<BestiaryFact>,
    val notes: List<String>,
)

/**
 * Read-only projection of EliteMobs' already-loaded config registries.
 * It never loads YAML, constructs an entity, or runs a power/loot action.
 */
internal object NativeDungeonBestiaryCatalog {
    private data class Fingerprint(
        val dungeons: Map<String, ContentPackagesConfigFields>,
        val challenges: Map<String, ContentPackagesConfigFields>,
        val bosses: Map<String, out CustomBossesConfigFields>,
        val bossCount: Int,
    )

    private data class Snapshot(
        val fingerprint: Fingerprint,
        val idsByContent: Map<String, List<String>>,
        val packagesByContent: Map<String, ContentPackagesConfigFields>,
        val discoveryIds: Map<String, String>,
        val ids: Set<String>,
        val configById: Map<String, CustomBossesConfigFields>,
        val phaseRootByFile: Map<String, String>,
        val entriesCache: java.util.concurrent.ConcurrentHashMap<String, List<BestiaryMob>>,
    )

    @Volatile
    private var cached: Snapshot? = null

    fun entries(contentId: String, refresh: Boolean = true): List<BestiaryMob> {
        if (refresh) {
            // Native reloads may replace objects inside the same registry maps. Menu opens
            // refresh that projection; kill events keep the constant-time cached path.
            val previous = cached
            if (previous != null) {
                val bosses = CustomBossesConfig.getCustomBosses()
                val packages = (ContentPackagesConfig.getDungeonPackages().values +
                    ContentPackagesConfig.getEnchantedChallengeDungeonPackages().values)
                    .filter { it.contentType?.name?.endsWith("DUNGEON") == true }
                    .associateBy { normalizeContentId(it.filename) }
                if (bosses.size != previous.configById.size ||
                    bosses.values.any { previous.configById[normalizeBossId(it.filename)] !== it } ||
                    packages.size != previous.packagesByContent.size ||
                    packages.any { (id, value) -> previous.packagesByContent[id] !== value }) invalidate()
            }
        }
        val current = snapshot()
        val normalized = normalizeContentId(contentId)
        // Global loot settings and power contents can also reload without a boss replacement.
        if (refresh) current.entriesCache.remove(normalized)
        return current.entriesCache.computeIfAbsent(normalized) { key ->
            val content = current.packagesByContent[key] ?: return@computeIfAbsent emptyList()
            current.idsByContent[key].orEmpty().mapNotNull { id ->
                current.configById[id]?.let { toBestiaryMob(id, current.configById, current.phaseRootByFile, content) }
            }
        }
    }

    /** Phase files resolve to their unique phase-one filename; ordinary summons keep their own discovery ID. */
    fun discoveryId(bossFilename: String): String {
        val current = snapshot()
        val normalized = normalizeBossId(bossFilename)
        val liveConfig = CustomBossesConfig.getCustomBoss(normalized)
        // registerRuntimeFile mutates EliteMobs' HashMap in place. Size catches additions; this identity check
        // catches same-name replacement on the event's own hot path without scanning the registry.
        if (liveConfig != null && current.configById[normalized] !== liveConfig) {
            invalidate()
            return snapshot().discoveryIds[normalized] ?: normalized
        }
        return current.discoveryIds[normalized] ?: normalized
    }

    fun contains(id: String): Boolean {
        val snapshot = snapshot()
        return normalizeBossId(id) in snapshot.ids
    }

    /** Call after an EliteMobs config reload that replaces a config without changing a registry size. */
    fun invalidate() {
        cached = null
    }

    private fun snapshot(): Snapshot {
        val dungeons = ContentPackagesConfig.getDungeonPackages()
        val challenges = ContentPackagesConfig.getEnchantedChallengeDungeonPackages()
        val bosses = CustomBossesConfig.getCustomBosses()
        val current = cached
        if (current != null && current.fingerprint.matches(dungeons, challenges, bosses)) return current

        return synchronized(this) {
            val latest = cached
            if (latest != null && latest.fingerprint.matches(dungeons, challenges, bosses)) latest
            else buildSnapshot(dungeons, challenges, bosses).also { cached = it }
        }
    }

    private fun Fingerprint.matches(
        dungeons: Map<String, ContentPackagesConfigFields>,
        challenges: Map<String, ContentPackagesConfigFields>,
        bosses: Map<String, out CustomBossesConfigFields>,
    ): Boolean = this.dungeons === dungeons && this.challenges === challenges && this.bosses === bosses && bossCount == bosses.size

    private fun buildSnapshot(
        dungeons: Map<String, ContentPackagesConfigFields>,
        challenges: Map<String, ContentPackagesConfigFields>,
        bosses: Map<String, out CustomBossesConfigFields>,
    ): Snapshot {
        val packages = (dungeons.values + challenges.values)
            .filter { it.contentType?.name?.endsWith("DUNGEON") == true }
            .distinctBy { normalizeContentId(it.filename) }
            .sortedBy { normalizeContentId(it.filename) }
        val configs = bosses.values.filterNotNull().associateBy { normalizeBossId(it.filename) }
        val phaseRootByFile = uniquePhaseRoots(configs)
        val idsByContent = LinkedHashMap<String, List<String>>()
        val packagesByContent = LinkedHashMap<String, ContentPackagesConfigFields>()

        for (content in packages) {
            val seedIds = configs.values.asSequence()
                .filter { isOwnedByContent(it, content) }
                .filterNot { it.isReinforcement || it.bossType == BossType.REINFORCEMENT }
                .map { normalizeBossId(it.filename) }
                .toCollection(linkedSetOf())
            val includedIds = expandRoster(seedIds, configs)
            // Phase configs are one encounter. Fold each uniquely-linked phase into its phase-one entry.
            val entryIds = includedIds.asSequence()
                .map { phaseRootByFile[it] ?: it }
                .distinct()
                .filter { it in configs }
                .sortedWith(compareBy({ displayName(configs.getValue(it)) }, { it }))
                .toList()
            val contentId = normalizeContentId(content.filename)
            idsByContent[contentId] = entryIds
            packagesByContent[contentId] = content
        }

        val ids = idsByContent.values.flatten().toSet()
        val discoveryIds = HashMap<String, String>()
        phaseRootByFile.forEach { (phase, root) -> if (root in ids) discoveryIds[phase] = root }
        ids.forEach { discoveryIds.putIfAbsent(it, it) }
        return Snapshot(
            Fingerprint(dungeons, challenges, bosses, bosses.size),
            idsByContent.toMap(),
            packagesByContent.toMap(),
            discoveryIds.toMap(),
            ids,
            configs.toMap(),
            phaseRootByFile.toMap(),
            java.util.concurrent.ConcurrentHashMap(),
        )
    }

    private fun isOwnedByContent(fields: CustomBossesConfigFields, content: ContentPackagesConfigFields): Boolean {
        val folder = content.dungeonConfigFolderName?.trim().orEmpty()
        val pathMatch = folder.isNotEmpty() && pathUnderCustomBosses(fields.file).any { it.equals(folder, ignoreCase = true) }
        if (pathMatch) return true
        val worlds = setOfNotNull(content.worldName, content.wormholeWorldName).filter(String::isNotBlank)
        return fields.spawnLocations.orEmpty().any { location ->
            val world = location.substringBefore(',').trim()
            worlds.any { it.equals(world, ignoreCase = true) }
        }
    }

    /** Only path metadata is read from the loaded config object; this performs no filesystem operation. */
    private fun pathUnderCustomBosses(file: File?): List<String> {
        val segments = file?.toPath()?.normalize()?.map { it.toString() }?.toList().orEmpty()
        val base = segments.indexOfLast { it.equals("custombosses", ignoreCase = true) }
        if (base < 0 || base >= segments.lastIndex) return emptyList()
        return segments.subList(base + 1, segments.lastIndex)
    }

    private fun expandRoster(
        seeds: Set<String>,
        configs: Map<String, CustomBossesConfigFields>,
    ): Set<String> {
        val included = linkedSetOf<String>()
        val pending = ArrayDeque<String>().apply { addAll(seeds) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!included.add(id)) continue
            val fields = configs[id] ?: continue
            configuredReferences(fields).forEach { if (it in configs && it !in included) pending.addLast(it) }
        }
        return included
    }

    private fun configuredReferences(fields: CustomBossesConfigFields): Set<String> = buildSet {
        fields.phases.orEmpty().forEach { raw -> addKnownBoss(raw.substringBefore(':').trim(), this) }
        fields.mountedEntity?.let { addKnownBoss(it, this) }
        fields.powers.orEmpty().forEach { power ->
            when (power) {
                is String -> summonReference(power)?.let { addKnownBoss(it, this) }
                is Map<*, *> -> {
                    val summon = power.entries.any { it.key?.toString().equals("summonType", ignoreCase = true) }
                    if (summon) power.entries.firstOrNull { it.key?.toString().equals("filename", ignoreCase = true) }
                        ?.value?.toString()?.let { addKnownBoss(it, this) }
                }
            }
        }
        rawScriptReinforcements(fields.rawEliteScripts).forEach { addKnownBoss(it, this) }
    }

    private fun addKnownBoss(raw: String, target: MutableSet<String>) {
        val candidate = normalizeBossId(raw.trim().substringAfterLast('/'))
        if (candidate.isNotBlank()) target.add(candidate)
    }

    private fun summonReference(power: String): String? {
        val parts = power.split(':')
        if (parts.firstOrNull()?.lowercase(Locale.ROOT) !in setOf("summon", "summonable")) return null
        if (parts.first().equals("summonable", ignoreCase = true)) {
            return parts.firstNotNullOfOrNull { part ->
                part.substringAfter('=', "").takeIf { part.substringBefore('=').equals("filename", ignoreCase = true) }
            }
        }
        return when (canonicalTrigger(parts.getOrNull(1).orEmpty())) {
            "ONCE", "ONDEATH", "ONCOMBATENTER" -> parts.getOrNull(2)
            "ONHIT" -> parts.getOrNull(3)
            else -> null
        }
    }

    private fun rawScriptReinforcements(section: ConfigurationSection?): Set<String> {
        if (section == null) return emptySet()
        val refs = linkedSetOf<String>()
        for (script in section.getKeys(false)) {
            val actions = section.getConfigurationSection(script)?.getMapList("Actions").orEmpty()
            for (action in actions) {
                val type = action.entries.firstOrNull { it.key.toString().equals("action", ignoreCase = true) }
                    ?.value?.toString()?.uppercase(Locale.ROOT)
                if (type != "SUMMON_REINFORCEMENT") continue
                action.entries.firstOrNull { it.key.toString().equals("sValue", ignoreCase = true) }
                    ?.value?.toString()?.let(refs::add)
            }
        }
        return refs
    }

    private fun uniquePhaseRoots(configs: Map<String, CustomBossesConfigFields>): Map<String, String> {
        val edges = HashMap<String, MutableList<String>>()
        configs.forEach { (root, fields) ->
            fields.phases.orEmpty().forEach { raw ->
                val child = normalizeBossId(raw.substringBefore(':').trim())
                if (child in configs && child != root) edges.getOrPut(root) { mutableListOf() }.add(child)
            }
        }
        return bestiaryPhaseCanonicalRoots(edges)
    }

    private fun toBestiaryMob(
        id: String,
        configs: Map<String, CustomBossesConfigFields>,
        phaseRootByFile: Map<String, String>,
        content: ContentPackagesConfigFields,
    ): BestiaryMob {
        val fields = configs.getValue(id)
        val phaseConfigs = phaseClosure(id, configs)
        val abilities = phaseConfigs.flatMapIndexed { index, phase ->
            val phaseFacts = abilitiesFor(phase, configs)
            if (index == 0) phaseFacts else phaseFacts.map { it.copy(name = "Фаза: ${displayName(phase)} — ${it.name}") }
        }.distinctBy { it.name to it.description }
        val loot = phaseConfigs.flatMapIndexed { index, phase ->
            val phaseLoot = lootFor(phase, content)
            if (phaseConfigs.size <= 1) phaseLoot else phaseLoot.map { it.copy(name = "Фаза: ${displayName(phase)} — ${it.name}") }
        }.distinctBy { it.name to it.description }
        val notes = buildList {
            val phases = fields.phases.orEmpty().mapNotNull { raw ->
                val phaseId = normalizeBossId(raw.substringBefore(':').trim())
                val phase = configs[phaseId] ?: return@mapNotNull null
                val percent = raw.substringAfter(':', "").toDoubleOrNull()
                if (percent == null) "${displayName(phase)}" else "${displayName(phase)} (${formatPercent(percent * 100)}% здоровья)"
            }
            if (phases.isNotEmpty()) add("Фазы: ${phases.joinToString(" → ")}.")
            if (phaseConfigs.size > 1) add("Добыча определяется фазой, в которой завершён бой; таблицы фаз не складываются.")
            fields.mountedEntity?.let { configs[normalizeBossId(it)] }?.let { add("Ездовой моб: ${displayName(it)}.") }
            if (fields.isDropsEliteMobsLoot()) add("Глобальные награды EliteMobs требуют вклада не менее 10% максимального здоровья; строки уникальной добычи имеют собственные условия.")
            if (fields.isDropsVanillaLoot()) add("Также включена обычная добыча Minecraft.")
            val references = configuredReferences(fields).filter { it in configs && it !in phaseRootByFile }
            references.mapNotNull(configs::get).distinctBy { it.filename }.forEach { add("Может призвать или сопровождать: ${displayName(it)}.") }
        }.distinct()

        return BestiaryMob(
            id = id,
            name = displayName(fields),
            kind = kindName(fields.bossType),
            level = fields.level.takeIf { it > 0 }?.let { "Уровень $it" } ?: "Динамический уровень",
            abilities = abilities,
            loot = loot,
            notes = notes,
        )
    }

    private fun phaseClosure(root: String, configs: Map<String, CustomBossesConfigFields>): List<CustomBossesConfigFields> {
        val ordered = mutableListOf<CustomBossesConfigFields>()
        val seen = hashSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val fields = configs[id] ?: continue
            ordered += fields
            fields.phases.orEmpty().forEach { pending.addLast(normalizeBossId(it.substringBefore(':').trim())) }
        }
        return ordered
    }

    private fun abilitiesFor(fields: CustomBossesConfigFields, configs: Map<String, CustomBossesConfigFields>): List<BestiaryFact> = buildList {
        fields.powers.orEmpty().forEach { power ->
            when (power) {
                is String -> {
                    val summon = summonSpec(power) { configs[it]?.let(::displayName) }
                    if (summon != null) add(summon)
                    else power.substringBefore(':').takeIf(String::isNotBlank)?.let { addPowerFact(it, configs = configs)?.let(::add) }
                }
                is Map<*, *> -> {
                    val summon = summonSpec(power) { configs[it]?.let(::displayName) }
                    if (summon != null) add(summon)
                    else power.entries.firstOrNull { it.key?.toString().equals("filename", ignoreCase = true) }
                        ?.value?.toString()?.let { powerId ->
                            val difficulty = power.entries.firstOrNull { it.key?.toString().equals("difficultyID", ignoreCase = true) }
                                ?.value?.toString()?.takeIf(String::isNotBlank)
                            addPowerFact(powerId, difficulty, configs)?.let(::add)
                        }
                }
            }
        }
        scriptActionFacts(fields.eliteScript, configs).forEach(::add)
    }

    private fun addPowerFact(
        powerId: String,
        condition: String? = null,
        configs: Map<String, CustomBossesConfigFields>,
    ): BestiaryFact? {
        val registered = PowersConfig.getPower(powerId) ?: return null
        val title = translatedPowerName(registered) ?: categoryName(registered)
        val actions = registered.eliteScriptBlueprints.orEmpty().flatMap { it.scriptActionsBlueprint.scriptActionsBlueprintList }
        val summaries = actionFacts(actions, configs).map { it.description }.distinct()
        val key = normalizePowerId(registered.filename).removeSuffix(".yml").removeSuffix(".yaml").removeSuffix(".lua")
        val description = when {
            summaries.isNotEmpty() -> summaries.joinToString(" ")
            builtInDescriptions[key] != null -> builtInDescriptions.getValue(key)
            else -> categoryDescription(registered)
        }
        val requirement = condition?.let { " Только при настроенной сложности: ${difficultyLabel(it)}." }.orEmpty()
        return BestiaryFact(title, description + requirement)
    }

    private fun scriptActionFacts(
        scripts: List<EliteScriptBlueprint>,
        configs: Map<String, CustomBossesConfigFields>,
    ): List<BestiaryFact> {
        val rawByName = scripts.associateBy { it.scriptName }
        val expanded = mutableListOf<ScriptActionBlueprint>()
        val visited = hashSetOf<String>()
        fun append(script: EliteScriptBlueprint) {
            if (!visited.add(script.scriptName)) return
            for (action in script.scriptActionsBlueprint.scriptActionsBlueprintList) {
                if (action.actionType == ActionType.RUN_SCRIPT) action.scripts.forEach { rawByName[it]?.let(::append) }
                else expanded += action
            }
        }
        scripts.forEach(::append)
        return actionFacts(expanded, configs)
    }

    private fun actionFacts(
        actions: List<ScriptActionBlueprint>,
        configs: Map<String, CustomBossesConfigFields>,
    ): List<BestiaryFact> = actions.mapNotNull { action ->
        val type = action.actionType ?: return@mapNotNull null
        val reinforcement = if (type == ActionType.SUMMON_REINFORCEMENT) configs[normalizeBossId(action.sValue)] else null
        val potion = action.potionEffectType?.name?.let(::potionLabel)
        val detail = when (type) {
            ActionType.DAMAGE -> "Наносит цели урон."
            ActionType.MODIFY_DAMAGE -> "Изменяет наносимый урон в ${formatPercent(action.multiplier.value.toDouble() * 100)}% от обычного значения."
            ActionType.POTION_EFFECT -> potion?.let { "Накладывает эффект «$it» на ${action.duration.value} тиков." } ?: "Накладывает эффект зелья на цель."
            ActionType.SET_ON_FIRE -> "Поджигает цель на ${action.duration.value} тиков."
            ActionType.STRIKE_LIGHTNING -> "Бьёт молнией по выбранной точке."
            ActionType.PUSH -> "Отбрасывает цель."
            ActionType.TELEPORT -> "Перемещает цель."
            ActionType.MAKE_INVULNERABLE -> "Делает цель неуязвимой на ${action.duration.value} тиков."
            ActionType.HEAL -> "Восстанавливает здоровье цели."
            ActionType.SET_MOB_AI -> "${if (action.bValue == true) "Включает" else "Отключает"} искусственный интеллект цели на ${action.duration.value} тиков."
            ActionType.SET_MOB_AWARE -> "${if (action.bValue == true) "Возвращает" else "Отключает"} внимательность цели на ${action.duration.value} тиков."
            ActionType.PLACE_BLOCK -> "Создаёт блок «${materialLabel(action.material)}»."
            ActionType.SUMMON_REINFORCEMENT -> reinforcement?.let { "Призывает ${displayName(it)}." } ?: "Призывает подкрепление."
            ActionType.SUMMON_ENTITY -> "Призывает ${entityLabel(action.sValue)}."
            ActionType.VISUAL_FREEZE -> "Замораживает цель на ${action.duration.value} тиков."
            ActionType.REMOVE_ELITE -> "Удаляет элитного моба со сцены."
            ActionType.SET_TIME -> "Меняет время суток."
            ActionType.SET_WEATHER -> "Меняет погоду."
            ActionType.PLAY_SOUND -> "Воспроизводит звуковой эффект."
            ActionType.SPAWN_PARTICLE -> "Создаёт визуальный эффект из частиц."
            ActionType.SPAWN_FIREWORKS -> "Запускает фейерверк."
            ActionType.SPAWN_FALLING_BLOCK -> "Создаёт падающий блок."
            ActionType.BOSS_BAR_MESSAGE, ActionType.ACTION_BAR_MESSAGE, ActionType.TITLE_MESSAGE, ActionType.MESSAGE -> null
            ActionType.RUN_COMMAND_AS_CONSOLE, ActionType.RUN_COMMAND_AS_PLAYER -> "Выполняет настроенное действие сервера."
            ActionType.RUN_SCRIPT, ActionType.NAVIGATE, ActionType.SCALE, ActionType.SET_FACING,
            ActionType.PLAY_ANIMATION, ActionType.TAG, ActionType.UNTAG -> null
        } ?: return@mapNotNull null
        val name = when (type) {
            ActionType.POTION_EFFECT -> potion?.let { "Эффект: $it" } ?: "Эффект зелья"
            ActionType.SUMMON_REINFORCEMENT -> reinforcement?.let { "Призыв: ${displayName(it)}" } ?: "Призыв подкрепления"
            ActionType.SUMMON_ENTITY -> "Призыв: ${entityLabel(action.sValue)}"
            ActionType.STRIKE_LIGHTNING -> "Удар молнией"
            ActionType.MODIFY_DAMAGE -> "Изменение урона"
            ActionType.PLACE_BLOCK -> "Создание блока"
            else -> actionLabels[type] ?: "Боевое действие"
        }
        BestiaryFact(name, detail)
    }.distinctBy { it.name to it.description }

    private fun summonSpec(power: Any, mobName: (String) -> String?): BestiaryFact? {
        val values: Map<String, String> = when (power) {
            is String -> {
                val type = power.substringBefore(':')
                if (!type.equals("summon", true) && !type.equals("summonable", true)) return null
                val parts = power.split(':')
                if (type.equals("summon", true)) {
                    val kind = parts.getOrNull(1).orEmpty()
                    val filenameIndex = when (kind.lowercase(Locale.ROOT)) {
                        "once", "ondeath", "oncombatenter" -> 2
                        "onhit" -> 3
                        else -> -1
                    }
                    if (filenameIndex < 0 || parts.size <= filenameIndex) return null
                    buildMap {
                        put("summonType", kind)
                        put("filename", parts[filenameIndex])
                        if (kind.equals("onHit", true)) put("chance", parts.getOrNull(2).orEmpty())
                    }
                } else parts.mapNotNull { part ->
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', "")
                    if (value.isBlank()) null else key.lowercase(Locale.ROOT) to value
                }.toMap()
            }
            is Map<*, *> -> {
                if (power.keys.none { it?.toString().equals("summonType", true) }) return null
                power.entries.mapNotNull { (key, value) ->
                    if (key == null || value == null) null else key.toString().lowercase(Locale.ROOT) to value.toString()
                }.toMap()
            }
            else -> return null
        }
        val filename = values.entries.firstOrNull { it.key.equals("filename", true) }?.value ?: return null
        val name = mobName(normalizeBossId(filename)) ?: return null
        val trigger = values.entries.firstOrNull { it.key.equals("summonType", true) }?.value?.let(::summonTrigger).orEmpty()
        val amount = values.entries.firstOrNull { it.key.equals("amount", true) }?.value?.toIntOrNull()
            ?.takeIf { it > 1 }?.let { " $it мобов" } ?: ""
        val chance = values.entries.firstOrNull { it.key.equals("chance", true) }?.value?.toDoubleOrNull()
        val chanceText = chance?.takeIf { it.isFinite() }?.let { " Шанс одного срабатывания: ${formatPercent(it * 100)}%." }.orEmpty()
        val description = "Призывает$amount $name$trigger.$chanceText"
        return BestiaryFact("Призыв: $name", description)
    }

    private fun lootFor(fields: CustomBossesConfigFields, content: ContentPackagesConfigFields): List<BestiaryFact> = buildList {
        fields.customLootTable?.entries.orEmpty().forEach { entry ->
            val itemName = when (entry) {
                is EliteCustomLootEntry -> CustomItem.getCustomItem(entry.filename)?.customItemsConfigFields?.let { item ->
                    bestiaryItemName(item.name, materialLabel(item.material), displayName(fields))
                } ?: "Пользовательский предмет"
                is VanillaCustomLootEntry -> materialLabel(entry.material)
                is CurrencyCustomLootEntry -> "${entry.currencyAmount} валюты"
                is CommandLootTable -> "Серверная награда"
                is ItemStackCustomLootEntry -> "Предмет из таблицы EliteMobs"
                else -> "Добыча EliteMobs"
            }
            val chance = entry.chance
            val description = buildString {
                if (chance.isFinite() && chance in 0.0..1.0) append("Настроенный шанс записи: ${formatPercent(chance * 100)}%. ")
                else append("Вероятность записи некорректна в конфигурации. ")
                when {
                    entry is CommandLootTable && chance in 0.0..1.0 -> append("С фактической проверкой команды шанс запуска после двух бросков равен ${formatPercent(bestiaryEffectiveLootChance("command", chance, false) * 100)}%.")
                    entry is VanillaCustomLootEntry && ItemSettingsConfig.isPutLootDirectlyIntoPlayerInventory() && chance in 0.0..1.0 -> append("При выдаче в инвентарь шанс хотя бы одного из ${entry.amount} предметов с учётом отдельного броска на каждый равен ${formatPercent(bestiaryEffectiveLootChance("vanilla", chance, true, entry.amount) * 100)}%; при выдаче на землю действует шанс записи.")
                    entry is EliteCustomLootEntry && entry.isClassLoot() -> {
                        val rank = classLootRank(fields)
                        val global = ClassLootSettingsConfig.dropChance(rank)
                        if (ClassLootSettingsConfig.enabled()) {
                            append("Это классовая экипировка: общий шанс для ранга «${classRankName(rank)}» — ${formatPercent(global * 100)}%; после выбора семейства и подходящего предмета применяется этот шанс записи. Итог для конкретного предмета зависит от доступных предметов, сложности и разрешений.")
                        } else append("Это классовая экипировка, но общая система классовой добычи сейчас выключена.")
                    }
                    else -> append("Шанс применяется отдельно к этой записи; таблица не выбирает её вместо остальных записей.")
                }
                if (entry.amount > 1) append(" Количество в настроенной строке: ${entry.amount}.")
                if (entry.wave > 0) append(" Награда этапа ${entry.wave}.")
                if (entry.permission.isNotBlank()) append(" Нужна проверка разрешения игрока.")
                if (entry is EliteCustomLootEntry) {
                    val difficulties = entry.difficultyIDs.orEmpty()
                    if (difficulties.isNotEmpty()) {
                        val names = difficulties.map { difficultyName(it, content) }.distinct()
                        append(" Доступна на сложности: ${names.joinToString(", ")}.")
                    }
                }
            }
            add(BestiaryFact(itemName, description))
        }
        if (fields.isDropsEliteMobsLoot() && fields.isDropsRandomLoot()) {
            add(BestiaryFact("Случайная добыча EliteMobs", randomLootDescription(fields)))
            if (SpecialItemSystemsConfig.isDropSpecialLoot() && SpecialItemSystemsConfig.getSpecialValues().isNotEmpty()) {
                val ordinaryChance = SpecialItemSystemsConfig.getNonEliteChanceToDrop()
                // LootTables retries the ordinary special roll when the boss roll fails.
                val specialChance = if (fields.getHealthMultiplier() > 1.0) {
                    val bossChance = SpecialItemSystemsConfig.getBossChanceToDrop()
                    bossChance + (1.0 - bossChance) * ordinaryChance
                } else ordinaryChance
                add(BestiaryFact(
                    "Особый предмет EliteMobs",
                    "Дополнительный независимый бросок: ${formatPercent(specialChance * 100)}% на подходящего участника; предмет выбирается из глобального набора особой добычи.",
                ))
            }
            if (ItemSettingsConfig.isUseEliteItemScrolls()) {
                add(BestiaryFact(
                    "Свиток предмета EliteMobs",
                    "Дополнительный независимый бросок с шансом ${formatPercent(ItemSettingsConfig.getEliteItemScrollChance() * 100)}% на подходящего участника.",
                ))
            }
        }
        val hasClassLoot = fields.customLootTable?.entries.orEmpty().filterIsInstance<EliteCustomLootEntry>().any { it.isClassLoot() }
        if (fields.isClassLoot() && !fields.isReinforcement() && hasClassLoot && ClassLootSettingsConfig.enabled()) {
            val rank = classLootRank(fields)
            val chance = ClassLootSettingsConfig.dropChance(rank)
            add(BestiaryFact(
                "Классовая экипировка",
                "Шанс классового броска для ранга «${classRankName(rank)}» — ${formatPercent(chance * 100)}% на участника с вкладом от 10%. Затем выбирается семейство по его весу и один доступный предмет; у настроенных строк остаётся свой дополнительный шанс и фильтры сложности/разрешений.",
            ))
        }
    }

    private fun randomLootDescription(fields: CustomBossesConfigFields): String {
        val baseChance = if (fields.isRegionalBoss()) ItemSettingsConfig.getRegionalBossNonUniqueDropRate()
            else ItemSettingsConfig.getFlatDropRate()
        val perLevel = ItemSettingsConfig.getLevelIncreaseDropRate()
        val chance = buildString {
            append("Базовый шанс одной случайной награды: ${formatPercent(baseChance * 100)}%")
            if (perLevel != 0.0) append(" + ${formatPercent(perLevel * 100)}% за каждый уровень предмета")
            append(". ")
        }
        val weights = linkedMapOf<String, Double>()
        if (ProceduralItemGenerationSettingsConfig.isDoProceduralItemDrops())
            weights["процедурная"] = ItemSettingsConfig.getProceduralItemWeight()
        if (ItemSettingsConfig.isDoEliteMobsLoot()) {
            if (!CustomItem.getWeighedFixedItems().isNullOrEmpty()) weights["взвешенные предметы"] = ItemSettingsConfig.getWeighedItemWeight()
            if (!CustomItem.getFixedItems().isNullOrEmpty()) weights["фиксированные предметы для подходящих уровней"] = ItemSettingsConfig.getFixedItemWeight()
            if (!CustomItem.getLimitedItems().isNullOrEmpty()) weights["предметы с пределом уровня"] = ItemSettingsConfig.getLimitedItemWeight()
            if (!CustomItem.getScalableItems().isNullOrEmpty()) weights["масштабируемые предметы"] = ItemSettingsConfig.getScalableItemWeight()
        }
        val distribution = if (weights.values.none { it > 0 && it.isFinite() }) "В доступных пулах нет категории с положительным весом."
        else weights.entries.filter { it.value > 0 && it.value.isFinite() }.joinToString(", ") { (name, weight) ->
            "$name — вес ${formatPercent(weight)}"
        }.let { "После успешного броска выбирается одна доступная категория по относительным весам: $it. Категории могут быть недоступны для уровня награды игрока." }
        return chance + distribution + " Персональная выдача требует не менее 10% нанесённого урона; предметный уровень зависит от награды игроку."
    }

    private fun classLootRank(fields: CustomBossesConfigFields): ClassLootSettingsConfig.Rank = when {
        fields.classLootRank.equals("BOSS", true) -> ClassLootSettingsConfig.Rank.BOSS
        fields.classLootRank.equals("MINIBOSS", true) -> ClassLootSettingsConfig.Rank.MINIBOSS
        fields.classLootRank.equals("TRASH", true) -> ClassLootSettingsConfig.Rank.TRASH
        fields.bossType == BossType.BOSS || fields.bossType == BossType.EVENT || fields.name.contains("\$bossLevel", true) || fields.name.contains("\$eventBossLevel", true) -> ClassLootSettingsConfig.Rank.BOSS
        fields.bossType == BossType.MINIBOSS || fields.name.contains("\$minibossLevel", true) -> ClassLootSettingsConfig.Rank.MINIBOSS
        else -> ClassLootSettingsConfig.Rank.TRASH
    }

    private fun classRankName(rank: ClassLootSettingsConfig.Rank): String = when (rank) {
        ClassLootSettingsConfig.Rank.BOSS -> "босс"
        ClassLootSettingsConfig.Rank.MINIBOSS -> "мини-босс"
        ClassLootSettingsConfig.Rank.TRASH -> "обычный моб"
    }

    private fun difficultyName(id: String, content: ContentPackagesConfigFields): String {
        val name = content.difficulties.orEmpty().firstOrNull { it["id"]?.toString().equals(id, true) }
            ?.get("name")?.toString()?.takeIf(String::isNotBlank)
        return name ?: difficultyLabel(id)
    }

    private fun translatedPowerName(fields: PowersConfigFields): String? {
        val translated = runCatching { TranslationsConfig.getTranslationsConfigFields()?.get(fields.filename, "name") as? String }.getOrNull()
        return translated?.let(::cleanVisibleText)?.takeIf { it.isNotBlank() && !it.equals(fields.filename, true) }
            ?: knownPowerNames[normalizePowerId(fields.filename).removeSuffix(".yml").removeSuffix(".yaml").removeSuffix(".lua")]
    }

    private fun categoryName(fields: PowersConfigFields): String = when (fields.powerType?.name) {
        "OFFENSIVE" -> "Атакующая способность"
        "DEFENSIVE" -> "Защитная способность"
        "MISCELLANEOUS" -> "Особая способность"
        else -> "Способность EliteMobs"
    }

    private fun categoryDescription(fields: PowersConfigFields): String {
        val category = when (fields.powerType?.name) {
            "OFFENSIVE" -> "Атакующая способность EliteMobs"
            "DEFENSIVE" -> "Защитная способность EliteMobs"
            "MISCELLANEOUS" -> "Дополнительный эффект EliteMobs"
            else -> "Способность EliteMobs"
        }
        val cooldown = fields.powerCooldown.takeIf { it > 0 }?.let { " Перезарядка: ${formatPercent(it / 20.0)} сек." }.orEmpty()
        return "$category.$cooldown"
    }

    private fun displayName(fields: CustomBossesConfigFields): String {
        val level = fields.level.takeIf { it > 0 }?.toString() ?: "динамический уровень"
        return cleanVisibleText(fields.name
            .replace("\$normalLevel", level, ignoreCase = true)
            .replace("\$minibossLevel", level, ignoreCase = true)
            .replace("\$bossLevel", level, ignoreCase = true)
            .replace("\$reinforcementLevel", level, ignoreCase = true)
            .replace("\$eventBossLevel", level, ignoreCase = true)
            .replace("\$level", level, ignoreCase = true))
    }

    private fun kindName(type: BossType): String = when (type) {
        BossType.NORMAL -> "Моб"
        BossType.MINIBOSS -> "Мини-босс"
        BossType.BOSS -> "Босс"
        BossType.REINFORCEMENT -> "Подкрепление"
        BossType.EVENT -> "Событийный босс"
    }

    private fun summonTrigger(raw: String): String = when (canonicalTrigger(raw)) {
        "ONCE" -> " при появлении"
        "ONHIT" -> " при попадании по боссу"
        "ONDEATH" -> " после смерти босса"
        "ONCOMBATENTER" -> " при начале боя"
        "GLOBAL" -> " периодически"
        else -> ""
    }

    private fun canonicalTrigger(raw: String): String = raw.uppercase(Locale.ROOT).replace("_", "").replace("-", "")

    private fun potionLabel(raw: String): String = when (raw.uppercase(Locale.ROOT)) {
        "HARM" -> "Мгновенный урон"
        "HEAL" -> "Мгновенное лечение"
        "SPEED" -> "Скорость"
        "SLOW" -> "Замедление"
        "SLOWNESS" -> "Замедление"
        "POISON" -> "Отравление"
        "WITHER" -> "Иссушение"
        "WEAKNESS" -> "Слабость"
        "STRENGTH" -> "Сила"
        "REGENERATION" -> "Регенерация"
        "INVISIBILITY" -> "Невидимость"
        "BLINDNESS" -> "Слепота"
        "LEVITATION" -> "Левитация"
        else -> raw.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun entityLabel(raw: String): String = when (raw.uppercase(Locale.ROOT)) {
        "ZOMBIE" -> "зомби"
        "SKELETON" -> "скелета"
        "WITHER_SKELETON" -> "визер-скелета"
        "VEX" -> "векса"
        "SPIDER" -> "паука"
        "CAVE_SPIDER" -> "пещерного паука"
        "CREEPER" -> "крипера"
        "BLAZE" -> "ифрита"
        "WOLF" -> "волка"
        "PILLAGER" -> "разбойника"
        else -> "моба"
    }

    private fun materialLabel(material: Material?): String {
        if (material == null) return "Предмет"
        val known = mapOf(
            Material.DIAMOND to "Алмаз", Material.EMERALD to "Изумруд", Material.GOLD_INGOT to "Золотой слиток",
            Material.IRON_INGOT to "Железный слиток", Material.NETHERITE_INGOT to "Незеритовый слиток",
            Material.BLAZE_POWDER to "Огненный порошок", Material.BONE to "Кость", Material.ARROW to "Стрела",
            Material.PAPER to "Бумага", Material.BOOK to "Книга", Material.EXPERIENCE_BOTTLE to "Пузырёк опыта",
            Material.GOLDEN_APPLE to "Золотое яблоко", Material.ENCHANTED_GOLDEN_APPLE to "Зачарованное золотое яблоко",
            Material.DIAMOND_SWORD to "Алмазный меч", Material.NETHERITE_SWORD to "Незеритовый меч",
            Material.SHIELD to "Щит", Material.CROSSBOW to "Арбалет", Material.BOW to "Лук",
        )
        known[material]?.let { return it }
        val equipment = mapOf("SWORD" to "меч", "AXE" to "топор", "HOE" to "мотыга", "SPEAR" to "копьё",
            "HELMET" to "шлем", "CHESTPLATE" to "нагрудник", "LEGGINGS" to "поножи", "BOOTS" to "ботинки")
        val materials = mapOf("WOODEN" to "дерево", "STONE" to "камень", "GOLDEN" to "золото",
            "IRON" to "железо", "DIAMOND" to "алмаз", "NETHERITE" to "незерит", "LEATHER" to "кожа", "CHAINMAIL" to "кольчуга")
        val slot = equipment[material.name.substringAfterLast('_')]
        val tier = materials[material.name.substringBefore('_')]
        if (slot != null && tier != null) return "${slot.replaceFirstChar { it.uppercase() }} ($tier)"
        return when (material.name) {
            "MACE" -> "Булава"
            "TRIDENT" -> "Трезубец"
            "COOKIE" -> "Печенье"
            "CAKE" -> "Торт"
            else -> material.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun cleanVisibleText(raw: String): String = raw
        .replace(Regex("(?i)[&§][0-9a-fk-orx]"), "")
        .replace(Regex("<[^>]*>"), "")
        .trim()

    private fun normalizeContentId(value: String): String = value.trim().removeSuffix(".yml").removeSuffix(".yaml")
    private fun normalizeBossId(value: String): String {
        val clean = value.trim().substringAfterLast('/')
        return when {
            clean.endsWith(".yml", true) -> clean
            clean.endsWith(".yaml", true) -> clean.removeSuffix(".yaml") + ".yml"
            clean.isBlank() -> clean
            else -> "$clean.yml"
        }
    }
    private fun normalizePowerId(value: String): String = value.substringAfterLast('/').lowercase(Locale.ROOT)
    private fun formatPercent(value: Double): String {
        if (!value.isFinite()) return "не задано"
        val rounded = BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        if (value > 0 && rounded.compareTo(BigDecimal.ZERO) == 0) return "<0,0001"
        return rounded.toPlainString().replace('.', ',')
    }
    private fun difficultyLabel(value: String): String = when (value.uppercase(Locale.ROOT)) {
        "NORMAL", "0" -> "Обычная"
        "HARD", "1" -> "Сложная"
        "MYTHIC", "2" -> "Мифическая"
        else -> value
    }

    private val actionLabels = mapOf(
        ActionType.DAMAGE to "Урон", ActionType.MODIFY_DAMAGE to "Модификатор урона",
        ActionType.POTION_EFFECT to "Эффект зелья", ActionType.SET_ON_FIRE to "Поджог",
        ActionType.STRIKE_LIGHTNING to "Удар молнией", ActionType.PUSH to "Отбрасывание",
        ActionType.TELEPORT to "Телепортация", ActionType.MAKE_INVULNERABLE to "Неуязвимость",
        ActionType.HEAL to "Лечение", ActionType.SET_MOB_AI to "Поведение моба",
        ActionType.SET_MOB_AWARE to "Внимательность моба", ActionType.PLACE_BLOCK to "Создание блока",
        ActionType.SUMMON_REINFORCEMENT to "Призыв", ActionType.SUMMON_ENTITY to "Призыв моба",
        ActionType.VISUAL_FREEZE to "Заморозка", ActionType.REMOVE_ELITE to "Удаление моба",
        ActionType.SET_TIME to "Смена времени", ActionType.SET_WEATHER to "Смена погоды",
        ActionType.PLAY_SOUND to "Звук", ActionType.SPAWN_PARTICLE to "Частицы",
        ActionType.SPAWN_FIREWORKS to "Фейерверк", ActionType.SPAWN_FALLING_BLOCK to "Падающий блок",
    )

    private val builtInDescriptions = mapOf(
        "attack_arrow" to "Выпускает по игроку очередь стрел.",
        "attack_blinding" to "Накладывает на поражённую цель слепоту.",
        "attack_confusing" to "Накладывает на цель тошноту и мешает ориентироваться.",
        "attack_fire" to "Поджигает цель при атаке.",
        "attack_fireball" to "Выпускает огненный шар по игроку.",
        "attack_freeze" to "Замедляет и временно замораживает цель.",
        "attack_gravity" to "Поднимает цель в воздух эффектом левитации.",
        "attack_gravity_lite" to "Кратковременно подбрасывает цель.",
        "attack_lightning" to "Обрушивает на цель удар молнии.",
        "attack_poison" to "Отравляет поражённую цель.",
        "attack_push" to "Отбрасывает цель от моба.",
        "attack_vacuum" to "Притягивает цель к мобу.",
        "attack_vaccuum" to "Притягивает цель к мобу.",
        "attack_weakness" to "Накладывает слабость на поражённую цель.",
        "attack_web" to "Опутывает цель паутиной.",
        "attack_wither" to "Накладывает иссушение на поражённую цель.",
        "arrow_rain" to "Обрушивает залп стрел на область.",
        "attack_fireworks" to "Запускает по цели боевые фейерверки.",
        "bonus_coins" to "Увеличивает награду монетами EliteMobs.",
        "bomb" to "Создаёт взрыв рядом с целью.",
        "bomb_2" to "Создаёт усиленный взрыв рядом с целью.",
        "bullet_hell" to "Выпускает серию снарядов по области.",
        "flamethrower" to "Выпускает поток огня по целям перед собой.",
        "frost_cone" to "Поражает цели конусом ледяного дыхания.",
        "ground_pound" to "Бьёт по земле и создаёт ударную волну.",
        "invisibility" to "Временно скрывает моба от игроков.",
        "meteor_shower" to "Обрушивает на область серию метеоров.",
        "movement_speed" to "Увеличивает скорость передвижения моба.",
        "shield_wall" to "Создаёт защитную стену перед мобом.",
        "shockwave_2" to "Отбрасывает цели ударной волной.",
        "shockwave_3" to "Отбрасывает цели усиленной ударной волной.",
        "summon_raug" to "Призывает Рауга в помощь.",
        "tracking_fireball" to "Выпускает самонаводящиеся огненные шары.",
        "ender_dragon_ender_fireball_bombardment" to "Обстреливает область огненными шарами дракона.",
    )

    private val knownPowerNames = mapOf(
        "attack_arrow" to "Залп стрел",
        "attack_blinding" to "Ослепляющая атака",
        "attack_confusing" to "Сбивающая с толку атака",
        "attack_fire" to "Поджог",
        "attack_fireball" to "Огненный шар",
        "attack_freeze" to "Заморозка",
        "attack_gravity" to "Гравитационная атака",
        "attack_gravity_lite" to "Лёгкая гравитационная атака",
        "attack_lightning" to "Громовой удар",
        "attack_poison" to "Отравление",
        "attack_push" to "Отбрасывание",
        "attack_vacuum" to "Притяжение",
        "attack_vaccuum" to "Притяжение",
        "attack_weakness" to "Слабость",
        "attack_web" to "Паутина",
        "attack_wither" to "Иссушение",
        "arrow_rain" to "Ливень стрел",
        "attack_fireworks" to "Залп фейерверков",
        "bonus_coins" to "Бонусные монеты",
        "bomb" to "Бомба",
        "bomb_2" to "Усиленная бомба",
        "bullet_hell" to "Шквал снарядов",
        "flamethrower" to "Огненное дыхание",
        "frost_cone" to "Ледяной конус",
        "ground_pound" to "Удар о землю",
        "invisibility" to "Невидимость",
        "meteor_shower" to "Метеоритный дождь",
        "movement_speed" to "Повышенная скорость",
        "shield_wall" to "Защитная стена",
        "shockwave_2" to "Ударная волна",
        "shockwave_3" to "Усиленная ударная волна",
        "summon_raug" to "Призыв Рауга",
        "tracking_fireball" to "Самонаводящийся огненный шар",
        "ender_dragon_ender_fireball_bombardment" to "Обстрел огненными шарами",
    )
}

/** Exact configured-entry success probability for the two native handlers with a second roll. */
internal fun bestiaryEffectiveLootChance(
    kind: String,
    configuredChance: Double,
    inventoryDelivery: Boolean,
    amount: Int = 1,
): Double =
    when {
        !configuredChance.isFinite() || configuredChance !in 0.0..1.0 -> Double.NaN
        kind == "command" -> configuredChance * (1.0 - configuredChance)
        kind == "vanilla" && inventoryDelivery && amount <= 0 -> 0.0
        kind == "vanilla" && inventoryDelivery -> configuredChance * (1.0 - (1.0 - configuredChance).pow(amount))
        else -> configuredChance
    }

/** Maps only phase files with one unambiguous phase-one ancestor. */
internal fun bestiaryPhaseCanonicalRoots(childrenByRoot: Map<String, List<String>>): Map<String, String> {
    val parents = HashMap<String, MutableSet<String>>()
    childrenByRoot.forEach { (root, children) ->
        children.forEach { child -> if (child != root) parents.getOrPut(child) { linkedSetOf() }.add(root) }
    }
    val allIds = childrenByRoot.keys + childrenByRoot.values.flatten()
    val result = HashMap<String, String>()
    for (id in allIds) {
        val roots = linkedSetOf<String>()
        val visited = hashSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(id) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val currentParents = parents[current].orEmpty()
            if (currentParents.isEmpty()) roots.add(current) else currentParents.forEach(pending::addLast)
        }
        if (roots.size == 1 && roots.single() != id) result[id] = roots.single()
    }
    return result
}

/** Names generated by EliteMobs remain readable without constructing or rolling an item. */
internal fun bestiaryItemName(authored: String?, material: String, boss: String): String {
    val name = authored.orEmpty().replace(Regex("(?i)[&§][0-9a-fk-orx]"), "").replace(Regex("<[^>]*>"), "").trim()
    if (name.isBlank() || name.equals("Default name", true)) return material
    return name.replace("\$boss", boss).replace("\$weapon", material).replace("\$item", material)
        .replace("\$difficulty", "сложность похода").replace("\$level", "уровень награды")
}
