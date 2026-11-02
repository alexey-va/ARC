package ru.arc.graves

import com.google.gson.Gson
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.network.BackendServerId
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class LegacyGraveEntry(val id: UUID, val hash: String, val activatedHash: String, val expiredHash: String)
internal data class LegacyGraveHandoff(val server: String, val records: List<LegacyGraveEntry>)

/**
 * A clean shutdown hands off only validated, loaded PLAYER records. The original
 * GravesX database stays untouched. Starting with GravesX invalidates the seal,
 * so a prior reload's snapshot can never become an authoritative later import.
 */
internal class LegacyGravesArchive(
    root: Path,
    private val server: String,
    private val legacy: GravesXAccess?,
    private val opened: CompletableFuture<NativeGraveRepository>,
    private val worker: BoundedAsyncExecutor,
) {
    private val gson = Gson()
    // Construction/invalidation is explicit plugin startup/reload maintenance.
    private val seal = AtomicFileStore(
        root, Path.of("data", "native-graves-legacy-handoff.json"), 1024L * 1024,
        { value: LegacyGraveHandoff -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        { raw -> gson.fromJson(raw.toString(Charsets.UTF_8), LegacyGraveHandoff::class.java) },
        { value ->
            require(BackendServerId.parseOrNull(value.server) != null && value.server == server)
            require(value.records.size <= 4096 && value.records.map { it.id }.distinct().size == value.records.size)
            value.records.forEach { entry ->
                listOf(entry.hash, entry.activatedHash, entry.expiredHash).forEach { hash -> require(hash.length == 64 && hash.all { c -> c in '0'..'9' || c in 'a'..'f' }) }
            }
        },
    )

    init { if (legacy != null) seal.deleteIfExists() }

    /** Paper capture first; SQL and seal durability then run off the gameplay thread. */
    fun checkpointOnShutdown(): CompletableFuture<Unit> {
        val source = legacy ?: return CompletableFuture.completedFuture(Unit)
        val captured = source.exportLoadedPlayerGraves()
        return opened.thenCompose { store ->
            store.loadAll().thenCompose { existing ->
                val byId = existing.associateBy { it.id }
                var writes = CompletableFuture.completedFuture(emptyList<NativeGraveRecord>())
                captured.forEach { next ->
                    writes = writes.thenCompose { saved ->
                        val previous = byId[next.id]
                        check(previous == null || (previous.server == server && previous.phase == NativeGravePhase.IMPORTED)) {
                            "Legacy grave overlaps native ownership id=${next.id} server=$server"
                        }
                        val candidate = next.copy(revision = previous?.revision?.plus(1) ?: 0)
                        val write = if (previous == null) store.insert(candidate) else store.replace(previous, candidate)
                        write.thenApply { accepted ->
                            check(accepted) { "Legacy handoff snapshot CAS declined id=${candidate.id}" }
                            saved + candidate
                        }
                    }
                }
                writes.thenCompose { saved ->
                    worker.submit {
                        val handoff = LegacyGraveHandoff(server, saved.map { record ->
                            LegacyGraveEntry(record.id, digest(record), digest(activated(record, false)), digest(activated(record, true)))
                        })
                        check(seal.write(handoff) == handoff) { "Legacy handoff seal readback differs" }
                        Unit
                    }
                }
            }
        }
    }

    /** Only a backend with GravesX absent may activate the exact final sealed contents. */
    fun activateSealed(): CompletableFuture<Unit> {
        if (legacy != null) return CompletableFuture.completedFuture(Unit)
        return worker.submit { seal.loadOrNull() }.thenCompose { handoff ->
            if (handoff == null) return@thenCompose CompletableFuture.completedFuture(Unit)
            opened.thenCompose { store ->
                store.loadAll().thenCompose { existing ->
                    val byId = existing.associateBy { it.id }
                    var activation = CompletableFuture.completedFuture(Unit)
                    handoff.records.forEach { entry ->
                        activation = activation.thenCompose {
                            val previous = requireNotNull(byId[entry.id]) { "Sealed legacy grave is missing id=${entry.id}" }
                            if (digest(previous) in setOf(entry.activatedHash, entry.expiredHash)) {
                                return@thenCompose CompletableFuture.completedFuture(Unit)
                            }
                            check(previous.phase == NativeGravePhase.IMPORTED && digest(previous) == entry.hash) {
                                "Sealed legacy grave payload differs id=${entry.id}"
                            }
                            val expired = previous.expiresAt >= 0 && previous.expiresAt <= System.currentTimeMillis()
                            val next = activated(previous, expired)
                            store.replace(previous, next).thenApply { accepted ->
                                check(accepted) { "Sealed legacy activation CAS declined id=${entry.id}" }
                                Unit
                            }
                        }
                    }
                    activation.thenCompose { worker.submit { seal.deleteIfExists(); Unit } }
                }
            }
        }
    }

    private fun digest(record: NativeGraveRecord) = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(NativeGravePayload.encode(record)),
    )

    private fun activated(record: NativeGraveRecord, expired: Boolean): NativeGraveRecord = record.copy(
        revision = record.revision + 1,
        phase = if (expired || (record.items.isEmpty() && record.experience == 0)) NativeGravePhase.CLAIMED else NativeGravePhase.ACTIVE,
        items = if (expired) emptyList() else record.items,
        experience = if (expired) 0 else record.experience,
    )
}
