package ru.arc.investigation

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil
import java.time.Instant

object InvestigationGui {
    private val guiConfig: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/investigations.yml")
    }

    fun openHub(player: Player, latest: InvestigationJournalRecord?) {
        val policy = InvestigationModule.configOrNull() ?: return
        val bypassCooldown = player.hasPermission(InvestigationModule.COOLDOWN_BYPASS_PERMISSION)
        ArcMenus.open(
            player,
            ArcMenuSchema.INVESTIGATION_HUB,
            title("hub.title", "<dark_gray>Бюро расследований"),
            elements = mapOf(
                "start" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_HUB,
                        "start",
                        render(
                            values = mapOf(
                                "fee" to InvestigationModule.money(policy.feeMinor),
                                "reward" to InvestigationModule.money(policy.rewardMinor),
                                "duration" to formatDuration(policy.duration.seconds),
                                "cooldown" to cooldownLine(latest, bypassCooldown),
                                "action" to "<green>Нажмите — оплата и выдача сразу.",
                            ),
                        ),
                    ),
                ) { InvestigationModule.startCase(it) },
                "contracts" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_HUB,
                        "contracts",
                        render(values = mapOf("action" to "<aqua>Нажмите, чтобы открыть.")),
                    ),
                ) { InvestigationModule.openContracts(it) },
            ),
        )
    }

    fun openCase(player: Player, record: InvestigationJournalRecord) {
        val witnesses = record.case.witnesses().map { witness -> witnessEntry(player, record, witness) }
        val checks = record.case.crossChecks(record.cluesMask)
        val ready = record.clueCount() >= InvestigationService.MIN_CLUES
        ArcMenus.open(
            player,
            ArcMenuSchema.INVESTIGATION_CASE,
            title("case.title", "<dark_gray>Материалы расследования"),
            elements = mapOf(
                "next-step" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_CASE,
                        "next-step",
                        render(
                            values = mapOf(
                                "time" to timeLine(record),
                                "clues" to record.clueCount().toString(),
                            ),
                            repeats = mapOf(
                                "directions" to nextDirections(record),
                                "warnings" to if (ready) {
                                    listOf("", "<red>Вердикт выносится один раз: ошибка закроет дело.")
                                } else {
                                    listOf("", "<dark_gray>Фома примет вердикт после трёх показаний.")
                                },
                            ),
                        ),
                    ),
                ),
                "dossier" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_CASE,
                        "dossier",
                        render(
                            values = mapOf("title" to record.case.displayTitle()),
                            repeats = mapOf("dossier" to wrapInvestigationLore(record.case.dossier())),
                        ),
                    ),
                ),
                "evidence" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_CASE,
                        "evidence",
                        render(
                            flags = if (checks.isEmpty()) emptySet() else setOf("has-links"),
                            repeats = mapOf(
                                "timeline" to wrapInvestigationLore(record.case.timeline(record.cluesMask)),
                                "links" to if (checks.isEmpty()) {
                                    listOf(
                                        "<dark_gray>Сопоставлено связей: 0/5",
                                        "<dark_gray>Опросите ещё свидетелей, чтобы открыть сверки.",
                                    )
                                } else {
                                    listOf("<light_purple>Сопоставлено связей: <white>${checks.size}/5") + wrapInvestigationLore(checks)
                                },
                            ),
                        ),
                    ),
                ),
                "return" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_CASE,
                        "return",
                        render(
                            values = mapOf(
                                "clues" to record.clueCount().toString(),
                                "missing" to (InvestigationService.MIN_CLUES - record.clueCount()).coerceAtLeast(0).toString(),
                                "action" to if (ready) {
                                    "<green>Вернитесь к Фоме и поговорите с ним."
                                } else {
                                    "<yellow>Сначала соберите хотя бы три показания."
                                },
                            ),
                            flags = if (ready) setOf("ready") else emptySet(),
                        ),
                    ),
                ) {
                    it.sendActionBar(
                        if (ready) {
                            TextUtil.mm("<green>Вернитесь к Фоме и поговорите с ним, чтобы вынести вердикт.")
                        } else {
                            TextUtil.mm("<yellow>Сначала соберите хотя бы три показания. Сейчас: <white>${record.clueCount()}/5<yellow>.")
                        },
                    )
                },
            ),
            regions = mapOf(ArcMenuSchema.WITNESSES to witnesses),
        )
    }

    /**
     * Focused response after a witness visit or reopening a collected statement.
     * The case-file GUI deliberately keeps every collected statement inline.
     */
    fun openTestimony(
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
    ) {
        val item = ArcMenus.item(
            ArcMenuSchema.INVESTIGATION_TESTIMONY,
            "statement",
            render(
                values = mapOf("name" to witness.displayName),
                repeats = mapOf(
                    "testimony" to testimonyStatus(record) + focusedTestimonyLore(record, witness),
                ),
            ),
        )
        item.type = org.bukkit.Material.matchMaterial(witness.itemMaterial) ?: item.type
        ArcMenus.open(
            player,
            ArcMenuSchema.INVESTIGATION_TESTIMONY,
            title("testimony.title", "<dark_gray>Показание свидетеля"),
            elements = mapOf("statement" to ArcMenus.entry(item)),
        )
    }

    fun openVerdicts(player: Player, record: InvestigationJournalRecord) {
        val ready = record.clueCount() >= InvestigationService.MIN_CLUES
        val verdicts = InvestigationVerdict.entries.mapIndexed { index, verdict ->
            val conclusion = record.case.conclusion(verdict)
            ArcMenus.entry(
                ArcMenus.item(
                    VERDICT_TEMPLATES[index],
                    render(
                        values = mapOf("title" to conclusion.title),
                        flags = if (ready) setOf("ready") else emptySet(),
                        repeats = mapOf("explanation" to wrapInvestigationLore(conclusion.explanation)),
                    ),
                ),
                enabled = ready,
            ) { InvestigationModule.submit(it, verdict) }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.INVESTIGATION_VERDICT,
            title("verdict.title", "<dark_gray>Выберите версию"),
            elements = mapOf(
                "question" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_VERDICT,
                        "question",
                        render(values = mapOf("question" to record.case.question())),
                    ),
                ),
                "back" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.INVESTIGATION_VERDICT,
                        "back",
                        render(values = mapOf("action" to "<gray>Ещё раз проверить показания и хронологию.")),
                    ),
                ) { openCase(it, record) },
            ),
            regions = mapOf(ArcMenuSchema.VERDICTS to verdicts),
        )
    }

    private fun witnessEntry(
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
    ): PaperMenuEntry {
        val collected = record.hasClue(witness)
        val item = ArcMenus.item(
            "investigation-witness",
            render(
                values = mapOf("name" to witness.displayName, "location" to witness.locationHint),
                flags = if (collected) setOf("collected") else emptySet(),
                repeats = mapOf("body" to if (collected) witnessLore(record, witness) + listOf("", "<aqua>Нажмите — перечитать показание.") else emptyList()),
            ),
        )
        item.type = org.bukkit.Material.matchMaterial(witness.itemMaterial) ?: item.type
        return ArcMenus.entry(item) {
            if (!collected) {
                it.sendActionBar(TextUtil.mm("<yellow>Найдите свидетеля лично: <white>${witness.locationHint}<yellow>."))
            } else {
                openTestimony(it, record, witness)
            }
        }
    }

    private fun title(path: String, fallback: String): Component = TextUtil.mm(guiConfig.string(path, fallback), true)

    private fun render(
        values: Map<String, String> = emptyMap(),
        flags: Set<String> = emptySet(),
        repeats: Map<String, List<String>> = emptyMap(),
    ) = PaperMenuItemRenderContext(
        values = values.mapValues { TextUtil.mm(it.value, true) },
        flags = flags,
        repeats = repeats.mapValues { (_, lines) -> lines.map { mapOf("line" to TextUtil.mm(it, true)) } },
    )

    private fun nextDirections(record: InvestigationJournalRecord): List<String> {
        val clueCount = record.clueCount()
        val nextWitness = record.case.witnesses().firstOrNull { !record.hasClue(it) }
        val directions = mutableListOf<String>()
        when {
            clueCount >= 5 -> {
                directions += "<green>Полная картина собрана: 5/5 показаний."
                directions += "<gray>Фома видит все пять этапов и готов принять вердикт."
            }
            clueCount >= InvestigationService.MIN_CLUES -> {
                directions += "<green>Вердикт доступен. Собрано показаний: $clueCount/5."
                directions += "<gray>Можно вынести вердикт или собрать ещё показания для полной картины."
                nextWitness?.let { directions += "<gray>Следующий свидетель: <white>${it.displayName}<gray>." }
            }
            nextWitness != null -> {
                directions += "<white>Найдите: <gold>${nextWitness.displayName}<white>."
                directions += "<gray>Место: <white>${nextWitness.locationHint}<gray>."
                directions += "<gray>Вердикт откроется после трёх показаний; сопоставления появятся по мере сбора."
            }
        }
        return directions
    }

    private fun testimonyStatus(record: InvestigationJournalRecord): List<String> {
        val clues = record.clueCount()
        val links = record.case.crossChecks(record.cluesMask).size
        return listOf(
            "<gray>Собрано показаний: <white>$clues/5",
            if (clues >= InvestigationService.MIN_CLUES) {
                "<green>Порог вердикта 3/5 достигнут; можно сопоставить показания у Фомы."
            } else {
                "<yellow>До вердикта: ещё ${InvestigationService.MIN_CLUES - clues} показания."
            },
            "<light_purple>Доступно сопоставлений: <white>$links/5",
            if (clues >= 5) "<aqua>Полная картина собрана: Фома готов принять итог." else "<gray>Пятое показание завершит картину дела.",
            "",
        )
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

    private val VERDICT_TEMPLATES =
        listOf(
            "investigation-verdict-one",
            "investigation-verdict-two",
            "investigation-verdict-three",
            "investigation-verdict-four",
            "investigation-verdict-five",
        )
}

