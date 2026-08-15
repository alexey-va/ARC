package ru.arc.hooks.slimefun

import com.denizenscript.denizen.objects.LocationTag
import com.denizenscript.denizencore.objects.core.ElementTag
import com.denizenscript.denizencore.tags.Attribute
import com.denizenscript.denizencore.tags.ObjectTagProcessor
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.block.Block

class SlimefunDenizenTagsTest : FreeSpec({

    "registers both tags once per Denizen processor" {
        val processor = locationProcessor()
        val block = mockk<Block>()
        val location = mockk<LocationTag>()
        val attribute = mockk<Attribute>()
        every { location.getBlockForTag(attribute) } returns block

        SlimefunDenizenTags.register(processor) { "ELECTRIC_FURNACE" }
        SlimefunDenizenTags.register(processor) { null }

        processor.registeredObjectTags.keys shouldBe SlimefunDenizenTags.names
        runTag(processor, SlimefunDenizenTags.IS_SLIMEFUN_BLOCK, attribute, location)?.asBoolean() shouldBe true
        runTag(processor, SlimefunDenizenTags.SLIMEFUN_BLOCK_ID, attribute, location)
            ?.asString()
            .shouldBe("ELECTRIC_FURNACE")
    }

    "reports a loaded vanilla block as false without an id" {
        val processor = locationProcessor()
        val block = mockk<Block>()
        val location = mockk<LocationTag>()
        val attribute = mockk<Attribute>()
        every { location.getBlockForTag(attribute) } returns block

        SlimefunDenizenTags.register(processor) { null }

        runTag(processor, SlimefunDenizenTags.IS_SLIMEFUN_BLOCK, attribute, location)?.asBoolean() shouldBe false
        runTag(processor, SlimefunDenizenTags.SLIMEFUN_BLOCK_ID, attribute, location).shouldBeNull()
    }

    "does not query Slimefun for an unavailable block" {
        val processor = locationProcessor()
        val location = mockk<LocationTag>()
        val attribute = mockk<Attribute>()
        every { location.getBlockForTag(attribute) } returns null
        var lookups = 0

        SlimefunDenizenTags.register(processor) {
            lookups++
            "UNEXPECTED"
        }

        runTag(processor, SlimefunDenizenTags.IS_SLIMEFUN_BLOCK, attribute, location).shouldBeNull()
        runTag(processor, SlimefunDenizenTags.SLIMEFUN_BLOCK_ID, attribute, location).shouldBeNull()
        lookups shouldBe 0
    }
})

private fun locationProcessor(): ObjectTagProcessor<LocationTag> =
    ObjectTagProcessor<LocationTag>().apply { type = LocationTag::class.java }

private fun runTag(
    processor: ObjectTagProcessor<LocationTag>,
    name: String,
    attribute: Attribute,
    location: LocationTag,
): ElementTag? = processor.registeredObjectTags.getValue(name).runner.run(attribute, location) as ElementTag?
