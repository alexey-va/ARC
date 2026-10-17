package ru.arc.hooks.elitemobs.arena

import com.google.gson.GsonBuilder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.inventory.ClickType
import java.nio.file.Files
import java.nio.file.Path

/** Exports production ItemMeta from the same shared Paper runtime exercised by the click tests. */
object NativeArenaMenuPreviewExport {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: NativeArenaMenuPreviewExport <native-menu.yml> <output-dir>" }
        val output = Path.of(args[1])
        Files.createDirectories(output)
        val contents = linkedMapOf<String, Any>()
        val slots = linkedMapOf<String, Any>()
        val legacy = LegacyComponentSerializer.legacySection()
        ArenaMenuHarness(nativeMenu = YamlConfiguration.loadConfiguration(Path.of(args[0]).toFile())).use { h ->
            val player = h.paper.addPlayer("Preview")
            fun export(state: String) {
                val inventory = player.openInventory.topInventory
                val texts = linkedMapOf<String, Any>("title" to legacy.serialize(player.openInventory.title()))
                val items = mutableListOf<Map<String, Any>>()
                val stateSlots = linkedMapOf<Int, String>()
                for (slot in 0..8) {
                    val item = inventory.getItem(slot)
                    stateSlots[slot] = item?.type?.name ?: "AIR"
                    if (item == null) continue
                    texts["name-$slot"] = legacy.serialize(requireNotNull(item.itemMeta.displayName()))
                    texts["lore-$slot"] = item.lore().orEmpty().map(legacy::serialize)
                    items += mapOf("name" to "$state:name-$slot", "lore" to "$state:lore-$slot",
                        "material" to item.type.name, "slot" to slot, "id" to "$state-slot-$slot")
                }
                contents[state] = texts
                slots[state] = stateSlots
                write(output.resolve("$state-manifest.json"), mapOf(
                    "schema" to 1, "plugin" to "ArcDungeons stock arena $state", "minecraft-version" to "26.1.2",
                    "minecraft-client-sha1" to "4e618f09a0c649dde3fdf829df443ce0b8831e65",
                    "sources" to mapOf(state to mapOf("path" to "content.json", "root" to state, "include" to listOf("*"))),
                    "surfaces" to mapOf("items" to items, "fragments" to listOf(mapOf("source" to state, "include" to listOf("title")))),
                    "limitations" to listOf("Actual production ItemMeta in MockBukkit, not a Minecraft client capture. Empty slots recorded separately."),
                ))
            }
            h.menus.open(player, "test.yml")
            repeat(3) { h.click(player, 5, ClickType.SHIFT_LEFT) }
            export("editable")
            h.levels.lobby(h.arena).admit(81) { true }
            h.menus.refresh()
            export("locked")
            h.levels.lobby(h.arena).reset()
            h.originalOnly()
            h.menus.open(player, "test.yml")
            export("original")
        }
        write(output.resolve("content.json"), contents)
        write(output.resolve("slot-map.json"), slots)
        println("WROTE $output")
    }

    private fun write(path: Path, value: Any) = Files.writeString(path,
        GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(value) + "\n")
}
