package ru.arc.sync.base

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/** Pure unit tests for Context — no Bukkit dependency. */
class ContextTest : DescribeSpec({

    describe("Context") {

        describe("put and get") {
            it("should store and retrieve a UUID by key") {
                val ctx = Context()
                val uuid = UUID.randomUUID()

                ctx.put("player", uuid)

                ctx.get<UUID>("player") shouldBe uuid
            }

            it("should overwrite value for the same key") {
                val ctx = Context()
                val first = UUID.randomUUID()
                val second = UUID.randomUUID()

                ctx.put("key", first)
                ctx.put("key", second)

                ctx.get<UUID>("key") shouldBe second
            }

            it("should store multiple distinct keys independently") {
                val ctx = Context()
                val uuid1 = UUID.randomUUID()
                val uuid2 = UUID.randomUUID()

                ctx.put("a", uuid1)
                ctx.put("b", uuid2)

                ctx.get<UUID>("a") shouldBe uuid1
                ctx.get<UUID>("b") shouldBe uuid2
            }

            it("should return null for unknown key via get<T?>") {
                val ctx = Context()

                val result: UUID? = ctx.get("missing")

                result.shouldBeNull()
            }
        }
    }
})
