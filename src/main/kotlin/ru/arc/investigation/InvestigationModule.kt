package ru.arc.investigation

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

    @Volatile private var config: InvestigationConfig? = null
    private var journal: InvestigationJournal? = null
    private var service: InvestigationService? = null
    private val tasks = LifecycleTaskScope()

    val available: Boolean get() = config?.enabled == true && service != null

    override fun init() {
        start(InvestigationConfig.load(ARC.instance.dataPath))
    }

    override fun reload() {
        shutdownRuntime()
        start(InvestigationConfig.load(ARC.instance.dataPath))
    }

    override fun shutdown() {
        shutdownRuntime()
        tasks.close()
        config = null
    }

    fun open(player: Player) {
        val currentConfig = config
        val currentService = service
        if (currentConfig == null || currentService == null || !currentConfig.enabled) {
            player.sendActionBar(TextUtil.mm("<red>Палата сделок сейчас закрыта."))
            return
        }
        if (!near(player, currentConfig.point("foma"))) {
            player.sendActionBar(TextUtil.mm("<yellow>Обратитесь к Фоме у стойки палаты сделок."))
            return
        }
        val current = currentService.current(player.uniqueId)
        if (current?.status == InvestigationStatus.MANUAL_REVIEW) {
            player.sendMessage(TextUtil.mm("<red>Фома:</red> <gray>Это дело заморожено для ручной сверки. Повторного списания не будет."))
            return
        }
        if (current?.status == InvestigationStatus.ACTIVE) {
            InvestigationGui.openCase(player, current)
        } else {
            InvestigationGui.openHub(player, currentService.latest(player.uniqueId))
        }
    }

    fun openConfirmation(player: Player) {
        val currentConfig = config ?: return unavailable(player)
        val currentService = service ?: return unavailable(player)
        if (!near(player, currentConfig.point("foma"))) {
            player.sendActionBar(TextUtil.mm("<yellow>Подтвердить оплату можно только у Фомы."))
            return
        }
        InvestigationGui.openConfirmation(player, currentService.balanceMinor(player.uniqueId), currentConfig.feeMinor, currentConfig.rewardMinor)
    }

    fun startCase(player: Player) {
        val currentConfig = config ?: return unavailable(player)
        val currentService = service ?: return unavailable(player)
        if (!near(player, currentConfig.point("foma"))) {
            player.sendActionBar(TextUtil.mm("<yellow>Фома уже убрал ведомость. Подойдите к нему снова."))
            return
        }
        when (val result = currentService.start(player.uniqueId)) {
            is InvestigationStartResult.Started -> {
                player.sendMessage(TextUtil.mm("<gold>Фома:</gold> <gray>Дело оплачено. У вас <white>${currentConfig.duration.seconds} секунд<gray>: опросите хотя бы двух свидетелей."))
                InvestigationGui.openCase(player, result.record)
            }
            is InvestigationStartResult.AlreadyActive -> InvestigationGui.openCase(player, result.record)
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

    fun collect(player: Player, witness: InvestigationWitness) {
        val currentConfig = config ?: return unavailable(player)
        val currentService = service ?: return unavailable(player)
        if (!near(player, currentConfig.point(witness.commandValue))) {
            player.sendActionBar(TextUtil.mm("<yellow>Подойдите ближе к свидетелю."))
            return
        }
        when (val result = currentService.collectClue(player.uniqueId, witness)) {
            is InvestigationClueResult.Evidence -> {
                result.lines.forEach { player.sendMessage(TextUtil.mm(it)) }
                if (result.firstRead) {
                    val count = result.record.clueCount()
                    player.sendActionBar(TextUtil.mm("<green>Показание внесено: <white>$count/3<green>."))
                }
                InvestigationGui.openCase(player, result.record)
            }
            is InvestigationClueResult.Expired -> timeout(player)
            InvestigationClueResult.NoActiveCase -> player.sendActionBar(TextUtil.mm("<gray>Сначала возьмите дело у Фомы."))
            InvestigationClueResult.Busy -> player.sendActionBar(TextUtil.mm("<yellow>Запись уже обновляется."))
            InvestigationClueResult.ManualReview -> player.sendActionBar(TextUtil.mm("<red>Дело заморожено для ручной сверки."))
            InvestigationClueResult.PersistenceFailure -> player.sendActionBar(TextUtil.mm("<red>Показание не удалось записать. Оно не засчитано."))
        }
    }

    fun submit(player: Player, verdict: InvestigationVerdict) {
        val currentService = service ?: return unavailable(player)
        when (val result = currentService.submitVerdict(player.uniqueId, verdict)) {
            is InvestigationVerdictResult.Success -> {
                player.closeInventory()
                player.sendMessage(TextUtil.mm("<green><bold>Вердикт принят.</bold> <gray>Палата выплатила <gold>${money(result.record.rewardMinor)} <white>💰</white><gray>."))
            }
            is InvestigationVerdictResult.Wrong -> {
                player.closeInventory()
                player.sendMessage(TextUtil.mm("<red><bold>Вердикт неверен.</bold> <gray>Правильная зацепка: <white>${verdictHint(result.record.case.verdict)}<gray>."))
            }
            is InvestigationVerdictResult.NeedClues -> player.sendActionBar(TextUtil.mm("<yellow>Нужно хотя бы два показания. Сейчас: <white>${result.collected}/3<yellow>."))
            is InvestigationVerdictResult.Expired -> timeout(player)
            InvestigationVerdictResult.NoActiveCase -> player.sendActionBar(TextUtil.mm("<gray>Активного дела нет."))
            InvestigationVerdictResult.Busy -> player.sendActionBar(TextUtil.mm("<yellow>Вердикт уже обрабатывается."))
            InvestigationVerdictResult.EconomyUnavailable -> player.sendActionBar(TextUtil.mm("<red>Касса не отвечает. Вердикт не зафиксирован — попробуйте ещё раз до конца времени."))
            InvestigationVerdictResult.ManualReview -> {
                player.closeInventory()
                player.sendMessage(TextUtil.mm("<red>Фома:</red> <gray>Вердикт верен, но выплата требует ручной сверки. Повторно её не запускайте."))
            }
            InvestigationVerdictResult.PersistenceFailure -> player.sendActionBar(TextUtil.mm("<red>Вердикт не удалось записать. Попробуйте ещё раз."))
        }
    }

    fun openContracts(player: Player) {
        val currentConfig = config ?: return unavailable(player)
        NpcContractsGui.openList(player, currentConfig.contractGroup)
    }

    internal fun configOrNull(): InvestigationConfig? = config

    internal fun money(minor: Long): String =
        java.math.BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()

    private fun start(loaded: InvestigationConfig) {
        config = loaded
        if (!loaded.enabled) {
            info("Investigations module disabled by configuration")
            return
        }
        val loadedJournal = FileInvestigationJournal(ARC.instance.dataPath)
        val epoch = tasks.restart()
        val loadedService =
            InvestigationService(
                journal = loadedJournal,
                wallet = RedisEconomyInvestigationWallet(),
                enabled = { config?.enabled == true },
                feeMinor = { requireNotNull(config).feeMinor },
                rewardMinor = { requireNotNull(config).rewardMinor },
                duration = { requireNotNull(config).duration },
                cooldown = { requireNotNull(config).cooldown },
                runSync = { action -> tasks.runSync(epoch, action) },
                random = Random(SecureRandom().nextLong()),
            )
        journal = loadedJournal
        service = loadedService
        loadedService.recover { record ->
            warn("Investigation {} requires manual review at stage {}", record.transactionId, record.status.name.lowercase(Locale.ROOT))
        }
        tasks.runTimer(epoch, 20L, 20L, loadedService::expireAll)
        info("Investigations module initialized for NPCs 367, 372, 373 and 374")
    }

    private fun shutdownRuntime() {
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
        player.sendMessage(TextUtil.mm("<red><bold>Время вышло.</bold> <gray>Фома закрыл ведомость без выплаты."))
    }

    private fun unavailable(player: Player) {
        player.sendActionBar(TextUtil.mm("<red>Палата сделок сейчас недоступна."))
    }

    private fun formatRemaining(until: Long): String {
        val seconds = ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = seconds % 3_600L / 60L
        return if (hours > 0L) "${hours}ч ${minutes}м" else "${minutes.coerceAtLeast(1L)}м"
    }

    private fun verdictHint(verdict: InvestigationVerdict): String =
        when (verdict) {
            InvestigationVerdict.AMOUNT_MISMATCH -> "суммы в документах не сходились"
            InvestigationVerdict.FORGED_SEAL -> "один признак печати не совпал с реестром"
            InvestigationVerdict.CLEAN -> "подозрительная деталь была уловкой, а документы совпали"
        }
}
