package ru.arc.investigation

import kotlin.random.Random

/**
 * Stable persisted choice slots. The enum names predate narrative cases and
 * remain unchanged so open journal records from the first live revision load.
 * New cases put a case-specific reconstruction into every slot.
 */
enum class InvestigationVerdict(val commandValue: String) {
    AMOUNT_MISMATCH("amount"),
    FORGED_SEAL("seal"),
    CARGO_SUBSTITUTION("cargo"),
    DUPLICATE_ENTRY("duplicate"),
    CLEAN("clean"),
    ;

    companion object {
        fun parse(raw: String?): InvestigationVerdict? = entries.firstOrNull { it.commandValue == raw?.lowercase() }
    }
}

data class InvestigationWitness(
    val commandValue: String,
    val displayName: String,
    val locationHint: String,
    val itemMaterial: String,
    val bit: Int,
) {
    fun validated(): InvestigationWitness {
        require(KEY_PATTERN.matches(commandValue)) { "Invalid investigation witness key" }
        validateNarrativeText(displayName, 48)
        validateNarrativeText(locationHint, 72)
        require(MATERIAL_PATTERN.matches(itemMaterial)) { "Invalid investigation witness material" }
        require(bit in listOf(1, 2, 4, 8, 16)) { "Invalid investigation witness bit" }
        return this
    }

    companion object {
        private val KEY_PATTERN = Regex("[a-z][a-z0-9_-]{2,31}")
        private val MATERIAL_PATTERN = Regex("[A-Z][A-Z0-9_]{1,63}")

        val LEGACY: List<InvestigationWitness> =
            listOf(
                InvestigationWitness("stavr", "Глашатай Ставр", "стол у входа", "BELL", 1),
                InvestigationWitness("prokhor", "Архивариус Прохор", "стол у входа", "BOOK", 2),
                InvestigationWitness("gordey", "Пристав Гордей", "патруль первого этажа", "SHIELD", 4),
                InvestigationWitness("agata", "Почерковед Агата", "второй этаж", "FEATHER", 8),
                InvestigationWitness("tikhon", "Счётовод Тихон", "второй этаж", "BARREL", 16),
            )
    }
}

enum class AmountTrap {
    NONE,
    ARITHMETIC,
    ARCHIVE_COPY,
}

enum class SealTrap {
    NONE,
    SYMBOL,
    WAX,
    INITIALS,
}

enum class CargoTrap {
    NONE,
    GOODS,
    QUANTITY,
}

enum class LedgerTrap {
    NONE,
    DUPLICATE,
}

data class InvestigationTimelineBeat(
    val order: Int,
    val time: String,
    val witness: String,
    val event: String,
) {
    fun validated(witnessKeys: Set<String>): InvestigationTimelineBeat {
        require(order in 1..5) { "Invalid investigation timeline order" }
        require(witness in witnessKeys) { "Invalid investigation timeline witness" }
        validateNarrativeText(time, 40)
        validateNarrativeText(event, 120)
        return this
    }
}

data class InvestigationTestimony(
    val lines: List<String>,
) {
    fun validated(): InvestigationTestimony {
        require(lines.size in 2..4) { "Investigation testimony must have 2..4 lines" }
        lines.forEach { validateNarrativeText(it, 150) }
        return this
    }
}

data class InvestigationCrossCheck(
    val first: String,
    val second: String,
    val insight: String,
) {
    fun validated(witnesses: Map<String, InvestigationWitness>): InvestigationCrossCheck {
        val firstWitness = requireNotNull(witnesses[first]) { "Invalid first cross-check witness" }
        val secondWitness = requireNotNull(witnesses[second]) { "Invalid second cross-check witness" }
        require(firstWitness != secondWitness) { "A cross-check needs two witnesses" }
        validateNarrativeText(insight, 140)
        return this
    }

    fun unlocked(mask: Int, witnesses: Map<String, InvestigationWitness>): Boolean {
        val firstWitness = requireNotNull(witnesses[first])
        val secondWitness = requireNotNull(witnesses[second])
        return mask and firstWitness.bit != 0 && mask and secondWitness.bit != 0
    }

    fun pairKey(): Set<String> = setOf(first, second)
}

