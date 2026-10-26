package ru.arc.staffspells

import com.google.gson.GsonBuilder
import org.bukkit.Particle
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.nio.file.Files
import java.nio.file.Path

/** Source geometry in a stationary hostile-caster fixture; no server collision, sound or packet claims. */
object StaffMobCasterPreviewExport {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val clips = listOf(StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.LANCE, StaffSpell.MARK)
            .map { clip(it, "${it.id}-normal", 8.0) } + listOf(
                clip(StaffSpell.EMBER, "ember-close", 3.0),
                clip(StaffSpell.EMBER, "ember-hard", 8.0, difficulty = 4.0),
                clip(StaffSpell.EMBER, "ember-cancel", 8.0, cancelAt = 10),
            )
        val output = Path.of(args[0])
        Files.createDirectories(output.toAbsolutePath().parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC · Маги-мобы · предупреждение и атака",
            "evidence" to "Production display geometry and mark-boundary samples, with the stationary controller timeline. Native particle accents, audio, collision execution, networking and movement are not exported.",
            "clips" to clips,
        )))
        println("STAFF_MOB_PREVIEW clips=${clips.size} output=$output")
    }

    private fun clip(spell: StaffSpell, id: String, distance: Double, difficulty: Double = 1.0,
        cancelAt: Int? = null): Map<String, Any> {
        val settings = StaffMobCasterSettings(difficulty = difficulty)
        val preparation = if (spell == StaffSpell.MARK) settings.markTelegraphTicks else settings.telegraphTicks
        val eye = Vector(0.0, 1.62, distance)
        val direction = Vector(0.0, .9 - 1.62, distance).normalize()
        val muzzle = Vector(0.0, 1.5, 0.0).add(direction.clone().multiply(.75))
        val aim = Vector(0.0, .9, distance)
        val heading = aim.clone().subtract(muzzle).normalize()
        val rotation = staffLanceOrientation(Vector3f(heading.x.toFloat(), heading.y.toFloat(), heading.z.toFloat()))
        val target = BoundingBox(-.3, 0.0, distance - .3, .3, 1.8, distance + .3).expand(.24)
        val contact = target.rayTrace(muzzle, heading, settings.range)!!.hitPosition
        val travel = muzzle.distance(contact)
        val positions = mutableListOf(0.0)
        while (positions.last() < travel - .0001 && positions.size < 81) {
            val age = positions.size
            val step = if (spell == StaffSpell.ICICLE) minOf(1.2, staffIcicleStep(age)) else staffEmberStep(age, .5)
            positions += minOf(travel, positions.last() + step)
        }
        val flightTicks = when (spell) {
            StaffSpell.MARK -> 0
            StaffSpell.LANCE -> (6..80).first {
                staffLanceFront(it - StaffSpellDisplayEffects.FRAME_TICKS, settings.range) >= travel
            }
            else -> positions.lastIndex
        }
        val impactAt = preparation + flightTicks
        val rawDuration = if (spell == StaffSpell.LANCE) preparation + staffLanceDuration(settings.range) else impactAt + 14
        val duration = ((rawDuration + 1) / 2) * 2
        val charged = when (spell) {
            StaffSpell.EMBER -> staffEmberBodyParts(.55)
            StaffSpell.ICICLE -> staffIcicleChargeParts(1.0 / 11.0, 0)
            else -> emptyList()
        }
        var previousBounds = emptyMap<String, Pair<Vector, Double>>()
        val frames = (0..duration step 2).map { tick ->
            val flightAge = tick - preparation
            val impactAge = tick - impactAt
            val markOrigin = Vector(0.0, .06, distance)
            var origin = muzzle.clone()
            var facing = Quaternionf(rotation)
            var body: List<StaffDisplayPart> = emptyList()
            val alive = cancelAt == null || tick < cancelAt
            if (alive && tick < rawDuration) when (spell) {
                StaffSpell.EMBER, StaffSpell.ICICLE -> {
                    body = when {
                        tick < preparation -> if (spell == StaffSpell.EMBER)
                            staffEmberBodyParts(tick.toDouble() / preparation * .55)
                        else staffIcicleChargeParts(tick.toDouble() / preparation / 11.0, 0)
                        impactAge < 0 -> charged
                        else -> {
                            val fresh = staffDisplayParts(spell, impactAge, 14, 1.0, 1.1, true,
                                charge = if (spell == StaffSpell.EMBER) .55 else 1.0 / 11.0)
                            val old = if (spell == StaffSpell.EMBER) charged.map { transform(it, rotation) } else charged
                            if (impactAge < 4) blendStaffParts(fresh, old, impactAge) else fresh
                        }
                    }
                    if (flightAge >= 0) origin = if (impactAge >= 0) contact.clone()
                        else muzzle.clone().add(heading.clone().multiply(positions[flightAge.coerceAtMost(positions.lastIndex)]))
                    if (impactAge >= 0 && spell == StaffSpell.EMBER) facing = Quaternionf()
                }
                StaffSpell.LANCE -> {
                    body = if (tick < preparation) staffMobLancePreparation(tick, preparation + 6, heading)
                    else staffDisplayParts(spell, flightAge, staffLanceDuration(settings.range), settings.range, .85, true)
                    if (tick < preparation) facing = Quaternionf()
                }
                StaffSpell.MARK -> {
                    origin = markOrigin
                    facing = Quaternionf().rotationY(Math.PI.toFloat())
                    body = staffDisplayParts(spell, if (tick < preparation) tick else impactAge,
                        if (tick < preparation) preparation + 2 else 14, .1, 1.6,
                        impact = tick >= preparation, animationAgeTicks = tick)
                }
                else -> Unit
            }
            val currentBounds = mutableMapOf<String, Pair<Vector, Double>>()
            val parts = body.mapIndexed { index, local ->
                val transformed = transform(local, facing)
                val at = Vector(transformed.center.x.toDouble(), transformed.center.y.toDouble(), transformed.center.z.toDouble()).add(origin)
                val radius = transformed.scale.length().toDouble() * .5
                val key = local.identity ?: index.toString()
                val previous = previousBounds[key] ?: (at to radius)
                val impact = spell == StaffSpell.LANCE || impactAge >= 0
                val visible = staffSweptPartClearOfEye(eye, Vector(0.0, -.01, -1.0).normalize(),
                    previous.first, at, maxOf(previous.second, radius), staffEyeClearance(spell, impact) + .15)
                currentBounds[key] = at to radius
                StaffRedesignPreviewExport.part(transformed.copy(visible = transformed.visible && visible), "mob-$spell", index, origin)
            }
            previousBounds = currentBounds
            val emissions = if (alive && spell == StaffSpell.MARK && tick < preparation && tick % 4 == 0)
                staffMobMarkBoundary(tick, preparation, 1.6).map { sample ->
                    val dust = sample.data as Particle.DustOptions
                    mapOf("particle" to "dust", "offset" to point(sample.offset.clone().add(markOrigin)),
                        "count" to sample.count, "spread" to point(sample.spread), "speed" to sample.speed,
                        "data" to mapOf("kind" to "dust", "rgb" to dust.color.asRGB(), "size" to dust.size))
                } else emptyList()
            mapOf("tick" to tick, "parts" to parts, "emissions" to emissions)
        }
        return mapOf("id" to id, "spell" to spell.id, "input" to "PRIMARY", "charge" to 0.0,
            "durationTicks" to duration, "tailTicks" to 12, "frameStepTicks" to 2,
            "frames" to frames, "quick" to false, "cameraRole" to "defender",
            // The existing viewer names its camera anchor casterEye; here it is deliberately the defender.
            "casterFeet" to point(Vector(0.0, 0.0, distance)), "casterEye" to point(eye),
            "casterLook" to point(Vector(0.0, -.01, -1.0)),
            "illustrativeTargets" to listOf(point(Vector())),
            "syntheticTerrain" to (-2..2).flatMap { x -> (-1..18).map { z ->
                mapOf("x" to x, "y" to -.5, "z" to z, "material" to "minecraft:grass_block")
            } },
            "timelineNote" to "Камера: защитник, цель в $distance блоках; подготовка $preparation т., выпуск $preparation т., ${if (cancelAt != null) "снятие сцены в $cancelAt т." else "контакт около $impactAt т."} Геометрия и контур из кода; стационарная фикстура, без native аудио, акцентных частиц и выполнения collision.")
    }

    private fun transform(part: StaffDisplayPart, rotation: Quaternionf) = part.copy(
        center = Quaternionf(rotation).transform(Vector3f(part.center)),
        rotation = Quaternionf(rotation).mul(part.rotation),
    )
    private fun point(v: Vector) = mapOf("x" to v.x, "y" to v.y, "z" to v.z)
}
