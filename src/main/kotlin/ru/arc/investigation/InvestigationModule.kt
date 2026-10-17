package ru.arc.investigation

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.integrations.contracts.contractsService
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import ru.arc.core.BoundedAsyncExecutor

internal data class InvestigationRuntimeConfig(
    val config: InvestigationConfig,
    val catalog: InvestigationStoryCatalog?,
)

internal fun loadInvestigationRuntimeConfig(
    dataPath: Path,
    additionalWitnessKeys: Set<String> = emptySet(),
    catalogLoader: () -> InvestigationStoryCatalog = { InvestigationStoryCatalog.load(dataPath) },
): InvestigationRuntimeConfig {
    val config = InvestigationConfig.read(dataPath)
    if (!config.enabled) return InvestigationRuntimeConfig(config, null)

    val catalog = catalogLoader()
    return InvestigationRuntimeConfig(
        config = config.validated(catalog.witnessKeys + additionalWitnessKeys),
        catalog = catalog,
    )
}

object InvestigationModule : PluginModule {
    override val name = "Investigations"
    override val priority = 83

    internal const val COOLDOWN_BYPASS_PERMISSION = "arc.investigations.cooldown.bypass"

    @Volatile private var config: InvestigationConfig? = null
    @Volatile private var catalog: InvestigationStoryCatalog? = null
    @Volatile private var service: InvestigationAsyncRuntime? = null
    private var tasks: LifecycleTaskScope? = null
    private var generation = 0L
    private var stopped = true
    private var predecessor = CompletableFuture.completedFuture(Unit)
    private var ready = CompletableFuture<Void>()

    val available: Boolean get() = config?.enabled == true && service?.available == true
    fun readiness(): CompletableFuture<Void> = ready.copy()
    fun drained(): CompletableFuture<Unit> = predecessor.copy()

    override fun init() {
        stopped = false
        org.bukkit.event.HandlerList.unregisterAll(InvestigationCaseFile)
        Bukkit.getPluginManager().registerEvents(InvestigationCaseFile, ARC.instance)
        InvestigationCaseFile.registerPreciseEntityHandlerIfRequired(ARC.instance)
        begin(null)
    }

    override fun reload() {
        val current = service ?: return
        val expected = generation
        current.prepareReload().whenComplete { loaded, failure ->
            tasks?.callSync {
                if (stopped || generation != expected || service !== current) return@callSync
                if (failure != null) {
                    current.rejectReload()
                    warn("Investigations reload rejected; current runtime remains active: {}", failure.message ?: failure::class.java.simpleName)
                } else begin(requireNotNull(loaded))
            }
        }
    }

    override fun shutdown() {
        if (stopped) return
        stopped = true
        generation++
        ready.completeExceptionally(CancellationException("Investigations shut down"))
        shutdownRuntime()
        config = null
        catalog = null
    }

