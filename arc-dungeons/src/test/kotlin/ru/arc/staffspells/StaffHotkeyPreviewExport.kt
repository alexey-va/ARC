package ru.arc.staffspells

import com.google.gson.GsonBuilder
import org.bukkit.block.data.BlockData
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Offline production geometry with explicitly synthetic cast paths; not native-client acceptance. */
object StaffHotkeyPreviewExport {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffHotkeyPreviewExport <q-f-clips.json>" }
        val output = Path.of(args[0])
        val clips = MockBukkitTestRuntime.open().use {
            buildList {
                StaffSpell.available.forEach { spell ->
                    add(if (spell == StaffSpell.LUNAR) lunarClip() else if (spell == StaffSpell.FAULT) faultClip() else qClip(spell))
                    add(mobilityClip(spell))
                }
            }
        }
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC · Q и F · 16 посохов",
            "frameStepTicks" to 2, "particleTailTicks" to 0, "previewReachBlocks" to 18.0,
            "evidence" to "Геометрические кадры вызывают production helpers; Q NECROMANCER показывает production-маркер приказа на синтетической точке сбора; сами скелеты здесь не симулируются. Доступные shared particle helpers включены. Траектории — синтетические сценарии в пределах указанных профилей, не запись серверного движения. Столкновения, урон, звук и нативный клиент не проверяются.",
            "clips" to clips,
        )).plus("\n"))
        println("STAFF_HOTKEY_PREVIEW clips=${clips.size} output=$output")
    }

    private data class Frame(
        val parts: List<StaffDisplayPart>,
        val at: Vector = Vector(),
        val emissions: List<ParticleEmission> = emptyList(),
        val motion: Vector? = null,
    )

    private data class ParticleEmission(val samples: List<StaffParticleSample>, val at: Vector = Vector())

    private fun lunarClip() = clip(
        StaffSpell.LUNAR, "Q", STAFF_LUNAR_TOTAL_TICKS,
        "Возвратный серп: production pose задаёт разворот и возврат. Положение серпа — синтетический сценарий; игрок не записан.",
        listOf("staffLunarBoomerangPose", "staffLunarBoomerangParts"), "кастер, старт впереди на +Z",
        geometry = { age ->
            val pose = staffLunarBoomerangPose(Vector3f(0f, 1.6f, 2f), Vector3f(0f, 0f, 1f),
                Vector3f((age.coerceAtLeast(12) - 12) * .12f, 1.6f, 0f), age)
            Frame(staffLunarBoomerangParts(pose.facingYaw, age),
                Vector(pose.position.x.toDouble(), pose.position.y.toDouble(), pose.position.z.toDouble()))
        },
    )

    /** Keep the original compact barrier-and-shatter representative. */
    private fun faultClip() = clip(
        StaffSpell.FAULT, "Q", 54,
        "Обсидиановая преграда: сжатый preview удерживает её 42 тика и затем показывает выброс осколков 12 тиков. Положение — синтетическая точка впереди.",
        listOf("staffFaultBarrierParts", "staffFaultBarrierShatterParts"), "пол у точки установки, +Z впереди",
        geometry = { age ->
            Frame(if (age < 42) staffFaultBarrierParts(age, Math.toRadians(22.0))
                else staffFaultBarrierShatterParts(age - 42, Math.toRadians(22.0)), Vector(0.0, 0.0, 3.0))
        },
    )

    private fun qClip(spell: StaffSpell): Map<String, Any> {
        val duration = when (spell) {
            StaffSpell.CHAIN -> 30
            StaffSpell.MARK -> 60
            StaffSpell.FROST -> 18
            StaffSpell.LANCE -> 14
            StaffSpell.EMBER -> 66
            StaffSpell.NOVA -> 72
            StaffSpell.ICICLE -> STAFF_ICICLE_RADIAL_TICKS
            StaffSpell.GALE -> 22
            StaffSpell.TIDE -> 25
            StaffSpell.REQUIEM -> 56
            StaffSpell.PRISM -> 60
            StaffSpell.NECROMANCER -> 24
            StaffSpell.GRAVITY -> 28
            StaffSpell.OSSUARY -> 38
            StaffSpell.LUNAR -> STAFF_LUNAR_TOTAL_TICKS
            StaffSpell.FAULT -> 90
            StaffSpell.INFERNO -> error("Retired staff has no Q clip")
        }
        val note: String
        val sources: List<String>
        val anchor: String
        val frame: (Int) -> Frame
        when (spell) {
            StaffSpell.CHAIN -> {
                note = "Шаровая молния: production orb geometry; центр проходит синтетические +Z 1.05 блока/тик до лимита дальности 28 блоков."
                sources = listOf("staffChainTechniqueParts")
                anchor = "кастерский глаз; +Z — синтетическая ось полёта"
                frame = { age -> Frame(staffChainTechniqueParts(0f, age / 2), Vector(0.0, 1.0, .75 + min(28.0, age * 1.05))) }
            }
            StaffSpell.MARK -> {
                note = "Гравитационная привязь: production tether между исходной точкой цели и синтетическим смещением цели на 3 блока. Это не запись движения противника."
                sources = listOf("staffMarkTetherParts")
                anchor = "центр синтетической цели впереди на +Z"
                frame = { age -> Frame(staffMarkTetherParts(age, Vector(0.0, 0.0, -3.0)), Vector(0.0, 1.0, 6.0)) }
            }
            StaffSpell.FROST -> {
                note = "Оттепель: production shards вокруг синтетического центра поражённой цели. Геометрия не представляет фактические slow-mark цели."
                sources = listOf("staffFrostThawParts")
                anchor = "центр цели впереди на +Z"
                frame = { age -> Frame(staffFrostThawParts(age), Vector(0.0, 1.0, 4.0)) }
            }
            StaffSpell.LANCE -> {
                note = "Солнечный гвоздь: production pin silhouette для синтетически закреплённой цели; фактическое попадание и стена не моделируются."
                sources = listOf("staffLancePinParts")
                anchor = "центр синтетической цели впереди на +Z"
                frame = { _ -> Frame(staffLancePinParts(2.2, 0f, held = true), Vector(0.0, 1.0, 4.0)) }
            }
            StaffSpell.EMBER -> {
                val origin = Vector3f(0f, .1f, 0f)
                note = "Пепельный след: production trail geometry из синтетического пути игрока (16 блоков по дуге). До t32 след записывается; синтетическое поджигание в t40 проходит по одному узлу каждые два тика. Это заданный сценарий, не записанное перемещение."
                sources = listOf("staffEmberTrailParts")
                anchor = "земля у кастера; путь синтетический, конец до 16 блоков впереди"
                frame = { age ->
                    val points = (0..8).map { index ->
                        Vector3f(sin(index * .55).toFloat() * .4f,
                            .1f + sin(index * .55).toFloat() * .25f, index * 2f)
                    }
                    Frame(if (age >= 60) emptyList() else staffEmberTrailParts(
                        points.take((age / 4 + 1).coerceAtMost(points.size)), origin,
                        if (age < 40) -1 else (age - 40) / 2,
                    ))
                }
            }
            StaffSpell.NOVA -> {
                note = "Живое семя: production trap geometry во время постановки и взведения на синтетической точке."
                sources = listOf("staffNovaSeedParts")
                anchor = "земля у точки постановки впереди на +Z"
                frame = { age -> Frame(staffNovaSeedParts(age), Vector(0.0, 0.0, 4.0)) }
            }
            StaffSpell.ICICLE -> {
                note = "Кристальный всплеск: production clusters grow around caster in4.5blocks. Contact tick6; collapse through18ticks. No enemy needed. Native collision/damage are not simulated."
                sources = listOf("staffIcicleRadialParts")
                anchor = "caster feet +0.04Y"
                frame = { age -> Frame(staffIcicleRadialParts(age), Vector(0.0, .04, 0.0)) }
            }
            StaffSpell.GALE -> {
                note = "Боковой шквал: production geometry проходит поперёк фиксированного коридора +Z по локальной оси +X, вслед за направлением реального бокового толчка в t7. После t10 ветер рассеивается; цели и урон здесь не симулируются."
                sources = listOf("staffGaleCorridorParts")
                anchor = "пол у кастера; коридор вдоль +Z"
                frame = { age -> Frame(staffGaleCorridorParts(age, 6.5, 2.0, 0.0), Vector(0.0, .08, 0.0)) }
            }
            StaffSpell.TIDE -> {
                note = "Водяной плен: production capsule geometry вокруг синтетически выбранной цели."
                sources = listOf("staffTideCapsuleParts")
                anchor = "цель впереди на +Z"
                frame = { age -> Frame(staffTideCapsuleParts(age), Vector(0.0, 0.0, 5.0)) }
            }
            StaffSpell.REQUIEM -> {
                note = "Час расплаты: production Q bell geometry; синтетическое повторное Q в t42 заменяет колокол 14-тиковым ударом. Это не primary-атака и не запись боя; target, звук и частицы контроллера здесь не симулируются."
                sources = listOf("staffRequiemBellParts(ageTicks)", "staffRequiemImpactParts")
                anchor = "земля у точки постановки впереди на +Z"
                frame = { age -> Frame(if (age < 42) staffRequiemBellParts(age)
                    else if (age < 56) staffRequiemImpactParts(age - 42, 4.1) else emptyList(),
                    Vector(0.0, 0.0, 5.0)) }
            }
            StaffSpell.PRISM -> {
                note = "Режущие грани: production tripwire geometry on a synthetic fixed ground point; target crossings are not simulated."
                sources = listOf("staffPrismTripwireParts")
                anchor = "земля впереди на +Z"
                frame = { age -> Frame(staffPrismTripwireParts(0f, age / 2), Vector(0.0, 0.0, 5.0)) }
            }
            StaffSpell.NECROMANCER -> {
                note = "Приказ войску: production-маркер на синтетической точке сбора. Работает без врага; движение скелетов и звук в preview не симулируются."
                sources = listOf("staffNecromancerCommandParts")
                anchor = "земля у точки сбора впереди на +Z"
                frame = { age -> Frame(staffNecromancerCommandParts(age), Vector(0.0, 0.0, 5.0)) }
            }
            StaffSpell.GRAVITY -> {
                note = "Подвешивание: production gravity-pin geometry вокруг синтетической цели. Цель и последующий бросок не моделируются."
                sources = listOf("staffGravityPinParts")
                anchor = "центр цели впереди на +Z"
                frame = { age -> Frame(staffGravityPinParts(age, thrown = false, yaw = 0f), Vector(0.0, 1.0, 6.0)) }
            }
            StaffSpell.OSSUARY -> {
                note = "Костяная челюсть: открытая production-ловушка ждёт до t28; синтетическое срабатывание в t28 запускает отдельный closing-age с нуля, как runtime. Закрытие занимает пять тиков, сцена удаляется через девять; столкновение и урон не симулируются."
                sources = listOf("staffOssuaryJawParts", "staffOssuaryJawClosingParts")
                anchor = "земля у точки постановки впереди на +Z"
                frame = { age -> Frame(if (age < 28) staffOssuaryJawParts(age)
                    else if (age < 37) staffOssuaryJawClosingParts(age - 28) else emptyList(),
                    Vector(0.0, 0.0, 4.0)) }
            }
            StaffSpell.LUNAR, StaffSpell.FAULT, StaffSpell.INFERNO -> error("Handled separately")
        }
        return clip(spell, "Q", duration, note, sources, anchor, frame,
            particleOnly = false)
    }

    private fun mobilityClip(spell: StaffSpell): Map<String, Any> {
        val profile = requireNotNull(staffMobilityProfile(spell))
        val movementDistance = profile.range
        fun offset(age: Int): Vector {
            if (profile.motion == StaffMobilityMotion.BLINK) return Vector(0.0, 0.0, movementDistance)
            val progress = (age.toDouble() / profile.durationTicks).coerceIn(0.0, 1.0)
            val eased = progress * progress * (3.0 - 2.0 * progress)
            val lift = if (profile.verticalImpulse > 0.0) profile.verticalImpulse * sin(progress * PI) * 1.6 else 0.0
            val sign = if (spell == StaffSpell.LANCE || spell == StaffSpell.EMBER) -1.0 else 1.0
            return Vector(0.0, lift, sign * movementDistance * eased)
        }
        val note = "${profile.motion}: production mobility profile (range=${profile.range}, forwardImpulse=${profile.forwardImpulse}, verticalImpulse=${profile.verticalImpulse}, steering=${profile.steering}, duration=${profile.durationTicks}t). Синтетическая камера следует за игроком: blink сразу переносит в конечную точку, остальные пути показывают синтетический сценарий до предела дальности ${"%.2f".format(Locale.ROOT, movementDistance)} блока с учётом направления отскока. Safe landing, collisions and server velocity are not simulated. Production silhouette and release particles are shared."
        return clip(spell, "F", profile.durationTicks + STAFF_MOBILITY_AFTERIMAGE_TICKS, note,
            listOf("staffMobilityProfile", "staffMobilityDisplayParts", "staffMobilityAfterimageParts", "staffMobilityParticleBurst"),
            "кастер; local +Z по синтетическому направлению движения", geometry = { age ->
                val position = offset(age)
                val emissions = if (age <= profile.durationTicks &&
                    (age == 0 || age % 4 == 0 || age == profile.durationTicks)) {
                    val burst = staffMobilityParticleBurst(spell, impulse = age == 0 || age == profile.durationTicks)
                    buildList {
                        if (age == 0) {
                            if (profile.motion == StaffMobilityMotion.BLINK) {
                                add(ParticleEmission(burst, Vector()))
                                add(ParticleEmission(burst, Vector(0.0, 0.0, movementDistance)))
                            } else add(ParticleEmission(burst, Vector()))
                        } else if (age % 4 == 0) {
                            add(ParticleEmission(burst, if (profile.motion == StaffMobilityMotion.BLINK)
                                Vector(0.0, 0.0, movementDistance) else position.clone()))
                        }
                        if (age == profile.durationTicks) add(ParticleEmission(burst, if (profile.motion == StaffMobilityMotion.BLINK)
                            Vector(0.0, 0.0, movementDistance) else position.clone()))
                    }
                } else emptyList()
                val direction = Vector(0.0, 0.0, 1.0)
                val parts = if (age < profile.durationTicks) {
                    staffMobilityDisplayParts(spell, age, profile.durationTicks, direction) +
                        staffMobilityAfterimageParts(spell, age, direction).map { part ->
                            part.copy(center = Vector3f(part.center).sub(position.x.toFloat(),
                                position.y.toFloat(), position.z.toFloat()))
                        }
                } else staffMobilityAfterimageParts(spell, age - profile.durationTicks, direction)
                Frame(parts, position, emissions, motion = position)
            })
    }

    private fun clip(
        spell: StaffSpell,
        input: String,
        duration: Int,
        note: String,
        sourceFunctions: List<String>,
        anchor: String,
        geometry: (Int) -> Frame,
        particleOnly: Boolean = false,
    ): Map<String, Any> = mapOf(
        "id" to "${spell.id}-${input.lowercase(Locale.ROOT)}", "spell" to spell.id, "input" to input,
        "quick" to false, "charge" to 0, "durationTicks" to duration,
        "tailTicks" to 0, "frameStepTicks" to 2, "particleOnly" to particleOnly,
        "anchor" to anchor, "previewReachBlocks" to 18.0,
        "timelineNote" to note, "sourceFunctions" to sourceFunctions,
        "frames" to (0..duration step 2).map { age ->
            val frame = geometry(age)
            mapOf("tick" to age, "syntheticMotionOffset" to point(frame.motion ?: frame.at),
                "particles" to frame.emissions.flatMap { emission -> emission.samples.map { sample(it, emission.at) } },
                "parts" to frame.parts.mapIndexed { index, part ->
                    StaffRedesignPreviewExport.part(part, spell.id, index, frame.at)
                })
        },
    )

    private fun sample(sample: StaffParticleSample, at: Vector): Map<String, Any> = buildMap {
        put("particle", sample.particle.name.lowercase(Locale.ROOT))
        put("offset", point(at.clone().add(sample.offset)))
        put("count", sample.count)
        put("spread", point(sample.spread))
        put("speed", sample.speed)
        particleData(sample.data)?.let { put("data", it) }
    }

    private fun particleData(data: Any?): Map<String, Any>? = when (data) {
        is org.bukkit.Particle.DustTransition -> mapOf(
            "kind" to "dust-transition", "fromRgb" to data.color.asRGB(),
            "toRgb" to data.toColor.asRGB(), "size" to data.size.toDouble(),
        )
        is org.bukkit.Particle.DustOptions -> mapOf(
            "kind" to "dust", "rgb" to data.color.asRGB(), "size" to data.size.toDouble(),
        )
        is BlockData -> mapOf("kind" to "block", "material" to data.material.name.lowercase(Locale.ROOT))
        else -> null
    }

    private fun point(vector: Vector) = mapOf("x" to vector.x, "y" to vector.y, "z" to vector.z)
}
