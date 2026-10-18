package ru.arc.onboarding

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID

internal data class OnboardingQuestView(
    val runId: String,
    val accepted: Boolean,
    val milestones: Set<OnboardingMilestone>,
    val canRestart: Boolean = false,
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
    private val restart: (Player) -> Unit,
    private val stop: (Player, String) -> Unit,
    private val openGoal: (Player, OnboardingQuestGoal) -> Unit,
    private val openTeleporter: (Player) -> Unit,
    private val text: (String) -> Component = { MiniMessage.miniMessage().deserialize(DEFAULT_TEXT.getValue(it)) },
    private val showDialog: (Player, PaperDialogScreen, () -> Unit, () -> Unit) -> Unit = { player, screen, reopen, dismiss ->
        ArcMenus.openDialog(player, screen, reopen = reopen, onDismiss = dismiss)
    },
) : AutoCloseable {
    private val pending = mutableMapOf<UUID, Long>()
    private var sequence = 0L

    /** The command/NPC entry starts the flow; native child navigation keeps the shared history. */
    fun open(player: Player, notice: String? = null) {
        forget(player.uniqueId)
        showDialog(player, screen(view(player), notice), { open(player) }, {})
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
    override fun close() { pending.clear() }

    internal fun pendingScreen(): PaperDialogScreen = screen(null).copy(body = listOf(prose("pending")))

    internal fun screen(snapshot: OnboardingQuestView?, notice: String? = null): PaperDialogScreen = PaperDialogScreen(
        id = "onboarding.quest",
        title = copy("title"),
        body = listOfNotNull(notice?.takeIf(String::isNotBlank)?.let { literal(it) }) +
            if (snapshot == null) listOf(prose("unavailable")) else body(snapshot),
        buttons = if (snapshot == null) emptyList() else buttons(snapshot),
        exitButton = PaperDialogButton(
            id = PaperDialogActionId.of("close"), label = copy("close"), width = 200, onClick = {},
        ),
        columns = 1,
    )

    private fun body(snapshot: OnboardingQuestView): List<PaperDialogBody> = buildList {
        add(prose(when {
            snapshot.completed -> "complete"
            !snapshot.accepted -> "offer"
            else -> instruction(snapshot)
        }))
        snapshot.notice?.takeIf(String::isNotBlank)?.let {
            add(literal(it))
        }
        add(DialogTables.body(
            rows = OnboardingQuestGoal.entries.map { goal ->
                copy("row-${goal.name.lowercase()}") to copy(rowStatus(snapshot, goal))
            },
            frame = DialogTables.Frame.EPIC,
            width = WIDTH,
            columns = DialogTables.Columns.LABEL_WIDE,
        ))
    }

    private fun buttons(snapshot: OnboardingQuestView): List<PaperDialogButton> = buildList {
        if (!snapshot.accepted) {
            add(button("accept", "accept", "accept-tip", snapshot) { player, current ->
                if (!current.accepted) accept(player, snapshot.runId) else open(player)
            })
        } else if (!snapshot.completed) {
            snapshot.goal?.takeUnless {
                it == OnboardingQuestGoal.TRAVEL ||
                    it == OnboardingQuestGoal.BUILD && OnboardingMilestone.AUTOBUILD_STARTED in snapshot.milestones
            }?.let { goal ->
                add(button("goal", "action-${goal.name.lowercase()}", "action-${goal.name.lowercase()}-tip", snapshot,
                    closes = goal == OnboardingQuestGoal.BUILD,
                ) { player, current ->
                    if (current.accepted && !current.completed && current.goal == goal) openGoal(player, goal)
                    else open(player)
                })
            }
        }
        add(button("teleport", "teleport", "teleport-tip", snapshot) { player, _ -> openTeleporter(player) })
        if (snapshot.canRestart) {
            add(button("restart", "restart", "restart-tip", snapshot) { player, current ->
                if (current.canRestart) restart(player) else open(player)
            })
            add(button("stop", "stop", "stop-tip", snapshot) { player, current ->
                if (current.canRestart) stop(player, snapshot.runId) else open(player)
            })
        }
    }

    private fun button(
        id: String,
        label: String,
        tooltip: String,
        snapshot: OnboardingQuestView,
        closes: Boolean = false,
        action: (Player, OnboardingQuestView) -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = copy(label),
        tooltip = copy(tooltip),
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

    private fun rowStatus(snapshot: OnboardingQuestView, goal: OnboardingQuestGoal): String {
        val done = when (goal) {
            OnboardingQuestGoal.TRAVEL -> OnboardingMilestone.FIRST_RTP in snapshot.milestones
            OnboardingQuestGoal.HOME -> OnboardingMilestone.HOME_CREATED in snapshot.milestones
            OnboardingQuestGoal.CLAIM -> OnboardingMilestone.FOOTHOLD_COMPLETE in snapshot.milestones
            OnboardingQuestGoal.BUILD -> snapshot.completed
        }
        return when {
            goal == OnboardingQuestGoal.HOME && snapshot.goal == OnboardingQuestGoal.HOME && done -> "status-mismatch"
            done -> "status-done"
            !snapshot.accepted -> "status-ahead"
            goal == OnboardingQuestGoal.BUILD && OnboardingMilestone.AUTOBUILD_STARTED in snapshot.milestones -> "status-building"
            goal == snapshot.goal -> "status-current"
            else -> "status-ahead"
        }
    }

    private fun copy(key: String) = text(key).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    private fun prose(key: String) = PaperDialogBody(copy(key), WIDTH)
    private fun literal(value: String) = PaperDialogBody(Component.text(value, BODY).decoration(TextDecoration.ITALIC, false), WIDTH)

    companion object {
        private const val WIDTH = 320
        private val BODY = TextColor.color(0xE8DFD2)

        internal val DEFAULT_TEXT = linkedMapOf(
            "title" to "<#c4abff>Первый дом",
            "offer" to "<#e8dfd2>Найдите место в мире выживания, защитите свой участок и постройте первый дом. Квест подскажет следующий шаг.",
            "complete" to "<#9bd48d>Первый дом готов.<newline><#e8dfd2>Точка возвращения сохранена, а участок защищён.",
            "unavailable" to "<#e8dfd2>Квест сейчас не запущен для вас.",
            "pending" to "<#e8dfd2>Обновляю квест…",
            "goal-travel" to "<#c4abff>Найдите место для дома<newline><#e8dfd2>Откройте телепортацию и выберите мир выживания.",
            "goal-home" to "<#c4abff>Сохраните точку дома<newline><#e8dfd2>Выберите место для базы и создайте здесь точку возвращения в меню домов.",
            "goal-match" to "<#c4abff>Совместите дом и участок<newline><#e8dfd2>Создайте точку дома внутри защищённого чанка. Дом и приват должны быть в одном месте.",
            "goal-claim" to "<#c4abff>Защитите участок<newline><#e8dfd2>Поставьте блок привата в чанке с точкой дома. Он есть в стартовом наборе.",
            "goal-build-book" to "<#c4abff>Откройте книгу «Первый дом»<newline><#e8dfd2>Возьмите её из стартового набора и откройте внутри своего защищённого участка.",
            "goal-build-start" to "<#c4abff>Постройте первый дом<newline><#e8dfd2>Выберите место для постройки в книге «Первый дом» и запустите строительство на своём участке.",
            "goal-building" to "<#c4abff>Дом строится<newline><#e8dfd2>Дождитесь завершения постройки. Квест отметит результат автоматически.",
            "row-travel" to "<#e8dfd2>Мир выживания",
            "row-home" to "<#e8dfd2>Точка дома",
            "row-claim" to "<#e8dfd2>Защита участка",
            "row-build" to "<#e8dfd2>Первый дом",
            "status-ahead" to "<#e8dfd2>Впереди",
            "status-current" to "<#c4abff>Текущая цель",
            "status-done" to "<#9bd48d>✔ Готово",
            "status-mismatch" to "<#ffb277>Вне участка",
            "status-building" to "<#c4abff>Строится",
            "accept" to "<#9bd48d>Начать квест",
            "accept-tip" to "<#e8dfd2>Начать путь к своему первому дому. Прогресс сохраняется между серверами.",
            "teleport" to "<#92bed8>Телепортация ›",
            "teleport-tip" to "<#e8dfd2>Выбрать мир или открыть другие направления телепортации.",
            "action-home" to "<#9bd48d>Мои дома ›",
            "action-home-tip" to "<#e8dfd2>Создать точку возвращения в выбранном месте.",
            "action-claim" to "<#9bd48d>Мой участок ›",
            "action-claim-tip" to "<#e8dfd2>Открыть управление приватом и подсказку по защите земли.",
            "action-build" to "<#9bd48d>Получить стартовый набор",
            "action-build-tip" to "<#e8dfd2>Получить обычный стартовый набор с книгой «Первый дом». Доступность набора проверяется как обычно.",
            "restart" to "<#c4a7e7>Начать заново",
            "restart-tip" to "<#e8dfd2>Начать новую проверку квеста для себя. Сбрасываются только этапы квеста.",
            "stop" to "<#ff6b61>Выключить тест",
            "stop-tip" to "<#e8dfd2>Остановить свой тест квеста и убрать персональный знак над телепортатором.",
            "close" to "<white>Закрыть",
        )
    }
}
