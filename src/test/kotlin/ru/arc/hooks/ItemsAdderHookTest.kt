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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ItemsAdderHookTest :
    FreeSpec({
        "pack-compressed handling" - {
            "publishes asynchronously" {
                val scheduler = TestTaskScheduler()
                var publications = 0
                val hook =
                    ItemsAdderHook(scheduler) {
                        publications++
                        true
                    }

                hook.requestPublish()

                publications shouldBeExactly 0
                scheduler.pendingCount() shouldBeExactly 1

                scheduler.executeImmediate()

                publications shouldBeExactly 1
            }

            "does not schedule a duplicate publication while one is pending" {
                val scheduler = TestTaskScheduler()
                var publications = 0
                val hook =
                    ItemsAdderHook(scheduler) {
                        publications++
                        true
                    }

                hook.requestPublish()
                hook.requestPublish()
                scheduler.executeImmediate()

                publications shouldBeExactly 1
            }

            "allows a later publication after the previous task fails" {
                val scheduler = TestTaskScheduler()
                var attempts = 0
                val hook =
                    ItemsAdderHook(scheduler) {
                        attempts++
                        error("test failure")
                    }

                hook.requestPublish()
                scheduler.executeImmediate()
                hook.requestPublish()
                scheduler.executeImmediate()

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
            }
        }

        "resource pack sync script" - {
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
