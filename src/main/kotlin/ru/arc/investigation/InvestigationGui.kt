package ru.arc.investigation

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.gui
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import java.time.Instant

object InvestigationGui {
    private val guiConfig: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/investigations.yml")
    }

    fun openHub(player: Player, latest: InvestigationJournalRecord?) {
        val policy = InvestigationModule.configOrNull() ?: return
        GuiUtils.constructAndShowAsync({ buildHub(player, latest, policy) }, player)
    }

    fun openCase(player: Player, record: InvestigationJournalRecord) {
        GuiUtils.constructAndShowAsync({ buildCase(player, record) }, player)
    }

    internal fun buildHub(
        player: Player,
        latest: InvestigationJournalRecord?,
        policy: InvestigationConfig,
    ): ChestGui =
        gui(guiConfig.string("hub.title", "<dark_gray>Бюро расследований"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 0) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>Как проходит расследование")
                    lore(
                        listOf(
                            "<gray>Вы получите одно торговое происшествие",
                            "<gray>с вопросом, который нужно разрешить.",
                            "",
                            "<white>1. <gray>Опросите сотрудников лично.",
                            "<white>2. <gray>Восстановите пять событий по времени.",
                            "<white>3. <gray>Сверьте показания и найдите уловку.",
                            "<white>4. <gray>Выберите точную реконструкцию.",
                            "",
                            "<yellow>После трёх показаний можно рискнуть.",
                            "<gray>Пять показаний открывают полную картину.",
                        ),
                    )
                }
                item(2, 1) {
                    style(InvestigationGuiRole.START)
                    display("<green><bold>Взять новое дело")
                    lore(
                        listOf(
                            "<gray>Взнос: <gold>${InvestigationModule.money(policy.feeMinor)} <white>💰",
                            "<gray>Награда: <gold>${InvestigationModule.money(policy.rewardMinor)} <white>💰",
                            "<gray>На расследование: <white>${policy.duration.seconds} секунд",
                            "<gray>Повтор: <white>${formatCooldown(policy.cooldown.toMinutes())}",
                            "",
                            cooldownLine(latest),
                            "<green>Нажмите, чтобы оплатить и начать.",
                        ),
                    )
                    onClick { InvestigationModule.startCase(player) }
                }
                item(6, 1) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua><bold>Заказы бюро")
                    lore(
                        listOf(
                            "<gray>Поставляйте бюро книги, чернила",
                            "<gray>и золото по отдельным контрактам.",
                            "",
                            "<aqua>Открыть книгу заказов.",
                        ),
                    )
                    onClick { InvestigationModule.openContracts(player) }
                }
            }
        }

    internal fun buildCase(player: Player, record: InvestigationJournalRecord): ChestGui =
        gui(guiConfig.string("case.title", "<dark_gray>Материалы расследования"), 5, player, guiConfig) {
            background()
            staticPane(width = 9, height = 5) {
                item(4, 0) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>${record.case.displayTitle()}")
                    lore(wrapInvestigationLore(record.case.dossier()))
                }
                item(8, 0) {
                    style(InvestigationGuiRole.STATUS)
                    display("<yellow><bold>Ход расследования")
                    lore(
                        listOf(
                            timeLine(record),
                            "<gray>Показания: <white>${record.clueCount()}/5",
                            "<gray>Сверки: <white>${record.case.crossChecks(record.cluesMask).size}/${record.case.totalCrossChecks()}",
                            "",
                            if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                                "<green>Можно вынести вердикт."
                            } else {
                                "<dark_gray>Нужно ещё ${InvestigationService.MIN_CLUES - record.clueCount()} показания."
                            },
                        ),
                    )
                }

                witnessItem(0, player, record, InvestigationWitness.STAVR, InvestigationGuiRole.STAVR, "стол у входа")
                witnessItem(2, player, record, InvestigationWitness.PROKHOR, InvestigationGuiRole.PROKHOR, "стол у входа")
                witnessItem(4, player, record, InvestigationWitness.GORDEY, InvestigationGuiRole.GORDEY, "патруль первого этажа")
                witnessItem(6, player, record, InvestigationWitness.AGATA, InvestigationGuiRole.AGATA, "второй этаж")
                witnessItem(8, player, record, InvestigationWitness.TIKHON, InvestigationGuiRole.TIKHON, "второй этаж")

                item(2, 2) {
                    style(InvestigationGuiRole.TIMELINE)
                    display("<aqua><bold>Хронология")
                    lore(wrapInvestigationLore(listOf("<gray>События в установленном порядке:", "") + record.case.timeline(record.cluesMask)))
                }
                item(6, 2) {
                    style(InvestigationGuiRole.CROSS_CHECK)
                    display("<light_purple><bold>Сверка показаний")
                    val checks = record.case.crossChecks(record.cluesMask)
                    lore(wrapInvestigationLore(
                        if (checks.isEmpty()) {
                            listOf(
                                "<gray>Пока ни одна пара показаний",
                                "<gray>не раскрывает общего противоречия.",
                                "",
                                "<dark_gray>Опросите ещё одного сотрудника.",
                            )
                        } else {
                            listOf("<gray>Установленные связи:", "") + checks
                        },
                    ))
                }

                InvestigationVerdict.entries.forEachIndexed { index, verdict ->
                    verdictItem(index * 2, player, record, verdict, VERDICT_ROLES[index])
                }

                item(4, 4) {
                    style(InvestigationGuiRole.RULES)
                    display("<yellow><bold>Цена ошибки")
                    lore(
                        listOf(
                            "<gray>Выберите не тип нарушения, а версию,",
                            "<gray>которая объясняет всю цепочку событий.",
                            "",
                            "<red>Ошибочная версия сразу закрывает дело.",
                            "<dark_gray>Закрыть меню можно клавишей Esc.",
                        ),
                    )
                }
                item(8, 4) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua>Заказы бюро")
                    lore(listOf("<gray>Открыть ресурсные контракты.", "<dark_gray>Таймер продолжит идти."))
                    onClick { InvestigationModule.openContracts(player) }
                }
            }
        }

    private fun ru.arc.gui.StaticPaneBuilder.witnessItem(
        x: Int,
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
        role: InvestigationGuiRole,
        location: String,
    ) {
        item(x, 1) {
            style(role)
            display(if (record.hasClue(witness)) "<green><bold>${witness.displayName}" else "<yellow><bold>${witness.displayName}")
            lore(
                if (record.hasClue(witness)) {
                    wrapInvestigationLore(record.case.testimony(witness)) + listOf("", "<green>Показание записано.")
                } else {
                    listOf(
                        "<gray>Где искать: <white>$location<gray>.",
                        "<gray>Этот человек видел один этап истории.",
                        "",
                        "<dark_gray>Опрос проводится только лично.",
                    )
                },
            )
            onClick { player.sendActionBar(TextUtil.mm("<yellow>Найдите свидетеля лично: <white>$location<yellow>.")) }
        }
    }

    private fun ru.arc.gui.StaticPaneBuilder.verdictItem(
        x: Int,
        player: Player,
        record: InvestigationJournalRecord,
        verdict: InvestigationVerdict,
        role: InvestigationGuiRole,
    ) {
        val conclusion = record.case.conclusion(verdict)
        item(x, 3) {
            style(role)
            display(
                if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                    "<gold><bold>${conclusion.title}"
                } else {
                    "<dark_gray><bold>${conclusion.title}"
                },
            )
            lore(
                listOf(
                    "<gray>Одна из пяти возможных реконструкций.",
                    "<gray>Сопоставьте её со всей хронологией.",
                    "",
                    if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                        "<red>Нажмите, чтобы вынести вердикт."
                    } else {
                        "<dark_gray>Откроется после трёх показаний."
                    },
                ),
            )
            onClick { InvestigationModule.submit(player, verdict) }
        }
    }

    private fun ru.arc.gui.ItemBuilder.style(role: InvestigationGuiRole) {
        material(role.fallback)
        fromConfig(guiConfig, "items.${role.configKey}")
    }

    private fun cooldownLine(latest: InvestigationJournalRecord?): String {
        val until = latest?.cooldownUntil ?: return "<gray>Готово к выдаче."
        if (until <= System.currentTimeMillis()) return "<gray>Готово к выдаче."
        val seconds = ((until - System.currentTimeMillis()) + 999L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = seconds % 3_600L / 60L
        return "<yellow>Перерыв: <white>${hours}ч ${minutes}м"
    }

    private fun timeLine(record: InvestigationJournalRecord): String {
        val remainingMillis = (requireNotNull(record.expiresAt) - Instant.now().toEpochMilli()).coerceAtLeast(0L)
        val seconds = (remainingMillis + 999L) / 1_000L
        return if (seconds > 20L) "<yellow>Осталось: <white>${seconds}с" else "<red><bold>Осталось: ${seconds}с"
    }

    private fun formatCooldown(minutes: Long): String {
        val hours = minutes / 60L
        val remainder = minutes % 60L
        return when {
            remainder == 0L -> "раз в $hours ч"
            hours == 0L -> "раз в $remainder мин"
            else -> "раз в $hours ч $remainder мин"
        }
    }

    private val VERDICT_ROLES =
        listOf(
            InvestigationGuiRole.THEORY_ONE,
            InvestigationGuiRole.THEORY_TWO,
            InvestigationGuiRole.THEORY_THREE,
            InvestigationGuiRole.THEORY_FOUR,
            InvestigationGuiRole.THEORY_FIVE,
        )
}

private val MINI_MESSAGE_TAG = Regex("<[^>]+>")

/** Wrap dynamic Russian evidence before it becomes lore; Minecraft does not wrap lore itself. */
internal fun wrapInvestigationLore(
    lines: List<String>,
    maxVisibleCharacters: Int = 46,
): List<String> =
    lines.flatMap { line ->
        if (line.isBlank()) return@flatMap listOf("")
        val wrapped = mutableListOf<String>()
        var current = StringBuilder()
        var visible = 0
        line.trim().split(Regex("\\s+")).forEach { word ->
            val wordLength = word.replace(MINI_MESSAGE_TAG, "").length
            if (visible > 0 && visible + 1 + wordLength > maxVisibleCharacters) {
                wrapped += current.toString()
                current = StringBuilder("<dark_gray>  ").append(word)
                visible = 2 + wordLength
            } else {
                if (visible > 0) {
                    current.append(' ')
                    visible++
                }
                current.append(word)
                visible += wordLength
            }
        }
        if (current.isNotEmpty()) wrapped += current.toString()
        wrapped
    }
