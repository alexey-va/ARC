package ru.arc.hooks.citizens

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.MetadataStore
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.trait.HologramTrait
import net.citizensnpcs.trait.text.Text
import java.util.UUID
import ru.arc.core.TestTaskScheduler

class ArcNpcHologramServiceTest : StringSpec({
    "repeated reconciliation must not remove a reattached empty Citizens trait" {
        mockkStatic(CitizensAPI::class)
        try {
            val registry = mockk<NPCRegistry>()
            val npc = mockk<NPC>(relaxed = true)
            val data = mockk<MetadataStore>(relaxed = true)
            val trait = mockk<HologramTrait>(relaxed = true)
            every { CitizensAPI.getNPCRegistry() } returns registry
            every { registry.iterator() } answers { mutableListOf(npc).iterator() }
            every { npc.id } returns 12
            every { npc.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000012")
            every { npc.rawName } returns "Эдгар"
            every { npc.isSpawned } returns false
            every { npc.entity } returns null
            every { npc.data() } returns data
            every { data.get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true) } returns false
            every { data.get<String>("arc_npc_hologram_backup_v1", "") } returns ""
            every { npc.getTraitNullable(Text::class.java) } returns null
            every { npc.getTraitNullable(HologramTrait::class.java) } returns trait
            every { trait.hologramRenderers } returns emptyList()
            every { trait.nameRenderer } returns null
            every { trait.lines } returns emptyList()
            every { trait.lineHeight } returns 0.25
            every { trait.viewRange } returns -1
            val store = mockk<NpcPresentationStore>(relaxed = true)
            every { store.load() } returns emptyMap()
            val service = ArcNpcHologramService(hologramConfig(), store, mockk(relaxed = true))

            repeat(100) { service.reconcileAll() }

            verify(exactly = 0) { npc.removeTrait(HologramTrait::class.java) }
            verify(exactly = 1) { store.save(any()) }
        } finally {
            unmockkStatic(CitizensAPI::class)
        }
    }

    "native text is saved before it is hidden and cleared, then survives repeated reconciliation" {
        withHologram(listOf("Мастер", "§6Эдгар")) { f ->
            f.service.reconcileAll()
            f.records.getValue(f.uuid).presentation.let {
                it.name shouldBe "§6Эдгар"
                it.nameVisible shouldBe true
                it.lines shouldBe listOf("Мастер")
            }
            verifyOrder {
                f.store.save(any())
                f.data.setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false)
                f.trait.clear()
            }
            repeat(100) { f.service.reconcileAll() }
            f.service.summary(f.npc)?.get("lines") shouldBe listOf("Мастер")
            verify(exactly = 1) { f.trait.clear() }
            verify(exactly = 1) { f.store.save(any()) }
        }
    }

    "failed durable migration leaves native content untouched" {
        withHologram(listOf("Мастер")) { f ->
            every { f.store.save(any()) } throws IllegalStateException("disk unavailable")
            shouldThrow<IllegalStateException> { f.service.reconcileAll() }
            verify(exactly = 0) { f.data.setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, any()) }
            verify(exactly = 0) { f.trait.clear() }
            verify(exactly = 0) { f.npc.removeTrait(HologramTrait::class.java) }
        }
    }

    "legacy backup wins over an empty native trait" {
        val backup = """{"hadHologram":true,"hologramLines":["Подмастерье","§6Эдгар"],"lineHeight":0.3,"viewRange":32,"nameplateValue":"false","speechBubbles":true,"sendTextToChat":false}"""
        withHologram(legacyBackup = backup) { f ->
            f.service.reconcileAll()
            val record = f.records.getValue(f.uuid)
            record.legacyBackup shouldBe backup
            record.presentation.name shouldBe "§6Эдгар"
            record.presentation.lines shouldBe listOf("Подмастерье")
            f.service.desiredSpeechBubbles(f.npc) shouldBe true
        }
    }

    "ARC rename and an explicitly empty body survive restart without mutating the internal name" {
        withHologram(listOf("Мастер")) { f ->
            f.service.reconcileAll()
            f.service.patchName(f.npc, "§6Мастер Эдгар") shouldBe true
            f.service.clearHologram(f.npc) shouldBe true
            f.service.close()
            val restarted = ArcNpcHologramService(hologramConfig(), f.store, TestTaskScheduler())
            restarted.reconcileAll()
            restarted.desiredName(f.npc) shouldBe "§6Мастер Эдгар"
            restarted.summary(f.npc) shouldBe null
            verify(exactly = 0) { f.npc.name = any() }
            verify(exactly = 0) { f.npc.getOrAddTrait(HologramTrait::class.java) }
            verify(exactly = 0) { f.trait.addLine(any<String>()) }
        }
    }

    "a reused numeric ID does not inherit the old UUID's presentation" {
        withHologram { f ->
            f.service.reconcileAll()
            f.service.patchName(f.npc, "Первый NPC")
            every { f.npc.uniqueId } returns UUID.randomUUID()
            every { f.npc.rawName } returns "Другой NPC"
            f.service.reconcileAll()
            f.service.desiredName(f.npc) shouldBe "Другой NPC"
            f.records.size shouldBe 2
        }
    }
})

private class HologramFixture(nativeLines: List<String>, legacyBackup: String) {
    val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
    val registry = mockk<NPCRegistry>()
    val npc = mockk<NPC>(relaxed = true)
    val data = mockk<MetadataStore>(relaxed = true)
    val trait = mockk<HologramTrait>(relaxed = true)
    val store = mockk<NpcPresentationStore>()
    var records = emptyMap<UUID, NpcPresentationRecord>()
    val service: ArcNpcHologramService

    init {
        var lines = nativeLines
        var nativeNameVisible = true
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { registry.iterator() } answers { mutableListOf(npc).iterator() }
        every { npc.id } returns 12
        every { npc.uniqueId } returns uuid
        every { npc.rawName } returns "Эдгар"
        every { npc.isSpawned } returns false
        every { npc.entity } returns null
        every { npc.data() } returns data
        every { data.get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true) } answers { nativeNameVisible }
        every { data.setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false) } answers { nativeNameVisible = false }
        every { data.get<String>("arc_npc_hologram_backup_v1", "") } returns legacyBackup
        every { npc.getTraitNullable(Text::class.java) } returns null
        every { npc.getTraitNullable(HologramTrait::class.java) } returns trait
        every { trait.hologramRenderers } returns emptyList()
        every { trait.nameRenderer } returns null
        every { trait.lines } answers { lines }
        every { trait.clear() } answers { lines = emptyList() }
        every { trait.lineHeight } returns 0.25
        every { trait.viewRange } returns -1
        every { store.load() } answers { records }
        every { store.save(any()) } answers { records = firstArg() }
        service = ArcNpcHologramService(hologramConfig(), store, TestTaskScheduler())
    }
}

private fun withHologram(nativeLines: List<String> = emptyList(), legacyBackup: String = "", block: (HologramFixture) -> Unit) {
    mockkStatic(CitizensAPI::class)
    try {
        block(HologramFixture(nativeLines, legacyBackup))
    } finally {
        unmockkStatic(CitizensAPI::class)
    }
}

private fun hologramConfig() = ArcNpcHologramConfig(
    enabled = true, followIntervalTicks = 2, reconcileIntervalTicks = 40,
    teleportDurationTicks = 2, nameOffset = 0.20, bodyGap = 0.26,
    viewRange = 1f, scale = 0.92f, lineWidth = 230, backgroundAlpha = 112,
    speechDurationTicks = 100,
)
