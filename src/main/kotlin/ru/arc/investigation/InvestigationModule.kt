package ru.arc.investigation

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.contracts.NpcContractsGui
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.security.SecureRandom
import java.util.Locale
import kotlin.random.Random

object InvestigationModule : PluginModule {
    override val name = "Investigations"
    override val priority = 83

    internal const val COOLDOWN_BYPASS_PERMISSION = "arc.investigations.cooldown.bypass"

    @Volatile private var config: InvestigationConfig? = null
    @Volatile private var catalog: InvestigationStoryCatalog? = null
    private var journal: InvestigationJournal? = null
    private var service: InvestigationService? = null
    private val tasks = LifecycleTaskScope()

    val available: Boolean get() = config?.enabled == true && service != null

    override fun init() {
        Bukkit.getPluginManager().registerEvents(InvestigationCaseFile, ARC.instance)
        val loadedCatalog = InvestigationStoryCatalog.load(ARC.instance.dataPath)
        start(InvestigationConfig.load(ARC.instance.dataPath, loadedCatalog.witnessKeys), loadedCatalog)
    }

    override fun reload() {
        val loadedCatalog =
            runCatching { InvestigationStoryCatalog.load(ARC.instance.dataPath) }
                .onFailure { warn("Investigations reload rejected; current catalog remains active: {}", it.message ?: it::class.java.simpleName) }
                .getOrNull() ?: return
        val unresolvedWitnessKeys =
            journal
                ?.records()
                .orEmpty()
                .filterNot { it.status.resolved }
                .flatMap { it.case.witnesses() }
                .map(InvestigationWitness::commandValue)
                .toSet()
        val loadedConfig =
            runCatching { InvestigationConfig.load(ARC.instance.dataPath, loadedCatalog.witnessKeys + unresolvedWitnessKeys) }
                .onFailure { warn("Investigations reload rejected; current policy remains active: {}", it.message ?: it::class.java.simpleName) }
                .getOrNull() ?: return
        shutdownRuntime()
        start(loadedConfig, loadedCatalog)
    }

    override fun shutdown() {
        shutdownRuntime()
        tasks.close()
        config = null
        catalog = null
    }

    fun open(player: Player) {
        val currentConfig = config
        val currentService = service
        if (currentConfig == null || currentService == null || !currentConfig.enabled) {
            player.sendActionBar(TextUtil.mm("<red>Бюро расследований сейчас закрыто."))
            return
        }
        if (!near(player, currentConfig.point("foma"))) {
            player.sendActionBar(TextUtil.mm("<yellow>Обратитесь к Фоме в бюро расследований."))
            return
        }
        val current = currentService.current(player.uniqueId)
        if (current?.status == InvestigationStatus.MANUAL_REVIEW) {
            InvestigationTargetGlow.clear(player)
            InvestigationCaseFile.remove(player)
            player.sendMessage(TextUtil.mm("<red>Фома:</red> <gray>Это дело заморожено для ручной сверки. Повторного списания не будет."))
            return
        }
        if (current?.status == InvestigationStatus.ACTIVE) {
            InvestigationTargetGlow.refresh(player, current, currentConfig)
            if (!InvestigationCaseFile.issue(player, current)) {
                player.sendActionBar(TextUtil.mm("<yellow>Освободите один слот: Фома не смог вернуть предмет «Дело»."))
            }
            if (current.clueCount() >= InvestigationService.MIN_CLUES) {
                InvestigationGui.openVerdicts(player, current)
            } else {
                InvestigationGui.openCase(player, current)
            }
        } else {
            InvestigationTargetGlow.clear(player)
            InvestigationGui.openHub(player, currentService.latest(player.uniqueId))
        }
    }

