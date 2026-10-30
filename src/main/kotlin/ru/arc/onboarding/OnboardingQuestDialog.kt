package ru.arc.onboarding

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal data class OnboardingQuestView(
    val runId: String,
    val accepted: Boolean,
    val milestones: Set<OnboardingMilestone>,
    val notice: String? = null,
) {
    val completed: Boolean get() = OnboardingMilestone.AUTOBUILD_COMPLETE in milestones

    val goal: OnboardingQuestGoal?
        get() = when {
            !accepted || completed -> null
            OnboardingMilestone.FIRST_RTP !in milestones -> OnboardingQuestGoal.TRAVEL
            OnboardingMilestone.HOME_CREATED !in milestones -> OnboardingQuestGoal.HOME
            OnboardingMilestone.LAND_CLAIMED in milestones && OnboardingMilestone.FOOTHOLD_COMPLETE !in milestones -> OnboardingQuestGoal.HOME
            OnboardingMilestone.FOOTHOLD_COMPLETE !in milestones -> OnboardingQuestGoal.CLAIM
            else -> OnboardingQuestGoal.BUILD
        }
}

internal enum class OnboardingQuestGoal { TRAVEL, HOME, CLAIM, BUILD }

/** Presentation only: cached progress and every domain action remain owned by OnboardingService. */
internal class OnboardingQuestDialog(
    private val view: (Player) -> OnboardingQuestView?,
    private val accept: (Player, String) -> Unit,
    private val openGoal: (Player, OnboardingQuestGoal) -> Unit,
    private val text: (String) -> Component = { MiniMessage.miniMessage().deserialize(DEFAULT_TEXT.getValue(it)) },
    private val showDialog: (Player, PaperDialogScreen, () -> Unit, () -> Unit) -> Unit = { player, screen, reopen, dismiss ->
        ArcMenus.openDialog(player, screen, reopen = reopen, onDismiss = dismiss)
    },
    private val isTracked: (Player, String) -> Boolean = StoryQuestJournal::isTracked,
    private val track: (UUID, String?) -> CompletionStage<Void> = StoryQuestJournal::track,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : AutoCloseable {
    private val pending = mutableMapOf<UUID, Long>()
    private var sequence = 0L

    /** The command/NPC entry starts the flow; native child navigation keeps the shared history. */
    fun open(player: Player, notice: String? = null) {
        forget(player.uniqueId)
        showDialog(player, screen(view(player), notice, isTracked(player, "first_home")), { open(player) }, {})
    }

    /** Loading replaces this same visit synchronously; its footer can still dismiss the work. */
    fun beginPending(player: Player): Long {
        val token = ++sequence
        pending[player.uniqueId] = token
        showDialog(player, pendingScreen(), { open(player) }, {
            if (isCurrent(player, token)) forget(player.uniqueId)
        })
        return token
    }

    fun isCurrent(player: Player, token: Long): Boolean = pending[player.uniqueId] == token

    /** The durable operation can finish after dismissal, but it must not reopen the player's screen. */
    fun finish(player: Player, token: Long, notice: String? = null) {
        if (isCurrent(player, token)) open(player, notice)
    }

    fun forget(playerId: UUID) { pending.remove(playerId) }
    override fun close() { pending.clear(); tasks.close() }

    internal fun pendingScreen(): PaperDialogScreen = screen(null).copy(body = listOf(prose("pending")))

    internal fun screen(snapshot: OnboardingQuestView?, notice: String? = null, tracked: Boolean = false): PaperDialogScreen = PaperDialogScreen(
        id = "onboarding.quest",
        title = copy("title"),
        body = listOfNotNull(notice?.takeIf(String::isNotBlank)?.let { literal(it) }) +
            if (snapshot == null) listOf(prose("unavailable")) else body(snapshot),
        buttons = if (snapshot == null) emptyList() else buttons(snapshot, tracked),
        exitButton = PaperDialogButton(
            id = PaperDialogActionId.of("close"), label = copy("close"), width = 200, onClick = {},
        ),
        columns = 1,
    )

    private fun body(snapshot: OnboardingQuestView): List<PaperDialogBody> = buildList {
        val goal = copy(when {
            snapshot.completed -> "complete"
            !snapshot.accepted -> "offer"
            else -> instruction(snapshot)
        })
        add(DialogTables.filledBody(listOf(listOf(goal)), null, 300, listOf(1)))
        snapshot.notice?.takeIf(String::isNotBlank)?.let {
            add(literal(it))
        }
        add(DialogTables.body(OnboardingQuestGoal.entries.map { step ->
            val done = when (step) {
                OnboardingQuestGoal.TRAVEL -> OnboardingMilestone.FIRST_RTP in snapshot.milestones
                OnboardingQuestGoal.HOME -> OnboardingMilestone.HOME_CREATED in snapshot.milestones && snapshot.goal != OnboardingQuestGoal.HOME
                OnboardingQuestGoal.CLAIM -> OnboardingMilestone.FOOTHOLD_COMPLETE in snapshot.milestones
                OnboardingQuestGoal.BUILD -> snapshot.completed
            }
            copy("step-${step.name.lowercase()}") to copy(when {
                done -> "step-done"
                snapshot.goal == step -> "step-current"
                else -> "step-ahead"
            })
        }, frame = DialogTables.Frame.UNCOMMON, width = 300, columns = DialogTables.Columns.LABEL_WIDE))
    }

    private fun buttons(snapshot: OnboardingQuestView, tracked: Boolean): List<PaperDialogButton> = buildList {
        if (!snapshot.accepted) {
            add(button("accept", "accept", snapshot) { player, current ->
                if (!current.accepted) accept(player, snapshot.runId) else open(player)
            })
        } else actionGoal(snapshot)?.let { goal ->
            add(button("goal", "action-${goal.name.lowercase()}", snapshot,
                closes = goal == OnboardingQuestGoal.BUILD,
            ) { player, current ->
                if (actionGoal(current) == goal) openGoal(player, goal) else open(player)
            })
        }
        if (snapshot.accepted && !snapshot.completed) add(button("track", if (tracked) "tracked" else "track", snapshot) { player, current ->
            if (current.accepted && !current.completed) toggleTracking(player) else open(player)
        }.copy(tooltip = copy(if (tracked) "untrack-tip" else "track-tip")))
    }

    private fun toggleTracking(player: Player) {
        val target = if (isTracked(player, "first_home")) null else "first_home"
        val visit = beginPending(player)
        runCatching { track(player.uniqueId, target).toCompletableFuture() }
            .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { _, failure ->
                if (failure != null) ru.arc.util.Logging.error("Could not save first-home tracking for {}", player.uniqueId, failure)
                finish(player, visit, if (failure == null) null else
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(copy("tracking-failed")))
            }
    }

    private fun actionGoal(snapshot: OnboardingQuestView): OnboardingQuestGoal? = snapshot.goal?.takeUnless {
        it == OnboardingQuestGoal.BUILD && OnboardingMilestone.AUTOBUILD_STARTED in snapshot.milestones
    }

    private fun button(
        id: String,
        label: String,
        snapshot: OnboardingQuestView,
        closes: Boolean = false,
        action: (Player, OnboardingQuestView) -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = copy(label),
        width = WIDTH,
        closeDialogBeforeAction = closes,
        onClick = { context ->
            val current = view(context.player)
            if (current == null || current.runId != snapshot.runId) open(context.player)
            else action(context.player, current)
        },
    )

    private fun instruction(snapshot: OnboardingQuestView): String = when (snapshot.goal) {
        OnboardingQuestGoal.TRAVEL -> "goal-travel"
        OnboardingQuestGoal.HOME -> if (OnboardingMilestone.HOME_CREATED in snapshot.milestones) "goal-match" else "goal-home"
        OnboardingQuestGoal.CLAIM -> "goal-claim"
        OnboardingQuestGoal.BUILD -> when {
            OnboardingMilestone.AUTOBUILD_STARTED in snapshot.milestones -> "goal-building"
            OnboardingMilestone.BUILD_BOOK_OPENED in snapshot.milestones -> "goal-build-start"
            else -> "goal-build-book"
        }
        null -> "complete"
    }

    private fun copy(key: String) = text(key).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    private fun prose(key: String) = PaperDialogBody(copy(key), WIDTH)
    private fun literal(value: String) = PaperDialogBody(Component.text(value, BODY).decoration(TextDecoration.ITALIC, false), WIDTH)

    companion object {
        private const val WIDTH = 270
        private val BODY = TextColor.color(0xE8DFD2)

        internal val DEFAULT_TEXT = linkedMapOf(
            "title" to "<#c4abff>Первый дом",
            "offer" to "<#e8dfd2>Найдите место и постройте свой первый дом.",
            "complete" to "<#9bd48d>Дом готов, участок защищён.",
            "unavailable" to "<#e8dfd2>Квест не запущен.",
            "pending" to "<#e8dfd2>Загрузка…",
            "step-travel" to "<#e8dfd2>Мир выживания",
            "step-home" to "<#e8dfd2>Точка дома",
            "step-claim" to "<#e8dfd2>Защита участка",
            "step-build" to "<#e8dfd2>Первый дом",
            "step-done" to "<#9bd48d>✔ Готово",
            "step-current" to "<#c4abff>Сейчас",
            "step-ahead" to "<#e8dfd2>Впереди",
            "track" to "<white>○ Отслеживать",
            "tracked" to "<#9bd48d>✔ Отслеживать",
            "track-tip" to "<#e8dfd2>Показать текущую цель на табло и в боссбаре.",
            "untrack-tip" to "<#e8dfd2>Скрыть этот сюжет с табло и боссбара.",
            "tracking-failed" to "Не удалось сохранить отслеживание. Попробуйте ещё раз.",
            "goal-travel" to "<#c4abff>1 / 4 · Найдите место<newline><#e8dfd2>Выберите мир выживания.",
            "goal-home" to "<#c4abff>2 / 4 · Точка дома<newline><#e8dfd2>Встаньте на месте базы и сохраните дом.",
            "goal-match" to "<#c4abff>2 / 4 · Точка дома<newline><#e8dfd2>Сохраните дом внутри своего привата.",
            "goal-claim" to "<#c4abff>3 / 4 · Защитите участок<newline><#e8dfd2>Поставьте блок привата в чанке с точкой дома.",
            "goal-build-book" to "<#c4abff>4 / 4 · Постройте дом<newline><#e8dfd2>Откройте книгу «Первый дом» на своём участке.",
            "goal-build-start" to "<#c4abff>4 / 4 · Постройте дом<newline><#e8dfd2>Выберите место в книге и начните стройку.",
            "goal-building" to "<#c4abff>4 / 4 · Дом строится<newline><#e8dfd2>Дождитесь окончания постройки.",
            "accept" to "<#9bd48d>Начать",
            "action-travel" to "<#9bd48d>Выбрать мир",
            "action-home" to "<#9bd48d>Мои дома",
            "action-claim" to "<#9bd48d>Мой участок",
            "action-build" to "<#9bd48d>Стартовый набор",
            "close" to "<white>Закрыть",
        )
    }
}
