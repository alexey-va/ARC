package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.Zrips.CMI.Modules.Display.CMIBillboard
import com.Zrips.CMI.Modules.Display.CMITextAlignment
import com.Zrips.CMI.Modules.Holograms.CMIHologramType

internal sealed interface CmiHologramPatch<out T> {
    data object Absent : CmiHologramPatch<Nothing>

    data class Set<T>(
        val value: T,
    ) : CmiHologramPatch<T>
}

internal data class OpsCmiHologramSpec(
    val location: CmiHologramPatch<CmiHologramLocationSpec>,
    val lines: CmiHologramPatch<List<String>>,
    val enabled: CmiHologramPatch<Boolean>,
    val showRange: CmiHologramPatch<Int>,
    val updateRange: CmiHologramPatch<Int>,
    val updateIntervalSeconds: CmiHologramPatch<Double>,
    val pageChangeIntervalSeconds: CmiHologramPatch<Double>,
    val spacing: CmiHologramPatch<Double>,
    val iconSpacing: CmiHologramPatch<Double>,
    val downOrder: CmiHologramPatch<Boolean>,
    val interactable: CmiHologramPatch<Boolean>,
    val commands: CmiHologramPatch<List<String>>,
    val permissionRequired: CmiHologramPatch<Boolean>,
    val checkLineOfSight: CmiHologramPatch<Boolean>,
    val sticky: CmiHologramPatch<Boolean>,
    val type: CmiHologramPatch<CMIHologramType>,
    val group: CmiHologramPatch<String>,
    val autoPagination: CmiHologramPatch<Boolean>,
    val display: CmiHologramPatch<CmiHologramDisplaySpec>,
    val icon: CmiHologramPatch<CmiHologramIconSpec>,
    val board: CmiHologramPatch<CmiHologramBoardSpec>,
    val interaction: CmiHologramPatch<CmiHologramInteractionSpec>,
    val animation: CmiHologramPatch<CmiHologramAnimationSpec>,
) {
    val changedFields: List<String>
        get() =
            listOf(
                "location" to location,
                "lines" to lines,
                "enabled" to enabled,
                "showRange" to showRange,
                "updateRange" to updateRange,
                "updateIntervalSeconds" to updateIntervalSeconds,
                "pageChangeIntervalSeconds" to pageChangeIntervalSeconds,
                "spacing" to spacing,
                "iconSpacing" to iconSpacing,
                "downOrder" to downOrder,
                "interactable" to interactable,
                "commands" to commands,
                "permissionRequired" to permissionRequired,
                "checkLineOfSight" to checkLineOfSight,
                "sticky" to sticky,
                "type" to type,
                "group" to group,
                "autoPagination" to autoPagination,
                "display" to display,
                "icon" to icon,
                "board" to board,
                "interaction" to interaction,
                "animation" to animation,
            ).mapNotNull { (name, patch) -> name.takeUnless { patch === CmiHologramPatch.Absent } }

    fun requireCreateFields() {
        require(location is CmiHologramPatch.Set) {
            "location required when creating a CMI hologram"
        }
        location.value.requireCreateFields()
    }

    companion object {
        private val rootFields =
            setOf(
                "location",
                "lines",
                "enabled",
                "showRange",
                "updateRange",
                "updateIntervalSeconds",
                "pageChangeIntervalSeconds",
                "spacing",
                "iconSpacing",
                "downOrder",
                "interactable",
                "commands",
                "permissionRequired",
                "checkLineOfSight",
                "sticky",
                "type",
                "group",
                "autoPagination",
                "display",
                "icon",
                "board",
                "interaction",
                "animation",
            )

        fun parse(body: JsonObject): OpsCmiHologramSpec {
            requireKnownHologramFields(body, rootFields, "hologram")
            require(body.size() > 0) { "HologramSpec must contain at least one field" }
            return OpsCmiHologramSpec(
                location = hologramField(body, "location", ::parseLocation),
                lines =
                    hologramField(body, "lines") {
                        hologramStringList(it, "lines", maxSize = 2_048, maxLength = 8_192)
                    },
                enabled = hologramField(body, "enabled") { hologramBoolean(it, "enabled") },
                showRange =
                    hologramField(body, "showRange") {
                        hologramInteger(it, "showRange").also { value ->
                            require(value in 1..512) { "showRange must be within 1..512" }
                        }
                    },
                updateRange =
                    hologramField(body, "updateRange") {
                        hologramInteger(it, "updateRange").also { value ->
                            require(value in 1..512) { "updateRange must be within 1..512" }
                        }
                    },
                updateIntervalSeconds =
                    hologramField(body, "updateIntervalSeconds") {
                        hologramRangedDouble(it, "updateIntervalSeconds", 0.0, 86_400.0)
                    },
                pageChangeIntervalSeconds =
                    hologramField(body, "pageChangeIntervalSeconds") {
                        hologramRangedDouble(it, "pageChangeIntervalSeconds", 0.0, 86_400.0)
                    },
                spacing =
                    hologramField(body, "spacing") {
                        hologramRangedDouble(it, "spacing", 0.01, 10.0)
                    },
                iconSpacing =
                    hologramField(body, "iconSpacing") {
                        hologramRangedDouble(it, "iconSpacing", 0.01, 10.0)
                    },
                downOrder = hologramField(body, "downOrder") { hologramBoolean(it, "downOrder") },
                interactable =
                    hologramField(body, "interactable") { hologramBoolean(it, "interactable") },
                commands =
                    hologramField(body, "commands") {
                        hologramStringList(it, "commands", maxSize = 256, maxLength = 2_048)
                    },
                permissionRequired =
                    hologramField(body, "permissionRequired") {
                        hologramBoolean(it, "permissionRequired")
                    },
                checkLineOfSight =
                    hologramField(body, "checkLineOfSight") {
                        hologramBoolean(it, "checkLineOfSight")
                    },
                sticky = hologramField(body, "sticky") { hologramBoolean(it, "sticky") },
                type = hologramField(body, "type", ::parseHologramType),
                group =
                    hologramField(body, "group") {
                        hologramStringAllowBlank(it, "group", maxLength = 64)
                    },
                autoPagination =
                    hologramField(body, "autoPagination") {
                        hologramBoolean(it, "autoPagination")
                    },
                display = hologramField(body, "display", ::parseDisplay),
                icon = hologramField(body, "icon", ::parseIcon),
                board = hologramField(body, "board", ::parseBoard),
                interaction = hologramField(body, "interaction", ::parseInteraction),
                animation = hologramField(body, "animation", ::parseAnimation),
            )
        }
    }
}

