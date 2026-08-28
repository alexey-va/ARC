package ru.arc.investigation

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.Logging.warn
import java.nio.file.Path
import kotlin.random.Random

internal data class GeneratedInvestigationStory(
    val seller: String,
    val goods: String,
    val correctVerdict: InvestigationVerdict,
    val narrative: InvestigationNarrative,
)

internal data class InvestigationWitnessDefinition(
    val key: String,
    val displayName: String,
    val locationHint: String,
    val itemMaterial: String,
) {
    fun snapshot(bit: Int): InvestigationWitness {
        require(Material.matchMaterial(itemMaterial) != null) { "Invalid investigation witness material: $itemMaterial" }
        return InvestigationWitness(key, displayName, locationHint, itemMaterial, bit).validated()
    }
}

private data class StoryTimelineTemplate(
    val order: Int,
    val time: String,
    val witness: String,
    val event: String,
)

private data class StoryCrossCheckTemplate(
    val first: String,
    val second: String,
    val insight: String,
)

private data class StoryConclusionTemplate(
    val title: String,
    val explanation: List<String>,
    val correct: Boolean,
)

private data class StoryTemplate(
    val plotId: String,
    val seller: String,
    val goods: String,
    val title: String,
    val briefing: List<String>,
    val requester: String,
    val stakes: List<String>,
    val question: String,
    val suspiciousLead: String,
    val variables: Map<String, List<String>>,
    val timeline: List<StoryTimelineTemplate>,
    val testimonies: Map<String, List<String>>,
    val crossChecks: List<StoryCrossCheckTemplate>,
    val conclusions: List<StoryConclusionTemplate>,
) {
    val witnessKeys: List<String> get() = timeline.sortedBy(StoryTimelineTemplate::order).map(StoryTimelineTemplate::witness)

    fun validated(
        witnesses: Map<String, InvestigationWitnessDefinition>,
        globalVariables: Map<String, List<String>>,
    ): StoryTemplate {
        require(PLOT_ID.matches(plotId)) { "Invalid investigation plot id: $plotId" }
        require(briefing.size in 3..5) { "$plotId briefing must have 3..5 lines" }
        require(stakes.size in 2..3) { "$plotId stakes must have 2..3 lines" }
        require(timeline.size == InvestigationNarrative.WITNESS_COUNT) { "$plotId timeline must have five events" }
        require(timeline.map(StoryTimelineTemplate::order) == (1..5).toList()) { "$plotId timeline order must be 1..5" }
        require(witnessKeys.distinct().size == InvestigationNarrative.WITNESS_COUNT) { "$plotId must use five distinct witnesses" }
        require(witnessKeys.all(witnesses::containsKey)) { "$plotId uses an unknown witness" }
        require(testimonies.keys == witnessKeys.toSet()) { "$plotId testimony roster is incomplete" }
        require(testimonies.values.all { it.size in 3..5 }) { "$plotId testimony must have 3..5 lines per witness" }
        require(crossChecks.size >= InvestigationNarrative.WITNESS_COUNT) { "$plotId needs at least five cross-checks" }
        require(crossChecks.all { it.first in witnessKeys && it.second in witnessKeys && it.first != it.second }) {
            "$plotId cross-check uses an invalid witness pair"
        }
        require(crossChecks.map { setOf(it.first, it.second) }.distinct().size == crossChecks.size) {
            "$plotId cross-check pairs must be unique"
        }
        require(conclusions.size == InvestigationVerdict.entries.size) { "$plotId must have five conclusions" }
        require(conclusions.count(StoryConclusionTemplate::correct) == 1) { "$plotId must have one correct conclusion" }

        val availableVariables = globalVariables.keys + variables.keys + BUILTIN_VARIABLES
        val unknown = allText().flatMap(PLACEHOLDER::findAll).map { it.groupValues[1] }.filterNot(availableVariables::contains).toSet()
        require(unknown.isEmpty()) { "$plotId uses unknown placeholders: ${unknown.sorted()}" }
        allText().forEach { validateTemplateText(it, plotId) }
        return this
    }

    fun render(
        random: Random,
        caseNumber: String,
        witnessDefinitions: Map<String, InvestigationWitnessDefinition>,
        globalVariables: Map<String, List<String>>,
    ): GeneratedInvestigationStory {
        val pools = globalVariables + variables
        val context = pools.mapValues { (_, values) -> values.random(random) }.toMutableMap()
        context["case"] = caseNumber
        val render: (String) -> String = { source -> renderText(source, context) }

        val roster =
            witnessKeys.mapIndexed { index, key ->
                requireNotNull(witnessDefinitions[key]).snapshot(1 shl index)
            }
        val renderedConclusions = conclusions.shuffled(random)
        val conclusionSlots = linkedMapOf<String, InvestigationConclusion>()
        var correctVerdict: InvestigationVerdict? = null
        InvestigationVerdict.entries.forEachIndexed { index, verdict ->
            val source = renderedConclusions[index]
            conclusionSlots[verdict.commandValue] = InvestigationConclusion(render(source.title), source.explanation.map(render))
            if (source.correct) correctVerdict = verdict
        }
        val correct = requireNotNull(correctVerdict)
        val narrative =
            InvestigationNarrative(
                schemaVersion = InvestigationNarrative.CURRENT_SCHEMA,
                plotId = plotId,
                title = render(title),
                briefing = briefing.map(render),
                question = render(question),
                suspiciousLead = render(suspiciousLead),
                timeline = timeline.map { InvestigationTimelineBeat(it.order, render(it.time), it.witness, render(it.event)) },
                testimonies = testimonies.mapValues { (_, lines) -> InvestigationTestimony(lines.map(render)) },
                crossChecks = crossChecks.map { InvestigationCrossCheck(it.first, it.second, render(it.insight)) },
                conclusions = conclusionSlots,
                witnesses = roster,
                requester = render(requester),
                stakes = stakes.map(render),
            ).validated(correct)
        return GeneratedInvestigationStory(render(seller), render(goods), correct, narrative)
    }

    private fun allText(): List<String> =
        listOf(seller, goods, title, requester, question, suspiciousLead) +
            briefing +
            stakes +
            variables.values.flatten() +
            timeline.flatMap { listOf(it.time, it.event) } +
            testimonies.values.flatten() +
            crossChecks.map(StoryCrossCheckTemplate::insight) +
            conclusions.flatMap { listOf(it.title) + it.explanation }

    companion object {
        private val PLOT_ID = Regex("[a-z0-9][a-z0-9_-]{2,47}")
        private val BUILTIN_VARIABLES = setOf("case")
    }
}

