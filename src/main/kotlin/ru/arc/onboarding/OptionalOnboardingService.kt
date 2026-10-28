package ru.arc.onboarding

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.util.Logging.error
import ru.ruscrafting.onboarding.api.CasinoIntroService
import ru.ruscrafting.onboarding.api.OptionalOnboardingProgressService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/** Optional side quests own no rewards and never change the initial home quest. All Bukkit access is on Paper. */
object OptionalOnboardingService : OptionalOnboardingProgressService {
    private data class Key(val player: UUID, val kind: OptionalQuestKind)
    private class Runtime(val config: OptionalQuestConfig, val store: OptionalQuestStore) {
        val tasks = LifecycleTaskScope()
        val players = mutableMapOf<Key, OptionalQuestProgress>()
        val tails = mutableMapOf<Key, CompletableFuture<OptionalQuestProgress?>>()
        val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
        val trials = mutableMapOf<UUID, MutableMap<CasinoIntroService.Game, CasinoIntroService.State>>()
        val npcs = mutableMapOf<OptionalQuestKind, OnboardingQuestNpc>()
        val locationPending = mutableSetOf<Key>()
        val selected = mutableMapOf<UUID, OptionalQuestKind>()
        val hud = OptionalQuestHud(config::text)
        lateinit var menu: OptionalQuestDialog
    }

    @Volatile private var runtime: Runtime? = null
    private var drained = CompletableFuture.completedFuture(Unit)