    fun startCase(player: Player) {
        val currentConfig = config ?: return unavailable(player)
        val currentService = service ?: return unavailable(player)
        if (!near(player, currentConfig.point("foma"))) {
            player.sendActionBar(TextUtil.mm("<yellow>Фома уже убрал ведомость. Подойдите к нему снова."))
            return
        }
        if (!InvestigationCaseFile.canIssue(player)) {
            player.sendActionBar(TextUtil.mm("<yellow>Освободите один слот инвентаря для предмета «Дело». Деньги не списаны."))
            return
        }
        when (
            val result = currentService.start(
                player.uniqueId,
                bypassCooldown = player.hasPermission(COOLDOWN_BYPASS_PERMISSION),
            )
        ) {
            is InvestigationStartResult.Started -> {
                player.closeInventory()
                InvestigationCaseFile.issue(player, result.record)
                InvestigationTargetGlow.refresh(player, result.record, currentConfig)
                val firstWitness = result.record.case.witnesses().first()
                player.sendMessage(
                    TextUtil.mm(
                        "<gold>Фома:</gold> <gray>Оплата принята. Вот ваше <gold>дело ${result.record.case.caseNumber}</gold>: " +
                            "<white>${result.record.case.displayTitle()}</white>.",
                    ),
                )
                player.sendMessage(
                    TextUtil.mm(
                        "<yellow>С чего начать:</yellow> <gray>откройте инвентарь, прочитайте предмет <gold>«Дело»</gold>, " +
                            "затем найдите <white>${firstWitness.displayName}</white> — ${firstWitness.locationHint}. " +
                            "ПКМ предметом снова откроет материалы.",
                    ),
                )
            }
            is InvestigationStartResult.AlreadyActive -> {
                InvestigationCaseFile.issue(player, result.record)
                InvestigationTargetGlow.refresh(player, result.record, currentConfig)
                InvestigationGui.openCase(player, result.record)
            }
            is InvestigationStartResult.Cooldown -> player.sendActionBar(TextUtil.mm("<yellow>Новое дело будет доступно через <white>${formatRemaining(result.until)}<yellow>."))
            InvestigationStartResult.InsufficientFunds -> player.sendActionBar(TextUtil.mm("<red>Недостаточно монет для ревизорской пробы."))
            InvestigationStartResult.EconomyUnavailable -> player.sendActionBar(TextUtil.mm("<red>Касса временно не отвечает. Деньги не списаны."))
            InvestigationStartResult.PaymentFailed -> player.sendActionBar(TextUtil.mm("<red>Оплата отклонена. Деньги не списаны."))
            InvestigationStartResult.JournalUnavailable -> player.sendActionBar(TextUtil.mm("<red>Фома не смог зарегистрировать дело. Деньги не списаны."))
            InvestigationStartResult.ManualReview -> player.sendMessage(TextUtil.mm("<red>Фома:</red> <gray>Сверка оплаты неоднозначна. Дело заморожено, повторного списания не будет."))
            InvestigationStartResult.Busy -> player.sendActionBar(TextUtil.mm("<yellow>Подождите: Фома ещё ставит отметку."))
            InvestigationStartResult.Disabled -> unavailable(player)
        }
    }

    fun collect(player: Player, witnessKey: String) {
        val currentConfig = config ?: return unavailable(player)
        val currentService = service ?: return unavailable(player)
        val current = currentService.current(player.uniqueId)
        val witness = current?.case?.witness(witnessKey)
        if (witness == null) {
            val knownWitness = catalog?.witnesses?.get(witnessKey.lowercase(Locale.ROOT))
            if (knownWitness == null) {
                player.sendActionBar(TextUtil.mm("<gray>Этот человек не относится к бюро расследований."))
            } else if (current == null) {
                player.sendMessage(
                    TextUtil.mm(
                        "<gold>${knownWitness.displayName}:</gold> <gray>${ambientReply(knownWitness.key)} " +
                            "Если вам нужны показания, сначала возьмите дело у Фомы.",
                    ),
                )
            } else {
                player.sendMessage(
                    TextUtil.mm(
                        "<gold>${knownWitness.displayName}:</gold> <gray>По делу ${current.case.caseNumber} меня не опрашивали. " +
                            "Ищите тех пятерых, кого Фома отметил в ведомости.",
                    ),
                )
            }
            return
        }
        if (!near(player, currentConfig.point(witness.commandValue))) {
            player.sendActionBar(TextUtil.mm("<yellow>Подойдите ближе к свидетелю."))
            return
        }
        when (val result = currentService.collectClue(player.uniqueId, witness)) {
            is InvestigationClueResult.Evidence -> {
                InvestigationCaseFile.issue(player, result.record)
                InvestigationTargetGlow.refresh(player, result.record, currentConfig)
                if (result.firstRead) {
                    val count = result.record.clueCount()
                    player.sendActionBar(TextUtil.mm("<green>Показание внесено: <white>$count/5<green>."))
                } else {
                    player.sendActionBar(TextUtil.mm("<gray>Это показание уже сохранено в материалах дела."))
                }
                InvestigationGui.openTestimony(player, result.record, witness)
            }
            is InvestigationClueResult.Expired -> timeout(player)
            InvestigationClueResult.NoActiveCase -> player.sendActionBar(TextUtil.mm("<gray>Сначала возьмите дело у Фомы."))
            InvestigationClueResult.Busy -> player.sendActionBar(TextUtil.mm("<yellow>Запись уже обновляется."))
            InvestigationClueResult.UnknownWitness -> player.sendActionBar(TextUtil.mm("<gray>Этот свидетель не относится к делу."))
            InvestigationClueResult.ManualReview -> player.sendActionBar(TextUtil.mm("<red>Дело заморожено для ручной сверки."))
            InvestigationClueResult.PersistenceFailure -> player.sendActionBar(TextUtil.mm("<red>Показание не удалось записать. Оно не засчитано."))
        }
    }

