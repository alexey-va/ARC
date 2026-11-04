package ru.arc.graves

import com.google.gson.GsonBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import java.nio.file.Files
import java.nio.file.Path

/** Offline geometry export; the renderer consumes the same plaque and layout used by GravesHud. */
object GraveAppearancePreview {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: GraveAppearancePreview <output.json>" }
        val epitaph = "Очень длинная надпись на память о последнем приключении и о верных друзьях рядом"
        val owner = ".WWWWWWWWWWWWWWWW"
        val scenes = GraveModels.all.map { model ->
            mapOf(
                "id" to model.id,
                "engraved" to model.engraved,
                "parts" to model.parts,
                "inscription" to model.inscription,
                "placements" to model.inscription.placements(epitaph, owner).map { placement ->
                    val layout = DialogTextLayout.layout(Component.text(placement.text), TextAlignment.LEFT, placement.lineWidth + 8)
                        as TextLayoutResult.Aligned
                    val wrapped = StringBuilder()
                    PlainTextComponentSerializer.plainText().serialize(layout.component).codePoints().forEach { point ->
                        if (Character.getType(point) != Character.PRIVATE_USE.toInt()) wrapped.appendCodePoint(point)
                    }
                    mapOf("text" to placement.text, "bottomY" to placement.bottomY, "scale" to placement.scale,
                        "lineWidth" to placement.lineWidth, "lineCount" to layout.lineCount, "lines" to wrapped.toString().split('\n'))
                },
            )
        }
        Files.writeString(Path.of(args[0]), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "epitaph" to epitaph, "owner" to owner, "models" to scenes,
            "scope" to "Production geometry and text layout; offline rendering does not establish native-client appearance.",
        )))
    }
}