internal data class CmiHologramLocationSpec(
    val world: CmiHologramPatch<String>,
    val x: CmiHologramPatch<Double>,
    val y: CmiHologramPatch<Double>,
    val z: CmiHologramPatch<Double>,
    val yaw: CmiHologramPatch<Float>,
    val pitch: CmiHologramPatch<Float>,
) {
    fun requireCreateFields() {
        require(world is CmiHologramPatch.Set) { "location.world required when creating a CMI hologram" }
        require(x is CmiHologramPatch.Set) { "location.x required when creating a CMI hologram" }
        require(y is CmiHologramPatch.Set) { "location.y required when creating a CMI hologram" }
        require(z is CmiHologramPatch.Set) { "location.z required when creating a CMI hologram" }
    }
}

internal data class CmiHologramDisplaySpec(
    val billboard: CmiHologramPatch<CMIBillboard>,
    val yaw: CmiHologramPatch<Int>,
    val pitch: CmiHologramPatch<Int>,
    val alignment: CmiHologramPatch<CMITextAlignment>,
    val backgroundAlpha: CmiHologramPatch<Int>,
    val textAlpha: CmiHologramPatch<Int>,
    val doubleSided: CmiHologramPatch<Boolean>,
    val shadowed: CmiHologramPatch<Boolean>,
    val scaleWidth: CmiHologramPatch<Double>,
    val scaleHeight: CmiHologramPatch<Double>,
    val seeThrough: CmiHologramPatch<Boolean>,
    val lineWidth: CmiHologramPatch<Int>,
    val fillerAmount: CmiHologramPatch<Int>,
    val backgroundColor: CmiHologramPatch<String>,
    val direction: CmiHologramPatch<CmiHologramVector3Spec>,
    val offset: CmiHologramPatch<CmiHologramVector3Spec>,
    val skyLight: CmiHologramPatch<Int>,
    val blockLight: CmiHologramPatch<Int>,
)

