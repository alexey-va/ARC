package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.angeschossen.lands.api.role.Role
import me.angeschossen.lands.api.role.enums.RoleType

class LandsUiRoleNameTest : StringSpec({
    "system role types use localized names while custom roles keep their stored names" {
        val settings = LandsUiSettings(true, 12, mapOf(
            "role-visitor-name" to "Гость",
            "role-tenant-name" to "Арендатор",
        ))
        val role = mockk<Role>()
        every { role.name } returns "Tenant"
        every { role.type } returns RoleType.TENANT
        settings.roleName(role) shouldBe "Арендатор"
        every { role.type } returns RoleType.VISITOR
        settings.roleName(role) shouldBe "Гость"
        every { role.type } returns RoleType.NORMAL
        settings.roleName(role) shouldBe "Tenant"
    }
})
