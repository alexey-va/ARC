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
import ru.ruscrafting.onboarding.api.CasinoIntroService
import ru.ruscrafting.onboarding.api.PrimisQuestGuidanceService
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The service owns acceptance, travel, trial admission and durable completion. */
internal class OptionalQuestDialog(
    private val view: (Player, OptionalQuestKind) -> OptionalQuestView?,
    private val accept: (Player, OptionalQuestKind) -> Unit,
    private val action: (Player, OptionalQuestKind, OptionalQuestAction) -> Unit,
    private val text: (String) -> Component = { MiniMessage.miniMessage().deserialize(DEFAULT_TEXT.getValue(it)) },
    private val showDialog: (Player, PaperDialogScreen, () -> Unit, () -> Unit) -> Unit = { player, screen, reopen, dismiss ->
        ArcMenus.openDialog(player, screen, reopen = reopen, onDismiss = dismiss)
    },
    private val isTracked: (Player, String) -> Boolean = StoryQuestJournal::isTracked,
    private val track: (UUID, String?) -> CompletionStage<Void> = StoryQuestJournal::track,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : AutoCloseable {
    private val pending = mutableMapOf<UUID, Pair<OptionalQuestKind, Long>>()
    private var sequence = 0L

    fun open(player: Player, kind: OptionalQuestKind, notice: String? = null) {
        forget(player.uniqueId)
        showDialog(player, screen(kind, view(player, kind), notice, isTracked(player, kind.id)), { open(player, kind) }, {})
    }

    fun beginPending(player: Player, kind: OptionalQuestKind): Long {
        val visit = ++sequence
        pending[player.uniqueId] = kind to visit
        showDialog(player, screen(kind, null).copy(body = listOf(panel(copy("pending")))),
            { open(player, kind) }, { if (isCurrent(player, kind, visit)) forget(player.uniqueId) })
        return visit
    }

    fun isCurrent(player: Player, kind: OptionalQuestKind, visit: Long): Boolean =
        pending[player.uniqueId] == (kind to visit)

    /** A result may persist after Escape, but must not reopen a dismissed or different quest. */
    fun finish(player: Player, kind: OptionalQuestKind, visit: Long, notice: String? = null) {
        if (isCurrent(player, kind, visit)) open(player, kind, notice)
    }

    fun forget(playerId: UUID) { pending.remove(playerId) }
    override fun close() { pending.clear(); tasks.close() }

    internal fun screen(kind: OptionalQuestKind, snapshot: OptionalQuestView?, notice: String? = null, tracked: Boolean = false): PaperDialogScreen {
        val current = snapshot?.takeIf { it.kind == kind }
        return PaperDialogScreen(
            id = "onboarding.optional.${kind.id}",
            title = copy("title-${kind.id}"),
            body = listOfNotNull(notice?.takeIf(String::isNotBlank)?.let {
                PaperDialogBody(Component.text(it, BODY).decoration(TextDecoration.ITALIC, false), WIDTH)
            }) + body(kind, current),
            buttons = buttons(kind, current, tracked),
            exitButton = PaperDialogButton(
                id = PaperDialogActionId.of("close"), label = copy("close"), width = 200, onClick = {},
            ),
            columns = 1,
        )
    }

    private fun body(kind: OptionalQuestKind, snapshot: OptionalQuestView?): List<PaperDialogBody> = buildList {
        val instruction = snapshot?.let(::optionalQuestInstruction)
        add(panel(when {
            snapshot == null -> copy("unavailable")
            snapshot.completed -> copy("complete-${kind.id}")
            !snapshot.accepted -> copy("offer-${kind.id}")
            instruction == null -> copy("saving")
            else -> copy("goal-$instruction").append(Component.newline())
                .append(copy("hint-$instruction"))
                .append(Component.newline()).append(Component.newline())
                .append(copy("progress")).append(Component.text(" ${snapshot.steps.count { it.kind == kind }} / ${kind.steps.size}", BODY))
        }))
        if (kind == OptionalQuestKind.CASINO && snapshot != null && !snapshot.completed &&
            (!snapshot.accepted || optionalQuestNextStep(snapshot)?.trialGame() != null)) {
            add(panel(copy("trial-rules")))
        }
        if (snapshot != null) add(DialogTables.body(
            kind.steps.map { step ->
                val key = step.name.lowercase(Locale.ROOT).replace('_', '-')
                copy("goal-$key") to copy(when {
                    step in snapshot.steps -> "step-done"
                    step == optionalQuestNextStep(snapshot) -> "step-current"
                    else -> "step-ahead"
                })
            }, frame = DialogTables.Frame.UNCOMMON, width = 300, columns = DialogTables.Columns.LABEL_WIDE,
        ))
    }

    private fun buttons(kind: OptionalQuestKind, snapshot: OptionalQuestView?, tracked: Boolean): List<PaperDialogButton> = buildList {
        if (snapshot == null) return@buildList
        if (!snapshot.accepted && !snapshot.completed) add(PaperDialogButton(
            id = PaperDialogActionId.of("accept"), label = copy("accept"), width = WIDTH,
            onClick = { context ->
                val current = view(context.player, kind)
                if (current?.kind == kind && !current.accepted && !current.completed) accept(context.player, kind)
                else open(context.player, kind)
            },
        ))
        availableActions(snapshot).forEach { destination ->
            val id = destination.name.lowercase(Locale.ROOT)
            add(PaperDialogButton(
                id = PaperDialogActionId.of(id), label = copy("action-${id.replace('_', '-')}"), width = WIDTH,
                onClick = { context ->
                    val current = view(context.player, kind)
                    if (current?.kind == kind && destination in availableActions(current)) action(context.player, kind, destination)
                    else open(context.player, kind)
                },
            ))
        }
        if (snapshot.accepted && !snapshot.completed) add(PaperDialogButton(
            id = PaperDialogActionId.of("track"), label = copy(if (tracked) "tracked" else "track"),
            tooltip = copy(if (tracked) "untrack-tip" else "track-tip"), width = WIDTH,
            onClick = { context ->
                val current = view(context.player, kind)
                if (current?.accepted == true && !current.completed) toggleTracking(context.player, kind)
                else open(context.player, kind)
            },
        ))
    }

    private fun toggleTracking(player: Player, kind: OptionalQuestKind) {
        val target = if (isTracked(player, kind.id)) null else kind.id
        val visit = beginPending(player, kind)
        runCatching { track(player.uniqueId, target).toCompletableFuture() }
            .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { _, failure ->
                if (failure != null) ru.arc.util.Logging.error("Could not save optional quest tracking for {}", player.uniqueId, failure)
                finish(player, kind, visit, if (failure == null) null else
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(copy("tracking-failed")))
            }
    }

    private fun copy(key: String) = text(key).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    private fun panel(content: Component) = DialogTables.filledBody(listOf(listOf(content)), null, 300, listOf(1))

    companion object {
        private const val WIDTH = 270
        private val BODY = TextColor.color(0xE8DFD2)

        internal val DEFAULT_TEXT = linkedMapOf(
            "title-adventure" to "<#c4abff>Первое приключение",
            "title-casino" to "<#ffb277>Знакомство с казино",
            "title-farm" to "<#9bd48d>Первая смена",
            "offer-adventure" to "<#e8dfd2>Загляните в гильдию искателей приключений, доберитесь до острова Примис и выполните первое задание.",
            "offer-casino" to "<#e8dfd2>Осмотрите три этажа казино и попробуйте слоты, блэкджек и плинко — по одному бесплатному раунду.",
            "offer-farm" to "<#e8dfd2>Познакомьтесь с производственными работами: пройдите первую смену на ферме, затем попробуйте лесопилку и шахту.",
            "complete-adventure" to "<#9bd48d>Первое приключение пройдено.<newline><#e8dfd2>Гильдия и остров Примис ждут вас снова.",
            "complete-casino" to "<#9bd48d>Знакомство с казино завершено.<newline><#e8dfd2>Вы осмотрели три этажа и попробовали все три игры.",
            "complete-farm" to "<#9bd48d>Первая смена завершена.<newline><#e8dfd2>Возвращайтесь на ферму, когда захотите поработать.",
            "unavailable" to "<#e8dfd2>Поручение сейчас недоступно. Загляните позже.",
            "pending" to "<#e8dfd2>Загрузка…",
            "saving" to "<#e8dfd2>Сохраняем выполнение поручения…",
            "progress" to "<#e8dfd2>Выполнено:",
            "step-done" to "<#9bd48d>✔ Готово",
            "step-current" to "<#c4abff>Сейчас",
            "step-ahead" to "<#e8dfd2>Впереди",
            "track" to "<white>○ Отслеживать",
            "tracked" to "<#9bd48d>✔ Отслеживать",
            "track-tip" to "<#e8dfd2>Показать текущую цель на табло и в боссбаре.",
            "untrack-tip" to "<#e8dfd2>Скрыть этот сюжет с табло и боссбара.",
            "tracking-failed" to "Не удалось сохранить отслеживание. Попробуйте ещё раз.",
            "accept" to "<#9bd48d>Начать поручение",
            "close" to "<white>Закрыть",
            "action-guild" to "<#92bed8>В гильдию",
            "action-primis" to "<#92bed8>На остров Примис",
            "action-farm" to "<#92bed8>На ферму",
            "action-casino" to "<#92bed8>В казино",
            "action-trial-slots" to "<#9bd48d>Проба слотов",
            "action-trial-blackjack" to "<#9bd48d>Проба блэкджека",
            "action-trial-plinko" to "<#9bd48d>Проба плинко",
            "trial-rules" to "<#e8dfd2>Одна бесплатная проба каждой игры.<newline>Пробная ставка — 5 фишек.<newline>Возможный выигрыш — до 25 фишек.<newline>После подготовки начните игру за столом или автоматом.",
            "trial-unavailable" to "<#e8dfd2>Бесплатная проба сейчас недоступна. Попробуйте позже.",
            "trial-failed" to "<#e8dfd2>Не удалось подготовить бесплатную пробу. Попробуйте ещё раз.",
            "save-failed" to "<#e8dfd2>Не удалось сохранить поручение. Попробуйте ещё раз.",
            "goal-guild-visited" to "<#77ee94>Посетите гильдию",
            "hint-guild-visited" to "<#e8dfd2>Войдите в подсвеченный портал рядом с Гарретом",
            "goal-primis-visited" to "<#77ee94>Доберитесь до Примиса",
            "hint-primis-visited" to "<#e8dfd2>В гильдии найдите подсвеченный портал Примиса",
            "goal-primis-intro-complete" to "<#77ee94>Первое задание Примиса",
            "hint-primis-intro-complete" to "<#e8dfd2>Поговорите с капитаном, главой деревни и первым помощником",
            "goal-primis-intro-accept" to "<#77ee94>Поговорите с капитаном",
            "hint-primis-intro-accept" to "<#e8dfd2>Примите первое задание у Сквалуса",
            "goal-primis-intro-objective" to "<#77ee94>Найдите главу деревни",
            "hint-primis-intro-objective" to "<#e8dfd2>Поговорите с Писцисом в деревне",
            "goal-primis-intro-turn-in" to "<#77ee94>Вернитесь к первому помощнику",
            "hint-primis-intro-turn-in" to "<#e8dfd2>Сдайте задание Иракунди",
            "goal-primis-intro-saving" to "<#77ee94>Первое задание выполнено",
            "hint-primis-intro-saving" to "<#e8dfd2>Сохраняем завершение квеста",
            "goal-casino-ground-visited" to "<#77ee94>Нижний этаж: покер",
            "hint-casino-ground-visited" to "<#e8dfd2>Подойдите к покерному столу",
            "goal-casino-middle-visited" to "<#77ee94>Средний этаж: бильярд",
            "hint-casino-middle-visited" to "<#e8dfd2>Подойдите к бильярдному столу",
            "goal-casino-upper-visited" to "<#77ee94>Верхний этаж: слоты",
            "hint-casino-upper-visited" to "<#e8dfd2>Подойдите к игровым автоматам",
            "goal-casino-slots-complete" to "<#77ee94>Подготовьте пробу слотов",
            "hint-casino-slots-complete" to "<#e8dfd2>Кнопка «Проба слотов»",
            "goal-casino-blackjack-complete" to "<#77ee94>Подготовьте пробу блэкджека",
            "hint-casino-blackjack-complete" to "<#e8dfd2>Кнопка «Проба блэкджека»",
            "goal-casino-plinko-complete" to "<#77ee94>Подготовьте пробу плинко",
            "hint-casino-plinko-complete" to "<#e8dfd2>Кнопка «Проба плинко»",
            "goal-casino-slots-complete-armed" to "<#77ee94>Сыграйте в слоты",
            "hint-casino-slots-complete-armed" to "<#e8dfd2>Бесплатная проба готова",
            "goal-casino-blackjack-complete-armed" to "<#77ee94>Сыграйте в блэкджек",
            "hint-casino-blackjack-complete-armed" to "<#e8dfd2>Бесплатная проба готова",
            "goal-casino-plinko-complete-armed" to "<#77ee94>Сыграйте в плинко",
            "hint-casino-plinko-complete-armed" to "<#e8dfd2>Бесплатная проба готова",
            "goal-trial-playing" to "<#77ee94>Раунд ещё идёт",
            "hint-trial-playing" to "<#e8dfd2>Дождитесь его завершения",
            "goal-trial-settling" to "<#77ee94>Игра завершена",
            "hint-trial-settling" to "<#e8dfd2>Сохраняем результат",
            "goal-trial-unavailable" to "<#ffb277>Проба пока недоступна",
            "hint-trial-unavailable" to "<#e8dfd2>Вернитесь чуть позже",
            "goal-farm-visited" to "<#77ee94>Побывайте на ферме",
            "hint-farm-visited" to "<#e8dfd2>Пройдите в подсвеченный портал фермы",
            "goal-farm-shift-complete" to "<#77ee94>Завершите первую смену",
            "hint-farm-shift-complete" to "<#e8dfd2>Работайте до конца смены",
        )
    }
}

internal fun optionalQuestNextStep(view: OptionalQuestView): OptionalQuestStep? =
    if (!view.accepted || view.completed) null else view.kind.steps.firstOrNull { it !in view.steps }

internal fun OptionalQuestStep.trialGame(): CasinoIntroService.Game? = when (this) {
    OptionalQuestStep.CASINO_SLOTS_COMPLETE -> CasinoIntroService.Game.SLOTS
    OptionalQuestStep.CASINO_BLACKJACK_COMPLETE -> CasinoIntroService.Game.BLACKJACK
    OptionalQuestStep.CASINO_PLINKO_COMPLETE -> CasinoIntroService.Game.PLINKO
    else -> null
}

/** Read the admission state without treating an armed or running round as a completed quest step. */
internal fun optionalQuestInstruction(view: OptionalQuestView): String? {
    val step = optionalQuestNextStep(view) ?: return null
    val key = step.name.lowercase(Locale.ROOT).replace('_', '-')
    if (step == OptionalQuestStep.PRIMIS_INTRO_COMPLETE) return when (view.primisStage) {
        PrimisQuestGuidanceService.Stage.NOT_ACCEPTED -> "primis-intro-accept"
        PrimisQuestGuidanceService.Stage.ACTIVE_OBJECTIVE -> "primis-intro-objective"
        PrimisQuestGuidanceService.Stage.READY_TO_TURN_IN -> "primis-intro-turn-in"
        PrimisQuestGuidanceService.Stage.COMPLETED -> "primis-intro-saving"
        PrimisQuestGuidanceService.Stage.UNAVAILABLE -> key
    }
    return when (step.trialGame()?.let(view.trialStates::get)) {
        CasinoIntroService.State.ARMED -> "$key-armed"
        CasinoIntroService.State.PLAYING -> "trial-playing"
        CasinoIntroService.State.COMPLETE -> "trial-settling"
        CasinoIntroService.State.UNAVAILABLE -> "trial-unavailable"
        else -> key
    }
}

private fun optionalQuestAction(view: OptionalQuestView): OptionalQuestAction? {
    val step = optionalQuestNextStep(view) ?: return null
    val game = step.trialGame()
    if (game != null && view.trialStates[game]?.let { it != CasinoIntroService.State.AVAILABLE } == true) return null
    return when (step) {
        OptionalQuestStep.GUILD_VISITED -> OptionalQuestAction.GUILD
        OptionalQuestStep.PRIMIS_VISITED -> OptionalQuestAction.PRIMIS
        OptionalQuestStep.FARM_VISITED -> OptionalQuestAction.FARM
        OptionalQuestStep.CASINO_SLOTS_COMPLETE -> OptionalQuestAction.TRIAL_SLOTS
        OptionalQuestStep.CASINO_BLACKJACK_COMPLETE -> OptionalQuestAction.TRIAL_BLACKJACK
        OptionalQuestStep.CASINO_PLINKO_COMPLETE -> OptionalQuestAction.TRIAL_PLINKO
        else -> null
    }
}

/** Permanent NPC destinations remain useful without accepting, or after finishing, the side quest. */
private fun availableActions(view: OptionalQuestView): List<OptionalQuestAction> = buildList {
    optionalQuestAction(view)?.let(::add)
    when (view.kind) {
        OptionalQuestKind.ADVENTURE -> {
            add(OptionalQuestAction.GUILD)
            if (view.completed || view.accepted && OptionalQuestStep.GUILD_VISITED in view.steps) add(OptionalQuestAction.PRIMIS)
        }
        OptionalQuestKind.FARM -> add(OptionalQuestAction.FARM)
        OptionalQuestKind.CASINO -> add(OptionalQuestAction.CASINO)
    }
}.distinct()
