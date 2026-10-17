package ru.arc.hooks.elitemobs

import com.google.gson.GsonBuilder
import com.magmaguy.elitemobs.advancedcombat.challenges.ClassChallengeInstance
import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.config.EconomySettingsConfig
import com.magmaguy.elitemobs.economy.EconomyHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.ConfigManager
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path

/** Captures real menu components for the shared surface renderer; this is not native-client evidence. */
object DungeonClassesPreviewExport {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: DungeonClassesPreviewExport <output-directory>" }
        val output = Path.of(args.single()).toAbsolutePath()
        Files.createDirectories(output)
        val configPath = Files.createTempDirectory("class-menu-preview")
        val source = requireNotNull(javaClass.getResourceAsStream("/modules/elitemobs.yml"))
        source.use { Files.copy(it, configPath.resolve("elitemobs.yml")) }
        val config = ConfigManager.create(configPath, "elitemobs.yml", "class-preview-${configPath.fileName}")
        config.load()
        val localization = DungeonClassLocalization(config)
        val catalog = BuiltInClassContent.catalog()
        val mm = MiniMessage.miniMessage()
        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
        fun serialize(component: Component): String {
            fun verifyFont(node: Component) {
                require(node.font() == null || node.font()?.asString() == "minecraft:default") { "Preview cannot flatten a custom font" }
                node.children().forEach(::verifyFont)
            }
            verifyFont(component)
            return legacy.serialize(component)
        }
        val captures = linkedMapOf<String, PaperDialogScreen>()
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("class-preview")
            for ((key, id, level, unlocked, atTrainer) in listOf(
                PreviewState("occultist", "occultist", 39, true),
                PreviewState("necromancer", "necromancer", 61, false),
                PreviewState("necromancer-trainer", "necromancer", 61, false, true),
            )) {
                val forms = catalog.forms().associate { definition ->
                    val effective = if (definition.id() == id) level else if (atTrainer && definition.id() == "occultist") 60
                        else definition.band().effectiveStart()
                    val lineage = catalog.lineageOf(definition.id())
                    val contributions = lineage.passiveContributionLevels(effective)
                    definition.id() to DungeonClassForm(
                        id = definition.id(), name = localization.formName(definition.id(), definition.displayName()),
                        rootId = catalog.rootOf(definition.id()).id(), parentId = definition.parentId(),
                        children = catalog.childrenOf(definition.id()).map { it.id() },
                        path = lineage.forms().map { localization.formName(it.id(), it.displayName()) },
                        unlocked = definition.id() != id || unlocked, selected = definition.id() == id && unlocked,
                        active = definition.id() == id && unlocked, requiredLevel = definition.band().effectiveStart(),
                        level = effective, cap = minOf(100, definition.band().effectiveEnd()), xp = if (unlocked) "450 / 1200" else "Недоступен",
                        foundations = emptyList(), blockers = if (unlocked || definition.id() != id) emptyList() else if (atTrainer)
                            listOf(DungeonClassRequirement("Испытание", "Победить наставника этого класса")) else listOf(
                            DungeonClassRequirement("Оккультист", "39 / 60"),
                            DungeonClassRequirement("Посохи", "48 / 60"), DungeonClassRequirement("Жезлы", "52 / 60"),
                            DungeonClassRequirement("Испытание", "Победить наставника этого класса"),
                        ),
                        weapons = definition.weaponAffinities().map(::dungeonSkillName),
                        passives = lineage.forms().map { localization.passive(it.id(), it.displayName(), contributions.getValue(it.id())) },
                    )
                }
                val service = mockk<DungeonClassService>()
                every { service.view(player) } returns DungeonClassesView(
                    DungeonClassAvailability.READY, activeFormId = if (unlocked) id else null,
                    roots = listOf(id), forms = forms,
                )
                val dungeon = mockk<EMDungeonQol>(relaxed = true)
                every { dungeon.text(any(), any(), *anyVararg()) } answers {
                    val values = thirdArg<Array<out Pair<String, Component>>>()
                    mm.deserialize(config.string("dungeon-qol.${firstArg<String>()}", secondArg()),
                        TagResolver.resolver(values.map { (key, value) -> Placeholder.component(key, value) }))
                }
                val trials = mockk<DungeonClassTrials>()
                if (atTrainer) {
                    val form = catalog.require(id)
                    // Native configuration is not initialized by this isolated UI exporter.
                    // Use the source default explicitly while retaining the real native fee function.
                    mockkStatic(EconomySettingsConfig::class)
                    val fee = try {
                        every { EconomySettingsConfig.getCurrencyShowerMultiplier() } returns 1.0
                        ClassChallengeInstance.fee(form)
                    } finally { unmockkStatic(EconomySettingsConfig::class) }
                    every { trials.quote(player, id) } returns DungeonClassTrialQuote(id, fee, EconomyHandler.formatCurrency(fee),
                        form.band().skillUnlockLevel())
                }
                val screens = mutableListOf<PaperDialogScreen>()
                val menus = DungeonSaveMenus(dungeon, classService = service, classTrials = trials, canGrantClasses = { false }) {
                    _, screen, _ -> screens += screen
                }
                menus.classDetail(player, id, atTrainer = atTrainer)
                captures[key] = screens.last()
            }
        }
        val content = linkedMapOf<String, Map<String, String>>()
        val dialogs = captures.map { (id, screen) ->
            val fields = linkedMapOf("title" to serialize(screen.title))
            val body = screen.body.mapIndexed { index, item ->
                fields["body-$index"] = serialize(item.text)
                mapOf("id" to "body-$index", "text-key" to "body-$index", "width" to item.width)
            }
            fun button(button: ru.arc.paper.menu.PaperDialogButton): Map<String, Any> {
                val key = button.id.value
                fields["button-$key"] = serialize(button.label)
                fields["tip-$key"] = serialize(button.tooltip ?: Component.empty())
                return mapOf("id" to key, "text-key" to "button-$key", "tooltip-key" to "tip-$key", "width" to button.width)
            }
            val buttons = screen.buttons.map(::button)
            val exit = screen.exitButton?.let(::button)
            content[id] = fields
            buildMap<String, Any> {
                put("id", id); put("source", id); put("format", "reference-multi-action")
                put("name-key", "title"); put("columns", screen.columns); put("body", body); put("buttons", buttons)
                if (exit != null) put("exit-button", exit)
                put("viewport", listOf(854, 480)); put("variants", listOf(mapOf("id" to "default")))
            }
        }
        val json = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        Files.writeString(output.resolve("content.json"), json.toJson(content))
        Files.writeString(output.resolve("manifest.json"), json.toJson(mapOf(
            "schema" to 1, "plugin" to "ARC passive classes", "minecraft-version" to "26.1.2",
            "minecraft-client-sha1" to "4e618f09a0c649dde3fdf829df443ce0b8831e65",
            "resource-pack" to mapOf("url" to "https://storage.yandexcloud.net/ruscraftinresources/RusCraftingResource.zip",
                "sha256-url" to "https://storage.yandexcloud.net/ruscraftinresources/RusCraftingResource.zip.sha256"),
            "glyphs" to content.values.flatMap { it.values }.flatMap { it.codePoints().toArray().toList() }
                .filter { Character.getType(it) == Character.PRIVATE_USE.toInt() || it == 0x1F48E }.distinct().map {
                    mapOf("character" to String(Character.toChars(it)), "provider" to "minecraft:default")
                },
            "sources" to captures.keys.associateWith { mapOf("path" to "content.json", "root" to it, "include" to listOf("*")) },
            "surfaces" to mapOf("dialogs" to dialogs),
            "limitations" to listOf("Production components with representative profiles; native client and input are unverified.",
                "Trainer quote uses the native fee function with the source-default currency multiplier 1.0; live server economy is not observed here."),
        )))
        configPath.toFile().deleteRecursively()
        println("CLASS_PREVIEW ${captures.keys} $output")
    }

    private data class PreviewState(val key: String, val formId: String, val level: Int, val unlocked: Boolean, val atTrainer: Boolean = false)
}
