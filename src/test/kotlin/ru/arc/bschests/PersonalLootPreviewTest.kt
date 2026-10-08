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
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.config.Config
import ru.arc.repository.CachedRepository
import ru.arc.repository.RepoResult
import java.util.UUID

class PersonalLootPreviewTest : TestBase() {
    private var previewScope: CoroutineScope? = null

    @AfterEach
    fun clearPreviewHarness() {
        runCatching { PersonalLootModule.javaClass.getDeclaredMethod("clearPreviewReads").apply { isAccessible = true }.invoke(PersonalLootModule) }
        setModuleField("repo", null)
        setModuleField("repositoryScope", null)
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
        installPreviewHarness(repository, maxPlayers = 5)

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
        installPreviewHarness(repository, maxPlayers = 1)

        PersonalLootModule.preview(viewer, chestUuid, listOf(block)) shouldBe PersonalLootPreview.Unavailable

        verify(exactly = 0) { repository.getNow(any()) }
        coVerify(exactly = 0) { repository.get(any()) }
    }

    private fun installPreviewHarness(repository: CachedRepository<CustomLootData>, maxPlayers: Int) {
        runCatching { PersonalLootModule.javaClass.getDeclaredMethod("clearPreviewReads").apply { isAccessible = true }.invoke(PersonalLootModule) }
        previewScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        setModuleField("repo", repository)
        setModuleField("repositoryScope", previewScope)
        setModuleField("config", mockk<Config>())
        setModuleField("key", NamespacedKey(plugin, "ploot"))
        setModuleField("uuidKey", NamespacedKey(plugin, "ploot_uuid"))
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