    internal fun init() {
        shutdown()
        // Startup/manual reload is a maintenance boundary; finish accepted Redis writes first.
        drained.handle { _, _ -> Unit }.join()
        val config = OptionalQuestConfig(Config(ARC.instance.dataPath,
            ConfigManager.moduleYamlRelative(ARC.instance.dataPath, "onboarding.yml")))
        config.validate()
        if (!config.enabled) return
        val store = ARC.redisManager?.let(::OptionalQuestStore) ?: return
        val current = Runtime(config, store)
        runtime = current
        current.menu = OptionalQuestDialog(::view, ::accept, ::action, config::text)
        Bukkit.getServicesManager().register(OptionalOnboardingProgressService::class.java, this, ARC.instance, ServicePriority.Normal)
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            config.npcIds.filterValues { it >= 0 }.forEach { (kind, npcId) ->
                current.npcs[kind] = OnboardingQuestNpc(ARC.instance, npcId,
                    visible = { current.players[Key(it.uniqueId, kind)] == null },
                    eligible = { runtime === current }, open = { open(it, kind) }).also { it.start() }
            }
        }
        current.tasks.runTimer(20L, 20L) { Bukkit.getOnlinePlayers().forEach {
            observeLocation(current, it)
            refreshHud(current, it)
        } }
        Bukkit.getOnlinePlayers().forEach(::resume)
    }

    internal fun shutdown() {
        val previous = runtime ?: return
        runtime = null
        Bukkit.getServicesManager().unregister(OptionalOnboardingProgressService::class.java, this)
        previous.menu.close()
        previous.hud.close()
        previous.npcs.values.forEach(OnboardingQuestNpc::close)
        previous.tasks.close()
        drained = CompletableFuture.allOf(*previous.pending.toTypedArray()).thenApply { Unit }
    }

    internal fun closeCompletion(): CompletableFuture<Unit> = drained.copy()

    internal fun view(player: Player, kind: OptionalQuestKind): OptionalQuestView? {
        val current = runtime ?: return null
        val progress = current.players[Key(player.uniqueId, kind)]
        return OptionalQuestView(kind, progress != null, progress?.completed == true, progress?.steps.orEmpty(),
            current.trials[player.uniqueId]?.toMap().orEmpty())
    }

    internal fun text(key: String): Component = runtime?.config?.text(key) ?: Component.empty()

    internal fun activeView(player: Player): OptionalQuestView? {
        val current = runtime ?: return null
        val active = OptionalQuestKind.entries.filter { kind -> current.players[Key(player.uniqueId, kind)]?.completed == false }
        val location = player.location
        val local = current.config.areas.entries.firstOrNull { (step, area) -> step.kind in active &&
            area.contains(player.world.name, location.x, location.y, location.z) }?.key?.kind
        val kind = local ?: current.selected[player.uniqueId]?.takeIf { it in active } ?: active.firstOrNull() ?: return null
        return view(player, kind)
    }

    private fun refreshHud(current: Runtime, player: Player) {
        if (valid(current, player)) current.hud.refresh(player,
            if (OnboardingService.guideView(player) == null) activeView(player) else null)
    }

    internal fun resume(player: Player) {
        val current = runtime ?: return
        OptionalQuestKind.entries.forEach { kind ->
            request(current, player, kind, { current.store.load(player.uniqueId, kind) }) { progress ->
                if (progress != null) observeLocation(current, player)
                if (kind == OptionalQuestKind.CASINO && progress != null) reconcileTrials(current, player)
            }
        }
    }

    internal fun leave(playerId: UUID) {
        val current = runtime ?: return
        current.players.keys.removeIf { it.player == playerId }
        current.trials.remove(playerId)
        current.selected.remove(playerId)
        current.hud.clear(playerId)
        current.menu.forget(playerId)
        current.npcs.values.forEach { it.clear(playerId) }
        current.tails.entries.removeIf { it.key.player == playerId && it.value.isDone }
    }

    fun open(player: Player, questId: String) {
        val kind = OptionalQuestKind.entries.singleOrNull { it.id.equals(questId, true) } ?: return
        open(player, kind)
    }

    internal fun open(player: Player, kind: OptionalQuestKind) {
        val current = runtime ?: return
        current.selected[player.uniqueId] = kind
        ArcMenus.beginDialogFlow(player)
        val visit = current.menu.beginPending(player, kind)
        request(current, player, kind, { current.store.load(player.uniqueId, kind) }, visit) { progress ->
            if (kind == OptionalQuestKind.CASINO && progress != null) reconcileTrials(current, player)
            observeLocation(current, player)
            current.menu.finish(player, kind, visit)
        }
    }

    private fun accept(player: Player, kind: OptionalQuestKind) {
        val current = runtime ?: return
        current.selected[player.uniqueId] = kind
        val visit = current.menu.beginPending(player, kind)
        request(current, player, kind, { current.store.accept(player.uniqueId, kind).thenApply { it } }, visit) {
            observeLocation(current, player)
            if (kind == OptionalQuestKind.CASINO) reconcileTrials(current, player)
            current.menu.finish(player, kind, visit)
        }
    }

    private fun action(player: Player, kind: OptionalQuestKind, action: OptionalQuestAction) {
        val current = runtime ?: return
        val progress = current.players[Key(player.uniqueId, kind)]
        when (action) {
            OptionalQuestAction.GUILD -> if (kind == OptionalQuestKind.ADVENTURE) command(player, "ag")
            OptionalQuestAction.PRIMIS -> if (kind == OptionalQuestKind.ADVENTURE && progress != null && OptionalQuestStep.GUILD_VISITED in progress.steps)
                command(player, "em dungeontp primis_adventure.yml")
            OptionalQuestAction.CASINO -> if (kind == OptionalQuestKind.CASINO) command(player, "rccasino")
            OptionalQuestAction.FARM -> if (kind == OptionalQuestKind.FARM) command(player, "arcfarms travel farm")
            else -> if (kind == OptionalQuestKind.CASINO && progress != null && !progress.completed) {
                val game = when (action) {
                    OptionalQuestAction.TRIAL_SLOTS -> CasinoIntroService.Game.SLOTS
                    OptionalQuestAction.TRIAL_BLACKJACK -> CasinoIntroService.Game.BLACKJACK
                    OptionalQuestAction.TRIAL_PLINKO -> CasinoIntroService.Game.PLINKO
                    else -> return
                }
                val provider = Bukkit.getServicesManager().load(CasinoIntroService::class.java)
                if (provider == null) { current.menu.open(player, kind, notice(current, "trial-unavailable")); return }
                val visit = current.menu.beginPending(player, kind)
                val pending = provider.arm(player.uniqueId, game).toCompletableFuture()
                current.pending += pending
                pending.whenCompleteSync(current.tasks) { state, failure ->
                    current.pending.remove(pending)
                    if (!valid(current, player)) return@whenCompleteSync
                    if (failure == null && state != null) current.trials.getOrPut(player.uniqueId, ::mutableMapOf)[game] = state
                    if (failure == null && state == CasinoIntroService.State.COMPLETE)
                        recordCasinoTrial(player.uniqueId, game, "readback:${game.name.lowercase()}")
                    current.menu.finish(player, kind, visit, if (failure == null && state != null) null else notice(current, "trial-failed"))
                }
            }
        }
    }

    private fun command(player: Player, command: String) { ArcMenus.closeDialog(player); player.performCommand(command) }

    private fun observeLocation(current: Runtime, player: Player) {
        if (!valid(current, player)) return
        val location = player.location
        OptionalQuestKind.entries.forEach { kind ->
            val key = Key(player.uniqueId, kind)
            val before = current.players[key] ?: return@forEach
            if (before.completed || key in current.locationPending) return@forEach
            val steps = current.config.areas.filter { (step, area) -> step.kind == kind && step !in before.steps &&
                area.contains(player.world.name, location.x, location.y, location.z) }.keys
            if (steps.isEmpty()) return@forEach
            current.locationPending += key
            val pending = enqueue(current, key) {
                steps.fold(CompletableFuture.completedFuture<OptionalQuestProgress?>(before)) { future, step ->
                    future.thenCompose { current.store.observe(key.player, kind, step) }
                }
            }
            pending.whenCompleteSync(current.tasks) { updated, failure ->
                current.locationPending.remove(key)
                if (failure == null && valid(current, player) && updated != null) publish(current, player, key, updated)
            }
        }
    }

    private fun reconcileTrials(current: Runtime, player: Player) {
        val provider = Bukkit.getServicesManager().load(CasinoIntroService::class.java) ?: return
        CasinoIntroService.Game.entries.forEach { game ->
            provider.state(player.uniqueId, game).whenCompleteSync(current.tasks) { state, failure ->
                if (failure != null || state == null || !valid(current, player)) return@whenCompleteSync
                current.trials.getOrPut(player.uniqueId, ::mutableMapOf)[game] = state
                if (state == CasinoIntroService.State.COMPLETE) recordCasinoTrial(player.uniqueId, game, "readback:${game.name.lowercase()}")
            }
        }
    }

    override fun recordCasinoTrial(playerId: UUID, game: CasinoIntroService.Game, receiptId: String): CompletionStage<Void> {
        require(receiptId.length in 1..160 && receiptId.none(Char::isISOControl))
        return record(playerId, game.questStep())
    }

    override fun recordFarmShift(playerId: UUID, eventId: String): CompletionStage<Void> {
        require(eventId.matches(Regex("[a-zA-Z0-9_.:-]{1,160}")))
        return record(playerId, OptionalQuestStep.FARM_SHIFT_COMPLETE)
    }

    override fun recordPrimisIntro(playerId: UUID, questId: String): CompletionStage<Void> {
        require(questId == "primis_main_quest_1.yml")
        return record(playerId, OptionalQuestStep.PRIMIS_INTRO_COMPLETE)
    }

    private fun record(playerId: UUID, step: OptionalQuestStep): CompletionStage<Void> {
        check(Bukkit.isPrimaryThread()) { "Optional quest observations must be captured on Paper" }
        val current = runtime ?: return CompletableFuture.completedFuture(null)
        val key = Key(playerId, step.kind)
        val pending = enqueue(current, key) { current.store.observe(playerId, step.kind, step) }
        pending.whenCompleteSync(current.tasks) { updated, failure ->
            val player = Bukkit.getPlayer(playerId)
            if (failure == null && player != null && valid(current, player) && updated != null) publish(current, player, key, updated)
        }
        return pending.thenApply { null }
    }

    private fun request(current: Runtime, player: Player, kind: OptionalQuestKind,
        operation: () -> CompletableFuture<OptionalQuestProgress?>, visit: Long? = null, complete: (OptionalQuestProgress?) -> Unit) {
        val key = Key(player.uniqueId, kind)
        enqueue(current, key, operation).whenCompleteSync(current.tasks) { updated, failure ->
            if (!valid(current, player)) return@whenCompleteSync
            if (failure != null) {
                if (visit != null) current.menu.finish(player, kind, visit, notice(current, "save-failed"))
                return@whenCompleteSync
            }
            publish(current, player, key, updated)
            complete(updated)
        }
    }

    private fun publish(current: Runtime, player: Player, key: Key, updated: OptionalQuestProgress?) {
        // Progress is append-only: an earlier read callback may never hide a later accepted step.
        if (updated != null) {
            val before = current.players[key]
            current.players[key] = if (before?.runId == updated.runId)
                OptionalQuestProgress.create(updated.kind, updated.runId, before.steps + updated.steps) else updated
        }
        current.npcs[key.kind]?.refresh(player)
        refreshHud(current, player)
    }

    private fun enqueue(current: Runtime, key: Key, operation: () -> CompletableFuture<OptionalQuestProgress?>): CompletableFuture<OptionalQuestProgress?> {
        val pending = (current.tails[key] ?: CompletableFuture.completedFuture(null)).handle { _, _ -> Unit }.thenCompose { operation() }
        current.tails[key] = pending
        current.pending += pending
        pending.whenComplete { _, failure ->
            current.pending.remove(pending)
            if (failure != null) error("Optional quest operation failed for {} ({})", key.player, key.kind.id, failure)
        }
        return pending
    }

    private fun valid(current: Runtime, player: Player) = runtime === current && player.isOnline && Bukkit.getPlayer(player.uniqueId) === player
    private fun notice(current: Runtime, key: String) = PlainTextComponentSerializer.plainText().serialize(current.config.text(key))
}