internal fun witnessLore(
    record: InvestigationJournalRecord,
    witness: InvestigationWitness,
): List<String> {
    if (!record.hasClue(witness)) {
        return listOf(
            "<gray>Где искать: <white>${witness.locationHint}<gray>.",
            "<gray>Этот человек видел один этап истории.",
            "",
            "<dark_gray>Опрос проводится только лично.",
        )
    }

    val moment = record.case.timelineBeat(witness)
    val comparisons = record.case.crossChecksFor(witness, record.cluesMask).take(2)
    val comparisonLore =
        if (comparisons.isEmpty()) {
            listOf("<dark_gray>Нужны слова ещё одного связанного свидетеля.")
        } else {
            comparisons.map { "<white>✔</white> <gray>$it" }
        }
    return wrapInvestigationLore(
        listOf(
            "<green>Показание записано.",
            "",
            "<gold><bold>Слова свидетеля",
        ) + record.case.testimony(witness) +
            listOf(
                "",
                "<aqua><bold>Что это устанавливает",
                if (moment == null) {
                    "<dark_gray>Момент не отделён от старого дела."
                } else {
                    "<gray>${moment.time} <white>•</white> <white>${moment.event}"
                },
                "",
                "<light_purple><bold>Связи с другими показаниями",
            ) + comparisonLore,
    )
}

/** Only the selected witness's own words belong on the click-focused screen. */
internal fun focusedTestimonyLore(
    record: InvestigationJournalRecord,
    witness: InvestigationWitness,
): List<String> = wrapInvestigationLore(record.case.testimony(witness))

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
