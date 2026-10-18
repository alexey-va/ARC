package ru.arc.bschests

import com.jeff_media.customblockdata.CustomBlockData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.config.Config
import ru.arc.repository.CachedRepository
import ru.arc.repository.RepoResult
import ru.arc.repository.InMemoryStorage
import ru.arc.repository.Storage
import java.util.UUID

class PersonalLootPreviewTest : TestBase() {
    private var previewScope: CoroutineScope? = null
    private var identities: PersonalLootIdentities? = null

    @AfterEach
    fun clearPreviewHarness() {
        runCatching { PersonalLootModule.javaClass.getDeclaredMethod("clearPreviewReads").apply { isAccessible = true }.invoke(PersonalLootModule) }
        setModuleField("repo", null)
        setModuleField("repositoryScope", null)
        setModuleField("identities", null)
        identities?.tasks?.close()
        previewScope?.cancel()
        previewScope = null
    }

    @Test
    fun `cold lookup hides until repository confirms absence and never creates or mutates loot`() = runBlocking<Unit> {
        val viewer = UUID.randomUUID()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid, "")
        val lootId = "$viewer:::$chestUuid"
        val repository = mockk<CachedRepository<CustomLootData>>()
        val readStarted = CompletableDeferred<Unit>()
        val readResult = CompletableDeferred<RepoResult<CustomLootData?>>()
        every { repository.getNow(lootId) } returns null
        coEvery { repository.get(lootId) } coAnswers {
            readStarted.complete(Unit)
            readResult.await()
        }
        installPreviewHarness(repository, chestUuid, maxPlayers = 5)

        PersonalLootModule.preview(viewer, chestUuid, listOf(block)) shouldBe PersonalLootPreview.Unavailable
        withTimeout(3_000) { readStarted.await() }
        readResult.complete(RepoResult.success<CustomLootData?>(null))

        val confirmedAbsent = withTimeout(3_000) {
            var result: PersonalLootPreview
            do {
                result = PersonalLootModule.preview(viewer, chestUuid, listOf(block))
                if (result !is PersonalLootPreview.Contents) delay(5)
            } while (result !is PersonalLootPreview.Contents)
            result
        }

