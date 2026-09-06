package ru.arc.dialogdemo

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.Config
import java.nio.file.Files

class DialogDemoTest : FreeSpec({
    "untrusted form text remains bounded literal text with useful paragraph breaks" {
        DialogDemoModule.safe("<click:run_command:/op x>Hello</click>\n§c\u0000Bye\t") shouldBe
            "<click:run_command:/op x>Hello</click>\ncBye"
        DialogDemoModule.safe("x".repeat(2000)).length shouldBe 512
    }
    "every showcase entry has a readable title label and tooltip in the shipped nested config" {
        val directory = Files.createTempDirectory("dialog-demo-test")
        try {
            val destination = directory.resolve("modules/dialog-demo.yml")
            Files.createDirectories(destination.parent)
            javaClass.getResourceAsStream("/modules/dialog-demo.yml")!!.use { Files.copy(it, destination) }
            val config = Config(directory, "modules/dialog-demo.yml")
            DialogDemoModule.pages.forEach { page ->
                val title = config.string("title.$page", "MISSING")
                title shouldNotContain "MISSING"
                if (page != "root") {
                    config.string("nav.$page", "MISSING") shouldNotContain "MISSING"
                    config.string("nav.$page-tip", "MISSING") shouldNotContain "MISSING"
                }
            }
            val source = java.nio.file.Path.of("src/main/kotlin/ru/arc/dialogdemo/DialogDemoModule.kt").toFile().readText()
            Regex("""(?<![\w.])(?:text|body|button|dialog|multi)\("([a-z][a-z0-9.-]*)"""").findAll(source).forEach { match ->
                config.string(match.groupValues[1], "MISSING") shouldNotContain "MISSING"
            }
            config.string("alignment.sample", "MISSING") shouldNotContain "MISSING"
            config.string("back", "MISSING") shouldNotContain "MISSING"
            listOf("custom.ack", "custom.result", "inputs.result", "callback.success").forEach {
                config.string(it, "MISSING") shouldNotContain "MISSING"
            }
        } finally { directory.toFile().deleteRecursively() }
    }
})
