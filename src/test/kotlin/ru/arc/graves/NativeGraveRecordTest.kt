package ru.arc.graves

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletionException

class NativeGraveRecordTest : FreeSpec({
    "snapshots defensively freeze their input and preserve every native slot and XP field" {
        val slots = MutableList<String?>(41) { null }.apply { this[40] = STACK }
        val inventory = NativeGraveInventory(slots, 7, 0.25f, 91)
        val items = mutableListOf(STACK)
        val record = grave().copy(items = items)
        slots[40] = null
        items.clear()

        inventory.slots[40] shouldBe STACK
        record.items shouldBe listOf(STACK)
        shouldThrow<UnsupportedOperationException> { (inventory.slots as MutableList<String?>)[40] = null }
        shouldThrow<UnsupportedOperationException> { (record.items as MutableList<String>).clear() }
        inventory.copy() shouldBe inventory
        record.copy() shouldBe record
    }

    "validation rejects invalid identity, coordinates, experience, phase receipts and item encodings" {
        val original = grave()
        listOf(
            original.copy(ownerName = "bad name"),
            original.copy(server = "Classic"),
            original.copy(world = "world\n"),
            original.copy(x = 30_000_001),
            original.copy(y = -2049),
            original.copy(experience = -1),
            original.copy(createdAt = -1),
            original.copy(expiresAt = original.createdAt - 1),
            original.copy(revision = -1),
            original.copy(completedSequence = -1),
            original.copy(completedSequence = 1),
            original.copy(operation = creation().copy(sequence = -1)),
            original.copy(items = List(257) { STACK }),
            original.copy(items = listOf("!bad")),
            original.copy(items = listOf("AQI")),
            original.copy(phase = NativeGravePhase.PREPARED),
            original.copy(phase = NativeGravePhase.CLAIMING),
            original.copy(phase = NativeGravePhase.CLAIMED),
            original.copy(phase = NativeGravePhase.REVIEW),
        ).forEach { record -> shouldThrow<IllegalArgumentException> { record.validate() } }
        original.copy(expiresAt = -1).validate()
        original.copy(ownerName = ".Bedrock").validate()
        original.copy(phase = NativeGravePhase.IMPORTED).validate()
        listOf("", "bad scope", "scope/path", "сеть", "x".repeat(129)).forEach { scope ->
            shouldThrow<IllegalArgumentException> { original.copy(operation = creation().copy(inventoryScope = scope)).validate() }
        }
        shouldThrow<IllegalArgumentException> { inventory().copy(progress = Float.NaN).validate() }
        shouldThrow<IllegalArgumentException> { inventory().copy(slots = List(40) { null }).validate() }
    }

    "decoded item limits apply per stack and across the grave plus both recovery snapshots" {
        val large = Base64.getEncoder().encodeToString(ByteArray(NativeGraveRecord.MAX_ITEM_BYTES))
        val original = grave()
        original.copy(items = List(16) { large }).validate()
        shouldThrow<IllegalArgumentException> { original.copy(items = List(17) { large }).validate() }
        val oversized = Base64.getEncoder().encodeToString(ByteArray(NativeGraveRecord.MAX_ITEM_BYTES + 1))
        shouldThrow<IllegalArgumentException> { original.copy(items = listOf(oversized)).validate() }
        val occupied = inventory().copy(slots = List(41) { if (it == 0) large else null })
        val operation = NativeGraveOperation(UUID.randomUUID(), "classic", NativeGraveOperationKind.CREATE, occupied, occupied)
        shouldThrow<IllegalArgumentException> {
            original.copy(items = List(15) { large }, phase = NativeGravePhase.PREPARED, operation = operation).validate()
        }
    }

    "canonical JSON roundtrips exact receipts and rejects missing fields, null stacks and lossy numbers" {
        val before = inventory().copy(slots = List(41) { if (it == 40) STACK else null })
        val operation = NativeGraveOperation(UUID.randomUUID(), "classic", NativeGraveOperationKind.CLAIM, before, inventory(),
            sequence = 42, inventoryScope = "network:pool_1-east.main")
        val original = grave().copy(phase = NativeGravePhase.CLAIMING, operation = operation, completedSequence = 21, checkpoint = before)
        val bytes = NativeGravePayload.encode(original)
        val restored = NativeGravePayload.decode(bytes)
        restored shouldBe original
        shouldThrow<UnsupportedOperationException> { (restored.operation!!.before.slots as MutableList<String?>).clear() }
        shouldThrow<UnsupportedOperationException> { (restored.checkpoint!!.slots as MutableList<String?>).clear() }

        fun changed(change: (com.google.gson.JsonObject) -> Unit): ByteArray =
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.also(change).toString().toByteArray()
        shouldThrow<IllegalArgumentException> { NativeGravePayload.decode(changed { it.remove("phase") }) }
        shouldThrow<IllegalArgumentException> { NativeGravePayload.decode(changed { it.addProperty("x", 1.5) }) }
        shouldThrow<IllegalArgumentException> { NativeGravePayload.decode(changed { it.addProperty("unknown", true) }) }
        shouldThrow<IllegalArgumentException> {
            NativeGravePayload.decode(changed { it.add("items", JsonParser.parseString("[null]")) })
        }
        shouldThrow<IllegalArgumentException> { NativeGravePayload.decode(bytes + " ".toByteArray()) }
    }

    "test repository has exact idempotent insert, strict payload CAS and retained claimed tombstones" {
        InMemoryNativeGraveRepository().use { repository ->
            val original = grave()
            repository.insert(original).join() shouldBe true
            repository.insert(original.copy()).join() shouldBe true
            repository.insert(original.copy(ownerName = "Other")).join() shouldBe false
            val changed = original.copy(experience = 5, revision = 1)
            repository.replace(original.copy(experience = 2), changed).join() shouldBe false
            repository.replace(original, changed).join() shouldBe true
            repository.replace(original, changed).join() shouldBe false
            shouldThrow<CompletionException> { repository.replace(changed, changed.copy(revision = 3)).join() }
            val tombstone = changed.copy(items = emptyList(), experience = 0, phase = NativeGravePhase.CLAIMED, revision = 2)
            repository.replace(changed, tombstone).join() shouldBe true
            repository.loadAll().join() shouldBe listOf(tombstone)
            repository.closeAsync().join()
            shouldThrow<CompletionException> { repository.loadAll().join() }
        }
    }

    "one pending inventory operation per owner spans different grave ids and releases only after acknowledgement" {
        InMemoryNativeGraveRepository().use { repository ->
            val first = grave().copy(phase = NativeGravePhase.PREPARED, operation = creation())
            val second = first.copy(id = UUID.randomUUID(), operation = creation())
            repository.insert(first).join() shouldBe true
            repository.insert(second).join() shouldBe false
            val active = first.copy(revision = 1, phase = NativeGravePhase.ACTIVE, operation = null)
            repository.replace(first, active).join() shouldBe true
            repository.insert(second).join() shouldBe true
            val competing = active.copy(revision = 2, phase = NativeGravePhase.CLAIMING, operation = creation().copy(kind = NativeGraveOperationKind.CLAIM))
            repository.replace(active, competing).join() shouldBe false
            repository.loadAll().join().first { it.id == active.id } shouldBe active
        }
    }

    "operation numbers are idempotent, owner bound and monotonic without using wall clocks" {
        InMemoryNativeGraveRepository().use { repository ->
            val owner = UUID.randomUUID()
            val operation = UUID.randomUUID()
            val first = repository.reserveSequence(operation, owner).join()
            (first > 0) shouldBe true
            repository.reserveSequence(operation, owner).join() shouldBe first
            shouldThrow<CompletionException> { repository.reserveSequence(operation, UUID.randomUUID()).join() }
            val second = repository.reserveSequence(UUID.randomUUID(), UUID.randomUUID()).join()
            (second > first) shouldBe true
            repository.reserveSequence(operation, owner).join() shouldBe first
        }
    }

    "pending transitions for one owner are exclusive within each actual inventory scope" {
        InMemoryNativeGraveRepository().use { repository ->
            val shared = grave().copy(phase = NativeGravePhase.PREPARED, operation = creation())
            val local = shared.copy(id = UUID.randomUUID(), server = "parkour",
                operation = creation().copy(server = "parkour", inventoryScope = "local:parkour"))
            repository.insert(shared).join() shouldBe true
            repository.insert(shared.copy(id = UUID.randomUUID(), operation = creation())).join() shouldBe false
            repository.insert(local).join() shouldBe true
            repository.insert(local.copy(id = UUID.randomUUID(), operation = local.operation!!.copy(id = UUID.randomUUID()))).join() shouldBe false
            shared.pendingOwnerId() shouldBe "${shared.ownerId}:network"
            local.pendingOwnerId() shouldBe "${shared.ownerId}:local:parkour"
            repository.loadAll().join().toSet() shouldBe setOf(shared, local)
        }
    }

    "review preserves its entitlement and receipt while releasing the owner's next transition" {
        InMemoryNativeGraveRepository().use { repository ->
            val prepared = grave().copy(phase = NativeGravePhase.PREPARED, operation = creation().copy(sequence = 1))
            val next = prepared.copy(id = UUID.randomUUID(), operation = creation().copy(sequence = 2))
            repository.insert(prepared).join() shouldBe true
            repository.insert(next).join() shouldBe false
            val review = prepared.copy(revision = 1, phase = NativeGravePhase.REVIEW)
            repository.replace(prepared, review).join() shouldBe true
            repository.insert(next).join() shouldBe true
            repository.loadAll().join().first { it.id == review.id } shouldBe review
            NativeGravePayload.decode(NativeGravePayload.encode(review)) shouldBe review
        }
    }

    "successful final CAS publishes the owner's maximum exact checkpoint and failed CAS leaves it unchanged" {
        InMemoryNativeGraveRepository().use { repository ->
            val first = grave()
            val second = grave().copy(ownerId = first.ownerId)
            val low = repository.reserveSequence(UUID.randomUUID(), first.ownerId).join()
            val high = repository.reserveSequence(UUID.randomUUID(), first.ownerId).join()
            val checkpoint = inventory().copy(level = 9, progress = 0.75f, totalExperience = 141)
            val completed = first.copy(revision = 1, completedSequence = high, checkpoint = checkpoint)
            repository.insert(first).join() shouldBe true
            repository.insert(second).join() shouldBe true
            repository.loadWatermarks(emptySet()).join() shouldBe emptyMap()
            repository.replace(first, completed).join() shouldBe true
            repository.replace(second, second.copy(revision = 1, completedSequence = low, checkpoint = inventory())).join() shouldBe true
            val expected = mapOf(first.ownerId to NativeGraveWatermark(first.ownerId, high, checkpoint))
            repository.loadWatermarks(setOf(first.ownerId)).join() shouldBe expected
            repository.replace(completed.copy(experience = 99), completed.copy(revision = 2, completedSequence = high + 1)).join() shouldBe false
            repository.loadWatermarks(setOf(first.ownerId)).join() shouldBe expected
            NativeGravePayload.decode(NativeGravePayload.encode(completed)) shouldBe completed
        }
    }

    "current queries include local or requested-owner live graves and preserve unfinished review/import records" {
        InMemoryNativeGraveRepository().use { repository ->
            val remoteOwner = UUID.randomUUID()
            val local = grave().copy(expiresAt = 3_000)
            val unlimited = grave().copy(expiresAt = -1)
            val remote = grave().copy(server = "classic_survival", ownerId = remoteOwner, expiresAt = 3_000)
            val unrelated = grave().copy(server = "classic_survival", expiresAt = 3_000)
            val expired = grave()
            val prepared = grave().copy(phase = NativeGravePhase.PREPARED, operation = creation())
            val claiming = grave().copy(phase = NativeGravePhase.CLAIMING, operation = creation().copy(kind = NativeGraveOperationKind.CLAIM))
            val review = grave().copy(phase = NativeGravePhase.REVIEW, operation = creation())
            val imported = grave().copy(phase = NativeGravePhase.IMPORTED)
            val claimed = grave().copy(phase = NativeGravePhase.CLAIMED, items = emptyList(), experience = 0)
            listOf(local, unlimited, remote, unrelated, expired, prepared, claiming, review, imported, claimed).forEach {
                repository.insert(it).join() shouldBe true
            }
            repository.loadCurrent("classic", setOf(remoteOwner), 2_000).join().map { it.id }.toSet() shouldBe
                setOf(local, unlimited, remote, prepared, claiming, review, imported).map { it.id }.toSet()
            repository.loadWatermarks(setOf(remoteOwner)).join() shouldBe emptyMap()
        }
    }
})

private const val STACK = "AQID"

private fun inventory() = NativeGraveInventory(List(41) { null }, 0, 0f, 0)

private fun creation() = NativeGraveOperation(UUID.randomUUID(), "classic", NativeGraveOperationKind.CREATE, inventory(), inventory())

private fun grave() = NativeGraveRecord(
    id = UUID.randomUUID(), ownerId = UUID.randomUUID(), ownerName = "Player", server = "classic", world = "world",
    x = 1, y = 64, z = -3, createdAt = 1_000, expiresAt = 2_000, experience = 3, items = listOf(STACK),
)
