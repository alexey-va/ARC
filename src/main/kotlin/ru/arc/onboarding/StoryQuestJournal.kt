package ru.arc.onboarding

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.util.Logging.error
import ru.ruscrafting.onboarding.api.StoryQuestService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/** One durable story selection shared by NPC acceptance, journal, sidebar and world guidance. */
internal object StoryQuestJournal : StoryQuestService {
    private class Runtime(val store: StoryQuestTrackingStore) {
        val tasks = LifecycleTaskScope()
        val selected = mutableMapOf<UUID, String>()
        val loaded = mutableSetOf<UUID>()
        val tails = mutableMapOf<UUID, CompletableFuture<*>>()
        val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
        val receipts = ConcurrentHashMap<CompletableFuture<Void>, CompletableFuture<Void>>()
    }
    @Volatile private var runtime: Runtime? = null
    private var drained = CompletableFuture.completedFuture(Unit)
    private val plain = PlainTextComponentSerializer.plainText()

    fun init() {
        drained.handle { _, _ -> Unit }.join() // Maintenance boundary: old accepted writes finish before a new generation.
        val redis = ARC.redisManager ?: return
        val current = Runtime(StoryQuestTrackingStore(redis))
        runtime = current
        Bukkit.getServicesManager().register(StoryQuestService::class.java, this, ARC.instance, ServicePriority.Normal)
        Bukkit.getOnlinePlayers().forEach(::resume)
    }

    fun resume(player: Player) {
        val current = runtime ?: return
        val id = player.uniqueId
        val pending = (current.tails[id] ?: CompletableFuture.completedFuture(null)).handle { _, _ -> Unit }
            .thenCompose { current.store.load(id) }
        current.tails[id] = pending
        current.pending += pending
        pending.whenCompleteSync(current.tasks) { selected, failure ->
            current.pending.remove(pending)
            if (runtime !== current || Bukkit.getPlayer(id) !== player || current.tails[id] !== pending) return@whenCompleteSync
            if (failure != null) { error("Story quest selection load failed for {}", id, failure); return@whenCompleteSync }
            if (selected != null) current.selected[id] = selected else current.selected.remove(id)
            current.loaded += id
            refresh(player)
        }
    }

    override fun ready(playerId: UUID): Boolean = runtime?.loaded?.contains(playerId) == true &&
        OptionalOnboardingService.journalReady(playerId) && OnboardingService.journalReady(playerId)

    override fun quests(playerId: UUID): List<StoryQuestService.Quest> {
        check(Bukkit.isPrimaryThread())
        val player = Bukkit.getPlayer(playerId) ?: return emptyList()
        if (!ready(playerId)) return emptyList()
        return buildList {
            OnboardingService.questView(player)?.let { home ->
                val lines = OnboardingService.guideView(player)?.let(::onboardingGuideLines).orEmpty()
                add(StoryQuestService.Quest("first_home", "Первый дом", "Найдите место, сохраните дом, защитите участок и постройте базу.",
                    lines.getOrNull(1)?.let(plain::serialize)?.removePrefix("★ ") ?: if (home.completed) "Дом готов" else "Поговорите с Проводником",
                    if (home.completed) 4 else when (home.goal) {
                        OnboardingQuestGoal.HOME -> 1
                        OnboardingQuestGoal.CLAIM -> 2
                        OnboardingQuestGoal.BUILD -> 3
                        else -> 0
                    }, 4, status(home.accepted, home.completed), home.accepted && !home.completed && isTracked(player, "first_home")))
            }
            OptionalQuestKind.entries.forEach { kind ->
                val view = OptionalOnboardingService.view(player, kind) ?: return@forEach
                val instruction = optionalQuestInstruction(view)
                fun text(key: String) = plain.serialize(OptionalOnboardingService.text(key))
                add(StoryQuestService.Quest(kind.id, text("title-${kind.id}"), text("offer-${kind.id}"),
                    instruction?.let { text("goal-$it") } ?: if (view.completed) "Цепочка завершена" else "Поговорите с наставником",
                    view.steps.size, kind.steps.size, status(view.accepted, view.completed),
                    view.accepted && !view.completed && isTracked(player, kind.id)))
            }
        }.toList()
    }

