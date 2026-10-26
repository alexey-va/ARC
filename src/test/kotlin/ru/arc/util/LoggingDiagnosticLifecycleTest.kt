package ru.arc.util

import io.kotest.core.spec.style.FreeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import ru.arc.logging.ArcDiagnosticLogging
import java.nio.file.Files
import java.nio.file.Paths

class LoggingDiagnosticLifecycleTest : FreeSpec({
    "diagnostic file is independent of Loki and retires its previous owner" {
        val root = Files.createTempDirectory("arc-diagnostic-lifecycle")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve("modules/logging.yml"), "enabled: false\ndiagnostics-file:\n  enabled: true\n")
        val first = mockk<AutoCloseable>(relaxed = true)
        val second = mockk<AutoCloseable>(relaxed = true)
        mockkObject(ArcDiagnosticLogging)
        try {
            every { ArcDiagnosticLogging.install(Paths.get("logs")) } returnsMany listOf(first, second)
            Logging.installForPlugin(root)
            Logging.installForPlugin(root)
            verify(exactly = 1) { first.close() }
            verify(exactly = 0) { second.close() }
            Logging.closeDiagnosticFile()
            Logging.closeDiagnosticFile()
            verify(exactly = 1) { second.close() }
        } finally {
            Logging.closeDiagnosticFile()
            unmockkObject(ArcDiagnosticLogging)
            root.toFile().deleteRecursively()
        }
    }

    "operator can disable the diagnostic file without enabling Loki" {
        val root = Files.createTempDirectory("arc-diagnostic-disabled")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve("modules/logging.yml"), "enabled: false\ndiagnostics-file:\n  enabled: false\n")
        mockkObject(ArcDiagnosticLogging)
        try {
            Logging.installForPlugin(root)
            verify(exactly = 0) { ArcDiagnosticLogging.install(any()) }
        } finally {
            Logging.closeDiagnosticFile()
            unmockkObject(ArcDiagnosticLogging)
            root.toFile().deleteRecursively()
        }
    }
})
