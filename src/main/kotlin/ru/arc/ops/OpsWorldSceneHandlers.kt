package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import ru.arc.ARC
import ru.arc.worldcontent.ManagedObjectReadback
import ru.arc.worldcontent.ManagedSceneState
import ru.arc.worldcontent.SceneMutationResult
import ru.arc.worldcontent.SceneObjectSpec
import ru.arc.worldcontent.ScenePreview
import ru.arc.worldcontent.WorldSceneManager
import ru.arc.worldcontent.WorldSceneRepository
import ru.arc.worldcontent.WorldSceneSpec
import ru.arc.worldcontent.WorldSceneSpecParser

object OpsWorldSceneHandlers {
    private fun manager(): WorldSceneManager =
        WorldSceneManager(WorldSceneRepository(ARC.instance.dataPath.resolve("data/world-scenes.json")))

    fun list(id: String? = null): Map<String, Any?> =
        OpsBukkitSync.call {
            val manager = manager()
            val states =
                if (id == null) {
                    manager.states()
                } else {
                    listOf(manager.state(id) ?: throw NoSuchElementException("World scene not found: $id"))
                }
            mapOf(
                "source" to "arc-managed-world-scenes",
                "server" to (ARC.serverName ?: "unknown"),
                "count" to states.size,
                "scenes" to states.map { stateToMap(manager, it) },
            )
        }

    fun preview(body: JsonObject): Map<String, Any?> {
        val id = requiredString(body, "id")
        val spec = WorldSceneSpecParser.parse(id, body)
        return OpsBukkitSync.call { previewToMap(manager().preview(spec)) }
    }

    fun apply(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val spec = WorldSceneSpecParser.parse(id, body)
        val digest = reviewDigest(body)
        return OpsBukkitSync.call { mutationToMap(manager().apply(spec, digest)) }
    }

    fun previewDelete(id: String): Map<String, Any?> =
        OpsBukkitSync.call { previewToMap(manager().previewDelete(id)) }

    fun delete(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val digest = reviewDigest(body)
        return OpsBukkitSync.call { mutationToMap(manager().delete(id, digest)) }
    }

    fun previewRollback(id: String): Map<String, Any?> =
        OpsBukkitSync.call { previewToMap(manager().previewRollback(id)) }

    fun rollback(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val digest = reviewDigest(body)
        return OpsBukkitSync.call { mutationToMap(manager().rollback(id, digest)) }
    }

    private fun stateToMap(
        manager: WorldSceneManager,
        state: ManagedSceneState,
    ): Map<String, Any?> {
        val readback = manager.readback(state.id).associateBy { it.state.spec.id }
        return mapOf(
            "id" to state.id,
            "revision" to state.revision,
            "updatedAt" to state.updatedAt,
            "objectCount" to state.objects.size,
            "rollbackAvailable" to (state.previousSpec != null),
            "healthy" to readback.values.all(ManagedObjectReadback::healthy),
            "objects" to state.currentSpec.objects.map { objectToMap(it, readback[it.id]) },
        )
    }

    private fun objectToMap(
        spec: SceneObjectSpec,
        readback: ManagedObjectReadback?,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "id" to spec.id,
            "kind" to spec.kind.wireName,
            "world" to spec.world,
            "x" to spec.x,
            "y" to spec.y,
            "z" to spec.z,
            "blockData" to spec.blockData,
            "namespacedId" to spec.namespacedId,
            "placement" to spec.placement?.wireName,
            "yaw" to spec.yaw,
            "pitch" to spec.pitch,
            "healthy" to readback?.healthy,
            "live" to readback?.live,
            "entityUuid" to readback?.state?.entityUuid,
            "barriers" to readback?.state?.barriers?.map { mapOf("x" to it.x, "y" to it.y, "z" to it.z) },
        ).filterValues { it != null }

    private fun previewToMap(preview: ScenePreview): Map<String, Any?> =
        mapOf(
            "source" to "arc-managed-world-scenes",
            "preview" to true,
            "persisted" to false,
            "sceneId" to preview.sceneId,
            "reviewDigest" to preview.reviewDigest,
            "create" to preview.createIds,
            "update" to preview.updateIds,
            "delete" to preview.deleteIds,
            "unchanged" to preview.unchangedIds,
            "conflicts" to preview.conflicts,
        )

    private fun mutationToMap(result: SceneMutationResult): Map<String, Any?> =
        mapOf(
            "source" to "arc-managed-world-scenes",
            "saved" to true,
            "sceneId" to result.sceneId,
            "revision" to result.revision,
            "objectCount" to result.objectCount,
            "created" to result.created,
            "updated" to result.updated,
            "deleted" to result.deleted,
        )

    private fun reviewDigest(body: JsonObject): String {
        val digest = requiredString(body, "reviewDigest").lowercase()
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "reviewDigest must be a SHA-256 hex string" }
        return digest
    }

    private fun requiredString(
        body: JsonObject,
        field: String,
    ): String {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$field is required")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$field must be a string" }
        return element.asString.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$field must not be empty")
    }
}
