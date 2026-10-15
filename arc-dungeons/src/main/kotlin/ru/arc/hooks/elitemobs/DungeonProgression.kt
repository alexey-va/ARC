package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import com.magmaguy.elitemobs.api.EliteSkillXpGainEvent
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.CombatLevelDisplay
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPBar
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerKickEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.logging.ArcLogging as Logging
import ru.arc.sync.DungeonXpBookRecord
import ru.arc.sync.EmSync
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.WeakHashMap

/** Captures only accepted native combat awards, never book/admin grants or presence in a party. */
internal class DungeonRunSkillLedger {
    val runId: UUID = UUID.randomUUID()
    private val earned = mutableMapOf<UUID, MutableMap<String, Long>>()
    private var completed = false

    fun record(player: UUID, skill: String, xp: Long) {
        if (completed || xp <= 0) return
        val totals = earned.getOrPut(player) { mutableMapOf() }
        val before = totals[skill] ?: 0L
        totals[skill] = before + minOf(xp, Long.MAX_VALUE - before)
    }

    fun complete(participants: Set<UUID>): Map<UUID, Map<String, Long>> {
        if (completed) return emptyMap()
        completed = true
        val result = earned.filterKeys { it in participants }.mapValues { (_, skills) ->
            skills.mapValues { (_, xp) -> xp / 10 }.filterValues { it > 0 }
        }.filterValues { it.isNotEmpty() }
        earned.clear()
        return result
    }
}

