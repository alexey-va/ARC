package ru.arc.dialogdemo

import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry
import io.papermc.paper.registry.data.dialog.input.TextDialogInput.MultilineOptions
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.set.RegistrySet
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.api.BinaryTagHolder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import java.time.Duration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.util.TextUtil
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment

/**
 * Stateless native API specimens, deliberately outside the production menu model:
 * that model exposes only multi-action/text screens. Fixed navigation and bounded
 * response echoes never mutate inventory, permissions, economy or other players.
 * No per-view callbacks, player sessions or asynchronous reopening are retained.
 */
object DialogDemoModule : PluginModule, Listener {
    override val name = "DialogDemo"
    override val priority = 87
    private lateinit var config: Config
    val pages = listOf("root", "alignment", "tables", "dividers", "text", "hover", "items", "inputs", "input-options", "actions",
        "template", "custom", "callbacks", "notice", "confirmation", "list", "links", "layout", "scroll", "lifecycle", "limits")

    override fun init() {
        reload()
        ARC.instance.server.pluginManager.registerEvents(this, ARC.instance)
    }
    override fun reload() {
        config = ConfigManager.of(ARC.instance.dataPath, "modules/dialog-demo.yml")
        config.mergeMissingFromBundled("modules/dialog-demo.yml")
    }
    override fun shutdown() = HandlerList.unregisterAll(this)

    private fun text(key: String) = TextUtil.mm(config.string(key, key))
    private fun body(key: String, width: Int = 420) = DialogBody.plainMessage(text(key), width)
    private fun action(event: ClickEvent) = DialogAction.staticAction(event)
    private fun command(page: String) = action(ClickEvent.runCommand("/arc dialogdemo $page"))
    private fun custom(id: String, payload: String = "{}") =
        DialogAction.customClick(Key.key("arc:dialog_demo/$id"), BinaryTagHolder.binaryTagHolder(payload))
    private fun button(key: String, action: DialogAction? = null, width: Int = 205) =
        ActionButton.builder(text(key)).width(width).tooltip(text("$key-tip")).action(action).build()
    private fun nav(page: String) = button("nav.$page", command(page))
    private fun back(parent: String = "root") = button("back", command(parent), 200)
    private fun dialog(title: String, bodies: List<DialogBody>, type: DialogType,
        inputs: List<DialogInput> = emptyList(), after: DialogBase.DialogAfterAction = DialogBase.DialogAfterAction.NONE,
        escape: Boolean = true, pause: Boolean = false, external: String = title): Dialog = Dialog.create { factory ->
        factory.empty().base(DialogBase.builder(text(title)).externalTitle(text(external))
            .body(bodies).inputs(inputs).pause(pause).canCloseWithEscape(escape).afterAction(after).build()).type(type)
    }
    private fun multi(title: String, bodies: List<DialogBody>, buttons: List<ActionButton> = emptyList(),
        inputs: List<DialogInput> = emptyList(), columns: Int = 2, parent: String = "root") =
        dialog(title, bodies, DialogType.multiAction(buttons + back(parent), button("close", custom("close"), 200), columns), inputs)

    private fun gallery(family: String, page: Int): Dialog {
        val tables = family == "tables"
        val perPage = if (tables) 3 else 6
        val itemCount = if (tables) DialogDesignGallery.TABLE_COUNT else DialogDesignGallery.DIVIDER_COUNT
        val pageCount = (itemCount + perPage - 1) / perPage
        val bodies = mutableListOf(body("gallery.$family.intro"))
        bodies += DialogBody.plainMessage(text("gallery.page").replaceText {
            it.matchLiteral("%page%").replacement("${page + 1} / $pageCount")
        }, 400)
        val last = minOf((page + 1) * perPage, itemCount)
        ((page * perPage + 1)..last).forEach { number ->
            bodies += body("gallery.$family.$number.title")
            bodies += DialogBody.plainMessage(if (tables) DialogDesignGallery.table(number, ::text)
                else DialogDesignGallery.divider(number, ::text), 400)
            bodies += body("gallery.$family.$number.note")
        }
        val buttons = (0 until pageCount).map { index ->
            val label = text("gallery.page-button").replaceText { it.matchLiteral("%page%").replacement((index + 1).toString()) }
                .color(net.kyori.adventure.text.format.TextColor.color(if (index == page) 0x9BD48D else 0x92BED8))
            ActionButton.builder(label).width(96).tooltip(text("gallery.$family.page-${index + 1}"))
                .action(command(if (index == 0) family else "$family-${index + 1}")).build()
        }
        return dialog("title.$family", bodies, DialogType.multiAction(buttons, back(), minOf(pageCount, 3)))
    }
    private fun item(material: Material, description: String, decorations: Boolean = true, tooltip: Boolean = true,
        width: Int = 16, height: Int = 16, amount: Int = 1): DialogBody {
        val stack = ItemStack(material, amount)
        stack.editMeta { meta ->
            meta.displayName(text("items.name"))
            meta.lore(listOf(text("items.lore")))
            if (meta is Damageable) meta.damage = material.maxDurability.toInt() / 2
        }
        return DialogBody.item(stack).description(DialogBody.plainMessage(text(description), 385))
            .showDecorations(decorations).showTooltip(tooltip).width(width).height(height).build()
    }

