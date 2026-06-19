package ru.arc.ops

import org.bukkit.inventory.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * CMI `!!binary` item blobs: gzip(base64) wrapping Paper [ItemStack.serializeAsBytes].
 *
 * Same format as CMI kiteditor / SavedItems (DataVersion + id + count + components).
 * Kit icons and previews should use [displayAmount] = 1.
 */
object CmiItemCodec {
    fun encode(
        stack: ItemStack,
        displayAmount: Int = 1,
    ): String {
        val copy = stack.clone()
        copy.amount = displayAmount.coerceIn(1, copy.maxStackSize.coerceAtLeast(1))
        return gzipBase64(copy.serializeAsBytes())
    }

    fun decode(blob: String): ItemStack {
        val nbt = gunzipBase64(blob)
        return ItemStack.deserializeBytes(nbt)
    }

    fun yamlBinaryLine(blob: String): String = "!!binary |-\n  ${blob.trim()}"

    private fun gzipBase64(raw: ByteArray): String {
        val bos = ByteArrayOutputStream(raw.size + 32)
        GZIPOutputStream(bos).use { it.write(raw) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    private fun gunzipBase64(blob: String): ByteArray {
        val compressed = Base64.getDecoder().decode(blob.replace("\n", "").trim())
        GZIPInputStream(ByteArrayInputStream(compressed)).use { return it.readBytes() }
    }
}
