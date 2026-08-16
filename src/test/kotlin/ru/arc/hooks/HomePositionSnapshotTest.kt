package ru.arc.hooks

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.position.World
import java.util.UUID

class HomePositionSnapshotTest :
    FreeSpec({
        val world = World.from("survival", UUID.fromString("8ea02e7f-d699-4f54-b1b9-849e9ea83a9d"))

        "matches the persisted HuskHomes result at the event position" {
            val expected = Position.at(10.25, 70.0, -20.75, 90f, 0f, world, "survival")
            val persisted = Position.at(10.25, 70.0, -20.75, 45f, 10f, world, "survival")

            HomePositionSnapshot.from(expected).matches(persisted).shouldBeTrue()
        }

        "rejects a stale home from a different position" {
            val expected = Position.at(10.25, 70.0, -20.75, world, "survival")
            val stale = Position.at(11.25, 70.0, -20.75, world, "survival")

            HomePositionSnapshot.from(expected).matches(stale).shouldBeFalse()
        }

        "rejects a home from another backend" {
            val expected = Position.at(10.25, 70.0, -20.75, world, "survival")
            val remote = Position.at(10.25, 70.0, -20.75, world, "spawn")

            HomePositionSnapshot.from(expected).matches(remote).shouldBeFalse()
        }
    })