        confirmedAbsent shouldBe PersonalLootPreview.Contents(emptyList(), usePhysicalTemplate = true)
        coVerify(exactly = 1) { repository.get(lootId) }
        verify(exactly = 0) { repository.markDirty(any()) }
        CustomBlockData(block, plugin).get(NamespacedKey(plugin, "ploot"), PersistentDataType.STRING) shouldBe ""
        CustomBlockData(block, plugin).get(NamespacedKey(plugin, "ploot_uuid"), PersistentDataType.STRING) shouldBe chestUuid.toString()
    }

    @Test
    fun `max player admission cap rejects another viewer before any repository lookup`() {
        val viewer = UUID.randomUUID()
        val admitted = UUID.randomUUID()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid, admitted.toString())
        val repository = mockk<CachedRepository<CustomLootData>>()
        installPreviewHarness(repository, chestUuid, maxPlayers = 1)

        PersonalLootModule.preview(viewer, chestUuid, listOf(block)) shouldBe PersonalLootPreview.Unavailable

        verify(exactly = 0) { repository.getNow(any()) }
        coVerify(exactly = 0) { repository.get(any()) }
    }

    @Test
    fun `preview rejects malformed claims different pools and unknown alias state before loot lookup`() {
        val viewer = UUID.randomUUID()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid, "not-a-player-uuid")
        val repository = mockk<CachedRepository<CustomLootData>>()
        installPreviewHarness(repository, chestUuid, maxPlayers = 5)
        PersonalLootModule.preview(viewer, chestUuid, listOf(block)) shouldBe PersonalLootPreview.Unavailable

        val companion = block.world.getBlockAt(5, 64, 4).apply { type = Material.CHEST }
        CustomBlockData(block, plugin).set(NamespacedKey(plugin, "ploot"), PersistentDataType.STRING, "")
        CustomBlockData(companion, plugin).apply {
            set(NamespacedKey(plugin, "ploot_uuid"), PersistentDataType.STRING, chestUuid.toString())
            set(NamespacedKey(plugin, "ploot"), PersistentDataType.STRING, "")
            set(NamespacedKey(plugin, "ploot_pool"), PersistentDataType.STRING, "different_custom_pool")
        }
        PersonalLootModule.preview(viewer, chestUuid, listOf(block, companion)) shouldBe PersonalLootPreview.Unavailable
        identities?.tasks?.close()
        identities = PersonalLootIdentities(InMemoryStorage())
        setModuleField("identities", identities)
        PersonalLootModule.preview(viewer, chestUuid, listOf(block)) shouldBe PersonalLootPreview.Unavailable
        verify(exactly = 0) { repository.getNow(any()) }
        coVerify(exactly = 0) { repository.get(any()) }
    }

    @Test
    fun `restart preview strictly loads aliases on existing worker without creating claims or rows`() = runBlocking<Unit> {
        val viewer = UUID.randomUUID()
        val canonical = UUID(0, 1)
        val legacy = UUID(0, 2)
        val block = markedChest(canonical, viewer.toString())
        val repository = mockk<CachedRepository<CustomLootData>>()
        installPreviewHarness(repository, canonical, maxPlayers = 5)
        val exhausted = CustomLootData(viewer, canonical, items = ru.arc.network.repos.ItemList(), filled = true, debrisAdded = true)
        val remaining = CustomLootData(viewer, legacy, items = ru.arc.network.repos.ItemList().apply {
            add(ItemStack(Material.DIAMOND, 3))
        }, filled = true, debrisAdded = true)
        every { repository.getNow(exhausted.id()) } returns exhausted
        every { repository.getNow(remaining.id()) } returns remaining
        val backing = InMemoryStorage<PersonalLootIdentity>()
        val members = listOf(canonical.toString(), legacy.toString())
        members.forEach { backing.put(PersonalLootIdentity(it, members, listOf(viewer.toString()))) }
        val started = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val delayed = object : Storage<PersonalLootIdentity> by backing {
            override suspend fun load(id: String): RepoResult<PersonalLootIdentity?> {
                started.complete(Unit)
                allowRead.await()
                return backing.load(id)
            }
        }
        identities?.tasks?.close()
        identities = PersonalLootIdentities(delayed)
        setModuleField("identities", identities)

        PersonalLootModule.preview(viewer, canonical, listOf(block)) shouldBe PersonalLootPreview.Unavailable
        withTimeout(3_000) { started.await() }
        verify(exactly = 0) { repository.getNow(any()) }
        allowRead.complete(Unit)
        val preview = withTimeout(3_000) {
            var result: PersonalLootPreview
            do {
                result = PersonalLootModule.preview(viewer, canonical, listOf(block))
                if (result !is PersonalLootPreview.Contents) delay(5)
            } while (result !is PersonalLootPreview.Contents)
            result
        }
        preview.items.filterNotNull().single() shouldBe ItemStack(Material.DIAMOND, 3)
        backing.saveCount shouldBe 0
        coVerify(exactly = 0) { repository.getOrCreate(any(), any()) }
        verify(exactly = 0) { repository.markDirty(any()) }
        CustomBlockData(block, plugin).get(NamespacedKey(plugin, "ploot"), PersistentDataType.STRING) shouldBe viewer.toString()
    }

    private fun installPreviewHarness(repository: CachedRepository<CustomLootData>, chestUuid: UUID, maxPlayers: Int) {
        runCatching { PersonalLootModule.javaClass.getDeclaredMethod("clearPreviewReads").apply { isAccessible = true }.invoke(PersonalLootModule) }
        previewScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        setModuleField("repo", repository)
        setModuleField("repositoryScope", previewScope)
        identities = PersonalLootIdentities(InMemoryStorage()).also {
            runBlocking { it.persist(PersonalLootGroup(listOf(chestUuid), emptySet())) }
        }
        setModuleField("identities", identities)
        setModuleField("config", mockk<Config>().also {
            every { it.string("loot.common-pool", "structures_common") } returns "structures_common"
        })
        setModuleField("key", NamespacedKey(plugin, "ploot"))
        setModuleField("uuidKey", NamespacedKey(plugin, "ploot_uuid"))
        setModuleField("poolKey", NamespacedKey(plugin, "ploot_pool"))
        setModuleField("inventories", setOf(InventoryType.CHEST))
        setModuleField("maxPlayers", maxPlayers)
        setModuleField("useBsLoot", true)
    }

    private fun markedChest(chestUuid: UUID, players: String) =
        server.addSimpleWorld("personal-loot-preview").getBlockAt(4, 64, 4).apply {
            type = Material.CHEST
            CustomBlockData(this, plugin).apply {
                set(NamespacedKey(plugin, "ploot_uuid"), PersistentDataType.STRING, chestUuid.toString())
                set(NamespacedKey(plugin, "ploot"), PersistentDataType.STRING, players)
            }
        }

    private fun setModuleField(name: String, value: Any?) {
        PersonalLootModule.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(PersonalLootModule, value)
    }
}
