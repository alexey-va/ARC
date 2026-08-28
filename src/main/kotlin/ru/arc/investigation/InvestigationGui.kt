package ru.arc.investigation

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
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
        val bypassCooldown = player.hasPermission(InvestigationModule.COOLDOWN_BYPASS_PERMISSION)
        GuiUtils.constructAndShowAsync({ buildHub(player, latest, policy, bypassCooldown) }, player)
    }

    fun openCase(player: Player, record: InvestigationJournalRecord) {
        GuiUtils.constructAndShowAsync({ buildCase(player, record) }, player)
    }

    fun openVerdicts(player: Player, record: InvestigationJournalRecord) {
        GuiUtils.constructAndShowAsync({ buildVerdicts(player, record) }, player)
    }

    fun openTestimony(
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
    ) {
        GuiUtils.constructAndShowAsync({ buildTestimony(player, record, witness) }, player)
    }

    internal fun buildHub(
        player: Player,
        latest: InvestigationJournalRecord?,
        policy: InvestigationConfig,
        bypassCooldown: Boolean,
    ): ChestGui =
        gui(guiConfig.string("hub.title", "<dark_gray>Бюро расследований"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 1) {
                    style(InvestigationGuiRole.START)
                    display("<green><bold>Взять дело за ${InvestigationModule.money(policy.feeMinor)} <white>💰</white>")
                    lore(
                        listOf(
                            "<white>Фома выдаст вам предмет <gold>«Дело»<white>.",
                            "<gray>В нём записаны происшествие, вопрос,",
                            "<gray>свидетели и точный порядок действий.",
                            "",
                            "<yellow>1. <gray>Прочитайте дело в инвентаре.",
                            "<yellow>2. <gray>Найдите отмеченных свидетелей.",
                            "<yellow>3. <gray>Соберите хотя бы три показания.",
                            "<yellow>4. <gray>Выберите верную версию событий.",
                            "",
                            "<gray>Награда: <gold>${InvestigationModule.money(policy.rewardMinor)} <white>💰</white>",
                            "<gray>Время: <white>${formatDuration(policy.duration.seconds)}",
                            if (bypassCooldown) {
                                "<gray>Повтор: <aqua>без ожидания для администратора"
                            } else {
                                "<gray>Повтор: <white>${formatCooldown(policy.cooldown.toMinutes())}"
                            },
                            "",
                            cooldownLine(latest, bypassCooldown),
                            "<green>Нажмите — оплата и выдача сразу.",
                        ),
                    )
                    onClick { InvestigationModule.startCase(player) }
                }
                item(8, 2) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua>Заказы бюро")
                    lore(
                        listOf(
                            "<gray>Отдельные поставки книг, чернил и золота.",
                            "<aqua>Нажмите, чтобы открыть.",
                        ),
                    )
                    onClick { InvestigationModule.openContracts(player) }
                }
            }
        }

    internal fun buildCase(player: Player, record: InvestigationJournalRecord): ChestGui =
        gui(guiConfig.string("case.title", "<dark_gray>Материалы расследования"), CASE_ROWS, player, guiConfig) {
            background()
            staticPane(width = 9, height = CASE_ROWS) {
                item(4, 0) {
                    style(InvestigationGuiRole.NEXT_STEP)
                    display("<yellow><bold>Что делать сейчас")
                    lore(nextStepLore(record))
                }

                record.case.witnesses().forEachIndexed { index, witness ->
                    witnessItem(index * 2, WITNESS_ROW, player, record, witness)
                }

                item(0, INFO_ROW) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>${record.case.displayTitle()}")
                    lore(wrapInvestigationLore(record.case.dossier()))
                }
                item(4, INFO_ROW) {
                    style(InvestigationGuiRole.EVIDENCE)
                    display("<aqua><bold>Хронология и связи")
                    val checks = record.case.crossChecks(record.cluesMask)
                    val checkLore =
                        if (checks.isEmpty()) {
                            listOf("", "<light_purple>Связи", "<dark_gray>Опросите ещё свидетелей, чтобы открыть сверки.")
                        } else {
                            listOf("", "<light_purple>Установленные связи") + checks
                        }
                    lore(
                        wrapInvestigationLore(
                            listOf("<aqua>Хронология") + record.case.timeline(record.cluesMask) + checkLore,
                        ),
                    )
                }
                item(8, INFO_ROW) {
                    style(InvestigationGuiRole.RETURN_TO_FOMA)
                    display(
                        if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                            "<green><bold>Вернуться к Фоме"
                        } else {
                            "<dark_gray><bold>Вернуться к Фоме"
                        },
                    )
                    lore(
                        listOf(
                            "<gray>Собрано показаний: <white>${record.clueCount()}/5<gray>.",
                            "",
                            if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                                "<green>Фома готов принять ваш вердикт."
                            } else {
                                "<dark_gray>Нужно ещё ${InvestigationService.MIN_CLUES - record.clueCount()} показания."
                            },
                            "<gray>Версии открываются только при разговоре с Фомой.",
                            "<red>Ошибка сразу закрывает дело.",
                        ),
                    )
                    onClick {
                        player.sendActionBar(
                            if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                                TextUtil.mm("<green>Вернитесь к Фоме и поговорите с ним, чтобы вынести вердикт.")
                            } else {
                                TextUtil.mm("<yellow>Сначала соберите хотя бы три показания. Сейчас: <white>${record.clueCount()}/5<yellow>.")
                            },
                        )
                    }
                }
            }
        }

    internal fun buildTestimony(
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
    ): ChestGui =
        gui(guiConfig.string("testimony.title", "<dark_gray>Показание свидетеля"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 1) {
                    style(InvestigationGuiRole.TESTIMONY)
                    material(Material.matchMaterial(witness.itemMaterial) ?: Material.PAPER)
                    display("<gold><bold>${witness.displayName}")
                    lore(wrapInvestigationLore(record.case.testimony(witness)))
                }
            }
        }

    internal fun buildVerdicts(player: Player, record: InvestigationJournalRecord): ChestGui =
        gui(guiConfig.string("verdict.title", "<dark_gray>Выберите версию"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 0) {
                    style(InvestigationGuiRole.CHOOSE_VERDICT)
                    display("<yellow><bold>${record.case.question()}")
                    lore(
                        listOf(
                            "<gray>Выберите версию, которая объясняет всю цепочку.",
                            "<red>Ошибочный вердикт сразу закроет дело.",
                        ),
                    )
                }
                InvestigationVerdict.entries.forEachIndexed { index, verdict ->
                    verdictItem(index * 2, player, record, verdict, VERDICT_ROLES[index])
                }
                item(4, 2) {
                    style(InvestigationGuiRole.BACK)
                    display("<aqua>Вернуться к материалам")
                    lore(listOf("<gray>Ещё раз проверить показания и хронологию."))
                    onClick { openCase(player, record) }
                }
            }
        }

    private fun ru.arc.gui.StaticPaneBuilder.witnessItem(
        x: Int,
        y: Int,
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
    ) {
        item(x, y) {
            material(Material.matchMaterial(witness.itemMaterial) ?: Material.PAPER)
            display(if (record.hasClue(witness)) "<green><bold>${witness.displayName}" else "<yellow><bold>${witness.displayName}")
            lore(
                if (record.hasClue(witness)) {
                    listOf(
                        "<green>Показание записано.",
                        "",
                        "<aqua>Нажмите, чтобы перечитать.",
                    )
                } else {
                    listOf(
                        "<gray>Где искать: <white>${witness.locationHint}<gray>.",
                        "<gray>Этот человек видел один этап истории.",
                        "",
                        "<dark_gray>Опрос проводится только лично.",
                    )
                },
            )
            onClick {
                if (record.hasClue(witness)) {
                    openTestimony(player, record, witness)
                } else {
                    player.sendActionBar(TextUtil.mm("<yellow>Найдите свидетеля лично: <white>${witness.locationHint}<yellow>."))
                }
            }
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
        item(x, 1) {
            style(role)
            display(
                if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                    "<gold><bold>${conclusion.title}"
                } else {
                    "<dark_gray><bold>${conclusion.title}"
                },
            )
            lore(
                wrapInvestigationLore(
                    conclusion.explanation.map { "<gray>$it" } +
                        listOf("", "<red>Нажмите, чтобы вынести вердикт."),
                ),
            )
            onClick { InvestigationModule.submit(player, verdict) }
        }
    }

    private fun nextStepLore(record: InvestigationJournalRecord): List<String> {
        val nextWitness = record.case.witnesses().firstOrNull { !record.hasClue(it) }
        val direction =
            when {
                record.clueCount() >= InvestigationService.MIN_CLUES ->
                    listOf(
                        "<green>Вернитесь к Фоме: он готов принять вердикт.",
                        if (nextWitness == null) {
                            "<gray>Вы собрали полную картину: пять показаний."
                        } else {
                            "<gray>Для полной картины ещё можно опросить ${nextWitness.displayName}."
                        },
                    )
                nextWitness != null ->
                    listOf(
                        "<white>Найдите: <gold>${nextWitness.displayName}<white>.",
                        "<gray>Место: <white>${nextWitness.locationHint}<gray>.",
                        "<gray>Поговорите с NPC, чтобы записать показание.",
                    )
                else ->
                    emptyList()
            }
        val verdictState =
            if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                listOf("", "<red>Вердикт выносится один раз: ошибка закроет дело.")
            } else {
                listOf("", "<dark_gray>Фома примет вердикт после трёх показаний.")
            }
        return listOf(timeLine(record), "<gray>Показания: <white>${record.clueCount()}/5", "") + direction + verdictState
    }

    private fun ru.arc.gui.ItemBuilder.style(role: InvestigationGuiRole) {
        material(role.fallback)
        fromConfig(guiConfig, "items.${role.configKey}")
    }

    private fun cooldownLine(
        latest: InvestigationJournalRecord?,
        bypassCooldown: Boolean,
    ): String {
        val until = latest?.cooldownUntil ?: return "<gray>Готово к выдаче."
        if (until <= System.currentTimeMillis()) return "<gray>Готово к выдаче."
        if (bypassCooldown) return "<aqua>Админ-доступ: <white>перерыв пропущен."
        val seconds = ((until - System.currentTimeMillis()) + 999L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = seconds % 3_600L / 60L
        return "<yellow>Перерыв: <white>${hours}ч ${minutes}м"
    }

    private fun timeLine(record: InvestigationJournalRecord): String {
        val remainingMillis = (requireNotNull(record.expiresAt) - Instant.now().toEpochMilli()).coerceAtLeast(0L)
        val seconds = (remainingMillis + 999L) / 1_000L
        val formatted = "%d:%02d".format(seconds / 60L, seconds % 60L)
        return if (seconds > 20L) "<yellow>Осталось: <white>$formatted" else "<red><bold>Осталось: $formatted"
    }

    private fun formatDuration(seconds: Long): String =
        when {
            seconds % 60L == 0L -> "${seconds / 60L} мин"
            seconds >= 60L -> "${seconds / 60L} мин ${seconds % 60L} сек"
            else -> "$seconds сек"
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

    internal const val CASE_ROWS = 5
    internal const val WITNESS_ROW = 2
    internal const val INFO_ROW = 4
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
        var activeColor = "<gray>"
        line.trim().split(Regex("\\s+")).forEach { word ->
            val wordLength = word.replace(MINI_MESSAGE_TAG, "").length
            if (visible > 0 && visible + 1 + wordLength > maxVisibleCharacters) {
                wrapped += current.toString()
                current = StringBuilder(activeColor).append("  ").append(word)
                visible = 2 + wordLength
            } else {
                if (visible > 0) {
                    current.append(' ')
                    visible++
                }
                current.append(word)
                visible += wordLength
            }
            COLOR_TAG.findAll(word).lastOrNull()?.let { match -> activeColor = match.value }
        }
        if (current.isNotEmpty()) wrapped += current.toString()
        wrapped
    }

private val COLOR_TAG =
    Regex("<(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|#[0-9a-fA-F]{6})>")
