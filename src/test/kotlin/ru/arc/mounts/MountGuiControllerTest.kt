package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.util.customModelDataOrNull
import java.time.Duration
import java.util.concurrent.CompletableFuture

class MountGuiControllerTest : TestBase() {
    @Test
    fun `detail ability controls stay centered for every supported count`() {
        centeredDetailAbilitySlots(0) shouldBe emptyList()
        centeredDetailAbilitySlots(1) shouldBe listOf(31)
        centeredDetailAbilitySlots(2) shouldBe listOf(30, 32)
        centeredDetailAbilitySlots(3) shouldBe listOf(29, 31, 33)
        centeredDetailAbilitySlots(4) shouldBe listOf(29, 30, 32, 33)
    }

    @Test
    fun `detail menu selects a favorite and recovers one reusable whistle`() {
        val mount = testMount()
        var favoriteMountId: String? = null
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } answers { favoriteMountId }
            every { setFavoriteMount(any(), mount) } answers {
                favoriteMountId = mount.id
                CompletableFuture.completedFuture(null)
            }
        }
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт: <mount>"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonSneakSwapHands } returns true
            every { quickSummonWhistle } returns true
            every { tuning } returns
                MountTuningDefinition(
                    speedPercentages = listOf(50, 65, 80, 90, 100),
                    walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                    walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
                )
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("FavoriteChooser")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            plainName(player.openInventory.topInventory.getItem(36)) shouldBe "Назад"
            plainName(player.openInventory.topInventory.getItem(38)) shouldBe " "
            plainName(player.openInventory.topInventory.getItem(13)) shouldBe "Выбрать любимым"
            plainName(player.openInventory.topInventory.getItem(42)) shouldBe "Свисток недоступен"

            controller.onClick(clickEvent(player.openInventory, 13))
            server.scheduler.performOneTick()
            favoriteMountId shouldBe mount.id
            plainName(player.openInventory.topInventory.getItem(13)) shouldBe "Любимый маунт"
            plainName(player.openInventory.topInventory.getItem(42)) shouldBe "Получить свисток"

            controller.onClick(clickEvent(player.openInventory, 42))
            player.inventory.contents.count { it?.type == Material.GOAT_HORN } shouldBe 1
            controller.onClick(clickEvent(player.openInventory, 42))
            player.inventory.contents.count { it?.type == Material.GOAT_HORN } shouldBe 1
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection prioritizes unlocked mounts while preserving catalog order inside each group`() {
        val firstLocked = testMount().copy(id = "locked-first", displayName = "Первый закрытый")
        val firstOwned = testMount().copy(id = "owned-first", displayName = "Первый полученный")
        val secondLocked = testMount().copy(id = "locked-second", displayName = "Второй закрытый")
        val secondOwned = testMount().copy(id = "owned-second", displayName = "Второй полученный")
        val profiles = mapOf(
            firstLocked.id to MountProfile(0, false, false),
            firstOwned.id to MountProfile(1, false, false),
            secondLocked.id to MountProfile(0, false, false),
            secondOwned.id to MountProfile(2, false, false),
        )

        prioritizeUnlockedMounts(listOf(firstLocked, firstOwned, secondLocked, secondOwned)) {
            checkNotNull(profiles[it.id])
        }.map(MountDefinition::id).shouldContainExactly(
            "owned-first",
            "owned-second",
            "locked-first",
            "locked-second",
        )
    }

    @Test
    fun `collection renders the available mount first with calm separated lore`() {
        val locked = testMount().copy(id = "locked", displayName = "Закрытый")
        val owned = testMount().copy(id = "owned", displayName = "Полученный")
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), locked) } returns MountProfile(0, false, false)
            every { profile(any(), owned) } returns MountProfile(2, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val config = mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(locked, owned)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("LoreRider")

        controller.start()
        try {
            controller.openList(player)

            val first = checkNotNull(player.openInventory.topInventory.getItem(10))
            plainName(first) shouldBe "Полученный"
            val lore = checkNotNull(first.itemMeta.lore()).map(PlainTextComponentSerializer.plainText()::serialize)
            lore.none { "●" in it } shouldBe true
            lore.count(String::isEmpty).shouldBeGreaterThanOrEqual(2)
            lore.filter(String::isNotEmpty).first() shouldBe "✔ Получен"
            val guide = checkNotNull(player.openInventory.topInventory.getItem(4))
            (1 + checkNotNull(guide.itemMeta.lore()).size) shouldBe 13
            plainName(player.openInventory.topInventory.getItem(53)) shouldBe " "
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection card explains innate abilities and active behaviors`() {
        val base = testMount()
        val mount =
            base.copy(
                abilities =
                    MountAbilities(
                        passives =
                            listOf(
                                MountPassiveAbilityDefinition(
                                    "armor",
                                    "Живая броня",
                                    MountAbilityEffect.RESISTANCE,
                                    description = listOf("Снижает входящий урон."),
                                ),
                            ),
                    ),
                behaviors =
                    listOf(
                        MountTrampleBehavior(
                            "trample",
                            "Топот",
                            description = listOf("Бьёт врагов под корпусом."),
                        ),
                    ),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = {
                    interactionConfig(
                        MountTuningDefinition(
                            listOf(50, 100),
                            listOf(110, 200, 400),
                            listOf(110, 200, 400),
                        ),
                    )
                },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("FeatureLoreRider")

        controller.start()
        try {
            controller.openList(player)
            val lore =
                checkNotNull(player.openInventory.topInventory.getItem(10)?.itemMeta?.lore())
                    .map(PlainTextComponentSerializer.plainText()::serialize)

            lore.contains("Особенности") shouldBe true
            lore.contains("• Живая броня") shouldBe true
            lore.contains("  Снижает входящий урон.") shouldBe true
            lore.contains("• Топот") shouldBe true
            lore.contains("  Бьёт врагов под корпусом.") shouldBe true
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `priced unowned mount is actionable while truly locked mount is passive`() {
        val pricedUnowned = testMount().copy(id = "priced", displayName = "Доступный")
        val lockedBase = testMount()
        val trulyLocked =
            lockedBase.copy(
                id = "truly-locked",
                displayName = "Закрытый",
                levels = lockedBase.levels.mapIndexed { index, level -> if (index == 0) level.copy(price = null) else level },
            )
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = interactionConfig(tuning)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), pricedUnowned) } returns MountProfile(0, false, false)
            every { profile(any(), trulyLocked) } returns MountProfile(0, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(pricedUnowned, trulyLocked)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("AcquisitionRider")

        controller.start()
        try {
            controller.openList(player)

            val pricedLore =
                checkNotNull(player.openInventory.topInventory.getItem(10)?.itemMeta?.lore())
                    .map(PlainTextComponentSerializer.plainText()::serialize)
            pricedLore.first() shouldBe "Доступен к получению"
            pricedLore.last() shouldBe "[▶] ЛКМ — открыть получение"

            val lockedLore =
                checkNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.lore())
                    .map(PlainTextComponentSerializer.plainText()::serialize)
            lockedLore.none { "▶" in it } shouldBe true

            controller.onClick(clickEvent(player.openInventory, 10))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(21)) shouldBe "Скорость: 100%"

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 11))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(11)) shouldBe "Закрытый"
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `category switch uses a distinct resource-pack icon for every mount type`() {
        val walking = testMount().copy(id = "walking", movement = MountMovement.WALKING)
        val flying = testMount().copy(id = "flying", movement = MountMovement.FLYING)
        val swimming = testMount().copy(id = "swimming", movement = MountMovement.SWIMMING)
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val styles =
            mapOf(
                MountGuiItemRole.CATEGORY_ALL to MountGuiItemStyle(Material.COMPASS, 11023),
                MountGuiItemRole.CATEGORY_FLYING to MountGuiItemStyle(Material.FEATHER, 11024),
                MountGuiItemRole.CATEGORY_WALKING to MountGuiItemStyle(Material.SADDLE, 11025),
                MountGuiItemRole.CATEGORY_SWIMMING to MountGuiItemStyle(Material.HEART_OF_THE_SEA, 11026),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val config = mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } answers { styles[firstArg()] ?: MountGuiItemStyle() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(walking, flying, swimming)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("IconRider")

        fun categoryIcon() = checkNotNull(player.openInventory.topInventory.getItem(49))

        controller.openList(player)
        categoryIcon().type shouldBe Material.COMPASS
        categoryIcon().customModelDataOrNull shouldBe 11023

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.FEATHER
        categoryIcon().customModelDataOrNull shouldBe 11024

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.SADDLE
        categoryIcon().customModelDataOrNull shouldBe 11025

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.HEART_OF_THE_SEA
        categoryIcon().customModelDataOrNull shouldBe 11026
    }

    @Test
    fun `progression submenu exposes selected tuning and routes a free speed change`() {
        val mount =
            testMount().copy(
                movement = MountMovement.WALKING,
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("keychain", "Брелок ×0.1", 0.1, grantOnly = true),
                        MountSizeOptionDefinition("standard", "Обычный ×1", 1.0),
                        MountSizeOptionDefinition("huge", "Огромный ×2", 2.0, minimumLevel = 2),
                        MountSizeOptionDefinition("absurd", "Абсурдный ×3", 3.0, minimumLevel = 3),
                        MountSizeOptionDefinition("colossal", "Колоссальный ×10", 10.0, grantOnly = true),
                    ),
            )
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val profile =
            MountProfile(
                level = 2,
                glowOwned = false,
                glowDisabled = false,
                ownedSizeIds = setOf("keychain"),
                selectedSpeedPercentage = 65,
                selectedStepHeightHundredths = 150,
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns profile
            every { favoriteMountId(any()) } returns null
        }
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт: <mount>"
            every { progressionTitle } returns "Развитие: <mount>"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        every { purchases.setSpeedTuning(any(), mount, tuning, 90, any()) } answers {
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        every { purchases.setSizeTuning(any(), mount, "keychain", any()) } answers {
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        every { purchases.setRiderViewAutoHide(any(), mount, false, any()) } answers {
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("TuningRider")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            controller.onClick(clickEvent(player.openInventory, 20))

            plainName(player.openInventory.topInventory.getItem(21)) shouldBe "Скорость: 65%"
            player.openInventory.topInventory.getItem(21)?.itemMeta?.enchantmentGlintOverride shouldBe true
            checkNotNull(player.openInventory.topInventory.getItem(21)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .none { "▶" in it } shouldBe true
            plainName(player.openInventory.topInventory.getItem(30)) shouldBe "Подъём: 1.50 блока"
            player.openInventory.topInventory.getItem(30)?.itemMeta?.enchantmentGlintOverride shouldBe true
            plainName(player.openInventory.topInventory.getItem(33)) shouldBe "Подъём: 4.00 блока"
            plainName(player.openInventory.topInventory.getItem(38)) shouldBe "Размер: брелок ×0.1"
            plainName(player.openInventory.topInventory.getItem(39)) shouldBe "Размер: обычный ×1"
            plainName(player.openInventory.topInventory.getItem(40)) shouldBe "Размер: огромный ×2"
            plainName(player.openInventory.topInventory.getItem(41)) shouldBe "Размер: абсурдный ×3"
            plainName(player.openInventory.topInventory.getItem(42)) shouldBe "Размер: колоссальный ×10"
            player.openInventory.topInventory.getItem(42)?.type shouldBe Material.BARRIER
            checkNotNull(player.openInventory.topInventory.getItem(42)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .any { it == "Особый размер" } shouldBe true
            plainName(player.openInventory.topInventory.getItem(49)) shouldBe "Корпус: скрывается"

            controller.onClick(clickEvent(player.openInventory, 39))
            controller.onClick(clickEvent(player.openInventory, 41))
            controller.onClick(clickEvent(player.openInventory, 42))
            verify(exactly = 0) { purchases.setSizeTuning(any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 38))
            verify(exactly = 1) { purchases.setSizeTuning(any(), mount, "keychain", any()) }

            controller.onClick(clickEvent(player.openInventory, 49))
            verify(exactly = 1) { purchases.setRiderViewAutoHide(any(), mount, false, any()) }

            controller.onClick(clickEvent(player.openInventory, 33))
            verify(exactly = 0) { purchases.setStepHeightTuning(any(), any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 23))

            verify(exactly = 1) { purchases.setSpeedTuning(any(), mount, tuning, 90, any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `ravager skins show only real deltas and localized visible trails`() {
        val trail =
            MountTrailDefinition(
                particle = "END_ROD",
                displayName = "Звёздный след",
                count = 3,
                backOffset = 0.28,
                heightRatio = 0.38,
            )
        val ravager =
            testMount().copy(
                id = "ravager",
                movement = MountMovement.WALKING,
                entityType = "RAVAGER",
                displayName = "Разоритель",
                appearance = MountAppearance(scale = 0.82),
                skins =
                    listOf(
                        MountSkinDefinition(
                            id = "starlight",
                            displayName = "Звёздный комплект",
                            iconMaterial = "AMETHYST_SHARD",
                            price = 2_500_000.0,
                            appearance = MountAppearance(scale = 0.82),
                            trail = trail,
                        ),
                    ),
            )
        val profile = MountProfile(level = 3, glowOwned = false, glowDisabled = false, ownedSkinIds = setOf("starlight"))
        val ownership = mockk<MountOwnership> {
            every { profile(any(), ravager) } returns profile
            every { favoriteMountId(any()) } returns null
        }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Разоритель"
            every { skinsTitle } returns "Облики маунта"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(ravager)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("RavagerStylist")

        controller.start()
        try {
            controller.openDetail(player, ravager.id)
            controller.onClick(clickEvent(player.openInventory, 40))

            val classicLore = checkNotNull(player.openInventory.topInventory.getItem(10)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
            classicLore.any { "0.82" in it || "взрослый" in it } shouldBe false
            classicLore.first() shouldBe "Базовый облик без следа."

            val starlightLore = checkNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
            starlightLore.any { it == "След: Звёздный след" } shouldBe true
            starlightLore.any { "END_ROD" in it || "0.82" in it || "взрослый" in it } shouldBe false
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `purchase confirmation owns the balance decision and insufficient accept is a no-op`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт"
            every { confirmTitle } returns "Покупка маунта"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 500_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("CarefulBuyer")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            controller.onClick(clickEvent(player.openInventory, 24))

            plainName(player.openInventory.topInventory.getItem(15)) shouldBe "Недостаточно средств"
            val info = checkNotNull(player.openInventory.topInventory.getItem(13)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
            info.any { it.startsWith("Баланс: ") } shouldBe true
            info.any { it.startsWith("Не хватает: ") } shouldBe true

            controller.onClick(clickEvent(player.openInventory, 15))
            verify(exactly = 0) { purchases.purchaseGlow(any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `detail progression skins and confirmation ignore every non-left click`() {
        val mount = testMount()
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = interactionConfig(tuning)
        val profile = MountProfile(level = 1, glowOwned = false, glowDisabled = false)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns profile
            every { favoriteMountId(any()) } returns null
            every { setFavoriteMount(any(), mount) } returns CompletableFuture.completedFuture(null)
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("ExactLeftRider")
        val nonLeftClicks =
            listOf(
                ClickType.RIGHT,
                ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY,
                ClickType.MIDDLE,
                ClickType.DOUBLE_CLICK,
            )

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            nonLeftClicks.forEach { controller.onClick(clickEvent(player.openInventory, 13, it)) }
            verify(exactly = 0) { ownership.setFavoriteMount(any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 20))
            nonLeftClicks.forEach { controller.onClick(clickEvent(player.openInventory, 20, it)) }
            verify(exactly = 0) { purchases.setSpeedTuning(any(), any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 45))
            controller.onClick(clickEvent(player.openInventory, 40))
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 11, it))
                player.openInventory.topInventory.size shouldBe 54
            }

            controller.onClick(clickEvent(player.openInventory, 11))
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 15, it))
                controller.onClick(clickEvent(player.openInventory, 11, it))
                player.openInventory.topInventory.size shouldBe 27
            }
            verify(exactly = 0) { purchases.purchaseSkin(any(), any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `list controls accept only their advertised exact clicks`() {
        val mounts =
            (0 until 30).map { index ->
                testMount().copy(id = "bee-$index", displayName = "Маунт $index")
            }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = interactionConfig(tuning)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(level = 1, glowOwned = false, glowDisabled = false)
            every { favoriteMountId(any()) } returns null
        }
        val sessions = mockk<MountSessionController>(relaxed = true) {
            every { spawn(any(), any(), any(), any()) } returns MountSpawnResult.ALREADY_RIDING
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(mounts) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = sessions,
            )
        val player = server.addPlayer("ListClickRider")
        val nonLeftClicks =
            listOf(
                ClickType.RIGHT,
                ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY,
                ClickType.MIDDLE,
                ClickType.DOUBLE_CLICK,
            )
        val unsupportedCardAndFilterClicks = nonLeftClicks - ClickType.RIGHT

        controller.start()
        try {
            nonLeftClicks.forEach {
                controller.openList(player)
                controller.onClick(clickEvent(player.openInventory, 50, it))
                plainName(player.openInventory.topInventory.getItem(10)) shouldBe "Маунт 0"

                controller.onClick(clickEvent(player.openInventory, 45, it))
                player.openInventory.topInventory.size shouldBe 54
            }

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 50))
            plainName(player.openInventory.topInventory.getItem(10)) shouldBe "Маунт 28"
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 48, it))
                plainName(player.openInventory.topInventory.getItem(10)) shouldBe "Маунт 28"
            }

            unsupportedCardAndFilterClicks.forEach {
                controller.openList(player)
                controller.onClick(clickEvent(player.openInventory, 49, it))
                player.openInventory.topInventory.getItem(49)?.itemMeta?.enchantmentGlintOverride shouldBe false

                controller.onClick(clickEvent(player.openInventory, 10, it))
                player.openInventory.topInventory.size shouldBe 54
            }
            verify(exactly = 0) { sessions.spawn(any(), any(), any(), any()) }

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 49, ClickType.RIGHT))
            player.openInventory.topInventory.getItem(49)?.itemMeta?.enchantmentGlintOverride shouldBe true

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 49))
            player.openInventory.topInventory.getItem(49)?.type shouldBe Material.FEATHER

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 10, ClickType.RIGHT))
            player.openInventory.topInventory.size shouldBe 45
        } finally {
            controller.shutdown()
        }
    }

    private fun clickEvent(
        view: org.bukkit.inventory.InventoryView,
        rawSlot: Int,
        clickType: ClickType = ClickType.LEFT,
    ): InventoryClickEvent {
        val action =
            when (clickType) {
                ClickType.RIGHT -> InventoryAction.PICKUP_HALF
                ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT,
                -> InventoryAction.MOVE_TO_OTHER_INVENTORY
                ClickType.NUMBER_KEY -> InventoryAction.HOTBAR_SWAP
                ClickType.MIDDLE -> InventoryAction.CLONE_STACK
                ClickType.DOUBLE_CLICK -> InventoryAction.COLLECT_TO_CURSOR
                else -> InventoryAction.PICKUP_ALL
            }
        return InventoryClickEvent(
            view,
            InventoryType.SlotType.CONTAINER,
            rawSlot,
            clickType,
            action,
            if (clickType == ClickType.NUMBER_KEY) 0 else -1,
        )
    }

    private fun interactionConfig(tuning: MountTuningDefinition) =
        mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { detailTitle } returns "Маунт: <mount>"
            every { progressionTitle } returns "Развитие: <mount>"
            every { skinsTitle } returns "Облики: <mount>"
            every { confirmTitle } returns "Подтверждение"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { backCommand } returns ""
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }

    private fun mountGuiController(
        configProvider: () -> MountModuleConfig,
        catalogProvider: () -> MountCatalog,
        ownership: MountOwnership,
        wallet: MountWallet,
        purchases: MountPurchaseCoordinator,
        sessions: MountSessionController,
    ): MountGuiController {
        val summons = MountSummonService(configProvider, catalogProvider, ownership, sessions)
        return MountGuiController(
            plugin = plugin,
            configProvider = configProvider,
            catalogProvider = catalogProvider,
            ownership = ownership,
            wallet = wallet,
            purchases = purchases,
            summons = summons,
            quickSummons = MountQuickSummonController(plugin, configProvider, summons),
        )
    }

    private fun plainName(stack: org.bukkit.inventory.ItemStack?): String =
        PlainTextComponentSerializer.plainText().serialize(checkNotNull(stack?.itemMeta?.displayName()))
}