    fun submit(player: Player, verdict: InvestigationVerdict) {
        val currentConfig = config ?: return unavailable(player)
        if (!near(player, currentConfig.point("foma"))) {
            player.closeInventory()
            player.sendActionBar(TextUtil.mm("<yellow>Вердикт принимает только Фома. Вернитесь к нему."))
            return
        }
        val currentService = service ?: return unavailable(player)
        when (val result = currentService.submitVerdict(player.uniqueId, verdict)) {
            is InvestigationVerdictResult.Success -> {
                player.closeInventory()
                InvestigationTargetGlow.clear(player)
                InvestigationCaseFile.remove(player, result.record.transactionId)
                sendResolution(player, result.record, true)
            }
            is InvestigationVerdictResult.Wrong -> {
                player.closeInventory()
                InvestigationTargetGlow.clear(player)
                InvestigationCaseFile.remove(player, result.record.transactionId)
                sendResolution(player, result.record, false)
            }
            is InvestigationVerdictResult.NeedClues -> player.sendActionBar(TextUtil.mm("<yellow>Нужно хотя бы три показания. Сейчас: <white>${result.collected}/5<yellow>."))
            is InvestigationVerdictResult.Expired -> timeout(player)
            InvestigationVerdictResult.NoActiveCase -> player.sendActionBar(TextUtil.mm("<gray>Активного дела нет."))
            InvestigationVerdictResult.Busy -> player.sendActionBar(TextUtil.mm("<yellow>Вердикт уже обрабатывается."))
            InvestigationVerdictResult.EconomyUnavailable -> player.sendActionBar(TextUtil.mm("<red>Касса не отвечает. Вердикт не зафиксирован — попробуйте ещё раз до конца времени."))
            InvestigationVerdictResult.ManualReview -> {
                player.closeInventory()
                InvestigationTargetGlow.clear(player)
                InvestigationCaseFile.remove(player)
                player.sendMessage(TextUtil.mm("<red>Фома:</red> <gray>Вердикт верен, но выплата требует ручной сверки. Повторно её не запускайте."))
            }
            InvestigationVerdictResult.PersistenceFailure -> player.sendActionBar(TextUtil.mm("<red>Вердикт не удалось записать. Попробуйте ещё раз."))
        }
    }

    fun openContracts(player: Player) {
        val currentConfig = config ?: return unavailable(player)
        NpcContractsGui.openList(player, currentConfig.contractGroup)
    }

    fun openCaseFile(player: Player, transactionId: String) {
        val current = service?.current(player.uniqueId)
        if (current?.status == InvestigationStatus.ACTIVE && current.transactionId == transactionId) {
            InvestigationCaseFile.issue(player, current)
            config?.let { InvestigationTargetGlow.refresh(player, current, it) }
            InvestigationGui.openCase(player, current)
            return
        }
        InvestigationCaseFile.remove(player, transactionId)
        player.sendActionBar(TextUtil.mm("<gray>Это дело уже закрыто. Материалы убраны."))
    }

    /** Rechecks after cross-server inventory restoration has had time to finish. */
    internal fun scheduleCaseFileCleanup(player: Player) {
        tasks.runLater(60L) {
            if (player.isOnline) InvestigationCaseFile.cleanupExpired(player)
        }
    }

    internal fun configOrNull(): InvestigationConfig? = config

    internal fun witnessKeys(): List<String> = catalog?.witnessKeys?.sorted().orEmpty()

    internal fun money(minor: Long): String =
        java.math.BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()

