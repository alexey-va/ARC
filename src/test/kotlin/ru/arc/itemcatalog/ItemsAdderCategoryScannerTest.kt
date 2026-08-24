package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ItemsAdderCategoryScannerTest : StringSpec({
    "reads enabled category metadata without scanning outside contents" {
        val root = Files.createTempDirectory("arc-items-catalog-scan")
        try {
            val configs = Files.createDirectories(root.resolve("pack/configs"))
            Files.writeString(
                configs.resolve("categories.yml"),
                """
                info:
                  namespace: pack
                categories:
                  furniture:
                    enabled: true
                    name: "&bМебель"
                    icon: pack:chair
                    permission: ia.menu.seecategory.furniture
                    items:
                      - pack:chair
                      - "pack:*"
                """.trimIndent(),
            )

            val result = ItemsAdderCategoryScanner().scan(root)

            result.scannedFiles shouldBe 1
            result.issues shouldBe emptyList()
            result.categories.single().let { category ->
                category.id shouldBe "furniture"
                category.itemPatterns shouldContainExactly listOf("pack:chair", "pack:*")
                category.source shouldBe "pack/configs/categories.yml"
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "skips malformed and oversized yaml while retaining valid categories" {
        val root = Files.createTempDirectory("arc-items-catalog-bounds")
        try {
            Files.writeString(root.resolve("bad.yml"), "categories: [")
            Files.writeString(root.resolve("large.yml"), "#".repeat(256))
            Files.writeString(
                root.resolve("valid.yml"),
                """
                categories:
                  valid:
                    items: [pack:item]
                """.trimIndent(),
            )

            val result = ItemsAdderCategoryScanner(maxFileBytes = 128).scan(root)

            result.categories.map { it.id } shouldContainExactly listOf("valid")
            result.issues.map { it.code }.toSet() shouldBe setOf("invalid_yaml", "file_too_large")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "does not follow yaml symlinks outside the contents root" {
        val root = Files.createTempDirectory("arc-items-catalog-symlink")
        val outside = Files.createTempFile("arc-items-catalog-outside", ".yml")
        try {
            Files.writeString(outside, "categories: {escaped: {items: [pack:item]}}")
            Files.createSymbolicLink(root.resolve("escaped.yml"), outside)

            val result = ItemsAdderCategoryScanner().scan(root)

            result.scannedFiles shouldBe 0
            result.categories shouldBe emptyList()
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    "bounds the total number of category patterns" {
        val root = Files.createTempDirectory("arc-items-catalog-patterns")
        try {
            Files.writeString(
                root.resolve("categories.yml"),
                "categories: {icons: {items: [pack:one, pack:two]}}",
            )

            val result = ItemsAdderCategoryScanner(maxTotalPatterns = 1).scan(root)

            result.categories.single().itemPatterns shouldContainExactly listOf("pack:one")
            result.issues.map { it.code } shouldContainExactly listOf("total_pattern_limit_reached")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