internal data class CmiHologramIconSpec(
    val billboard: CmiHologramPatch<CMIBillboard>,
    val offset: CmiHologramPatch<CmiHologramVector3Spec>,
    val scale: CmiHologramPatch<CmiHologramVector3Spec>,
    val direction: CmiHologramPatch<CmiHologramVector3Spec>,
    val yaw: CmiHologramPatch<Int>,
    val pitch: CmiHologramPatch<Int>,
    val roll: CmiHologramPatch<Int>,
)

internal data class CmiHologramBoardSpec(
    val enabled: CmiHologramPatch<Boolean>,
    val material: CmiHologramPatch<String>,
    val dimensions: CmiHologramPatch<CmiHologramVector3Spec>,
    val offset: CmiHologramPatch<CmiHologramVector3Spec>,
    val direction: CmiHologramPatch<CmiHologramVector3Spec>,
)

internal data class CmiHologramInteractionSpec(
    val dimensions: CmiHologramPatch<CmiHologramVector2Spec>,
    val offset: CmiHologramPatch<CmiHologramVector3Spec>,
    val particleDimensions: CmiHologramPatch<CmiHologramVector2Spec>,
    val particleOffset: CmiHologramPatch<CmiHologramVector3Spec>,
    val particlePosition: CmiHologramPatch<Int>,
    val particleSpacing: CmiHologramPatch<Double>,
    val particleCount: CmiHologramPatch<Int>,
    val effect: CmiHologramPatch<CmiHologramParticleSpec>,
    val showHoverParticle: CmiHologramPatch<Boolean>,
    val showClickParticle: CmiHologramPatch<Boolean>,
    val basePrefix: CmiHologramPatch<String>,
    val hoverPrefix: CmiHologramPatch<String>,
)

internal data class CmiHologramAnimationSpec(
    val fadeInTicks: CmiHologramPatch<Int>,
    val fadeOutTicks: CmiHologramPatch<Int>,
    val autoRotateDegreesPerTick: CmiHologramPatch<Int>,
)

internal data class CmiHologramParticleSpec(
    val particle: String,
    val color: String?,
    val colorFrom: String?,
    val colorTo: String?,
    val offset: CmiHologramVector3Spec?,
    val amount: Int?,
    val speed: Double?,
    val size: Int?,
    val material: String?,
    val duration: Int?,
)

internal data class CmiHologramVector2Spec(
    val x: Double,
    val y: Double,
)

internal data class CmiHologramVector3Spec(
    val x: Double,
    val y: Double,
    val z: Double,
)

private fun parseLocation(element: JsonElement): CmiHologramLocationSpec {
    val body = hologramObject(element, "location")
    requireKnownHologramFields(body, setOf("world", "x", "y", "z", "yaw", "pitch"), "location")
    require(body.size() > 0) { "location must contain at least one field" }
    return CmiHologramLocationSpec(
        world =
            hologramField(body, "world") {
                hologramString(it, "location.world", maxLength = 80)
            },
        x = hologramField(body, "x") { hologramFiniteDouble(it, "location.x") },
        y = hologramField(body, "y") { hologramFiniteDouble(it, "location.y") },
        z = hologramField(body, "z") { hologramFiniteDouble(it, "location.z") },
        yaw = hologramField(body, "yaw") { hologramFiniteDouble(it, "location.yaw").toFloat() },
        pitch = hologramField(body, "pitch") { hologramFiniteDouble(it, "location.pitch").toFloat() },
    )
}