data class InvestigationConclusion(
    val title: String,
    val explanation: List<String>,
) {
    fun validated(): InvestigationConclusion {
        validateNarrativeText(title, 72)
        require(explanation.size in 1..3) { "Investigation conclusion must have 1..3 explanation lines" }
        explanation.forEach { validateNarrativeText(it, 140) }
        return this
    }
}

data class InvestigationNarrative(
    val schemaVersion: Int,
    val plotId: String,
    val title: String,
    val briefing: List<String>,
    val question: String,
    val suspiciousLead: String,
    val timeline: List<InvestigationTimelineBeat>,
    val testimonies: Map<String, InvestigationTestimony>,
    val crossChecks: List<InvestigationCrossCheck>,
    val conclusions: Map<String, InvestigationConclusion>,
    /** Embedded roster keeps an already paid case stable across config reloads. */
    val witnesses: List<InvestigationWitness>? = null,
) {
    fun validated(correct: InvestigationVerdict): InvestigationNarrative {
        require(schemaVersion in LEGACY_SCHEMA..CURRENT_SCHEMA) { "Unsupported investigation narrative schema" }
        require(PLOT_ID.matches(plotId)) { "Invalid investigation plot id" }
        validateNarrativeText(title, 72)
        require(briefing.size in 2..4) { "Investigation briefing must have 2..4 lines" }
        briefing.forEach { validateNarrativeText(it, 140) }
        validateNarrativeText(question, 140)
        validateNarrativeText(suspiciousLead, 140)

        val roster = witnessRoster()
        require(roster.size == WITNESS_COUNT) { "Investigation must have five witnesses" }
        roster.forEach(InvestigationWitness::validated)
        require(roster.map(InvestigationWitness::commandValue).distinct().size == WITNESS_COUNT) { "Investigation witnesses must be unique" }
        require(roster.map(InvestigationWitness::bit) == listOf(1, 2, 4, 8, 16)) { "Investigation witness bits must follow roster order" }
        val witnessMap = roster.associateBy(InvestigationWitness::commandValue)
        val witnessKeys = witnessMap.keys

        require(timeline.size == WITNESS_COUNT) { "Investigation timeline must have five events" }
        timeline.forEach { it.validated(witnessKeys) }
        require(timeline.map(InvestigationTimelineBeat::order).toSet() == (1..5).toSet()) { "Investigation timeline order is incomplete" }
        require(timeline.map(InvestigationTimelineBeat::witness).toSet() == witnessKeys) { "Every witness must own one timeline event" }

        require(testimonies.keys == witnessKeys) { "Investigation testimony roster is incomplete" }
        testimonies.values.forEach(InvestigationTestimony::validated)

        require(crossChecks.size >= WITNESS_COUNT) { "Investigation needs at least five cross-checks" }
        crossChecks.forEach { it.validated(witnessMap) }
        require(crossChecks.map(InvestigationCrossCheck::pairKey).distinct().size == crossChecks.size) { "Investigation cross-check pairs must be unique" }
        require(anyThreeWitnessesUnlockCrossCheck(roster, witnessMap)) { "Every three-witness path must unlock a cross-check" }

        require(conclusions.keys == verdictKeys()) { "Investigation must offer five reconstructions" }
        conclusions.values.forEach(InvestigationConclusion::validated)
        require(conclusions.values.map(InvestigationConclusion::title).distinct().size == conclusions.size) { "Investigation conclusions must be distinct" }
        require(conclusions.containsKey(correct.commandValue)) { "Correct investigation reconstruction is missing" }
        return this
    }

    fun witnessRoster(): List<InvestigationWitness> = witnesses ?: InvestigationWitness.LEGACY

    private fun anyThreeWitnessesUnlockCrossCheck(
        witnesses: List<InvestigationWitness>,
        witnessMap: Map<String, InvestigationWitness>,
    ): Boolean {
        for (first in witnesses.indices) {
            for (second in first + 1 until witnesses.size) {
                for (third in second + 1 until witnesses.size) {
                    val mask = witnesses[first].bit or witnesses[second].bit or witnesses[third].bit
                    if (crossChecks.none { it.unlocked(mask, witnessMap) }) return false
                }
            }
        }
        return true
    }

    companion object {
        const val LEGACY_SCHEMA = 2
        const val CURRENT_SCHEMA = 3
        const val WITNESS_COUNT = 5
        private val PLOT_ID = Regex("[a-z0-9][a-z0-9_-]{2,47}")
    }
}