    fun open(player: Player, page: String = "root", value: String = "") {
        val screen = when (page) {
            "tables", "tables-2", "tables-3", "tables-4", "tables-5", "tables-6" -> gallery("tables", page.substringAfter('-', "1").toInt() - 1)
            "dividers", "dividers-2", "dividers-3" -> gallery("dividers", page.substringAfter('-', "1").toInt() - 1)
            "root" -> dialog("title.root", listOf(body("intro")),
                DialogType.multiAction(pages.drop(1).map(::nav), button("close", custom("close"), 200), 2))
            "alignment" -> multi("title.alignment", listOf(
                body("alignment.intro"),
                body("alignment.left"), DialogTextLayout.body(text("alignment.sample"), TextAlignment.LEFT),
                body("alignment.center"), DialogTextLayout.body(text("alignment.sample"), TextAlignment.CENTER),
                body("alignment.right"), DialogTextLayout.body(text("alignment.sample"), TextAlignment.RIGHT),
                body("alignment.padding-intro"),
                DialogTextLayout.body(paddingSample(), TextAlignment.LEFT)), listOf(nav("tables"), nav("dividers")))
            "text" -> multi("title.text", listOf(body("text.styles"), body("text.colors"), body("text.sections"),
                DialogBody.plainMessage(Component.translatable("block.minecraft.diamond_block"), 420),
                DialogBody.plainMessage(Component.keybind("key.swapOffhand"), 420), body("text.font")))
            "hover" -> multi("title.hover", listOf(body("hover.intro"),
                DialogBody.plainMessage(text("hover.text").hoverEvent(HoverEvent.showText(text("hover.detail")))),
                DialogBody.plainMessage(text("hover.item").hoverEvent(ItemStack(Material.DIAMOND, 3).asHoverEvent())),
                DialogBody.plainMessage(text("hover.entity").hoverEvent(player.asHoverEvent())),
                DialogBody.plainMessage(text("hover.copy").clickEvent(ClickEvent.copyToClipboard("RusCrafting Dialog API"))),
                DialogBody.plainMessage(text("hover.insert").insertion("RusCrafting"))))
            "items" -> multi("title.items", listOf(body("items.intro"),
                item(Material.DIAMOND, "items.stack", amount = 32),
                item(Material.DIAMOND_PICKAXE, "items.damage"),
                item(Material.DIAMOND_PICKAXE, "items.hidden", decorations = false, tooltip = false),
                item(Material.BOOK, "items.size", width = 48, height = 48),
                DialogBody.item(ItemStack(Material.EMERALD)).build()))
            "inputs" -> multi("title.inputs", listOf(body("inputs.intro")),
                listOf(button("submit", custom("form"))), listOf(
                    DialogInput.text("name", text("inputs.name")).initial("Игрок").maxLength(32).width(350).build(),
                    DialogInput.text("notes", text("inputs.notes")).initial("Первая строка\nВторая строка")
                        .maxLength(256).width(350).multiline(MultilineOptions.create(5, 65)).build(),
                    DialogInput.bool("enabled", text("inputs.enabled")).initial(true).onTrue("on").onFalse("off").build(),
                    DialogInput.singleOption("choice", text("inputs.choice"), options()).width(350).build(),
                    DialogInput.numberRange("amount", text("inputs.amount"), 0f, 100f).width(350)
                        .initial(25f).step(5f).labelFormat("%s: %s").build()))
            "input-options" -> multi("title.input-options", listOf(body("input-options.intro")),
                listOf(button("submit", custom("variants"))), listOf(
                    DialogInput.text("hidden", text("inputs.name")).labelVisible(false).initial("Без подписи · лимит 24")
                        .maxLength(24).width(220).build(),
                    DialogInput.text("autoheight", text("input-options.autoheight")).maxLength(128)
                        .multiline(MultilineOptions.create(3, null)).width(350).build(),
                    DialogInput.bool("flag", text("input-options.flag")).initial(false).build(),
                    DialogInput.singleOption("variant", text("inputs.choice"), options()).labelVisible(false).width(220).build(),
                    DialogInput.numberRange("continuous", text("input-options.continuous"), -1f, 1f).width(350).build()))
            "actions" -> multi("title.actions", listOf(body("actions.intro")), listOf(
                button("actions.copy", action(ClickEvent.copyToClipboard("/arc dialogdemo"))),
                button("actions.suggest", action(ClickEvent.suggestCommand("/arc dialogdemo alignment"))),
                button("actions.run", command("notice")),
                button("actions.url", action(ClickEvent.openUrl("https://docs.papermc.io/paper/dev/dialogs/"))),
                button("actions.inline", action(ClickEvent.showDialog(notice()))),
                button("actions.custom", custom("ack", "{source:static,sample:7}")),
                button("actions.none"),
                button("actions.page", action(ClickEvent.changePage(2)))))
            "template" -> multi("title.template", listOf(body("template.intro")),
                listOf(button("submit", DialogAction.commandTemplate("/arc dialogdemo receipt $(amount) $(enabled)"))),
                listOf(DialogInput.numberRange("amount", text("inputs.amount"), 1f, 10f).initial(3f).step(1f).build(),
                    DialogInput.bool("enabled", text("inputs.enabled"), true, "on", "off")))
            "receipt" -> multi("title.receipt", listOf(body("receipt.intro"),
                DialogBody.plainMessage(Component.text(safe(value)), 420)), parent = "template")
            "custom" -> multi("title.custom", listOf(body("custom.intro")),
                listOf(button("custom.echo", custom("payload", "{source:demo,sample:7}"))),
                listOf(DialogInput.text("message", text("custom.message")).maxLength(128).build()))
            "callbacks" -> {
                val owner = player.uniqueId
                val callback = DialogAction.customClick({ view, audience ->
                    val viewer = audience as? Player
                    if (viewer?.uniqueId == owner && ARC.instance.isEnabled) {
                        response(viewer, "callback.success", listOf(safe(view.getText("message") ?: "—")), "callbacks")
                    }
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofSeconds(60)).build())
                multi("title.callbacks", listOf(body("callback.intro")), listOf(button("callback.try", callback)),
                    listOf(DialogInput.text("message", text("custom.message")).maxLength(128).build()))
            }
            "notice" -> notice()
            "notice-default" -> dialog("title.notice", listOf(body("notice.default")), DialogType.notice(), after = DialogBase.DialogAfterAction.CLOSE)
            "confirmation" -> dialog("title.confirmation", listOf(body("confirmation.intro")),
                DialogType.confirmation(button("yes", command("accepted")), button("no", command("cancelled"))))
            "accepted", "cancelled" -> multi("title.confirmation", listOf(body("confirmation.$page")), parent = "confirmation")
            "list" -> dialog("title.list", listOf(body("list.intro"),
                DialogBody.plainMessage(text("list.builtin-quick").clickEvent(ClickEvent.showDialog(Dialog.QUICK_ACTIONS))),
                DialogBody.plainMessage(text("list.builtin-options").clickEvent(ClickEvent.showDialog(Dialog.CUSTOM_OPTIONS))),
                DialogBody.plainMessage(text("list.builtin-links").clickEvent(ClickEvent.showDialog(Dialog.SERVER_LINKS)))),
                DialogType.dialogList(RegistrySet.valueSet(RegistryKey.DIALOG, listOf(
                    dialog("list.first-title", listOf(body("list.first-body")), DialogType.notice(back("list")), external = "list.first-external"),
                    dialog("list.second-title", listOf(body("list.second-body")), DialogType.notice(back("list")), external = "list.second-external")
                )), back(), 2, 205))
            "links" -> dialog("title.links", listOf(body("links.intro")), DialogType.serverLinks(back(), 2, 205))
            "layout", "columns1", "columns3" -> multi("title.layout", listOf(body("layout.intro"), body("layout.narrow", 180),
                body("layout.wide", 420)), listOf(button("layout.one", command("columns1")),
                button("layout.two", command("layout")), button("layout.three", command("columns3")),
                button("layout.small", custom("noop"), 90), button("layout.medium", custom("noop"), 150),
                button("layout.large", custom("noop"), 205)), columns = when(page) { "columns1" -> 1; "columns3" -> 3; else -> 2 })
            "scroll" -> multi("title.scroll", (1..18).map { DialogBody.plainMessage(
                Component.text("$it. ").append(text("scroll.paragraph")), 420) },
                (1..12).map { ActionButton.builder(Component.text("$it · ").append(text("scroll.button")))
                    .width(205).action(command("notice")).build() })
            "lifecycle" -> multi("title.lifecycle", listOf(body("lifecycle.intro")), listOf(
                button("lifecycle.none", command("after-none")), button("lifecycle.close", command("after-close")),
                button("lifecycle.wait", command("after-wait")), button("lifecycle.esc", command("escape-off")),
                button("lifecycle.pause", command("pause"))))
            "after-none", "after-close", "after-wait", "escape-off", "pause" -> dialog("title.lifecycle",
                listOf(body(if (page == "pause") "lifecycle.pause-body" else "lifecycle.$page")), DialogType.multiAction(listOf(button("lifecycle.try", custom("noop"))),
                    back("lifecycle"), 1), after = when(page) {
                        "after-close", "pause" -> DialogBase.DialogAfterAction.CLOSE
                        "after-wait" -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE
                        else -> DialogBase.DialogAfterAction.NONE
                    }, escape = page != "escape-off", pause = page == "pause")
            else -> multi("title.limits", listOf(body("limits.body")))
        }
        player.showDialog(screen)
    }