private fun parseDisplay(element: JsonElement): CmiHologramDisplaySpec {
    val body = hologramObject(element, "display")
    val allowed =
        setOf(
            "billboard",
            "yaw",
            "pitch",
            "alignment",
            "backgroundAlpha",
            "textAlpha",
            "doubleSided",
            "shadowed",
            "scaleWidth",
            "scaleHeight",
            "seeThrough",
            "lineWidth",
            "fillerAmount",
            "backgroundColor",
            "direction",
            "offset",
            "skyLight",
            "blockLight",
        )
    requireKnownHologramFields(body, allowed, "display")
    require(body.size() > 0) { "display must contain at least one field" }
    return CmiHologramDisplaySpec(
        billboard =
            hologramField(body, "billboard") {
                hologramEnum<CMIBillboard>(hologramString(it, "display.billboard", 32), "display.billboard")
            },
        yaw =
            hologramField(body, "yaw") {
                hologramInteger(it, "display.yaw").also { value ->
                    require(value in -360..360) { "display.yaw must be within -360..360" }
                }
            },
        pitch =
            hologramField(body, "pitch") {
                hologramInteger(it, "display.pitch").also { value ->
                    require(value in -360..360) { "display.pitch must be within -360..360" }
                }
            },
        alignment =
            hologramField(body, "alignment") {
                hologramEnum<CMITextAlignment>(
                    hologramString(it, "display.alignment", 32),
                    "display.alignment",
                )
            },
        backgroundAlpha = byteField(body, "backgroundAlpha"),
        textAlpha = byteField(body, "textAlpha"),
        doubleSided =
            hologramField(body, "doubleSided") {
                hologramBoolean(it, "display.doubleSided")
            },
        shadowed =
            hologramField(body, "shadowed") {
                hologramBoolean(it, "display.shadowed")
            },
        scaleWidth =
            hologramField(body, "scaleWidth") {
                hologramRangedDouble(it, "display.scaleWidth", 0.01, 64.0)
            },
        scaleHeight =
            hologramField(body, "scaleHeight") {
                hologramRangedDouble(it, "display.scaleHeight", 0.01, 64.0)
            },
        seeThrough =
            hologramField(body, "seeThrough") {
                hologramBoolean(it, "display.seeThrough")
            },
        lineWidth =
            hologramField(body, "lineWidth") {
                hologramInteger(it, "display.lineWidth").also { value ->
                    require(value in 0..4_096) { "display.lineWidth must be within 0..4096" }
                }
            },
        fillerAmount =
            hologramField(body, "fillerAmount") {
                hologramInteger(it, "display.fillerAmount").also { value ->
                    require(value in 0..4_096) { "display.fillerAmount must be within 0..4096" }
                }
            },
        backgroundColor =
            hologramField(body, "backgroundColor") {
                hologramColor(it, "display.backgroundColor")
            },
        direction = hologramField(body, "direction") { parseVector3(it, "display.direction") },
        offset = hologramField(body, "offset") { parseVector3(it, "display.offset") },
        skyLight =
            hologramField(body, "skyLight") {
                hologramInteger(it, "display.skyLight").also { value ->
                    require(value in -1..15) { "display.skyLight must be within -1..15" }
                }
            },
        blockLight =
            hologramField(body, "blockLight") {
                hologramInteger(it, "display.blockLight").also { value ->
                    require(value in -1..15) { "display.blockLight must be within -1..15" }
                }
            },
    )
}

private fun parseIcon(element: JsonElement): CmiHologramIconSpec {
    val body = hologramObject(element, "icon")
    val allowed = setOf("billboard", "offset", "scale", "direction", "yaw", "pitch", "roll")
    requireKnownHologramFields(body, allowed, "icon")
    require(body.size() > 0) { "icon must contain at least one field" }
    return CmiHologramIconSpec(
        billboard =
            hologramField(body, "billboard") {
                hologramEnum<CMIBillboard>(hologramString(it, "icon.billboard", 32), "icon.billboard")
            },
        offset = hologramField(body, "offset") { parseVector3(it, "icon.offset") },
        scale = hologramField(body, "scale") { parseVector3(it, "icon.scale", min = 0.01, max = 64.0) },
        direction = hologramField(body, "direction") { parseVector3(it, "icon.direction") },
        yaw = angleField(body, "yaw", "icon.yaw"),
        pitch = angleField(body, "pitch", "icon.pitch"),
        roll = angleField(body, "roll", "icon.roll"),
    )
}