/**
 * Fully configuration-owned authored plot catalog. Code only parses, validates,
 * renders placeholders and shuffles the five persisted verdict slots.
 */
internal class InvestigationStoryCatalog private constructor(
    val witnesses: Map<String, InvestigationWitnessDefinition>,
    private val globalVariables: Map<String, List<String>>,
    private val stories: List<StoryTemplate>,
) {
    val plotIds: Set<String> = stories.map(StoryTemplate::plotId).toSet()
    val witnessKeys: Set<String> = witnesses.keys
    val storyCount: Int get() = stories.size

    fun generate(
        random: Random,
        caseNumber: String,
        excludedPlotId: String? = null,
    ): GeneratedInvestigationStory {
        val eligibleStories = stories.filterNot { it.plotId == excludedPlotId }.ifEmpty { stories }
        return eligibleStories.random(random).render(random, caseNumber, witnesses, globalVariables)
    }

    fun generatePlot(
        plotId: String,
        random: Random,
        caseNumber: String,
    ): GeneratedInvestigationStory =
        requireNotNull(stories.firstOrNull { it.plotId == plotId }) { "Unknown investigation plot: $plotId" }
            .render(random, caseNumber, witnesses, globalVariables)

    companion object {
        const val RESOURCE = "investigation-cases.yml"
        private val KEY_PATTERN = Regex("[a-z][a-z0-9_-]{2,31}")

        fun load(dataPath: Path): InvestigationStoryCatalog =
            parse(ConfigManager.ofModule(dataPath, RESOURCE))

        internal fun parse(config: Config): InvestigationStoryCatalog {
            require(config.int("schema-version", 0) == 1) { "Unsupported investigation catalog schema" }
            val variables = parseVariables(config, "variables")
            require("seller" in variables && "goods" in variables) { "Investigation catalog needs seller and goods variables" }

            val witnesses =
                config.keys("witnesses").sorted().associateWith { key ->
                    require(KEY_PATTERN.matches(key)) { "Invalid investigation witness key: $key" }
                    val root = "witnesses.$key"
                    InvestigationWitnessDefinition(
                        key = key,
                        displayName = requiredString(config, "$root.name"),
                        locationHint = requiredString(config, "$root.location"),
                        itemMaterial = requiredString(config, "$root.material").uppercase(),
                    ).also { it.snapshot(1) }
                }
            require(witnesses.size in 5..24) { "Investigation catalog needs 5..24 witnesses" }

            val stories =
                config.keys("cases").sorted().mapNotNull { plotId ->
                    runCatching { parseStory(config, plotId).validated(witnesses, variables) }
                        .onFailure { warn("Skipping investigation case {}: {}", plotId, it.message ?: it::class.java.simpleName) }
                        .getOrNull()
                }
            require(stories.isNotEmpty()) { "Investigation catalog contains no valid cases" }
            require(stories.map(StoryTemplate::plotId).distinct().size == stories.size) { "Investigation case ids must be unique" }
            return InvestigationStoryCatalog(witnesses, variables, stories).also { catalog ->
                catalog.stories.forEachIndexed { index, story ->
                    story.render(Random(index + 1), "А-${1000 + index}", witnesses, variables)
                }
            }
        }

        private fun parseStory(config: Config, plotId: String): StoryTemplate {
            val root = "cases.$plotId"
            val timeline =
                config.keys("$root.timeline").map { rawOrder ->
                    val order = rawOrder.toIntOrNull() ?: error("$plotId has non-numeric timeline key: $rawOrder")
                    val entry = "$root.timeline.$rawOrder"
                    StoryTimelineTemplate(
                        order = order,
                        time = requiredString(config, "$entry.time"),
                        witness = requiredString(config, "$entry.witness").lowercase(),
                        event = requiredString(config, "$entry.event"),
                    )
                }.sortedBy(StoryTimelineTemplate::order)
            val testimonies =
                config.keys("$root.testimonies").associateWith { witness ->
                    config.stringList("$root.testimonies.$witness").also { require(it.isNotEmpty()) { "$plotId testimony for $witness is empty" } }
                }
            val crossChecks =
                config.keys("$root.cross-checks").sorted().map { checkId ->
                    val entry = "$root.cross-checks.$checkId"
                    val pair = config.stringList("$entry.witnesses")
                    require(pair.size == 2) { "$plotId cross-check $checkId needs two witnesses" }
                    StoryCrossCheckTemplate(pair[0].lowercase(), pair[1].lowercase(), requiredString(config, "$entry.insight"))
                }
            val conclusions =
                config.keys("$root.conclusions").sorted().map { conclusionId ->
                    val entry = "$root.conclusions.$conclusionId"
                    StoryConclusionTemplate(
                        title = requiredString(config, "$entry.title"),
                        explanation = config.stringList("$entry.explanation"),
                        correct = config.boolean("$entry.correct", false),
                    )
                }
            return StoryTemplate(
                plotId = plotId,
                seller = config.stringOrNull("$root.seller") ?: "{seller}",
                goods = config.stringOrNull("$root.goods") ?: "{goods}",
                title = requiredString(config, "$root.title"),
                briefing = config.stringList("$root.briefing"),
                requester = requiredString(config, "$root.requester"),
                stakes = config.stringList("$root.stakes"),
                question = requiredString(config, "$root.question"),
                suspiciousLead = requiredString(config, "$root.suspicious-lead"),
                variables = parseVariables(config, "$root.variables"),
                timeline = timeline,
                testimonies = testimonies,
                crossChecks = crossChecks,
                conclusions = conclusions,
            )
        }

        private fun parseVariables(config: Config, root: String): Map<String, List<String>> =
            config.keys(root).sorted().associateWith { key ->
                require(KEY_PATTERN.matches(key)) { "Invalid investigation variable key: $key" }
                config.stringList("$root.$key").map(String::trim).also { values ->
                    require(values.isNotEmpty() && values.none(String::isBlank)) { "Investigation variable $key is empty" }
                    values.forEach {
                        validateTemplateText(it, "variable $key")
                        require(PLACEHOLDER.find(it) == null) { "Investigation variable $key cannot contain placeholders" }
                    }
                }
            }

        private fun requiredString(config: Config, path: String): String =
            requireNotNull(config.stringOrNull(path)?.trim()?.takeIf(String::isNotEmpty)) { "Missing investigation field: $path" }
    }
}

private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_-]{1,31})}")

private fun renderText(
    source: String,
    context: Map<String, String>,
): String {
    val rendered = PLACEHOLDER.replace(source) { match -> requireNotNull(context[match.groupValues[1]]) }
    require(PLACEHOLDER.find(rendered) == null) { "Unresolved investigation placeholder" }
    return rendered
}

private fun validateTemplateText(
    value: String,
    owner: String,
) {
    require(value.isNotBlank() && value.length <= 220 && value.none(Char::isISOControl)) { "Invalid investigation text in $owner" }
}