    private fun start(
        loaded: InvestigationConfig,
        loadedCatalog: InvestigationStoryCatalog,
    ) {
        config = loaded
        catalog = loadedCatalog
        val epoch = tasks.restart()
        tasks.runTimer(epoch, 20L, 20L) {
            Bukkit.getOnlinePlayers().forEach { player -> InvestigationCaseFile.cleanupExpired(player) }
        }
        if (!loaded.enabled) {
            info("Investigations module disabled by configuration")
            return
        }
        val loadedJournal = FileInvestigationJournal(ARC.instance.dataPath)
        val loadedService =
            InvestigationService(
                journal = loadedJournal,
                wallet = RedisEconomyInvestigationWallet(),
                enabled = { config?.enabled == true },
                feeMinor = { requireNotNull(config).feeMinor },
                rewardMinor = { requireNotNull(config).rewardMinor },
                duration = { requireNotNull(config).duration },
                cooldown = { requireNotNull(config).cooldown },
                caseGenerator = InvestigationCaseGenerator(loadedCatalog),
                runSync = { action -> tasks.runSync(epoch, action) },
                random = Random(SecureRandom().nextLong()),
            )
        journal = loadedJournal
        service = loadedService
        loadedService.recover { record ->
            warn("Investigation {} requires manual review at stage {}", record.transactionId, record.status.name.lowercase(Locale.ROOT))
        }
        tasks.runTimer(epoch, 20L, 20L, loadedService::expireAll)
        tasks.runTimer(epoch, 20L, 40L) {
            Bukkit.getOnlinePlayers().forEach { player ->
                InvestigationTargetGlow.refresh(player, loadedService.current(player.uniqueId), loaded)
            }
        }
        info("Investigations module initialized with {} cases and {} witnesses", loadedCatalog.storyCount, loadedCatalog.witnessKeys.size)
    }

    private fun shutdownRuntime() {
        InvestigationTargetGlow.clearAll()
        tasks.cancelAll()
        service = null
        journal = null
    }

    private fun near(player: Player, point: InvestigationNpcPoint): Boolean {
        if (player.world.name != config?.world) return false
        val location = player.location
        val dx = location.x - point.x
        val dy = location.y - point.y
        val dz = location.z - point.z
        return dx * dx + dy * dy + dz * dz <= point.radius * point.radius
    }

    private fun timeout(player: Player) {
        player.closeInventory()
        InvestigationTargetGlow.clear(player)
        InvestigationCaseFile.remove(player)
        player.sendMessage(TextUtil.mm("<red><bold>Время вышло.</bold> <gray>Фома закрыл дело без выплаты."))
    }

    private fun unavailable(player: Player) {
        player.sendActionBar(TextUtil.mm("<red>Бюро расследований сейчас недоступно."))
    }

    private fun formatRemaining(until: Long): String {
        val seconds = ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = seconds % 3_600L / 60L
        return if (hours > 0L) "${hours}ч ${minutes}м" else "${minutes.coerceAtLeast(1L)}м"
    }

    private fun ambientReply(witnessKey: String): String =
        when (witnessKey) {
            "stavr" -> "Колокол помнит цену лучше покупателя, но хуже архива."
            "prokhor" -> "У любой красивой версии должен быть номер страницы."
            "gordey" -> "Проход свободен. Чужие пломбы и моё терпение — не трогать."
            "agata" -> "Подлинный штрих ещё не делает правдивым весь документ."
            "tikhon" -> "Если сумма выглядит круглой, проверьте, кто обрезал углы."
            "mikula" -> "Товар можно узнать по узору, но цену — только по истории."
            "vlada" -> "Случайность случается один раз. Второй раз она уже требует акта."
            "ratsha" -> "Весы отвечают на поставленный вопрос, а не на тот, что вы забыли."
            "zhdan" -> "Груз выдаёт себя не весом, а тем, как режет плечо верёвка."
            "elisey" -> "Печать подтверждает оттиск. Смысл листа придётся доказать отдельно."
            "marfa" -> "У воска хорошая память, особенно когда его пытались греть второй раз."
            "varvara" -> "Короткий путь бывает самым долгим, если кто-то переставил отметки."
            "domna" -> "На торгах слушайте тех, кто молчит после собственной ставки."
            else -> "Сегодня в зале достаточно странных сделок и без новых показаний."
        }

    private fun sendResolution(
        player: Player,
        record: InvestigationJournalRecord,
        success: Boolean,
    ) {
        val conclusion = record.case.conclusion(record.case.verdict)
        val heading = if (success) "<green><bold>Вердикт принят.</bold>" else "<red><bold>Вердикт неверен.</bold>"
        player.sendMessage(TextUtil.mm("$heading <gold>${conclusion.title}</gold>"))
        conclusion.explanation.forEach { player.sendMessage(TextUtil.mm("<gray>• $it")) }
        if (success) {
            player.sendMessage(
                TextUtil.mm(
                    "<gray>Бюро выплатило <gold>${money(record.rewardMinor)} <white>💰</white><gray> за точную реконструкцию.",
                ),
            )
        }
    }
}
