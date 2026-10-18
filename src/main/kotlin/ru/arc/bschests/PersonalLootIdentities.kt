package ru.arc.bschests

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.arc.core.LifecycleTaskScope
import ru.arc.repository.Entity
import ru.arc.repository.Storage
import ru.arc.repository.redis.RedisStorage
import ru.arc.redis.RedisOperations
import ru.arc.util.Common
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An immutable alias entry. Original player:::chest loot records are never moved or copied. */
internal data class PersonalLootIdentity(
    val chestUuid: String,
    val members: List<String>,
    val players: List<String>,
    val version: Int = 1,
) : Entity {
    override fun id(): String = chestUuid
}

internal data class PersonalLootGroup(val members: List<UUID>, val players: Set<UUID>) {
    val canonical: UUID get() = members.first()

    fun withPlayers(additional: Collection<UUID>): PersonalLootGroup = copy(players = players + additional)
}

/**
 * Worker-owned personal-loot alias reconciliation. Every member retains the complete group and
 * claim set, so a partially acknowledged write or a separated chest can recover the same union.
 * Only the caller's bounded, already-authorized physical container supplies new aliases.
 */
internal class PersonalLootIdentities(private val storage: Storage<PersonalLootIdentity>) {
    val tasks = LifecycleTaskScope()
    private val cache = ConcurrentHashMap<UUID, PersonalLootIdentity>()
    private val pendingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    // ponytail: one suspended operation lane; partition by resolved group only if contention warrants it.
    private val operations = Mutex()

    fun admit(player: UUID): Boolean = pendingPlayers.add(player)
    fun release(player: UUID) { pendingPlayers.remove(player) }

    suspend fun <T> serialized(operation: suspend () -> T): T = operations.withLock { operation() }

    suspend fun resolve(markers: Map<UUID, Set<UUID>>): PersonalLootGroup {
        require(markers.isNotEmpty()) { "Personal loot has no identity" }
        val members = markers.keys.toMutableSet()
        val players = markers.values.flatten().toMutableSet()
        val visited = mutableSetOf<UUID>()
        while (true) {
            require(members.size <= MAX_MEMBERS && players.size <= MAX_PLAYERS) { "Personal loot identity exceeds recovery bounds" }
            val next = members.firstOrNull { it !in visited } ?: break
            visited += next
            // Never infer absence from loadAll: RedisStorage deliberately skips malformed bulk rows.
            val entry = storage.load(next.toString()).getOrThrow() ?: continue
            val group = validate(entry, next)
            members += group.members
            players += group.players
        }
        return PersonalLootGroup(members.sortedBy(UUID::toString), players)
    }

    /** Read-only preview recovery uses the same operation lane, without creating claims or rows. */
    suspend fun readForPreview(chestUuid: UUID) = serialized {
        val group = resolve(mapOf(chestUuid to emptySet()))
        cacheGroup(group)
    }

    /** Completion means every alias has reached storage, not merely the repository's dirty cache. */
    suspend fun persist(group: PersonalLootGroup) {
        require(group.members.size in 1..MAX_MEMBERS && group.players.size <= MAX_PLAYERS)
        val members = group.members.map(UUID::toString)
        val players = group.players.map(UUID::toString).sorted()
        val entries = members.map { PersonalLootIdentity(it, members, players) }
        // RedisStorage writes this entire immutable batch in one acknowledged HSET. No background
        // writer can clear a newer dirty mark or overwrite this snapshot after the acknowledgement.
        storage.saveMany(entries).getOrThrow()
        cacheGroup(group)
    }

    private fun cacheGroup(group: PersonalLootGroup) {
        val members = group.members.map(UUID::toString)
        val players = group.players.map(UUID::toString).sorted()
        group.members.forEach { cache[it] = PersonalLootIdentity(it.toString(), members, players) }
    }

    fun cached(chestUuid: UUID): PersonalLootGroup? = cache[chestUuid]?.let { validate(it, chestUuid) }

    private fun validate(entry: PersonalLootIdentity, expected: UUID): PersonalLootGroup {
        require(entry.version == 1 && parsePersonalLootUuid(entry.chestUuid) == expected) { "Invalid personal loot identity record" }
        require(entry.members.size in 1..MAX_MEMBERS && entry.players.size <= MAX_PLAYERS) { "Personal loot identity exceeds recovery bounds" }
        val members = entry.members.map { requireNotNull(parsePersonalLootUuid(it)) { "Invalid personal loot alias" } }
        val players = entry.players.map { requireNotNull(parsePersonalLootUuid(it)) { "Invalid personal loot claim" } }.toSet()
        require(expected in members && members.distinct().size == members.size) { "Invalid personal loot alias membership" }
        return PersonalLootGroup(members.sortedBy(UUID::toString), players)
    }

    private companion object {
        const val MAX_MEMBERS = 64
        const val MAX_PLAYERS = 4096
    }
}

/** A present JSON null is corrupt identity data, never proof that this UUID has no previous claims. */
internal fun personalLootIdentityStorage(redis: RedisOperations, storageKey: String): Storage<PersonalLootIdentity> {
    val delegate = Common.gson.getAdapter(PersonalLootIdentity::class.java)
    val strictGson = Common.gson.newBuilder().registerTypeAdapter(PersonalLootIdentity::class.java,
        object : TypeAdapter<PersonalLootIdentity>() {
            override fun read(reader: JsonReader): PersonalLootIdentity {
                require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Personal loot identity must be a JSON object" }
                return requireNotNull(delegate.read(reader))
            }

            override fun write(writer: JsonWriter, value: PersonalLootIdentity?) = delegate.write(writer, requireNotNull(value))
        },
    ).create()
    return RedisStorage(redis, storageKey, PersonalLootIdentity::class.java, strictGson)
}
