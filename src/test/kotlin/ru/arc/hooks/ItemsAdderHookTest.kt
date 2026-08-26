package ru.arc.hooks

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ItemsAdderHookTest :
    FreeSpec({
        "pack-compressed handling" - {
            "publishes only after generated zip is replaced and stable" {
                val scheduler = TestTaskScheduler()
                val directory = Files.createTempDirectory("arc-resourcepack-publication-gate")
                val generatedZip = directory.resolve("generated.zip")
                generatedZip.writeText("old")
                var publications = 0
                val hook =
                    ItemsAdderHook(
                        scheduler,
                        GeneratedPackPublicationGate(
                            generatedZip,
                            pollIntervalTicks = 1,
                            requiredStablePolls = 2,
                            maximumPolls = 4,
                        ),
                    ) {
                        publications++
                        true
                    }

                hook.requestPublish()

                publications shouldBeExactly 0
                scheduler.timerCount() shouldBeExactly 1

                val replacement = directory.resolve("generated-new.zip")
                replacement.writeText("new-pack")
                Files.move(replacement, generatedZip, StandardCopyOption.REPLACE_EXISTING)

                scheduler.tick()
                publications shouldBeExactly 0

                scheduler.tick()

                publications shouldBeExactly 1
                scheduler.timerCount() shouldBeExactly 0
            }

            "does not publish when ItemsAdder leaves the previous zip in place" {
                val scheduler = TestTaskScheduler()
                val generatedZip = Files.createTempFile("arc-resourcepack-unchanged", ".zip")
                generatedZip.writeText("old")
                var publications = 0
                val hook =
                    ItemsAdderHook(
                        scheduler,
                        GeneratedPackPublicationGate(
                            generatedZip,
                            pollIntervalTicks = 1,
                            requiredStablePolls = 2,
                            maximumPolls = 3,
                        ),
                    ) {
                        publications++
                        true
                    }

                hook.requestPublish()
                scheduler.tick(3)

                publications shouldBeExactly 0
                scheduler.timerCount() shouldBeExactly 0
            }

            "does not schedule a duplicate publication while one is pending" {
                val scheduler = TestTaskScheduler()
                val directory = Files.createTempDirectory("arc-resourcepack-duplicate")
                val generatedZip = directory.resolve("generated.zip")
                generatedZip.writeText("old")
                var publications = 0
                val hook =
                    ItemsAdderHook(
                        scheduler,
                        GeneratedPackPublicationGate(
                            generatedZip,
                            pollIntervalTicks = 1,
                            requiredStablePolls = 1,
                            maximumPolls = 3,
                        ),
                    ) {
                        publications++
                        true
                    }

                hook.requestPublish()
                hook.requestPublish()
                generatedZip.writeText("new-pack")
                scheduler.tick()

                publications shouldBeExactly 1
            }

            "allows a later publication after the previous task fails" {
                val scheduler = TestTaskScheduler()
                val directory = Files.createTempDirectory("arc-resourcepack-retry")
                val generatedZip = directory.resolve("generated.zip")
                generatedZip.writeText("old")
                var attempts = 0
                val hook =
                    ItemsAdderHook(
                        scheduler,
                        GeneratedPackPublicationGate(
                            generatedZip,
                            pollIntervalTicks = 1,
                            requiredStablePolls = 1,
                            maximumPolls = 3,
                        ),
                    ) {
                        attempts++
                        error("test failure")
                    }

                hook.requestPublish()
                generatedZip.writeText("first-new-pack")
                scheduler.tick()
                hook.requestPublish()
                generatedZip.writeText("second-new-pack-with-different-size")
                scheduler.tick()

                attempts shouldBeExactly 2
            }
        }

        "bundled resource pack sync script" - {
            "installs an executable copy and replaces stale content" {
                val dataFolder = Files.createTempDirectory("arc-bundled-resourcepack-sync")

                val installed = BundledResourcePackSyncScript.install(dataFolder)

                installed.shouldExist()
                Files.isExecutable(installed).shouldBeTrue()
                installed.readText().contains("ItemsAdder generated.zip").shouldBeTrue()

                installed.writeText("stale")
                BundledResourcePackSyncScript.install(dataFolder)

                installed.readText().contains("ItemsAdder generated.zip").shouldBeTrue()
            }
        }

        "public bundled config" - {
            "contains no credentials" {
                val dataFolder = Files.createTempDirectory("arc-resourcepack-config")

                val environment = ResourcePackSyncConfig.load(dataFolder).processEnvironment()

                environment["AWS_ACCESS_KEY_ID"] shouldBe ""
                environment["AWS_SECRET_ACCESS_KEY"] shouldBe ""
                environment["S3_BUCKET"] shouldBe "ruscraftinresources"
                environment["IA_MIRROR_ENABLED"] shouldBe "0"
                environment["IA_MIRROR_BACKUP_KEEP"] shouldBe "3"
            }
        }

        "resource pack sync script" - {
            "refuses a zip with a corrupted payload before upload" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-corrupt")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                val payload = "CRC-CHECK-PAYLOAD".toByteArray()

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                    val checksum = CRC32().apply { update(payload) }
                    output.putNextEntry(
                        ZipEntry("assets/test/payload.txt").apply {
                            method = ZipEntry.STORED
                            size = payload.size.toLong()
                            compressedSize = payload.size.toLong()
                            crc = checksum.value
                        },
                    )
                    output.write(payload)
                    output.closeEntry()
                }
                val archiveBytes = Files.readAllBytes(resourcePackZip)
                val payloadOffset = archiveBytes.indexOf(payload)
                (payloadOffset >= 0) shouldBe true
                archiveBytes[payloadOffset] = (archiveBytes[payloadOffset].toInt() xor 0x01).toByte()
                Files.write(resourcePackZip, archiveBytes)
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                                "RP_NOTIFY_ENABLED" to "0",
                            )
                    },
                ).publish(resourcePackZip).shouldBeFalse()
                Files.exists(uploadedZip).shouldBeFalse()
            }

            "leaves an archive byte-identical when modern metadata already exists" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-existing-metadata")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeRedis = directory.resolve("fake-redis.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeRedis.writeText(fakeRedisPublisherScript())
                fakeRedis.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            notificationEnvironment(fakeRedis, directory) +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                uploadedZip.shouldExist()
                Files.mismatch(resourcePackZip, uploadedZip) shouldBe -1L
            }

            "patches modern ItemsAdder metadata in staging before upload" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-metadata")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeRedis = directory.resolve("fake-redis.sh")
                val capturedNotification = directory.resolve("redis-notification.txt")
                val capturedManifest = directory.resolve("manifest.txt")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"pack_format":75,"description":"ItemsAdder"},"supported_formats":[32,9999]}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                    repeat(2_000) { index ->
                        output.putNextEntry(ZipEntry("assets/test/empty-$index.txt"))
                        output.closeEntry()
                    }
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeRedis.writeText(fakeRedisPublisherScript())
                fakeRedis.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            notificationEnvironment(fakeRedis, directory) +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                                "CAPTURED_NOTIFICATION" to capturedNotification.toAbsolutePath().toString(),
                                "CAPTURED_MANIFEST" to capturedManifest.toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                uploadedZip.shouldExist()
                capturedNotification.shouldExist()
                capturedNotification.readText() shouldContain
                    "PUBLISH arc.resourcepack.published spawn<>#<>#<>v1:"
                capturedManifest.shouldExist()
                ZipFile(uploadedZip.toFile()).use { archive ->
                    archive.entries().asSequence().count { it.name == "pack.mcmeta" } shouldBeExactly 1
                    archive.size() shouldBeExactly 2_001
                    val metadata =
                        archive.getInputStream(archive.getEntry("pack.mcmeta")).bufferedReader().use { reader ->
                            JsonParser.parseReader(reader).asJsonObject
                        }
                    metadata.getAsJsonObject("pack").get("min_format").asInt shouldBeExactly 32
                    metadata.getAsJsonObject("pack").get("max_format").asInt shouldBeExactly 9_999
                    metadata.getAsJsonObject("pack").getAsJsonArray("supported_formats").let { range ->
                        range[0].asInt shouldBeExactly 32
                        range[1].asInt shouldBeExactly 64
                    }
                    metadata.has("supported_formats").shouldBeFalse()
                }

                ZipFile(resourcePackZip.toFile()).use { original ->
                    val metadata =
                        original.getInputStream(original.getEntry("pack.mcmeta")).bufferedReader().use { reader ->
                            JsonParser.parseReader(reader).asJsonObject
                        }
                    metadata.getAsJsonObject("pack").has("min_format").shouldBeFalse()
                    metadata.getAsJsonObject("pack").has("max_format").shouldBeFalse()
                }
            }

            "moves legacy supported formats into pack even when min and max already exist" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-supported-formats")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeRedis = directory.resolve("fake-redis.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"pack_format":75,"description":"ItemsAdder","min_format":32,"max_format":9999},"supported_formats":[32,9999]}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeRedis.writeText(fakeRedisPublisherScript())
                fakeRedis.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            notificationEnvironment(fakeRedis, directory) +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                ZipFile(uploadedZip.toFile()).use { archive ->
                    val metadata =
                        archive.getInputStream(archive.getEntry("pack.mcmeta")).bufferedReader().use { reader ->
                            JsonParser.parseReader(reader).asJsonObject
                        }
                    metadata.has("supported_formats").shouldBeFalse()
                    metadata.getAsJsonObject("pack").getAsJsonArray("supported_formats").let { range ->
                        range[0].asInt shouldBeExactly 32
                        range[1].asInt shouldBeExactly 64
                    }
                }
            }

            "removes the duplicate vanilla entity directory from the modern block atlas" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-entity-atlas")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeRedis = directory.resolve("fake-redis.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                val atlasPath = "ia_overlay_modern_atlas/assets/minecraft/atlases/blocks.json"

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                    output.putNextEntry(ZipEntry(atlasPath))
                    output.write(
                        """{"sources":[{"type":"directory","source":"entity","prefix":"entity/"},{"type":"directory","source":"blocks","prefix":"blocks/"}]}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeRedis.writeText(fakeRedisPublisherScript())
                fakeRedis.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            notificationEnvironment(fakeRedis, directory) +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                ZipFile(uploadedZip.toFile()).use { archive ->
                    val atlas =
                        archive.getInputStream(archive.getEntry(atlasPath)).bufferedReader().use { reader ->
                            JsonParser.parseReader(reader).asJsonObject
                        }
                    val sources = atlas.getAsJsonArray("sources")
                    sources.size() shouldBeExactly 1
                    sources[0].asJsonObject.get("source").asString shouldBe "blocks"
                }
            }

            "keeps the entity atlas source when a custom model references it" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-entity-atlas-guard")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeRedis = directory.resolve("fake-redis.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                val atlasPath = "ia_overlay_modern_atlas/assets/minecraft/atlases/blocks.json"

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                    output.putNextEntry(ZipEntry(atlasPath))
                    output.write(
                        """{"sources":[{"type":"directory","source":"entity","prefix":"entity/"}]}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                    output.putNextEntry(ZipEntry("assets/example/models/item/entity_texture.json"))
                    output.write(
                        """{"textures":{"layer0":"minecraft:entity/chest/normal"}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeRedis.writeText(fakeRedisPublisherScript())
                fakeRedis.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            notificationEnvironment(fakeRedis, directory) +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                ZipFile(uploadedZip.toFile()).use { archive ->
                    val atlas =
                        archive.getInputStream(archive.getEntry(atlasPath)).bufferedReader().use { reader ->
                            JsonParser.parseReader(reader).asJsonObject
                        }
                    atlas.getAsJsonArray("sources").size() shouldBeExactly 1
                }
            }

            "mirrors spawn content and caches to survival before reloading it" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-survival-mirror")
                val networkRoot = directory.resolve("network")
                val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                val targetItemsAdder = networkRoot.resolve("classic_survival/plugins/ItemsAdder")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeTmux = directory.resolve("fake-tmux.sh")
                val capturedTmux = directory.resolve("tmux-command.txt")
                val targetLog = networkRoot.resolve("classic_survival/logs/latest.log")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                sourceItemsAdder.resolve("contents/demo").let { path ->
                    Files.createDirectories(path)
                    path.resolve("source.txt").writeText("spawn-content")
                }
                sourceItemsAdder.resolve("storage").let { path ->
                    Files.createDirectories(path)
                    path.resolve("items_ids_cache.yml").writeText("PAPER:\n  demo:item: 42\n")
                    path.resolve("font_images_unicode_cache.yml").writeText(
                        "demo:first: \ndemo:second: \n",
                    )
                }
                targetItemsAdder.resolve("contents/legacy").let { path ->
                    Files.createDirectories(path)
                    path.resolve("survival-only.txt").writeText("legacy-content")
                }
                targetItemsAdder.resolve("storage").let { path ->
                    Files.createDirectories(path)
                    path.resolve("items_ids_cache.yml").writeText("PAPER:\n  legacy:item: 99\n")
                }
                Files.createDirectories(resourcePackZip.parent)
                Files.createDirectories(targetLog.parent)
                targetLog.writeText("[00:00:00] server ready\n")
                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeTmux.writeText(
                    """
                    |#!/bin/sh
                    |case "${'$'}1" in
                    |  list-sessions)
                    |    printf '%s\n' survival
                    |    ;;
                    |  send-keys)
                    |    printf '%s\n' "${'$'}*" > "${'$'}CAPTURED_TMUX"
                    |    printf '%s\n' 'demo:second: ' 'demo:first: ' > "${'$'}FAKE_TARGET_STORAGE/font_images_unicode_cache.yml"
                    |    printf '%s\n' '[00:00:01] [Server thread/WARN]: Ресурсы • Reload completed.' >> "${'$'}FAKE_TARGET_LOG"
                    |    ;;
                    |  *)
                    |    exit 2
                    |    ;;
                    |esac
                    """.trimMargin(),
                )
                fakeTmux.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                                "RP_NOTIFY_ENABLED" to "0",
                                "IA_MIRROR_ENABLED" to "1",
                                "IA_MIRROR_TMUX_CLI" to fakeTmux.toAbsolutePath().toString(),
                                "CAPTURED_TMUX" to capturedTmux.toAbsolutePath().toString(),
                                "FAKE_TARGET_LOG" to targetLog.toAbsolutePath().toString(),
                                "FAKE_TARGET_STORAGE" to
                                    targetItemsAdder.resolve("storage").toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                targetItemsAdder.resolve("contents/demo/source.txt").readText() shouldBe "spawn-content"
                Files.exists(targetItemsAdder.resolve("contents/legacy")).shouldBeFalse()
                targetItemsAdder.resolve("storage/items_ids_cache.yml").readText() shouldBe
                    "PAPER:\n  demo:item: 42\n"
                targetItemsAdder.resolve("storage/font_images_unicode_cache.yml").readLines() shouldBe
                    listOf("demo:second: ", "demo:first: ")
                capturedTmux.readText() shouldContain "send-keys -t survival iareload Enter"

                val backupRoot = networkRoot.resolve(".mc-ops/itemsadder-mirror")
                val backups = Files.list(backupRoot).use { paths ->
                    paths.iterator().asSequence()
                        .filter { Files.isDirectory(it) && it.fileName.toString() != ".lock" }
                        .toList()
                }
                backups.size shouldBeExactly 1
                backups.single().resolve("contents/legacy/survival-only.txt").readText() shouldBe
                    "legacy-content"
                Files.exists(backupRoot.resolve(".lock")).shouldBeFalse()
            }

            "rejects a survival mirror target that is not a server directory name" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-mirror-traversal")
                val sourceItemsAdder = directory.resolve("network/classic/plugins/ItemsAdder")
                val targetItemsAdder = directory.resolve("network/classic_survival/plugins/ItemsAdder")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val uploadedZip = directory.resolve("uploaded.zip")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                Files.createDirectories(sourceItemsAdder.resolve("contents"))
                Files.createDirectories(sourceItemsAdder.resolve("storage"))
                Files.createDirectories(targetItemsAdder.resolve("contents"))
                Files.createDirectories(targetItemsAdder.resolve("storage"))
                Files.createDirectories(resourcePackZip.parent)
                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                                "RP_NOTIFY_ENABLED" to "0",
                                "IA_MIRROR_ENABLED" to "1",
                                "IA_MIRROR_TARGET_SERVER" to "../outside",
                            )
                    },
                ).publish(resourcePackZip).shouldBeFalse()

                Files.exists(uploadedZip).shouldBeFalse()
                Files.exists(directory.resolve("network/.mc-ops")).shouldBeFalse()
            }

            "fails publication when Velocity has no notification subscriber" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-no-proxy")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeRedis = directory.resolve("fake-redis.sh")
                val capturedManifest = directory.resolve("manifest.txt")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write(
                        """{"pack":{"description":"ready","min_format":75,"max_format":75}}"""
                            .toByteArray(),
                    )
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                fakeRedis.writeText(fakeRedisPublisherScript())
                fakeRedis.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        testEnvironment() +
                            notificationEnvironment(fakeRedis, directory) +
                            mapOf(
                                "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                                "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                                "CAPTURED_MANIFEST" to capturedManifest.toAbsolutePath().toString(),
                                "FAKE_REDIS_SUBSCRIBERS" to "0",
                            )
                    },
                ).publish(resourcePackZip).shouldBeFalse()
                Files.exists(capturedManifest).shouldBeFalse()
            }

            "passes the generated zip through RP_SOURCE" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync")
                val resourcePackZip = directory.resolve("generated pack.zip")
                val script = directory.resolve("resourcepack_sync.sh")
                val capturedSource = directory.resolve("captured-source.txt")
                resourcePackZip.writeText("zip")
                script.writeText(
                    """
                    |#!/bin/sh
                    |printf '%s|%s|%s' "${'$'}RP_SOURCE" "${'$'}AWS_ACCESS_KEY_ID" "${'$'}S3_BUCKET" > "${capturedSource.toAbsolutePath()}"
                    """.trimMargin(),
                )
                script.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        mapOf(
                            "AWS_ACCESS_KEY_ID" to "test-access-key",
                            "AWS_SECRET_ACCESS_KEY" to "test-secret-key",
                            "S3_BUCKET" to "test-bucket",
                        )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                capturedSource.shouldExist()
                capturedSource.readText() shouldBe
                    "${resourcePackZip.toAbsolutePath().normalize()}|test-access-key|test-bucket"
            }

            "reports a non-zero script exit" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-failure")
                val resourcePackZip = directory.resolve("generated.zip")
                val script = directory.resolve("resourcepack_sync.sh")
                resourcePackZip.writeText("zip")
                script.writeText(
                    """
                    |#!/bin/sh
                    |exit 17
                    """.trimMargin(),
                )
                script.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(script, ::testEnvironment).publish(resourcePackZip).shouldBeFalse()
            }

            "does not run when the generated zip is missing" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-missing")
                val marker = directory.resolve("was-run")
                val script = directory.resolve("resourcepack_sync.sh")
                script.writeText(
                    """
                    |#!/bin/sh
                    |touch "${marker.toAbsolutePath()}"
                    """.trimMargin(),
                )
                script.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(script, ::testEnvironment)
                    .publish(directory.resolve("missing.zip"))
                    .shouldBeFalse()

                Files.exists(marker).shouldBeFalse()
            }

            "does not run without configured credentials" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-no-credentials")
                val resourcePackZip = directory.resolve("generated.zip")
                val marker = directory.resolve("was-run")
                val script = directory.resolve("resourcepack_sync.sh")
                resourcePackZip.writeText("zip")
                script.writeText(
                    """
                    |#!/bin/sh
                    |touch "${marker.toAbsolutePath()}"
                    """.trimMargin(),
                )
                script.toFile().setExecutable(true).shouldBeTrue()

                ResourcePackSyncScript(
                    script,
                    processEnvironment = {
                        mapOf(
                            "AWS_ACCESS_KEY_ID" to "",
                            "AWS_SECRET_ACCESS_KEY" to "",
                        )
                    },
                ).publish(resourcePackZip).shouldBeFalse()

                Files.exists(marker).shouldBeFalse()
            }
        }
    })

private fun testEnvironment(): Map<String, String> =
    mapOf(
        "AWS_ACCESS_KEY_ID" to "test-access-key",
        "AWS_SECRET_ACCESS_KEY" to "test-secret-key",
    )

private fun fakeAwsUploaderScript(): String =
    """
    |#!/bin/sh
    |if [ "${'$'}4" = "-" ]; then
    |  exit 1
    |fi
    |if [ "${'$'}3" = "-" ]; then
    |  if [ -n "${'$'}CAPTURED_MANIFEST" ]; then
    |    cat > "${'$'}CAPTURED_MANIFEST"
    |  else
    |    cat >/dev/null
    |  fi
    |  exit 0
    |fi
    |cp "${'$'}3" "${'$'}CAPTURED_UPLOAD"
    """.trimMargin()

private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    for (offset in 0..size - needle.size) {
        if (needle.indices.all { index -> this[offset + index] == needle[index] }) return offset
    }
    return -1
}

private fun notificationEnvironment(
    fakeRedis: java.nio.file.Path,
    directory: java.nio.file.Path,
): Map<String, String> =
    mapOf(
        "RP_NOTIFY_ENABLED" to "1",
        "REDIS_CLI" to fakeRedis.toAbsolutePath().toString(),
        "REDIS_HOST" to "redis.test",
        "REDIS_PORT" to "6379",
        "REDIS_USERNAME" to "test-user",
        "REDISCLI_AUTH" to "test-password",
        "REDIS_SERVER_NAME" to "spawn",
        "REDIS_WIRE_DELIMITER" to "<>#<>#<>",
        "RP_PUBLISHED_CHANNEL" to "arc.resourcepack.published",
        "RP_PUBLISHED_ACK_KEY" to "arc:resourcepack:hash-refresh-acks",
        "FAKE_REDIS_STATE" to directory.resolve("redis-ack.txt").toAbsolutePath().toString(),
    )

private fun fakeRedisPublisherScript(): String =
    """
    |#!/bin/sh
    |all_args="${'$'}*"
    |while [ "${'$'}#" -gt 0 ]; do
    |  case "${'$'}1" in
    |    PUBLISH)
    |      if [ -n "${'$'}CAPTURED_NOTIFICATION" ]; then
    |        printf '%s\n' "${'$'}all_args" > "${'$'}CAPTURED_NOTIFICATION"
    |      fi
    |      notification="${'$'}3"
    |      hash_and_request="${'$'}{notification#*v1:}"
    |      hash="${'$'}{hash_and_request%%:*}"
    |      request="${'$'}{hash_and_request#*:}"
    |      printf 'v1:%s:%s\n' "${'$'}request" "${'$'}hash" > "${'$'}FAKE_REDIS_STATE"
    |      printf '%s\n' "${'$'}{FAKE_REDIS_SUBSCRIBERS:-1}"
    |      exit 0
    |      ;;
    |    HGET)
    |      [ -f "${'$'}FAKE_REDIS_STATE" ] && cat "${'$'}FAKE_REDIS_STATE"
    |      exit 0
    |      ;;
    |  esac
    |  shift
    |done
    |exit 2
    """.trimMargin()