private fun parseBoard(element: JsonElement): CmiHologramBoardSpec {
    val body = hologramObject(element, "board")
    val allowed = setOf("enabled", "material", "dimensions", "offset", "direction")
    requireKnownHologramFields(body, allowed, "board")
    require(body.size() > 0) { "board must contain at least one field" }
    return CmiHologramBoardSpec(
        enabled = hologramField(body, "enabled") { hologramBoolean(it, "board.enabled") },
        material =
            hologramField(body, "material") {
                hologramString(it, "board.material", maxLength = 80)
            },
        dimensions =
            hologramField(body, "dimensions") {
                parseVector3(it, "board.dimensions", min = 0.0, max = 64.0)
            },
        offset = hologramField(body, "offset") { parseVector3(it, "board.offset") },
        direction = hologramField(body, "direction") { parseVector3(it, "board.direction") },
    )
}

private fun parseInteraction(element: JsonElement): CmiHologramInteractionSpec {
    val body = hologramObject(element, "interaction")
    val allowed =
        setOf(
            "dimensions",
            "offset",
            "particleDimensions",
            "particleOffset",
            "particlePosition",
            "particleSpacing",
            "particleCount",
            "effect",
            "showHoverParticle",
            "showClickParticle",
            "basePrefix",
            "hoverPrefix",
        )
    requireKnownHologramFields(body, allowed, "interaction")
    require(body.size() > 0) { "interaction must contain at least one field" }
    return CmiHologramInteractionSpec(
        dimensions =
            hologramField(body, "dimensions") {
                parseVector2(it, "interaction.dimensions", min = 0.0, max = 64.0)
            },
        offset = hologramField(body, "offset") { parseVector3(it, "interaction.offset") },
        particleDimensions =
            hologramField(body, "particleDimensions") {
                parseVector2(it, "interaction.particleDimensions", min = 0.0, max = 64.0)
            },
        particleOffset =
            hologramField(body, "particleOffset") {
                parseVector3(it, "interaction.particleOffset")
            },
        particlePosition =
            hologramField(body, "particlePosition") {
                hologramInteger(it, "interaction.particlePosition").also { value ->
                    require(value in Short.MIN_VALUE..Short.MAX_VALUE) {
                        "interaction.particlePosition must fit a signed short"
                    }
                }
            },
        particleSpacing =
            hologramField(body, "particleSpacing") {
                hologramRangedDouble(it, "interaction.particleSpacing", -64.0, 64.0)
            },
        particleCount =
            hologramField(body, "particleCount") {
                hologramInteger(it, "interaction.particleCount").also { value ->
                    require(value in 0..1_000) { "interaction.particleCount must be within 0..1000" }
                }
            },
        effect = hologramField(body, "effect", ::parseParticle),
        showHoverParticle =
            hologramField(body, "showHoverParticle") {
                hologramBoolean(it, "interaction.showHoverParticle")
            },
        showClickParticle =
            hologramField(body, "showClickParticle") {
                hologramBoolean(it, "interaction.showClickParticle")
            },
        basePrefix =
            hologramField(body, "basePrefix") {
                hologramStringAllowBlank(it, "interaction.basePrefix", maxLength = 256)
            },
        hoverPrefix =
            hologramField(body, "hoverPrefix") {
                hologramStringAllowBlank(it, "interaction.hoverPrefix", maxLength = 256)
            },
    )
}

private fun parseAnimation(element: JsonElement): CmiHologramAnimationSpec {
    val body = hologramObject(element, "animation")
    val allowed = setOf("fadeInTicks", "fadeOutTicks", "autoRotateDegreesPerTick")
    requireKnownHologramFields(body, allowed, "animation")
    require(body.size() > 0) { "animation must contain at least one field" }
    return CmiHologramAnimationSpec(
        fadeInTicks = nonNegativeTickField(body, "fadeInTicks", "animation.fadeInTicks"),
        fadeOutTicks = nonNegativeTickField(body, "fadeOutTicks", "animation.fadeOutTicks"),
        autoRotateDegreesPerTick =
            hologramField(body, "autoRotateDegreesPerTick") {
                hologramInteger(it, "animation.autoRotateDegreesPerTick").also { value ->
                    require(value in -360..360) {
                        "animation.autoRotateDegreesPerTick must be within -360..360"
                    }
                }
            },
    )
}

