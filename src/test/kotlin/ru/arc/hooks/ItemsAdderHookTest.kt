package ru.arc.hooks

import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.nio.file.Path
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
                dataFolder.resolve("scripts/resourcepack_variants.py").shouldExist()
                dataFolder.resolve("scripts/resourcepack_fonts.py").shouldExist()
                dataFolder.resolve("scripts/resourcepack_clients.json").shouldExist()
                dataFolder.resolve("scripts/resourcepack-requirements.txt").shouldExist()
                Files.isExecutable(installed).shouldBeTrue()
                installed.readText().contains("ItemsAdder generated.zip").shouldBeTrue()

                installed.writeText("stale")
                dataFolder.resolve("scripts/resourcepack_fonts.py").writeText("stale font optimizer")
                BundledResourcePackSyncScript.install(dataFolder)

                installed.readText().contains("ItemsAdder generated.zip").shouldBeTrue()
                dataFolder.resolve("scripts/resourcepack_fonts.py").readText().contains("def optimize_fonts").shouldBeTrue()
            }
        }

        "public bundled config" - {
            "contains no credentials" {
                val dataFolder = Files.createTempDirectory("arc-resourcepack-config")

                val environment = ResourcePackSyncConfig.load(dataFolder).processEnvironment()

                environment["AWS_ACCESS_KEY_ID"] shouldBe ""
                environment["AWS_SECRET_ACCESS_KEY"] shouldBe ""
                environment["S3_BUCKET"] shouldBe "ruscraftinresources"
                environment.containsKey("S3_RP_ARCHIVE_PREFIX").shouldBeFalse()
                environment["IA_MIRROR_ENABLED"] shouldBe "0"
                environment["IA_MIRROR_TARGETS"] shouldBe ""
                environment["IA_MIRROR_BACKUP_KEEP"] shouldBe "3"
            }

            "passes configured mirror targets as separate directory-session lines" {
                val dataFolder = Files.createTempDirectory("arc-resourcepack-config-targets")
                val module = dataFolder.resolve("modules/resourcepack-sync.yml")
                Files.createDirectories(module.parent)
                module.writeText(
                    """
                    survival-mirror:
                      targets:
                        - classic_survival=survival
                        - parkour=parkour
                    """.trimIndent(),
                )

                ResourcePackSyncConfig.load(dataFolder).processEnvironment()["IA_MIRROR_TARGETS"] shouldBe
                    "classic_survival=survival\nparkour=parkour"
            }
        }

        "resource pack sync script" - {
            "excludes Bedrock and editor files while retaining Java assets and licenses" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-client-files")
                val source = directory.resolve("generated.zip")
                val uploaded = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                val retained = mapOf(
                    "pack.mcmeta" to """{"pack":{"min_format":75,"max_format":75}}""",
                    "assets/freeminecraftmodels/models/entity/model.json" to "{}",
                    "assets/freeminecraftmodels/textures/entity/model.png" to "java-texture",
                    "assets/playing_cards/LICENSE.txt" to "license",
                )
                val excluded = listOf(
                    "assets/freeminecraftmodels/rspm_bedrock_pack/models/entity/model.geo.json",
                    "overlay/assets/elitemobs/rspm_bedrock_pack/textures/model.png",
                    "assets/arc/textures/Desktop.INI",
                    "assets/littlecatset/textures/animations/source.aseprite",
                )
                ZipOutputStream(Files.newOutputStream(source)).use { output ->
                    (retained + excluded.associateWith { "unused" }).forEach { (name, text) ->
                        output.putNextEntry(ZipEntry(name))
                        output.write(text.toByteArray())
                        output.closeEntry()
                    }
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                ResourcePackSyncScript(script) {
                    testEnvironment() + mapOf(
                        "AWS_CLI" to fakeAws.toString(), "CAPTURED_UPLOAD" to uploaded.toString(),
                        "RP_NOTIFY_ENABLED" to "0",
                    )
                }.publish(source).shouldBeTrue()
                ZipFile(uploaded.toFile()).use { archive ->
                    archive.entries().asSequence().map { it.name }.toSet() shouldBe retained.keys
                    retained.forEach { (name, text) ->
                        archive.getInputStream(archive.getEntry(name)).readAllBytes().decodeToString() shouldBe text
                    }
                }
                ZipFile(source.toFile()).use { archive ->
                    excluded.forEach { (archive.getEntry(it) != null) shouldBe true }
                }
            }

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

            "writes only the current resource pack and manifest, then skips an unchanged pack" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-current-only")
                val resourcePackZip = directory.resolve("generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val capturedManifest = directory.resolve("manifest.txt")
                val capturedWrites = directory.resolve("s3-writes.txt")
                val fakeAws = directory.resolve("fake-aws.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                ZipOutputStream(Files.newOutputStream(resourcePackZip)).use { output ->
                    output.putNextEntry(ZipEntry("pack.mcmeta"))
                    output.write("""{"pack":{"description":"ready","min_format":75,"max_format":75}}""".toByteArray())
                    output.closeEntry()
                }
                fakeAws.writeText(fakeAwsUploaderScript())
                fakeAws.toFile().setExecutable(true).shouldBeTrue()
                val publisher = ResourcePackSyncScript(script) {
                    testEnvironment() +
                        mapOf(
                            "AWS_CLI" to fakeAws.toAbsolutePath().toString(),
                            "CAPTURED_UPLOAD" to uploadedZip.toAbsolutePath().toString(),
                            "CAPTURED_MANIFEST" to capturedManifest.toAbsolutePath().toString(),
                            "CAPTURED_S3_WRITES" to capturedWrites.toAbsolutePath().toString(),
                            "RP_NOTIFY_ENABLED" to "0",
                        )
                }

                publisher.publish(resourcePackZip).shouldBeTrue()
                capturedWrites.readLines() shouldBe listOf(
                    "s3://ruscraftinresources/RusCraftingResource.zip",
                    "s3://ruscraftinresources/RusCraftingResource.zip.sha256",
                )
                Files.mismatch(resourcePackZip, uploadedZip) shouldBe -1L
                capturedManifest.readText() shouldContain "RusCraftingResource.zip"

                Files.delete(capturedWrites)
                publisher.publish(resourcePackZip).shouldBeTrue()
                Files.exists(capturedWrites).shouldBeFalse()
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

            "mirrors spawn contents and stable IDs to two consumers without copying local state" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-consumer-mirrors")
                val networkRoot = directory.resolve("network")
                val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                val targets = listOf("classic_survival" to "survival", "parkour" to "parkour")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeTmux = directory.resolve("fake-tmux.sh")
                val capturedTmux = directory.resolve("tmux-command.txt")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                val localState = listOf(
                    "storage/players/local.yml",
                    "storage/mobs/local.yml",
                    "storage/cache/local.bin",
                    "cache/local.bin",
                    "storage/unrelated_cache.yml",
                )

                sourceItemsAdder.resolve("contents/demo").let { path ->
                    Files.createDirectories(path)
                    path.resolve("source.txt").writeText("spawn-content")
                }
                writeMirrorIdMetadata(sourceItemsAdder)
                (localState + "storage/players/source-only.yml").forEach { relative ->
                    val path = sourceItemsAdder.resolve(relative)
                    Files.createDirectories(path.parent)
                    path.writeText("spawn-private-state")
                }
                targets.forEach { (name, _) ->
                    val targetItemsAdder = networkRoot.resolve("${name}/plugins/ItemsAdder")
                    targetItemsAdder.resolve("contents/legacy").let { path ->
                        Files.createDirectories(path)
                        path.resolve("target-only.txt").writeText("legacy-${name}-content")
                    }
                    writeMirrorIdMetadata(targetItemsAdder)
                    itemsAdderIdMetadataFiles.forEach { filename ->
                        targetItemsAdder.resolve("storage/${filename}").writeText("legacy: 99\n")
                    }
                    targetItemsAdder.resolve("storage/items_ids_cache.yml").writeText("PAPER:\n  legacy:item: 99\n")
                    localState.forEach { relative ->
                        val path = targetItemsAdder.resolve(relative)
                        Files.createDirectories(path.parent)
                        path.writeText("${name}-local-state")
                    }
                    val targetLog = networkRoot.resolve("${name}/logs/latest.log")
                    Files.createDirectories(targetLog.parent)
                    targetLog.writeText("[00:00:00] server ready\n")
                }
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
                fakeTmux.writeText(
                    """
                    |#!/bin/sh
                    |case "${'$'}1" in
                    |  list-sessions)
                    |    printf '%s\n' survival parkour
                    |    ;;
                    |  send-keys)
                    |    printf '%s\n' "${'$'}*" >> "${'$'}CAPTURED_TMUX"
                    |    case "${'$'}3" in
                    |      survival) server=classic_survival ;;
                    |      parkour) server=parkour ;;
                    |      *) exit 3 ;;
                    |    esac
                    |    target="${'$'}FAKE_NETWORK_ROOT/${'$'}server"
                    |    storage="${'$'}target/plugins/ItemsAdder/storage"
                    |    printf '%s\n' 'demo:second: ' 'demo:first: ' > "${'$'}storage/font_images_unicode_cache.yml"
                    |    printf '%s\n' '{}' > "${'$'}storage/custom_fires_ids_cache.yml"
                    |    printf '%s\n' native-write > "${'$'}storage/players/new-during-reload.yml"
                    |    printf '%s\n' '[00:00:01] [Server thread/WARN]: Ресурсы • Reload completed.' >> "${'$'}target/logs/latest.log"
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
                                "IA_MIRROR_TARGETS" to targets.joinToString("\n") { (name, session) -> "${name}=${session}" },
                                "IA_MIRROR_TMUX_CLI" to fakeTmux.toAbsolutePath().toString(),
                                "CAPTURED_TMUX" to capturedTmux.toAbsolutePath().toString(),
                                "FAKE_NETWORK_ROOT" to networkRoot.toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeTrue()

                val backupRoot = networkRoot.resolve(".mc-ops/itemsadder-mirror")
                targets.forEach { (name, session) ->
                    val targetItemsAdder = networkRoot.resolve("${name}/plugins/ItemsAdder")
                    targetItemsAdder.resolve("contents/demo/source.txt").readText() shouldBe "spawn-content"
                    Files.exists(targetItemsAdder.resolve("contents/legacy")).shouldBeFalse()
                    targetItemsAdder.resolve("storage/items_ids_cache.yml").readText() shouldBe
                        "PAPER:\n  demo:item: 42\n"
                    targetItemsAdder.resolve("storage/font_images_unicode_cache.yml").readLines() shouldBe
                        listOf("demo:second: ", "demo:first: ")
                    targetItemsAdder.resolve("storage/custom_fires_ids_cache.yml").readText() shouldBe "{}\n"
                    targetItemsAdder.resolve("storage/real_wire_ids_cache.yml").readText() shouldBe ""
                    itemsAdderIdMetadataFiles.filterNot { filename ->
                        filename in setOf("font_images_unicode_cache.yml", "custom_fires_ids_cache.yml")
                    }.forEach { filename ->
                        targetItemsAdder.resolve("storage/${filename}").readText() shouldBe
                            sourceItemsAdder.resolve("storage/${filename}").readText()
                    }
                    localState.forEach { relative ->
                        targetItemsAdder.resolve(relative).readText() shouldBe "${name}-local-state"
                    }
                    Files.exists(targetItemsAdder.resolve("storage/players/source-only.yml")).shouldBeFalse()
                    targetItemsAdder.resolve("storage/players/new-during-reload.yml").readText() shouldBe "native-write\n"
                    capturedTmux.readText() shouldContain "send-keys -t ${session} iareload Enter"

                    val backups = Files.list(backupRoot.resolve(name)).use { paths ->
                        paths.iterator().asSequence().filter { Files.isDirectory(it) }.toList()
                    }
                    backups.size shouldBeExactly 1
                    backups.single().resolve("contents/legacy/target-only.txt").readText() shouldBe
                        "legacy-${name}-content"
                    backups.single().resolve("storage/items_ids_cache.yml").readText() shouldBe
                        "PAPER:\n  legacy:item: 99\n"
                    Files.exists(backups.single().resolve("storage/players")).shouldBeFalse()
                }
                Files.exists(backupRoot.resolve(".lock")).shouldBeFalse()
                uploadedZip.shouldExist()
            }

            "rejects semantic ItemsAdder cache drift after the survival reload" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-survival-cache-drift")
                val networkRoot = directory.resolve("network")
                val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                val targetItemsAdder = networkRoot.resolve("classic_survival/plugins/ItemsAdder")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val uploadedZip = directory.resolve("uploaded.zip")
                val fakeAws = directory.resolve("fake-aws.sh")
                val fakeTmux = directory.resolve("fake-tmux.sh")
                val targetLog = networkRoot.resolve("classic_survival/logs/latest.log")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))

                sourceItemsAdder.resolve("contents").let { Files.createDirectories(it) }
                writeMirrorIdMetadata(sourceItemsAdder)
                targetItemsAdder.resolve("contents").let { Files.createDirectories(it) }
                targetItemsAdder.resolve("storage").let { Files.createDirectories(it) }
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
                    |    printf '%s\n' 'PAPER:' '  demo:item: 99' > "${'$'}FAKE_TARGET_STORAGE/items_ids_cache.yml"
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
                                "FAKE_TARGET_LOG" to targetLog.toAbsolutePath().toString(),
                                "FAKE_TARGET_STORAGE" to
                                    targetItemsAdder.resolve("storage").toAbsolutePath().toString(),
                            )
                    },
                ).publish(resourcePackZip).shouldBeFalse()
            }

            "seeds an empty consumer without a generated ZIP, credentials, upload, notification or reload" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-mirror-only")
                val networkRoot = directory.resolve("network")
                val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                val targetItemsAdder = networkRoot.resolve("parkour/plugins/ItemsAdder")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val forbiddenCall = directory.resolve("forbidden-call.txt")
                val fakeExternal = directory.resolve("forbidden-external.sh")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                Files.createDirectories(sourceItemsAdder.resolve("contents/demo"))
                sourceItemsAdder.resolve("contents/demo/source.txt").writeText("spawn-content")
                writeMirrorIdMetadata(sourceItemsAdder)
                listOf("players", "mobs", "cache").forEach { tree ->
                    val path = sourceItemsAdder.resolve("storage/${tree}/source-only.yml")
                    Files.createDirectories(path.parent)
                    path.writeText("spawn-private-state")
                }
                Files.createDirectories(resourcePackZip.parent)
                Files.createDirectories(targetItemsAdder.parent)
                fakeExternal.writeText(
                    """
                    |#!/bin/sh
                    |printf '%s\n' "${'$'}*" >> "${'$'}FORBIDDEN_CALL"
                    |exit 9
                    """.trimMargin(),
                )
                fakeExternal.toFile().setExecutable(true).shouldBeTrue()

                val (exitCode, output) = runMirrorOnlyScript(
                    script,
                    resourcePackZip,
                    mapOf(
                        "IA_MIRROR_TARGETS" to "parkour=parkour",
                        "AWS_CLI" to fakeExternal.toString(),
                        "REDIS_CLI" to fakeExternal.toString(),
                        "IA_MIRROR_TMUX_CLI" to fakeExternal.toString(),
                        "FORBIDDEN_CALL" to forbiddenCall.toString(),
                        "RP_NOTIFY_ENABLED" to "1",
                        "RP_VARIANTS_ENABLED" to "1",
                    ),
                )

                withClue(output) { exitCode shouldBeExactly 0 }
                targetItemsAdder.resolve("contents/demo/source.txt").readText() shouldBe "spawn-content"
                itemsAdderIdMetadataFiles.forEach { filename ->
                    targetItemsAdder.resolve("storage/${filename}").readText() shouldBe
                        sourceItemsAdder.resolve("storage/${filename}").readText()
                }
                Files.list(targetItemsAdder.resolve("storage")).use { paths ->
                    paths.iterator().asSequence().map { it.fileName.toString() }.toSet() shouldBe
                        itemsAdderIdMetadataFiles.toSet()
                }
                Files.exists(resourcePackZip).shouldBeFalse()
                Files.exists(forbiddenCall).shouldBeFalse()
                Files.exists(networkRoot.resolve(".mc-ops/itemsadder-mirror/.lock")).shouldBeFalse()
            }

            "rejects the complete target set before changing any consumer" {
                val invalidTargets = listOf(
                    Triple("traversal", "../outside=parkour", "server-directory=tmux-session"),
                    Triple("duplicate-server", "classic_survival=other", "Duplicate ItemsAdder mirror target server"),
                    Triple("duplicate-session", "parkour=survival", "Duplicate ItemsAdder mirror target tmux session"),
                    Triple("missing-server", "missing=parkour", "Missing mirror target server directory"),
                    Triple("escaped-server", "parkour=parkour", "escapes the network root"),
                    Triple("symlinked-storage", "parkour=parkour", "Invalid target ItemsAdder tree"),
                )
                invalidTargets.forEach { (caseName, invalidTarget, expectedError) ->
                    val directory = Files.createTempDirectory("arc-resourcepack-sync-${caseName}")
                    val networkRoot = directory.resolve("network")
                    val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                    val firstTarget = networkRoot.resolve("classic_survival/plugins/ItemsAdder")
                    val secondTarget = networkRoot.resolve("parkour/plugins/ItemsAdder")
                    val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                    val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                    Files.createDirectories(sourceItemsAdder.resolve("contents"))
                    writeMirrorIdMetadata(sourceItemsAdder)
                    Files.createDirectories(resourcePackZip.parent)
                    listOf(firstTarget, secondTarget).forEach { target ->
                        Files.createDirectories(target.resolve("contents"))
                        target.resolve("contents/legacy.txt").writeText("previous-content")
                        writeMirrorIdMetadata(target)
                        target.resolve("storage/items_ids_cache.yml").writeText("legacy: 99\n")
                    }
                    if (caseName == "escaped-server") {
                        val outside = Files.createDirectories(directory.resolve("outside"))
                        networkRoot.resolve("parkour").toFile().deleteRecursively().shouldBeTrue()
                        Files.createSymbolicLink(networkRoot.resolve("parkour"), outside)
                    }
                    if (caseName == "symlinked-storage") {
                        val outside = Files.createDirectories(directory.resolve("outside-storage"))
                        secondTarget.resolve("storage").toFile().deleteRecursively().shouldBeTrue()
                        Files.createSymbolicLink(secondTarget.resolve("storage"), outside)
                    }

                    val (exitCode, output) = runMirrorOnlyScript(
                        script,
                        resourcePackZip,
                        mapOf("IA_MIRROR_TARGETS" to "classic_survival=survival\n${invalidTarget}"),
                    )

                    withClue("${caseName}: ${output}") {
                        exitCode shouldBeExactly 1
                        output shouldContain expectedError
                        firstTarget.resolve("contents/legacy.txt").readText() shouldBe "previous-content"
                        firstTarget.resolve("storage/items_ids_cache.yml").readText() shouldBe "legacy: 99\n"
                        Files.exists(networkRoot.resolve(".mc-ops")).shouldBeFalse()
                    }
                }
            }

            "leaves another mirror's lock and consumer files intact" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-mirror-lock")
                val networkRoot = directory.resolve("network")
                val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                val targetItemsAdder = networkRoot.resolve("classic_survival/plugins/ItemsAdder")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val lock = networkRoot.resolve(".mc-ops/itemsadder-mirror/.lock")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                Files.createDirectories(sourceItemsAdder.resolve("contents"))
                writeMirrorIdMetadata(sourceItemsAdder)
                Files.createDirectories(resourcePackZip.parent)
                Files.createDirectories(targetItemsAdder.resolve("contents"))
                targetItemsAdder.resolve("contents/legacy.txt").writeText("previous-content")
                writeMirrorIdMetadata(targetItemsAdder)
                Files.createDirectories(lock)

                val (exitCode, output) = runMirrorOnlyScript(script, resourcePackZip)

                withClue(output) {
                    exitCode shouldBeExactly 1
                    output shouldContain "Another ItemsAdder mirror is already running"
                }
                lock.shouldExist()
                targetItemsAdder.resolve("contents/legacy.txt").readText() shouldBe "previous-content"
            }

            "restores the consumer contents and seven metadata files when replacement fails" {
                val directory = Files.createTempDirectory("arc-resourcepack-sync-mirror-rollback")
                val networkRoot = directory.resolve("network")
                val sourceItemsAdder = networkRoot.resolve("classic/plugins/ItemsAdder")
                val targetItemsAdder = networkRoot.resolve("classic_survival/plugins/ItemsAdder")
                val resourcePackZip = sourceItemsAdder.resolve("output/generated.zip")
                val fakeBin = Files.createDirectories(directory.resolve("bin"))
                val fakeMove = fakeBin.resolve("mv")
                val script = BundledResourcePackSyncScript.install(directory.resolve("arc-data"))
                Files.createDirectories(sourceItemsAdder.resolve("contents"))
                sourceItemsAdder.resolve("contents/new.txt").writeText("new-content")
                writeMirrorIdMetadata(sourceItemsAdder)
                Files.createDirectories(resourcePackZip.parent)
                Files.createDirectories(targetItemsAdder.resolve("contents"))
                targetItemsAdder.resolve("contents/legacy.txt").writeText("previous-content")
                writeMirrorIdMetadata(targetItemsAdder)
                val previousMetadata = itemsAdderIdMetadataFiles.associateWith { filename -> "legacy-${filename}: 99\n" }
                previousMetadata.forEach { (filename, value) ->
                    targetItemsAdder.resolve("storage/${filename}").writeText(value)
                }
                Files.createDirectories(targetItemsAdder.resolve("storage/players"))
                targetItemsAdder.resolve("storage/players/local.yml").writeText("local-player-data")
                fakeMove.writeText(
                    """
                    |#!/bin/sh
                    |case "${'$'}2" in
                    |  */.arc-mirror-staging.*/storage/items_ids_cache.yml) exit 33 ;;
                    |esac
                    |exec /bin/mv "${'$'}@"
                    """.trimMargin(),
                )
                fakeMove.toFile().setExecutable(true).shouldBeTrue()

                val (exitCode, output) = runMirrorOnlyScript(
                    script,
                    resourcePackZip,
                    mapOf("PATH" to "${fakeBin}:${System.getenv("PATH")}"),
                )

                withClue(output) {
                    exitCode shouldBeExactly 1
                    output shouldContain "Unable to activate classic_survival ItemsAdder storage/items_ids_cache.yml"
                }
                targetItemsAdder.resolve("contents/legacy.txt").readText() shouldBe "previous-content"
                Files.exists(targetItemsAdder.resolve("contents/new.txt")).shouldBeFalse()
                previousMetadata.forEach { (filename, value) ->
                    targetItemsAdder.resolve("storage/${filename}").readText() shouldBe value
                }
                targetItemsAdder.resolve("storage/players/local.yml").readText() shouldBe "local-player-data"
                Files.exists(networkRoot.resolve(".mc-ops/itemsadder-mirror/.lock")).shouldBeFalse()
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
                writeMirrorIdMetadata(sourceItemsAdder)
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

private val itemsAdderIdMetadataFiles = listOf(
    "custom_fires_ids_cache.yml",
    "font_images_unicode_cache.yml",
    "items_ids_cache.yml",
    "real_blocks_ids_cache.yml",
    "real_blocks_note_ids_cache.yml",
    "real_transparent_blocks_ids_cache.yml",
    "real_wire_ids_cache.yml",
)

private fun writeMirrorIdMetadata(itemsAdder: Path) {
    val storage = Files.createDirectories(itemsAdder.resolve("storage"))
    itemsAdderIdMetadataFiles.forEach { filename ->
        storage.resolve(filename).writeText(
            when (filename) {
                "custom_fires_ids_cache.yml", "real_wire_ids_cache.yml" -> ""
                "items_ids_cache.yml" -> "PAPER:\n  demo:item: 42\n"
                "font_images_unicode_cache.yml" -> "demo:first: \ndemo:second: \n"
                else -> "demo:entry: 42\n"
            },
        )
    }
}

private fun runMirrorOnlyScript(
    script: Path,
    resourcePackZip: Path,
    environment: Map<String, String> = emptyMap(),
): Pair<Int, String> {
    val processBuilder = ProcessBuilder("bash", script.toString()).redirectErrorStream(true)
    processBuilder.environment().putAll(
        mapOf(
            "RP_SOURCE" to resourcePackZip.toAbsolutePath().toString(),
            "AWS_ACCESS_KEY_ID" to "",
            "AWS_SECRET_ACCESS_KEY" to "",
            "IA_MIRROR_ENABLED" to "1",
            "IA_MIRROR_ONLY" to "1",
            "IA_MIRROR_TARGETS" to "",
            "IA_MIRROR_TARGET_SERVER" to "classic_survival",
            "IA_MIRROR_TARGET_SESSION" to "survival",
        ) + environment,
    )
    val process = processBuilder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
}

private fun testEnvironment(): Map<String, String> =
    mapOf(
        "AWS_ACCESS_KEY_ID" to "test-access-key",
        "AWS_SECRET_ACCESS_KEY" to "test-secret-key",
        "RP_VARIANTS_ENABLED" to "0",
    )

private fun fakeAwsUploaderScript(): String =
    """
    |#!/bin/sh
    |if [ "${'$'}4" = "-" ]; then
    |  if [ -n "${'$'}CAPTURED_MANIFEST" ] && [ -f "${'$'}CAPTURED_MANIFEST" ]; then
    |    cat "${'$'}CAPTURED_MANIFEST"
    |    exit 0
    |  fi
    |  exit 1
    |fi
    |if [ -n "${'$'}CAPTURED_S3_WRITES" ]; then
    |  printf '%s\n' "${'$'}4" >> "${'$'}CAPTURED_S3_WRITES"
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
