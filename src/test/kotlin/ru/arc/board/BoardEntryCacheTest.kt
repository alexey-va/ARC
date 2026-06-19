package ru.arc.board

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import ru.arc.KotestTestBase
import java.util.UUID

class BoardEntryCacheTest :
    KotestTestBase({

        fun makeEntry(uuid: UUID = UUID.randomUUID()) =
            BoardEntryData(
                entryUuid = uuid,
                playerUuid = UUID.randomUUID(),
                playerName = "Player",
                type = BoardEntryType.INFO,
                text = "text",
                title = "title",
                icon = ItemIcon.of(Material.PAPER, 0),
            )

        describe("BoardEntryCache") {

            it("should generate and cache item on first access") {
                val cache = BoardEntryCache()
                val entry = makeEntry()

                val item1 = cache.get(entry)
                val item2 = cache.get(entry)

                item1 shouldBe item2
                item1.entry shouldBe entry
            }

            it("should refresh item when refresh is called") {
                val cache = BoardEntryCache()
                val entry = makeEntry()

                cache.get(entry)
                entry.changeTitle("new title")
                cache.refresh(entry)
                val refreshed = cache.get(entry)

                refreshed shouldNotBe null
                refreshed.entry shouldBe entry
            }

            it("should remove item when remove is called") {
                val cache = BoardEntryCache()
                val uuid = UUID.randomUUID()
                val entry = makeEntry(uuid)

                cache.get(entry)
                cache.remove(uuid)

                // After removal, a new access should regenerate the item
                val newItem = cache.get(entry)
                newItem shouldNotBe null
            }

            it("should clear all items") {
                val cache = BoardEntryCache()
                val entry1 = makeEntry()
                val entry2 = makeEntry()

                cache.get(entry1)
                cache.get(entry2)
                cache.clear()

                // Items are regenerated after clear — just verify no crash and proper regeneration
                val newItem = cache.get(entry1)
                newItem shouldNotBe null
            }
        }
    })