private fun parseParticle(element: JsonElement): CmiHologramParticleSpec {
    val body = hologramObject(element, "interaction.effect")
    val allowed =
        setOf(
            "particle",
            "color",
            "colorFrom",
            "colorTo",
            "offset",
            "amount",
            "speed",
            "size",
            "material",
            "duration",
        )
    requireKnownHologramFields(body, allowed, "interaction.effect")
    val particle =
        body.get("particle")
            ?: throw IllegalArgumentException("interaction.effect.particle required")
    return CmiHologramParticleSpec(
        particle = hologramString(particle, "interaction.effect.particle", maxLength = 80),
        color = optionalParticleColor(body, "color"),
        colorFrom = optionalParticleColor(body, "colorFrom"),
        colorTo = optionalParticleColor(body, "colorTo"),
        offset = optionalVector3(body, "offset", "interaction.effect.offset"),
        amount = optionalRangedInt(body, "amount", "interaction.effect.amount", 0, 1_000),
        speed =
            body.get("speed")?.let {
                hologramRangedDouble(it, "interaction.effect.speed", 0.0, 100.0)
            },
        size = optionalRangedInt(body, "size", "interaction.effect.size", 0, 1_000),
        material =
            body.get("material")?.let {
                hologramString(it, "interaction.effect.material", maxLength = 80)
            },
        duration = optionalRangedInt(body, "duration", "interaction.effect.duration", 0, 120_000),
    )
}

private fun parseVector2(
    element: JsonElement,
    path: String,
    min: Double = -1_000_000.0,
    max: Double = 1_000_000.0,
): CmiHologramVector2Spec {
    val body = hologramObject(element, path)
    requireKnownHologramFields(body, setOf("x", "y"), path)
    return CmiHologramVector2Spec(
        x = requiredVectorComponent(body, "x", path, min, max),
        y = requiredVectorComponent(body, "y", path, min, max),
    )
}

private fun parseVector3(
    element: JsonElement,
    path: String,
    min: Double = -1_000_000.0,
    max: Double = 1_000_000.0,
): CmiHologramVector3Spec {
    val body = hologramObject(element, path)
    requireKnownHologramFields(body, setOf("x", "y", "z"), path)
    return CmiHologramVector3Spec(
        x = requiredVectorComponent(body, "x", path, min, max),
        y = requiredVectorComponent(body, "y", path, min, max),
        z = requiredVectorComponent(body, "z", path, min, max),
    )
}

private fun byteField(
    body: JsonObject,
    key: String,
): CmiHologramPatch<Int> =
    hologramField(body, key) {
        hologramInteger(it, "display.$key").also { value ->
            require(value in 0..255) { "display.$key must be within 0..255" }
        }
    }

private fun angleField(
    body: JsonObject,
    key: String,
    path: String,
): CmiHologramPatch<Int> =
    hologramField(body, key) {
        hologramInteger(it, path).also { value ->
            require(value in -360..360) { "$path must be within -360..360" }
        }
    }

private fun nonNegativeTickField(
    body: JsonObject,
    key: String,
    path: String,
): CmiHologramPatch<Int> =
    hologramField(body, key) {
        hologramInteger(it, path).also { value ->
            require(value in 0..120_000) { "$path must be within 0..120000" }
        }
    }

private fun parseHologramType(element: JsonElement): CMIHologramType {
    val raw = hologramString(element, "type", maxLength = 32)
    return when (raw.lowercase().replace("-", "_")) {
        "auto" -> CMIHologramType.Auto
        "textdisplay", "text_display" -> CMIHologramType.TextDisplay
        "armorstand", "armor_stand" -> CMIHologramType.ArmorStand
        else -> throw IllegalArgumentException("type must be one of auto, text_display, armor_stand")
    }
}

private fun hologramColor(
    element: JsonElement,
    path: String,
): String {
    val value = hologramString(element, path, maxLength = 32)
    require(
        value.matches(Regex("^#[0-9a-fA-F]{6}$")) ||
            value.matches(Regex("^[a-zA-Z_]{3,24}$")),
    ) {
        "$path must be a #RRGGBB value or a named CMI color"
    }
    return value
}

private fun optionalParticleColor(
    body: JsonObject,
    key: String,
): String? =
    body.get(key)?.let {
        val path = "interaction.effect.$key"
        val value = hologramString(it, path, maxLength = 7)
        require(value.matches(Regex("^#[0-9a-fA-F]{6}$"))) { "$path must use #RRGGBB" }
        value
    }