    private fun paddingSample(): Component {
        val result = Component.text()
        listOf(0, 8, 16, 32).forEachIndexed { index, pixels ->
            if (index > 0) result.append(Component.newline())
            result.append(text("alignment.padding-marker"))
            result.append(DialogTextLayout.spacing.padding(pixels))
            result.append(text("alignment.padding-sample").replaceText {
                it.matchLiteral("%pixels%").replacement(pixels.toString())
            })
        }
        return result.build()
    }

    private fun options() = listOf(OptionEntry.create("build", text("inputs.build"), true),
        OptionEntry.create("explore", text("inputs.explore"), false), OptionEntry.create("trade", text("inputs.trade"), false))
    private fun notice() = dialog("title.notice", listOf(body("notice.intro"), DialogBody.plainMessage(text("notice.default-link").clickEvent(ClickEvent.runCommand("/arc dialogdemo notice-default")))), DialogType.notice(back()))

    @EventHandler
    fun onClick(event: PlayerCustomClickEvent) {
        if (event.identifier.namespace() != "arc" || !event.identifier.value().startsWith("dialog_demo/")) return
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        when (event.identifier.value().substringAfter('/')) {
            "close" -> player.closeDialog()
            "noop" -> Unit
            "ack" -> response(player, "custom.ack", emptyList(), "actions")
            "form" -> response(player, "inputs.result", read(event.dialogResponseView,
                listOf("name", "notes", "choice"), listOf("enabled"), listOf("amount")), "inputs")
            "variants" -> response(player, "inputs.result", read(event.dialogResponseView,
                listOf("hidden", "autoheight", "variant"), listOf("flag"), listOf("continuous")), "input-options")
            "payload" -> response(player, "custom.result", read(event.dialogResponseView,
                listOf("message", "source"), emptyList(), listOf("sample")), "custom")
        }
    }
    private fun read(view: DialogResponseView?, texts: List<String>, bools: List<String>, floats: List<String>): List<String> =
        texts.map { "$it: ${safe(view?.getText(it) ?: "—")}" } +
            bools.map { "$it: ${view?.getBoolean(it) ?: "—"}" } +
            floats.map { "$it: ${view?.getFloat(it)?.takeIf(Float::isFinite) ?: "—"}" }
    private fun response(player: Player, title: String, lines: List<String>, parent: String) {
        player.showDialog(multi(title, listOf(DialogBody.plainMessage(Component.text(lines.joinToString("\n\n")), 420)), parent = parent))
    }
    internal fun safe(value: String): String = value.take(512).filter { it == '\n' || (!it.isISOControl() && it != '§') }
}
