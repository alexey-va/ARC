package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ChatGlyphPolicyTest : FreeSpec({
    val chatPermission = "ia.user.image.chat"
    val premiumPermission = "ia.user.image.use.arc.premium_token"
    val premiumToken = "\uE516"
    val premiumDefinition = ChatGlyphDefinition(
        id = "arc:premium_token",
        unicode = premiumToken,
        permission = premiumPermission,
        technical = false,
    )

    "ordinary Cyrillic and ordinary emoji remain unchanged" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))

        policy.violation("Привет ☀️ 🧭", hasPermission = { false }) shouldBe null
    }

    "a permissioned raw glyph needs both the chat gate and its expanded image node" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))
        val queriedPermissions = mutableListOf<String>()

        val violation = policy.violation(
            message = "${premiumToken} coins",
            hasPermission = { permission ->
                queriedPermissions += permission
                permission == chatPermission || permission == premiumPermission
            },
        )

        violation shouldBe null
        queriedPermissions.shouldContainExactly(chatPermission, premiumPermission)
    }

    "the chat gate is required even when the effective per-image permission is granted" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))

        policy.violation(
            message = premiumToken,
            hasPermission = { it == premiumPermission },
        ) shouldBe ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "arc:premium_token")
    }

    "a configured permission is checked through the full effective ItemsAdder node" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))
        val queriedPermissions = mutableListOf<String>()

        val violation = policy.violation(
            message = premiumToken,
            hasPermission = { permission ->
                queriedPermissions += permission
                permission == chatPermission || permission == premiumPermission
            },
        )

        violation shouldBe null
        queriedPermissions.shouldContainExactly(chatPermission, premiumPermission)
    }

    "an explicit effective denial blocks a glyph when no wildcard grant applies" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))
        val queriedPermissions = mutableListOf<String>()

        val violation = policy.violation(
            message = premiumToken,
            hasPermission = { permission ->
                queriedPermissions += permission
                permission == chatPermission
            },
        )

        violation shouldBe ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "arc:premium_token")
        queriedPermissions.shouldContainExactly(chatPermission, premiumPermission)
    }

    "a public compact glyph still requires the channel chat gate" {
        val publicDefinition = premiumDefinition.copy(permission = null)
        val policy = ChatGlyphPolicy(listOf(publicDefinition))

        policy.violation(premiumToken, hasPermission = { it == chatPermission }) shouldBe null
        policy.violation(premiumToken, hasPermission = { false }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "arc:premium_token")
    }

    "recognized chat and PlaceholderAPI aliases use the same permissions as the raw glyph" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))
        val grantedPermissions = setOf(chatPermission, premiumPermission)

        policy.violation(":premium_token:", grantedPermissions::contains) shouldBe null
        policy.violation("%img_premium_token%", grantedPermissions::contains) shouldBe null
        policy.violation(":arc:premium_token:", grantedPermissions::contains) shouldBe null
        policy.violation(":premium_token:", hasPermission = { it == chatPermission }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "arc:premium_token")
    }

    "unknown colon text remains ordinary text" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))

        policy.violation(
            "write :not_a_registered_glyph: or %img_unknown%",
            hasPermission = { false },
        ) shouldBe null
    }

    "technical definitions are denied even when every permission is granted" {
        val technicalDefinition = premiumDefinition.copy(technical = true)
        val policy = ChatGlyphPolicy(listOf(technicalDefinition))

        policy.violation(
            premiumToken,
            hasPermission = { true },
        ) shouldBe ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL, "arc:premium_token")
    }

    "offset placeholders are technical even when the alias has no definition" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))

        policy.violation(":offset_-32:", hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL)
        policy.violation("%img_offset_32%", hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL)
        policy.violation(":offset_custom:", hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL)
    }

    "duplicate Unicode definitions require every matching image grant" {
        val secondDefinition = ChatGlyphDefinition(
            id = "other:token",
            unicode = premiumToken,
            permission = "ia.user.image.use.other.token",
            technical = false,
        )
        val policy = ChatGlyphPolicy(listOf(secondDefinition, premiumDefinition))

        policy.violation(
            premiumToken,
            hasPermission = { it == chatPermission || it == premiumPermission },
        ) shouldBe ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "other:token")

        policy.violation(
            premiumToken,
            hasPermission = { it == chatPermission || it == premiumPermission || it == "ia.user.image.use.other.token" },
        ) shouldBe null
    }

    "a duplicate technical definition keeps the shared scalar technical" {
        val technicalDefinition = ChatGlyphDefinition(
            id = "arc:internal_panel",
            unicode = premiumToken,
            permission = null,
            technical = true,
        )
        val policy = ChatGlyphPolicy(listOf(premiumDefinition, technicalDefinition))

        policy.violation(premiumToken, hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL, "arc:internal_panel")
    }

    "the first and last BMP private-use scalars are rejected" {
        val policy = ChatGlyphPolicy(emptyList())

        policy.violation("\uE000", hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
        policy.violation("\uF8FF", hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
    }

    "the first and last supplementary private-use scalars are rejected" {
        val policy = ChatGlyphPolicy(emptyList())

        policy.violation(String(Character.toChars(0xF0000)), hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
        policy.violation(String(Character.toChars(0xFFFFD)), hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
        policy.violation(String(Character.toChars(0x100000)), hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
        policy.violation(String(Character.toChars(0x10FFFD)), hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
    }

    "valid scalars outside private-use ranges and isolated surrogates are unaffected" {
        val policy = ChatGlyphPolicy(emptyList())

        policy.violation("\uD7FF", hasPermission = { true }) shouldBe null
        policy.violation("\uF900", hasPermission = { true }) shouldBe null
        policy.violation(String(Character.toChars(0xEFFFF)), hasPermission = { true }) shouldBe null
        policy.violation(String(Character.toChars(0xFFFFE)), hasPermission = { true }) shouldBe null
        policy.violation(String(Character.toChars(0xFFFFF)), hasPermission = { true }) shouldBe null
        policy.violation(String(Character.toChars(0x10FFFE)), hasPermission = { true }) shouldBe null
        policy.violation(String(Character.toChars(0x10FFFF)), hasPermission = { true }) shouldBe null
        policy.violation("\uDFFF", hasPermission = { true }) shouldBe null
    }

    "a known supplementary private-use glyph remains individually permissioned" {
        val supplementaryGlyph = String(Character.toChars(0xF0E00))
        val supplementaryDefinition = ChatGlyphDefinition(
            id = "elitemobs:rank_menu",
            unicode = supplementaryGlyph,
            permission = "ia.user.image.use.elitemobs.rank_menu",
            technical = false,
        )
        val policy = ChatGlyphPolicy(listOf(supplementaryDefinition))

        policy.violation(
            supplementaryGlyph,
            hasPermission = { it == chatPermission || it == "ia.user.image.use.elitemobs.rank_menu" },
        ) shouldBe null
        policy.violation(
            supplementaryGlyph,
            hasPermission = { it == chatPermission },
        ) shouldBe ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "elitemobs:rank_menu")
    }

    "a PUA scalar inside mixed text is found without damaging neighboring Unicode" {
        val policy = ChatGlyphPolicy(emptyList())

        policy.violation("Обычный текст ${String(Character.toChars(0x100001))} конец", hasPermission = { true }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
    }

    "current permission results are read again for every message" {
        val policy = ChatGlyphPolicy(listOf(premiumDefinition))
        var imagePermissionGranted = false
        val hasPermission: (String) -> Boolean = { permission ->
            permission == chatPermission || permission == premiumPermission && imagePermissionGranted
        }

        policy.violation(premiumToken, hasPermission) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, "arc:premium_token")

        imagePermissionGranted = true
        policy.violation(premiumToken, hasPermission) shouldBe null
    }

    "operators retain the existing bypass for known, technical and unknown private-use glyphs" {
        val technicalDefinition = premiumDefinition.copy(id = "arc:internal", technical = true)
        val policy = ChatGlyphPolicy(listOf(technicalDefinition))
        val unknownPrivateUse = String(Character.toChars(0x10FFFD))

        policy.violation("$premiumToken $unknownPrivateUse :offset_-2:", hasPermission = { false }, isOperator = true) shouldBe null
    }
})