private fun optionalVector3(
    body: JsonObject,
    key: String,
    path: String,
): CmiHologramVector3Spec? = body.get(key)?.let { parseVector3(it, path) }

private fun optionalRangedInt(
    body: JsonObject,
    key: String,
    path: String,
    min: Int,
    max: Int,
): Int? =
    body.get(key)?.let {
        hologramInteger(it, path).also { value ->
            require(value in min..max) { "$path must be within $min..$max" }
        }
    }

private fun requiredVectorComponent(
    body: JsonObject,
    key: String,
    path: String,
    min: Double,
    max: Double,
): Double {
    val element = body.get(key) ?: throw IllegalArgumentException("$path.$key required")
    return hologramRangedDouble(element, "$path.$key", min, max)
}

private inline fun <T> hologramField(
    body: JsonObject,
    key: String,
    parse: (JsonElement) -> T,
): CmiHologramPatch<T> {
    if (!body.has(key)) return CmiHologramPatch.Absent
    val element = body.get(key)
    require(!element.isJsonNull) { "$key cannot be null" }
    return CmiHologramPatch.Set(parse(element))
}

private fun requireKnownHologramFields(
    body: JsonObject,
    allowed: Set<String>,
    path: String,
) {
    val unknown = body.keySet() - allowed
    require(unknown.isEmpty()) {
        "$path contains unknown field(s): ${unknown.sorted().joinToString(", ")}"
    }
}

private fun hologramObject(
    element: JsonElement,
    path: String,
): JsonObject {
    require(element.isJsonObject) { "$path must be a JSON object" }
    return element.asJsonObject
}

private fun hologramString(
    element: JsonElement,
    path: String,
    maxLength: Int,
): String {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$path must be a string" }
    val value = element.asString.trim()
    require(value.isNotEmpty()) { "$path must not be blank" }
    require(value.length <= maxLength) { "$path must be at most $maxLength characters" }
    require(value.none(Char::isISOControl)) { "$path contains control characters" }
    return value
}

private fun hologramStringAllowBlank(
    element: JsonElement,
    path: String,
    maxLength: Int,
): String {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$path must be a string" }
    val value = element.asString
    require(value.length <= maxLength) { "$path must be at most $maxLength characters" }
    require(value.none(Char::isISOControl)) { "$path contains control characters" }
    return value
}

private fun hologramStringList(
    element: JsonElement,
    path: String,
    maxSize: Int,
    maxLength: Int,
): List<String> {
    require(element.isJsonArray) { "$path must be an array" }
    val array = element.asJsonArray
    require(array.size() <= maxSize) { "$path must contain at most $maxSize entries" }
    return array.mapIndexed { index, value ->
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$path[$index] must be a string" }
        value.asString.also { text ->
            require(text.length <= maxLength) { "$path[$index] must be at most $maxLength characters" }
            require(text.none(Char::isISOControl)) { "$path[$index] contains control characters" }
        }
    }
}

private fun hologramBoolean(
    element: JsonElement,
    path: String,
): Boolean {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) { "$path must be a boolean" }
    return element.asBoolean
}

private fun hologramFiniteDouble(
    element: JsonElement,
    path: String,
): Double {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path must be a number" }
    return element.asDouble.also { require(it.isFinite()) { "$path must be finite" } }
}

private fun hologramRangedDouble(
    element: JsonElement,
    path: String,
    min: Double,
    max: Double,
): Double =
    hologramFiniteDouble(element, path).also {
        require(it in min..max) { "$path must be within $min..$max" }
    }

private fun hologramInteger(
    element: JsonElement,
    path: String,
): Int {
    val number = hologramFiniteDouble(element, path)
    require(number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
        "$path must be an integer"
    }
    return number.toInt()
}

private inline fun <reified T : Enum<T>> hologramEnum(
    raw: String,
    path: String,
): T =
    runCatching { enumValueOf<T>(raw.uppercase()) }
        .getOrElse {
            throw IllegalArgumentException(
                "$path must be one of ${enumValues<T>().joinToString(", ") { value -> value.name.lowercase() }}",
            )
        }