    fun open(player: Player) {
        val currentConfig = config
        val currentService = service
        if (currentConfig == null || currentService?.available != true || !currentConfig.enabled) {
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
        val currentService = service?.takeIf { it.available } ?: return unavailable(player)
        if (!near(player, currentConfig.point("foma"))) {
            player.sendActionBar(TextUtil.mm("<yellow>Фома уже убрал ведомость. Подойдите к нему снова."))
            return
        }
        if (!InvestigationCaseFile.canIssue(player)) {
            player.sendActionBar(TextUtil.mm("<yellow>Освободите один слот инвентаря для предмета «Дело». Деньги не списаны."))
            return
        }
        observe(currentService.startCase(player.uniqueId,
            bypassCooldown = player.hasPermission(COOLDOWN_BYPASS_PERMISSION),
            isCurrentPlayer = { player.isOnline && Bukkit.getPlayer(player.uniqueId) === player }) resume@ { result ->
            if (!player.isOnline || Bukkit.getPlayer(player.uniqueId) !== player) return@resume
            when (result) {
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
        })
    }

    fun collect(player: Player, witnessKey: String) {
        collect(player, witnessKey, requireProximity = true)
    }

    /** A Citizens click is already authoritative proof that the player reached this NPC. */
    fun collectFromNpcClick(player: Player, witnessKey: String) {
        collect(player, witnessKey, requireProximity = false)
    }

    private fun collect(
        player: Player,
        witnessKey: String,
        requireProximity: Boolean,
    ) {
        val currentConfig = config ?: return unavailable(player)
        val currentService = service?.takeIf { it.available } ?: return unavailable(player)
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
        if (requireProximity && !near(player, currentConfig.point(witness.commandValue))) {
            player.sendActionBar(TextUtil.mm("<yellow>Подойдите ближе к свидетелю."))
            return
        }
        observe(currentService.collect(player.uniqueId, witness) resume@ { result ->
            if (!player.isOnline || Bukkit.getPlayer(player.uniqueId) !== player) return@resume
            when (result) {
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
        })
    }

    fun submit(player: Player, verdict: InvestigationVerdict) {
        val currentConfig = config ?: return unavailable(player)
        if (!near(player, currentConfig.point("foma"))) {
            player.closeInventory()
            player.sendActionBar(TextUtil.mm("<yellow>Вердикт принимает только Фома. Вернитесь к нему."))
            return
        }
        val currentService = service?.takeIf { it.available } ?: return unavailable(player)
        observe(currentService.submit(player.uniqueId, verdict) resume@ { result ->
            if (!player.isOnline || Bukkit.getPlayer(player.uniqueId) !== player) return@resume
            when (result) {
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
        })
    }

    fun openContracts(player: Player) {
        val currentConfig = config ?: return unavailable(player)
        contractsService()?.openList(player, currentConfig.contractGroup)
    }

    fun openCaseFile(player: Player, transactionId: String) {
        if (service?.available != true) return unavailable(player)
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
        tasks?.runLater(60L) {
            if (player.isOnline) InvestigationCaseFile.cleanupExpired(player)
        }
    }

    internal fun configOrNull(): InvestigationConfig? = config

    internal fun witnessKeys(): List<String> = catalog?.witnessKeys?.sorted().orEmpty()

    internal fun money(minor: Long): String =
        java.math.BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()

    private fun begin(prepared: InvestigationRuntimeConfig?) {
        ready.completeExceptionally(CancellationException("Investigation generation superseded"))
        shutdownRuntime()
        val expected = ++generation
        val scope = LifecycleTaskScope()
        tasks = scope
        val completion = CompletableFuture<Void>()
        ready = completion
        val root = ARC.instance.dataPath
        predecessor.whenComplete { _, failure ->
            scope.callSync {
                if (stopped || generation != expected || tasks !== scope) throw CancellationException("Investigation bootstrap superseded")
                if (failure != null) throw failure
                val worker = BoundedAsyncExecutor("ARC-investigations", 1, 256)
                lateinit var runtime: InvestigationAsyncRuntime
                runtime = InvestigationAsyncRuntime(scope, worker,
                    load = { keys -> loadInvestigationRuntimeConfig(root, keys) },
                    journalFactory = { FileInvestigationJournal(root) },
                    wallet = RedisEconomyInvestigationWallet(),
                    currentGeneration = { !stopped && generation == expected && service === runtime },
                    canWithdraw = { id ->
                        val player = Bukkit.getPlayer(id)
                        val policy = config
                        player != null && player.isOnline && policy != null &&
                            near(player, policy.point("foma"), policy) && InvestigationCaseFile.canIssue(player)
                    },
                    onReady = { loaded ->
                        config = loaded.config
                        catalog = loaded.catalog
                        if (loaded.config.enabled) startTimers(runtime, loaded.config)
                        else info("Investigations disabled by configuration; story catalog load skipped")
                        loaded.catalog?.let { info("Investigations module initialized with {} cases and {} witnesses", it.storyCount, it.witnessKeys.size) }
                    },
                    onFailure = { id, _ -> Bukkit.getPlayer(id)?.takeIf(Player::isOnline)?.let(::unavailable) },
                    onManualReview = { record -> warn("Investigation {} requires manual review at stage {}",
                        record.transactionId, record.status.name.lowercase(Locale.ROOT)) },
                    onBackgroundFailure = { failure -> warn("Investigation expiration did not complete: {}", failure.message ?: failure::class.java.simpleName) })
                service = runtime
                runtime.readiness().whenComplete { _, preparationFailure ->
                    if (preparationFailure == null) completion.complete(null) else completion.completeExceptionally(preparationFailure)
                }
                runtime.start(prepared)
            }.whenComplete { _, attachFailure ->
                if (attachFailure != null) completion.completeExceptionally(attachFailure)
            }
        }
        completion.whenComplete { _, failure ->
            if (failure != null && !stopped && generation == expected) warn("Investigations preparation failed; admission is closed: {}", failure.message ?: failure::class.java.simpleName)
        }
    }

    private fun startTimers(runtime: InvestigationAsyncRuntime, policy: InvestigationConfig) {
        var round = 0
        var refreshing: CompletableFuture<Unit>? = null
        runtime.tasks.runTimer(20L, 20L) {
            runtime.requestExpiry()
            if (refreshing?.isDone == false) return@runTimer
            val glow = round++ % 2 == 0
            val players = Bukkit.getOnlinePlayers().toList().iterator()
            refreshing = ARC.instance.nativeWork.submit({ service === runtime && !stopped }) {
                if (players.hasNext()) {
                    val player = players.next()
                    if (player.isOnline) {
                        InvestigationCaseFile.cleanupExpired(player)
                        if (glow) InvestigationTargetGlow.refresh(player, runtime.current(player.uniqueId), policy)
                    }
                }
                !players.hasNext()
            }
            refreshing?.whenComplete { _, failure ->
                if (failure != null && failure !is CancellationException && service === runtime && !stopped) {
                    warn("Investigation native refresh did not complete: {}", failure.message ?: failure::class.java.simpleName)
                }
            }
        }
    }

    private fun shutdownRuntime() {
        val glowDrain = runCatching { InvestigationTargetGlow.clearAllAsync() }
            .getOrElse { CompletableFuture.failedFuture<Void>(it) }
        val old = service
        service = null
        val oldTasks = tasks
        tasks = null
        val closeFailure = runCatching {
            if (old != null) old.close() else oldTasks?.close()
        }.exceptionOrNull()
        // A superseded bootstrap has no worker yet; it still owns the preceding generation's drain.
        val workerDrain = old?.drained() ?: predecessor
        predecessor = CompletableFuture.allOf(workerDrain, glowDrain,
            closeFailure?.let { CompletableFuture.failedFuture<Void>(it) } ?: CompletableFuture.completedFuture(null))
            .thenApply { Unit }
    }

    private fun observe(operation: CompletableFuture<*>) {
        operation.whenComplete { _, failure ->
            if (failure != null && !stopped) warn("Investigation operation did not complete: {}", failure.message ?: failure::class.java.simpleName)
        }
    }

    private fun near(player: Player, point: InvestigationNpcPoint, policy: InvestigationConfig? = config): Boolean {
        if (player.world.name != policy?.world) return false
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
        conclusion.explanation.forEach { player.sendMessage(TextUtil.mm("<white>•</white> <gray>$it")) }
        if (success) {
            player.sendMessage(
                TextUtil.mm(
                    "<gray>Бюро выплатило <gold>${money(record.rewardMinor)} <white>💰</white><gray> за точную реконструкцию.",
                ),
            )
        }
    }
}
