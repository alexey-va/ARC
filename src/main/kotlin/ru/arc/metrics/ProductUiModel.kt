package ru.arc.metrics

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.security.MessageDigest

/** Stable operator-owned identifiers only. Never derive these from item lore, player names or dialog inputs. */
data class ProductUiButton(val slot: Int, val feature: ProductFeature? = null)
data class ProductUiView(val surface: String, val revision: String, val buttons: Map<String, ProductUiButton>)

enum class ProductUiKind { OPEN, IMPRESSION, CLICK, ATTEMPT, BLOCKED, CLOSE, NO_CHOICE, CENSORED }

data class ProductUiSignal(
    val eventId: String,
    val source: String,
    val player: String,
    val occurredAt: Long,
    val kind: ProductUiKind,
    val surface: String,
    val revision: String,
    val button: String = "_menu",
    val feature: String? = null,
    val durationMillis: Long = 0,
)

/** Paper-only domain observations reuse the existing Redis lifecycle and bounded product persistence. */
internal object ProductUiCodec {
    const val CHANNEL = "arc-product-ui-v1"
    val ID = Regex("[a-z0-9_.:/-]{1,96}")
    val REVISION = Regex("[a-f0-9]{12}")
    private val HASH = Regex("[a-f0-9]{64}")
    private val EVENT = Regex("[a-f0-9-]{16,64}")
    private val FIELDS = setOf("eventId", "source", "player", "occurredAt", "kind", "surface", "revision", "button", "feature", "durationMillis")
    fun decode(payload: String, origin: String, now: Long, retentionDays: Int, gson: Gson): ProductUiSignal? {
        val codec = BoundedJsonCodec(
            gson,
            ProductUiSignal::class.java,
            JsonObjectContract(allowedFields = FIELDS, requiredFields = FIELDS - "feature"),
            JsonResourceBounds(maxCharacters = 2_048, maxDepth = 2, maxContainerEntries = 12, maxTotalNodes = 24, maxStringCharacters = 256),
        ) { signal -> require(valid(signal)) { "Invalid product UI signal" } }
        return runCatching { codec.decode(payload) }.getOrNull()?.takeIf {
            it.source == origin && valid(it) && it.occurredAt in (now - retentionDays * 86_400_000L)..(now + 300_000L)
        }
    }
    fun valid(s: ProductUiSignal): Boolean = runCatching {
        EVENT.matches(s.eventId) && HASH.matches(s.player) && ID.matches(s.source) &&
            ID.matches(s.surface) && REVISION.matches(s.revision) && ID.matches(s.button) &&
            s.kind in ProductUiKind.entries && s.durationMillis in 0..86_400_000 &&
            (s.feature == null || ProductFeature.entries.any { it.label == s.feature })
    }.getOrDefault(false)
    fun revision(structure: String): String = MessageDigest.getInstance("SHA-256")
        .digest(structure.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}

/** Per-player/per-day aggregate; the containing product store owns bounds, retention and atomic saves. */
data class ProductUiRow(
    val surface: String = "",
    val revision: String = "",
    val button: String = "_menu",
    var feature: String? = null,
    val counts: MutableMap<String, Long> = linkedMapOf(),
    var durationMillis: Long = 0,
) {
    fun add(event: String) { counts[event] = (counts[event] ?: 0) + 1 }
    fun valid(): Boolean = ProductUiCodec.ID.matches(surface) && ProductUiCodec.REVISION.matches(revision) &&
        ProductUiCodec.ID.matches(button) && counts.size <= 12 && counts.all { it.key in EVENTS && it.value in 0..1_000_000 }
    fun copyForSave(): ProductUiRow = copy(counts = counts.toMutableMap())
    val key: String get() = "$surface|$revision|$button"
    companion object {
        val EVENTS = ProductUiKind.entries.map { it.name.lowercase() }.toSet() + setOf("destination", "result")
    }
}

data class ProductUiAttribution(
    val surface: String,
    val revision: String,
    val button: String,
    val feature: String?,
    val occurredAt: Long,
    var destination: Boolean = false,
    var result: Boolean = false,
)

internal fun ProductOutcome.uiFeature(): ProductFeature? = when (this) {
    ProductOutcome.DUNGEON_COMPLETE -> ProductFeature.DUNGEONS
    ProductOutcome.MOUNT_RIDE -> ProductFeature.MOUNTS
    ProductOutcome.TREASURE_CLAIM -> ProductFeature.TREASURE
    ProductOutcome.RTP_COMPLETE, ProductOutcome.FIRST_RTP_COMPLETE -> ProductFeature.RTP
    ProductOutcome.HOME_CREATED -> ProductFeature.HOMES
    ProductOutcome.LAND_CLAIMED -> ProductFeature.LANDS
    ProductOutcome.AUTOBUILD_COMPLETE -> ProductFeature.AUTOBUILD
    ProductOutcome.CONTRACT_COMPLETE -> ProductFeature.CONTRACTS
    else -> null
}

/** Exact semantic IDs, not localized labels or fuzzy matching. Unmapped actions have click coverage only. */
internal fun productUiFeature(surface: String, button: String): ProductFeature? {
    val raw = button.removePrefix("element:").removePrefix("region:")
    val id = if (surface.startsWith("arc:help.")) raw.removePrefix("action_").removePrefix("goal_action_").removePrefix("item_") else raw
    if (surface == "arc:lands.create" && id == "create_submit") return ProductFeature.LANDS
    return ProductFeature.entries.firstOrNull { it.label == id }
        ?: when (id) {
            "dungeon", "dung" -> ProductFeature.DUNGEONS
            "home", "sethome", "create_home", "now_homes", "my_homes", "rec_home" -> ProductFeature.HOMES
            "claim", "privat", "now_lands", "my_lands", "rec_land" -> ProductFeature.LANDS
            "jobs", "job" -> ProductFeature.JOBS
            "duel", "multiplayer" -> ProductFeature.DUELS
            "build", "autobuild" -> ProductFeature.AUTOBUILD
            else -> ProductFeature.entries.firstOrNull { surface.substringAfter(':') == it.label }
        }
}