/** Immutable evidence generated before any money is withdrawn. */
data class InvestigationCase(
    val caseNumber: String,
    val seller: String,
    val goods: String,
    val quantity: Int,
    val unitPrice: Int,
    val announcedTotal: Int,
    val archiveTotal: Int,
    val registeredSeal: String,
    val documentSeal: String,
    val registeredWax: String,
    val documentWax: String,
    val registeredInitials: String,
    val documentInitials: String,
    val oddity: String,
    val amountTrap: AmountTrap,
    val sealTrap: SealTrap,
    val verdict: InvestigationVerdict,
    val stavrVariant: Int,
    val prokhorVariant: Int,
    val gordeyVariant: Int,
    /** Nullable fields keep journal records written by earlier live revisions readable. */
    val manifestGoods: String? = null,
    val observedGoods: String? = null,
    val manifestQuantity: Int? = null,
    val observedQuantity: Int? = null,
    val ledgerReference: String? = null,
    val duplicateReference: String? = null,
    val cargoTrap: CargoTrap? = null,
    val ledgerTrap: LedgerTrap? = null,
    val agataVariant: Int = 0,
    val tikhonVariant: Int = 0,
    val narrative: InvestigationNarrative? = null,
) {
    val expectedTotal: Int get() = Math.multiplyExact(quantity, unitPrice)
    val declaredGoods: String get() = manifestGoods ?: goods
    val inspectedGoods: String get() = observedGoods ?: goods
    val declaredQuantity: Int get() = manifestQuantity ?: quantity
    val inspectedQuantity: Int get() = observedQuantity ?: quantity
    val entryReference: String get() = ledgerReference ?: caseNumber
    val effectiveCargoTrap: CargoTrap get() = cargoTrap ?: CargoTrap.NONE
    val effectiveLedgerTrap: LedgerTrap get() = ledgerTrap ?: LedgerTrap.NONE

    fun validated(): InvestigationCase {
        require(CASE_PATTERN.matches(caseNumber)) { "Invalid investigation case number" }
        listOf(seller, goods, registeredSeal, documentSeal, registeredWax, documentWax, registeredInitials, documentInitials, oddity)
            .forEach { validateLegacyText(it) }
        require(quantity in 8..96 && unitPrice in 3..24) { "Invalid investigation arithmetic" }
        require(announcedTotal > 0 && archiveTotal > 0) { "Invalid investigation totals" }
        listOf(declaredGoods, inspectedGoods, entryReference).forEach { validateLegacyText(it) }
        require(declaredQuantity in 8..96 && inspectedQuantity in 1..128) { "Invalid cargo quantity" }
        require(duplicateReference == null || CASE_PATTERN.matches(duplicateReference)) { "Invalid duplicate reference" }
        require(stavrVariant in 0..3 && prokhorVariant in 0..3 && gordeyVariant in 0..3) { "Invalid witness variant" }
        require(agataVariant in 0..3 && tikhonVariant in 0..3) { "Invalid specialist variant" }

        if (narrative != null) {
            narrative.validated(verdict)
        } else {
            validateLegacyEvidence()
        }
        return this
    }

    fun fingerprint(): String =
        if (narrative != null) {
            listOf(
                narrative.plotId,
                narrative.title,
                seller,
                goods,
                narrative.briefing.joinToString("/"),
                narrative.timeline.joinToString("/") { it.event },
                narrative.conclusions.values.joinToString("/") { it.title },
                verdict,
            ).joinToString("|")
        } else {
            listOf(
                seller,
                goods,
                quantity,
                unitPrice,
                announcedTotal,
                archiveTotal,
                registeredSeal,
                documentSeal,
                registeredWax,
                documentWax,
                registeredInitials,
                documentInitials,
                oddity,
                amountTrap,
                sealTrap,
                declaredGoods,
                inspectedGoods,
                declaredQuantity,
                inspectedQuantity,
                entryReference,
                duplicateReference,
                effectiveCargoTrap,
                effectiveLedgerTrap,
                verdict,
            ).joinToString("|")
        }

    fun displayTitle(): String = narrative?.title ?: "Ведомость $caseNumber"

    fun witnesses(): List<InvestigationWitness> = narrative?.witnessRoster() ?: InvestigationWitness.LEGACY

    fun witness(key: String?): InvestigationWitness? =
        witnesses().firstOrNull { it.commandValue == key?.lowercase() }

    fun question(): String = narrative?.question ?: "Какой из документов содержит решающее расхождение?"

    fun dossier(): List<String> =
        narrative?.let { story ->
            listOf("<gold><bold>Дело $caseNumber</bold> <dark_gray>· <white>${story.title}") +
                story.briefing.map { "<gray>$it" } +
                listOf("", "<yellow>Вопрос: <white>${story.question}", "<dark_gray>Зацепка: ${story.suspiciousLead}")
        } ?: legacyDossier()

    fun testimony(witness: InvestigationWitness): List<String> =
        narrative?.testimonies?.get(witness.commandValue)?.lines?.mapIndexed { index, line ->
            if (index == 0) "<gold>${witness.displayName} <dark_gray>» <gray>$line" else "<dark_gray>  $line"
        } ?: legacyTestimony(witness)

    fun timeline(mask: Int): List<String> =
        narrative?.timeline?.sortedBy(InvestigationTimelineBeat::order)?.map { beat ->
            val witness = requireNotNull(witness(beat.witness))
            if (mask and witness.bit != 0) {
                "<gray>${beat.time} <dark_gray>• <white>${beat.event}"
            } else {
                "<dark_gray>${beat.time} • событие ещё не установлено"
            }
        } ?: listOf("<dark_gray>Старое дело не содержит отдельной хронологии.")

    fun crossChecks(mask: Int): List<String> =
        narrative?.let { story ->
            val witnessMap = witnesses().associateBy(InvestigationWitness::commandValue)
            story.crossChecks.filter { it.unlocked(mask, witnessMap) }.map { "<green>✔ <gray>${it.insight}" }
        }.orEmpty()

    fun totalCrossChecks(): Int = narrative?.crossChecks?.size ?: 0

    fun conclusion(verdict: InvestigationVerdict): InvestigationConclusion =
        narrative?.conclusions?.get(verdict.commandValue) ?: legacyConclusion(verdict)

    private fun validateLegacyEvidence() {
        when (verdict) {
            InvestigationVerdict.AMOUNT_MISMATCH -> {
                require(amountTrap != AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Amount case has invalid traps" }
                require(sealMatches()) { "Amount case must have a valid seal" }
                require(cargoMatches() && duplicateReference == null) { "Amount case has conflicting evidence" }
                when (amountTrap) {
                    AmountTrap.ARITHMETIC -> require(announcedTotal != expectedTotal && archiveTotal == expectedTotal)
                    AmountTrap.ARCHIVE_COPY -> require(announcedTotal == expectedTotal && archiveTotal != expectedTotal)
                    AmountTrap.NONE -> error("Amount case lacks a trap")
                }
            }

            InvestigationVerdict.FORGED_SEAL -> {
                require(amountTrap == AmountTrap.NONE && sealTrap != SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Seal case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal) { "Seal case must have exact totals" }
                require(cargoMatches() && duplicateReference == null) { "Seal case has conflicting evidence" }
                require(!sealMatches()) { "Seal case must contain one forged field" }
                require(listOf(registeredSeal != documentSeal, registeredWax != documentWax, registeredInitials != documentInitials).count { it } == 1) {
                    "Seal case must contain exactly one decisive mismatch"
                }
            }

            InvestigationVerdict.CARGO_SUBSTITUTION -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap != CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Cargo case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches()) { "Cargo case has conflicting document evidence" }
                require(!cargoMatches() && duplicateReference == null) { "Cargo case lacks a substitution" }
                require((declaredGoods != inspectedGoods) xor (declaredQuantity != inspectedQuantity)) { "Cargo case must contain one decisive mismatch" }
            }

            InvestigationVerdict.DUPLICATE_ENTRY -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.DUPLICATE) { "Duplicate case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches() && cargoMatches()) { "Duplicate case has conflicting evidence" }
                require(duplicateReference != null && duplicateReference != entryReference) { "Duplicate case lacks a linked entry" }
            }

            InvestigationVerdict.CLEAN -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Clean case cannot contain a trap" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches() && cargoMatches() && duplicateReference == null) {
                    "Clean case evidence must agree"
                }
            }
        }
    }

    private fun legacyDossier(): List<String> =
        listOf(
            "<gold><bold>Дело $caseNumber</bold> <dark_gray>· <white>$seller",
            "<gray>Накладная: <white>$declaredQuantity × $declaredGoods <gray>по <white>$unitPrice <gray>монет.",
            "<gray>Итог в ведомости: <white>$announcedTotal<gray>. Печать: <white>$documentWax, $documentSeal, $documentInitials<gray>.",
            "<gray>Запись реестра: <white>$entryReference<gray>.",
            "<dark_gray>Странность: $oddity",
        )

    private fun legacyTestimony(witness: InvestigationWitness): List<String> =
        when (witness.commandValue) {
            "stavr" -> listOf("<gold>Ставр:</gold> <gray>Я объявлял: <white>$declaredQuantity × $unitPrice<gray>, итог <white>$announcedTotal<gray>.", "<dark_gray>$oddity")
            "prokhor" -> listOf("<aqua>Прохор:</aqua> <gray>В архивной копии итог <white>$archiveTotal<gray>.", "<gray>Реестр печатей: <white>$registeredWax, $registeredSeal, $registeredInitials<gray>.")
            "gordey" -> listOf("<red>Гордей:</red> <gray>На листе: <white>$documentWax, $documentSeal, $documentInitials<gray>.", "<dark_gray>Бумага цела; $oddity")
            "agata" -> listOf("<light_purple>Агата:</light_purple> <gray>Сверила почерк, воск и оттиск.", "<gray>На листе стоят инициалы <white>$documentInitials<gray>.")
            "tikhon" -> listOf("<blue>Тихон:</blue> <gray>На складе: <white>$inspectedQuantity × $inspectedGoods<gray>.", "<gray>Накладная требует <white>$declaredQuantity × $declaredGoods<gray>.")
            else -> error("Legacy investigation does not contain witness ${witness.commandValue}")
        }

    private fun legacyConclusion(verdict: InvestigationVerdict): InvestigationConclusion =
        when (verdict) {
            InvestigationVerdict.AMOUNT_MISMATCH -> InvestigationConclusion("Ошибка в сумме", listOf("Цифры ведомости не сходятся."))
            InvestigationVerdict.FORGED_SEAL -> InvestigationConclusion("Поддельная печать", listOf("Один признак печати не из реестра."))
            InvestigationVerdict.CARGO_SUBSTITUTION -> InvestigationConclusion("Подмена груза", listOf("Содержимое или число мест подменено."))
            InvestigationVerdict.DUPLICATE_ENTRY -> InvestigationConclusion("Повторная запись", listOf("Один груз провели по реестру дважды."))
            InvestigationVerdict.CLEAN -> InvestigationConclusion("Сделка чиста", listOf("Подозрительная деталь была уловкой."))
        }

    private fun sealMatches(): Boolean =
        registeredSeal == documentSeal && registeredWax == documentWax && registeredInitials == documentInitials

    private fun cargoMatches(): Boolean = declaredGoods == inspectedGoods && declaredQuantity == inspectedQuantity

    companion object {
        private val CASE_PATTERN = Regex("[А-Я]-[0-9]{4}")
    }
}

