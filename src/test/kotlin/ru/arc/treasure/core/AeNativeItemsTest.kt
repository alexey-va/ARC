package ru.arc.treasure.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime

class AeNativeItemsTest {
    @Test
    fun `supports only the fixed native AE item allowlist and validated args`() {
        val materializer = AeNativeItemMaterializer { null }

        listOf(
            ae("magic", 16, AeArg.RandomTier, AeArg.IntRange(3, 3)),
            ae("whitescroll", 4),
            ae("blackscroll", 2, AeArg.IntRange(95, 95)),
            ae("randomizer", 8, AeArg.RandomTier),
            ae("holywhitescroll", 1),
        ).forEach { assertTrue(materializer.supports(it)) }

        listOf(
            ae("mystery", 1),
            ae("secret", 1, AeArg.RandomTier),
            ae("builderswand", 1),
            ae("magic", 16, AeArg.RandomTier),
            ae("magic", 16, AeArg.RandomTier, AeArg.IntRange(0, 3)),
            ae("magic", 16, AeArg.RandomTier, AeArg.IntRange(3, 3), AeArg.RandomTier),
            ae("blackscroll", 1, AeArg.IntRange(95, 101)),
            ae("randomizer", 8),
            Treasure.Ae(kind = AeKind.RANDOM_BOOK, amount = 3),
            ae("magic", 65, AeArg.RandomTier, AeArg.IntRange(3, 3)),
        ).forEach { assertFalse(materializer.supports(it)) }

        assertFalse(materializer.available())
    }

    @Test
    fun `preview uses deterministic arguments and splits cloned native metadata by stack size`() {
        MockBukkitTestRuntime.open().use {
            val marker = NamespacedKey("arc", "ae-native-test")
            val prototype = ItemStack(Material.EGG).apply {
                itemMeta = itemMeta.apply {
                    persistentDataContainer.set(marker, PersistentDataType.STRING, "native-ae")
                }
            }
            val factory = mockk<AeNativeItemFactories>()
            every { factory.magicDust("SIMPLE", 5) } returns prototype
            val materializer = AeNativeItemMaterializer { factory }

            val rewards =
                materializer.create(
                    ae("magic", 32, AeArg.RandomTier, AeArg.IntRange(3, 7)),
                    preview = true,
                )

            val items = requireNotNull(rewards)
            assertEquals(listOf(16, 16), items.map { it.amount })
            assertTrue(
                items.all {
                    it.itemMeta.persistentDataContainer.get(marker, PersistentDataType.STRING) == "native-ae"
                },
            )
            verify(exactly = 1) { factory.magicDust("SIMPLE", 5) }
        }
    }

    @Test
    fun `grant uses existing AE loot tier and exact fixed dust success`() {
        MockBukkitTestRuntime.open().use {
            val factory = mockk<AeNativeItemFactories>()
            every { factory.magicDust("ELITE", 3) } returns ItemStack(Material.PAPER)
            val materializer = AeNativeItemMaterializer { factory }

            io.mockk.mockkObject(AeLoot)
            try {
                every { AeLoot.randomTier() } returns "ELITE"
                every { AeLoot.rollInt(3, 3) } returns 3

                val result = materializer.create(ae("magic", 16, AeArg.RandomTier, AeArg.IntRange(3, 3)))

                assertEquals(1, result?.size)
                assertEquals(16, result?.single()?.amount)
                verify(exactly = 1) { factory.magicDust("ELITE", 3) }
            } finally {
                unmockkObject(AeLoot)
            }
        }
    }

    @Test
    fun `native factory absence rejects creation without a fake or command fallback`() {
        val materializer = AeNativeItemMaterializer { null }

        assertFalse(materializer.available())
        assertNull(materializer.create(ae("whitescroll", 1)))
    }

    @Test
    fun `allowlisted rewards call their native factory with deterministic preview arguments`() {
        MockBukkitTestRuntime.open().use {
            val prototype = ItemStack(Material.PAPER)
            val factory = mockk<AeNativeItemFactories>()
            every { factory.magicDust("SIMPLE", 4) } returns prototype
            every { factory.whiteScroll() } returns prototype
            every { factory.blackScroll(85) } returns prototype
            every { factory.randomizer("SIMPLE") } returns prototype
            every { factory.holyWhiteScroll() } returns prototype
            val materializer = AeNativeItemMaterializer { factory }
            fun total(treasure: Treasure.Ae): Int =
                requireNotNull(materializer.create(treasure, preview = true)).sumOf { it.amount }

            assertEquals(2, total(ae("magic", 2, AeArg.RandomTier, AeArg.IntRange(3, 5))))
            assertEquals(2, total(ae("whitescroll", 2)))
            assertEquals(3, total(ae("blackscroll", 3, AeArg.IntRange(80, 90))))
            assertEquals(2, total(ae("randomizer", 2, AeArg.RandomTier)))
            assertEquals(1, total(ae("holywhitescroll", 1)))

            verify(exactly = 1) { factory.magicDust("SIMPLE", 4) }
            verify(exactly = 1) { factory.whiteScroll() }
            verify(exactly = 1) { factory.blackScroll(85) }
            verify(exactly = 1) { factory.randomizer("SIMPLE") }
            verify(exactly = 1) { factory.holyWhiteScroll() }
            assertTrue(materializer.available())
        }
    }

    private fun ae(
        name: String,
        amount: Int,
        vararg args: AeArg,
    ) =
        Treasure.Ae(
            kind = AeKind.ITEM,
            itemName = name,
            amount = amount,
            args = args.toList(),
        )
}