/** Native skills remain authoritative; book entitlements share their existing EmSync snapshot. */
internal class DungeonProgression(
    private val sync: () -> EmSync?,
    private val adventure: DungeonAdventureService = NativeDungeonAdventureService,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val text: (String, String) -> Component = { _, fallback ->
        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(fallback)
    },
) : Listener, AutoCloseable {
    private data class Completion(val players: List<Player>, val title: String) {
        val participants = players.mapTo(mutableSetOf()) { it.uniqueId }
    }
    private val runs = WeakHashMap<DungeonInstance, DungeonRunSkillLedger>()
    private val finishing = WeakHashMap<DungeonInstance, Completion>()
    private val finished = java.util.Collections.newSetFromMap(WeakHashMap<DungeonInstance, Boolean>())
    private val busy = mutableSetOf<UUID>()
    private var closed = false

    private fun ready(player: Player, requireOnline: Boolean = true): Boolean = !closed && (!requireOnline || player.isOnline) &&
        SkillsConfig.isSkillSystemEnabled() && PlayerData.isDataLoaded(player.uniqueId) &&
        sync()?.isReady(player.uniqueId) == true

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun earned(event: EliteSkillXpGainEvent) {
        if (event.isCancelled) return
        val old = PlayerData.getSkillXP(event.player.uniqueId, event.skillType)
        record(event, event.skillType, minOf(event.xp, Long.MAX_VALUE - old).coerceAtLeast(0))
    }

    /** Called only after the paired listener has actually incremented the companion. */
    fun record(event: EliteSkillXpGainEvent, skill: SkillType, amount: Long) {
        val player = event.player
        if (amount <= 0 || event.isCancelled || !ready(player) || player.gameMode == GameMode.SPECTATOR) return
        if (event.eliteEntity.location?.world != player.world) return
        val instance = DungeonInstance.getDungeonInstances().firstOrNull { it.world == player.world } ?: return
        if (instance in finished || instance.isCancelled) return
        val participant = player in instance.participants || player.uniqueId in finishing[instance]?.participants.orEmpty()
        if (!participant || (instance.state.name != "ONGOING" && instance !in finishing)) return
        // An event is native post-eligibility evidence, including contribution, lockout and XP boosts.
        runs.getOrPut(instance) { DungeonRunSkillLedger() }.record(player.uniqueId, skill.name, amount)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: DungeonCompleteEvent) {
        if (closed) return
        val instance = event.dungeonInstance
        if (instance in finished || instance in finishing) return
        val title = plainDungeonQuestText(instance.contentPackagesConfigFields.name).take(100)
        finishing[instance] = Completion(instance.participants.toList(), title)
        // Kill objectives dispatch completion before SkillXPHandler at NORMAL. The outer death
        // MONITOR seals after that native commit; one tick also covers non-kill objectives.
        tasks.runLater(1) { flush(instance) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun deathFinished(event: EliteMobDeathEvent) {
        val world = event.eliteEntity.location?.world ?: return
        finishing.keys.toList().filter { it.world == world }.forEach(::flush)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun quitting(event: PlayerQuitEvent) = flushPlayer(event.player)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun kicking(event: PlayerKickEvent) { if (!event.isCancelled) flushPlayer(event.player) }

    private fun flushPlayer(player: Player) {
        finishing.entries.toList().filter { player.uniqueId in it.value.participants }.forEach { flush(it.key) }
    }

    /** Called before EmSync is detached so accepted completion rights enter its final snapshot. */
    fun flushCompletions() = finishing.keys.toList().forEach(::flush)

    private fun flush(instance: DungeonInstance) {
        if (closed || instance in finished) return
        val pending = finishing.remove(instance) ?: return
        finished.add(instance)
        val ledger = runs.remove(instance) ?: return
        val rewards = ledger.complete(pending.participants)
        for (player in pending.players) {
            val xp = rewards[player.uniqueId] ?: continue
            if (!ready(player, requireOnline = false)) {
                Logging.error("Dungeon skill book could not be captured before player sync detached: {} / {}", player.uniqueId, ledger.runId)
                continue
            }
            val owner = sync() ?: continue
            val id = UUID.nameUUIDFromBytes("${ledger.runId}:${player.uniqueId}".toByteArray(StandardCharsets.UTF_8)).toString()
            val book = DungeonXpBookRecord(id, pending.title, System.currentTimeMillis(), xp)
            if (!owner.addSkillBook(player.uniqueId, book)) continue
            owner.persist(player).whenCompleteSync(tasks) { _, failure ->
                if (failure != null) {
                    Logging.error("Dungeon skill book snapshot pending for {} / {}", player.uniqueId, id, failure)
                } else if (player.isOnline && !closed && sync() === owner) {
                    player.sendMessage(text("book-received", "<#c4abff>Книга прохождения получена · Развитие навыков ›")
                        .clickEvent(ClickEvent.runCommand("/dungeon skills")))
                }
            }
        }
    }

    fun view(player: Player): DungeonProgressionView {
        if (!SkillsConfig.isSkillSystemEnabled()) return DungeonProgressionView(DungeonProgressionAvailability.DISABLED)
        if (!ready(player)) return DungeonProgressionView(DungeonProgressionAvailability.LOADING)
        val skills = adventure.skills(player) ?: return DungeonProgressionView(DungeonProgressionAvailability.LOADING)
        return DungeonProgressionView(
            availability = DungeonProgressionAvailability.READY,
            skills = skills.map { info ->
                val type = skillTypeFromId(info.id)
                val kind = type?.let(::dungeonMasteryKind)
                val percent = dungeonMasteryPercent(info.level)
                val nextLevel = ((info.level / 10 + 1) * 10).takeIf { it <= 100 }
                val label = when (kind) {
                    DungeonMasteryKind.ARMOR_DEFENSE -> plainText("armor-defense", "Защита от элитных мобов")
                    DungeonMasteryKind.MELEE_ATTACK_SPEED -> plainText("melee-attack-speed", "Скорость ближних атак")
                    DungeonMasteryKind.STAFF_CAST_SPEED -> plainText("staff-cast-speed", "Скорость заклинаний")
                    null -> null
                }
                val unlock = adventure.perks(player, info.id)?.perks?.filter { it.requiredLevel > info.level }
                    ?.minWithOrNull(compareBy<DungeonSkillPerkInfo> { it.requiredLevel }.thenBy { it.id })
                DungeonProgressionSkill(
                    skill = type?.let { info.copy(name = dungeonSkillName(it)) } ?: info,
                    maxLevel = null,
                    gainDescription = if (type == SkillType.ARMOR)
                        plainText("armor-gain", "Опыт за защиту в бою с элитными мобами. Книги сохраняют эту ветку.")
                        else plainText("weapon-gain", "Опыт за бой этим оружием. Штатная оружейная пара развивается автоматически."),
                    passive = label?.let { DungeonProgressionPassive(it, percent.toDouble(), nextLevel,
                        nextLevel?.let { level -> dungeonMasteryPercent(level).toDouble() }) },
                    nextUnlock = unlock?.let { DungeonProgressionUnlock(it.name, it.requiredLevel, it.description.joinToString(" ")) },
                )
            },
            books = sync()?.skillBooks(player.uniqueId).orEmpty().mapNotNull { preview(player, it.id) },
        )
    }

    private fun plainText(key: String, fallback: String): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text(key, fallback))

    fun preview(player: Player, bookId: String): DungeonProgressionBook? {
        if (!ready(player)) return null
        val book = sync()?.skillBooks(player.uniqueId)?.singleOrNull { it.id == bookId } ?: return null
        val gains = book.xp.entries.sortedBy { it.key }.mapNotNull { (name, reward) ->
            val type = skillTypeFromId(name) ?: return@mapNotNull null
            val before = PlayerData.getSkillXP(player.uniqueId, type)
            val gain = minOf(reward, Long.MAX_VALUE - before).coerceAtLeast(0)
            if (gain <= 0) return@mapNotNull null
            DungeonProgressionBookGain(type.name, dungeonSkillName(type), gain,
                SkillXPCalculator.levelFromTotalXP(before), SkillXPCalculator.levelFromTotalXP(before + gain))
        }
        return gains.takeIf { it.isNotEmpty() }?.let { DungeonProgressionBook(book.id, book.title, book.earnedAt, it) }
    }

    fun consume(player: Player, bookId: String, complete: (DungeonProgressionBookResult) -> Unit) {
        if (!ready(player)) { complete(DungeonProgressionBookResult.LOADING); return }
        val owner = sync() ?: run { complete(DungeonProgressionBookResult.LOADING); return }
        if (!busy.add(player.uniqueId)) { complete(DungeonProgressionBookResult.PENDING); return }
        val preview = preview(player, bookId)
        if (preview == null || owner.removeSkillBook(player.uniqueId, bookId) == null) {
            busy.remove(player.uniqueId); complete(DungeonProgressionBookResult.MISSING); return
        }
        // No inventory mutation: entitlement removal and native XP enter one serialized Redis payload.
        // On an unknown save outcome never restore the book or replay the increment.
        try {
            val applied = mutableListOf<Triple<SkillType, Long, Long>>()
            for (gain in preview.gains) {
                val skill = requireNotNull(skillTypeFromId(gain.skillId))
                val old = PlayerData.getSkillXP(player.uniqueId, skill)
                val updated = PlayerData.addSkillXP(player.uniqueId, skill, gain.xp)
                applied += Triple(skill, old, updated)
            }
            owner.persist(player).whenCompleteSync(tasks) { _, failure ->
                busy.remove(player.uniqueId)
                if (failure != null) Logging.error("Dungeon skill book consumption save pending for {} / {}", player.uniqueId, bookId, failure)
                if (!closed && player.isOnline && sync() === owner) {
                    if (failure == null) runCatching {
                        for ((skill, old, updated) in applied) SkillXPBar.showXPGain(player, skill, old, updated, updated - old)
                        CombatLevelDisplay.updateDisplay(player)
                    }.onFailure { Logging.error("Dungeon skill book feedback failed for {}", player.uniqueId, it) }
                    complete(if (failure == null) DungeonProgressionBookResult.APPLIED else DungeonProgressionBookResult.FAILED)
                }
            }
        } catch (failure: Exception) {
            busy.remove(player.uniqueId)
            Logging.error("Dungeon skill book application interrupted for {} / {}; do not replay", player.uniqueId, bookId, failure)
            complete(DungeonProgressionBookResult.FAILED)
        }
    }

    override fun close() {
        closed = true
        tasks.close()
        runs.clear(); finishing.clear(); finished.clear(); busy.clear()
    }
}