class InvestigationCaseGenerator internal constructor(
    private val catalog: InvestigationStoryCatalog,
) {
    fun generate(
        random: Random,
        previous: InvestigationCase? = null,
    ): InvestigationCase {
        repeat(24) {
            val generated = generateOnce(random, previous?.narrative?.plotId)
            if (previous == null || generated.fingerprint() != previous.fingerprint()) return generated
        }
        return generateOnce(random, previous?.narrative?.plotId)
    }

    private fun generateOnce(
        random: Random,
        excludedPlotId: String?,
    ): InvestigationCase {
        val caseNumber = randomCaseNumber(random)
        val generated = catalog.generate(random, caseNumber, excludedPlotId)
        val quantity = QUANTITIES.random(random)
        val unitPrice = UNIT_PRICES.random(random)
        val expected = quantity * unitPrice
        val seal = SEALS.random(random)
        val wax = WAXES.random(random)
        val initials = INITIALS.random(random)
        return InvestigationCase(
            caseNumber = caseNumber,
            seller = generated.seller,
            goods = generated.goods,
            quantity = quantity,
            unitPrice = unitPrice,
            announcedTotal = expected,
            archiveTotal = expected,
            registeredSeal = seal,
            documentSeal = seal,
            registeredWax = wax,
            documentWax = wax,
            registeredInitials = initials,
            documentInitials = initials,
            oddity = generated.narrative.suspiciousLead,
            amountTrap = AmountTrap.NONE,
            sealTrap = SealTrap.NONE,
            verdict = generated.correctVerdict,
            stavrVariant = random.nextInt(4),
            prokhorVariant = random.nextInt(4),
            gordeyVariant = random.nextInt(4),
            manifestGoods = generated.goods,
            observedGoods = generated.goods,
            manifestQuantity = quantity,
            observedQuantity = quantity,
            ledgerReference = caseNumber,
            duplicateReference = null,
            cargoTrap = CargoTrap.NONE,
            ledgerTrap = LedgerTrap.NONE,
            agataVariant = random.nextInt(4),
            tikhonVariant = random.nextInt(4),
            narrative = generated.narrative,
        ).validated()
    }

    private fun randomCaseNumber(random: Random): String =
        "${CASE_PREFIXES.random(random)}-${random.nextInt(1000, 10000)}"

    private val CASE_PREFIXES = listOf("А", "Б", "В", "Г", "Д", "К", "М", "Р", "Т")
    private val QUANTITIES = listOf(12, 16, 18, 20, 24, 28, 32, 36, 40, 48, 54, 60, 64, 72, 80, 96)
    private val UNIT_PRICES = listOf(3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15, 18, 20, 24)
    private val SEALS = listOf("сокол", "ключ", "ладья", "дубовый лист", "башня", "волчья голова", "звезда", "молот")
    private val WAXES = listOf("алый воск", "синий воск", "зелёный воск", "чёрный воск", "янтарный воск")
    private val INITIALS = listOf("АК", "БР", "ВЛ", "ГС", "ДМ", "КТ", "НР", "ПФ")
}

private fun verdictKeys(): Set<String> = InvestigationVerdict.entries.map(InvestigationVerdict::commandValue).toSet()

private fun validateLegacyText(value: String) {
    require(value.isNotBlank() && value.length <= 140 && value.none(Char::isISOControl)) { "Invalid investigation text" }
}

private fun validateNarrativeText(value: String, maxLength: Int) {
    require(value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl)) { "Invalid investigation narrative text" }
}