    override fun open(playerId: UUID, questId: String): Boolean {
        check(Bukkit.isPrimaryThread())
        val player = Bukkit.getPlayer(playerId) ?: return false
        if (!ready(playerId) || quests(playerId).none { it.id() == questId }) return false
        if (questId == "first_home") OnboardingService.open(player, beginFlow = false)
        else OptionalOnboardingService.open(player, OptionalQuestKind.fromId(questId), beginFlow = false)
        return true
    }

    override fun track(playerId: UUID, questId: String?): CompletionStage<Void> {
        check(Bukkit.isPrimaryThread())
        val current = runtime ?: return CompletableFuture.failedFuture(IllegalStateException("Story journal unavailable"))
        val player = Bukkit.getPlayer(playerId) ?: return CompletableFuture.failedFuture(IllegalStateException("Player offline"))
        val active = questId == null || questId == "first_home" && OnboardingService.guideView(player) != null ||
            OptionalQuestKind.entries.firstOrNull { it.id == questId }?.let { OptionalOnboardingService.view(player, it) }
                ?.let { it.accepted && !it.completed } == true
        if (!active) return CompletableFuture.failedFuture(IllegalArgumentException("Only an active story can be tracked"))
        val result = CompletableFuture<Void>()
        val pending = (current.tails[playerId] ?: CompletableFuture.completedFuture(null)).handle { _, _ -> Unit }
            .thenCompose { current.store.save(playerId, questId) }
        current.tails[playerId] = result
        current.pending += pending
        current.receipts[result] = pending
        result.whenComplete { _, _ -> current.receipts.remove(result) }
        pending.whenComplete { _, failure ->
            current.pending.remove(pending)
            if (failure != null) result.completeExceptionally(failure)
            else if (runtime !== current) result.complete(null)
        }
        pending.whenCompleteSync(current.tasks) { _, failure ->
            if (failure != null) {
                error("Story quest selection save failed for {} ({})", playerId, questId, failure)
                result.completeExceptionally(failure)
            } else {
                if (runtime === current && Bukkit.getPlayer(playerId) === player) {
                    current.selected[playerId] = questId.orEmpty()
                    current.loaded += playerId
                    refresh(player)
                }
                result.complete(null)
            }
        }
        return result
    }

    fun isTracked(player: Player, questId: String): Boolean = selected(player) == questId

    private fun selected(player: Player): String? {
        val current = runtime ?: return null
        if (player.uniqueId !in current.loaded) return null
        return storyQuestSelection(current.selected[player.uniqueId], OnboardingService.guideView(player) != null,
            OptionalQuestKind.entries.filter { kind ->
                OptionalOnboardingService.view(player, kind)?.let { it.accepted && !it.completed } == true
            }.map { it.id })
    }

    fun accepted(player: Player, questId: String) { track(player.uniqueId, questId) }
    fun refresh(player: Player) {
        OnboardingService.refreshTracking(player)
        OptionalOnboardingService.refreshTracking(player)
    }

    fun leave(playerId: UUID) { runtime?.let { it.selected.remove(playerId); it.loaded.remove(playerId) } }
    fun shutdown() {
        val previous = runtime ?: return
        runtime = null
        Bukkit.getServicesManager().unregister(StoryQuestService::class.java, this)
        // Durable writes drain without depending on Paper callbacks after task retirement.
        previous.receipts.forEach { (receipt, operation) ->
            operation.whenComplete { _, failure ->
                if (failure == null) receipt.complete(null) else receipt.completeExceptionally(failure)
            }
        }
        drained = CompletableFuture.allOf(*previous.pending.toTypedArray()).handle { _, failure ->
            if (failure != null) error("Story quest selection drain failed", failure)
            previous.tasks.close()
            Unit
        }
    }
    fun closeCompletion(): CompletableFuture<Unit> = drained.copy()
    private fun status(accepted: Boolean, complete: Boolean) = when {
        complete -> StoryQuestService.Status.COMPLETED
        accepted -> StoryQuestService.Status.ACTIVE
        else -> StoryQuestService.Status.AVAILABLE
    }
}

/** A saved empty or completed selection must never silently select a different quest. */
internal fun storyQuestSelection(saved: String?, homeActive: Boolean, optionalActive: List<String>): String? {
    val active = (if (homeActive) listOf("first_home") else emptyList()) + optionalActive
    return if (saved != null) saved.takeIf { it in active } else active.firstOrNull()
}
