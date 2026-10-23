package ru.arc.staffspells

import com.google.gson.GsonBuilder
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Arrow
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/** Production Q controller captures and synthetic F paths; not native-client acceptance. */
object StaffHotkeyPreviewExport {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffHotkeyPreviewExport <q-f-clips.json>" }
        val output = Path.of(args[0])
        // Each controller fixture owns its MockBukkit runtime; never nest runtimes.
        val qClips = StaffSpell.available.associateWith { captureQ(it) }
        val proximity = captureQ(StaffSpell.CHAIN, proximity = true)
        val fClips = MockBukkitTestRuntime.open().use {
            StaffSpell.available.associateWith(::mobilityClip)
        }
        val clips = buildList {
            StaffSpell.available.forEach { spell ->
                add(qClips.getValue(spell))
                if (spell == StaffSpell.CHAIN) add(proximity)
                add(fClips.getValue(spell))
            }
        }
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC · Q и F · 16 посохов",
            "frameStepTicks" to 1, "particleTailTicks" to 0, "previewReachBlocks" to 18.0,
            "evidence" to "Q: реальные StaffSpellTechniques/StaffSpellConjurations в MockBukkit, production display callbacks и все вызовы native particles/cue; входы, плоский пол, стена и маршруты целей заданы тестом. Серые фигуры — условные тела, не модели Minecraft. Контакты принимает mocked damage bridge; AI, физика мобов, сетевой transport, звучание и native client не проверяются. F: прежние production helpers на синтетических путях.",
            "clips" to clips,
        )).plus("\n"))
        println("STAFF_HOTKEY_PREVIEW clips=${clips.size} qControllerCaptures=${qClips.size + 1} output=$output")
    }

    private data class Frame(
        val parts: List<StaffDisplayPart>,
        val at: Vector = Vector(),
        val emissions: List<ParticleEmission> = emptyList(),
        val motion: Vector? = null,
    )

    private data class ParticleEmission(val samples: List<StaffParticleSample>, val at: Vector = Vector())

    private data class CapturedScene(
        val key: String,
        var origin: Location,
        val born: Int,
        val duration: Int,
        var geometry: (Int) -> List<StaffDisplayPart>,
    )

    /** Capture controller intent before the display transport and particle audience/budget gates. */
    private fun captureQ(spell: StaffSpell, proximity: Boolean = false): Map<String, Any> {
        var result: Map<String, Any> = emptyMap()
        val settings = staffSettings(range = 56.0, markTicks = 80)
        val withHarness: ((StaffHarness) -> Unit) -> Unit = if (spell == StaffSpell.NECROMANCER)
            { block -> withSummonHarness(block = block) }
        else { block -> withStaffHarness(settings = settings, block = block) }
        withHarness { h ->
            val world = h.paper.addSimpleWorld("q-preview-${spell.id}-$proximity")
            if (spell == StaffSpell.NECROMANCER)
                for (x in -1..1) for (z in -1..3) world.loadChunk(x, z)
            for (x in -12..12) for (z in -4..36) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("q-preview", world, spell)
            val actorFeet = when (spell) {
                StaffSpell.CHAIN -> if (proximity) listOf(2.1 to 10.5) else emptyList()
                StaffSpell.GALE -> listOf(.5 to 6.5, 1.9 to 10.5, -3.5 to 5.0, 4.5 to 5.0)
                StaffSpell.ICICLE -> listOf(.5 to 3.5, -2.1 to 2.5, 3.1 to 2.5)
                StaffSpell.PRISM -> listOf(5.5 to 14.5)
                StaffSpell.NOVA -> listOf(.5 to 7.45)
                StaffSpell.OSSUARY -> listOf(5.5 to 7.45)
                StaffSpell.EMBER -> listOf(.5 to 5.5, .5 to 9.5)
                StaffSpell.FAULT, StaffSpell.REQUIEM -> listOf(.5 to 8.5)
                StaffSpell.LUNAR -> listOf(.5 to 8.5)
                StaffSpell.NECROMANCER -> listOf(.5 to 18.5)
                else -> listOf(.5 to 6.5)
            }
            val targets = actorFeet.map { (x, z) -> h.zombie(world, x, z) }
            if (spell in setOf(StaffSpell.MARK, StaffSpell.FROST, StaffSpell.TIDE,
                    StaffSpell.GRAVITY, StaffSpell.LANCE, StaffSpell.NECROMANCER)) {
                player.teleport(player.location.setDirection(center(targets.first()).toVector()
                    .subtract(player.eyeLocation.toVector())))
            }
            val wall = if (spell == StaffSpell.LANCE) (-1..1).flatMap { x -> (64..67).map { y ->
                world.getBlockAt(x, y, 8).type = Material.STONE
                mapOf("x" to x + .5, "y" to y - 63.5, "z" to 8.5, "material" to "minecraft:stone")
            } } else emptyList()
            val initialFeet = player.location.clone()
            val initialEye = player.eyeLocation.clone()
            var age = 0
            var nextScene = 0
            val scenes = linkedMapOf<UUID, CapturedScene>()
            val lifecycle = mutableListOf<Map<String, Any>>()
            val contacts = mutableListOf<Map<String, Any>>()
            val commandEvents = mutableListOf<Map<String, Any>>()
            val sounds = mutableListOf<Map<String, Any>>()
            val emissions = mutableListOf<Map<String, Any>>()
            fun captureScene(at: Location, duration: Int, geometry: (Int) -> List<StaffDisplayPart>): UUID {
                val id = UUID(0L, (++nextScene).toLong())
                val key = "${spell.id}:scene:$nextScene"
                scenes[id] = CapturedScene(key, at.clone(), age, duration, geometry)
                lifecycle += mapOf("tick" to age, "event" to "spawn", "scene" to key,
                    "durationTicks" to duration, "origin" to point(relative(at)))
                return id
            }
            every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
                captureScene(arg(2), arg(3), arg(4))
            }
            every { h.effects.custom(any(), any(), any(), any(), any(), any()) } answers {
                captureScene(arg(2), arg(3), arg(5))
            }
            every { h.effects.updateCustom(any(), any(), any()) } answers {
                scenes[firstArg<UUID?>()]?.let { scene ->
                    scene.origin = secondArg<Location>().clone()
                    val parts = thirdArg<List<StaffDisplayPart>>().toList()
                    scene.geometry = { parts }
                }
                Unit
            }
            every { h.effects.isActive(any()) } answers {
                scenes[firstArg<UUID?>()]?.let { age - it.born < it.duration } == true
            }
            every { h.effects.remove(any()) } answers {
                scenes.remove(firstArg<UUID?>())?.let { scene ->
                    lifecycle += mapOf("tick" to age, "event" to "remove", "scene" to scene.key)
                }
                Unit
            }
            val cast = StaffSpellCast(player.uniqueId, UUID(0L, 8021L + spell.ordinal), 1, null, null, false)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            every { damage.capture(any(), any()) } returns cast
            every { damage.eligible(any(), any(), any()) } answers {
                secondArg<LivingEntity>().let { candidate -> targets.any { it === candidate } &&
                    candidate.isValid && !candidate.isDead && !candidate.isInvulnerable }
            }
            every { damage.hit(any(), any(), any(), any(), any()) } answers {
                val target = secondArg<LivingEntity>()
                contacts += mapOf("tick" to age, "actor" to "target:${targets.indexOfFirst { it === target }}",
                    "point" to point(relative(center(target))), "power" to arg<Double>(3),
                    "vanillaDamage" to arg<Double>(4))
                true
            }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            every { visuals.withOwner(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
            every { visuals.particles(any(), any(), any()) } answers {
                val at = relative(firstArg())
                val clearance = thirdArg<Double>()
                secondArg<List<StaffParticleSample>>().forEach {
                    emissions += sample(it, at) + ("clearance" to clearance)
                }
            }
            every { visuals.cue(any(), any(), any(), any()) } answers {
                sounds += mapOf("tick" to age, "sound" to secondArg<Sound>().key.toString(),
                    "point" to point(relative(firstArg())), "volume" to arg<Float>(2), "pitch" to arg<Float>(3))
            }
            val targeting = StaffSpellTargeting(settings, damage)
            val liveSummons = if (spell == StaffSpell.NECROMANCER)
                StaffSpellSummons(settings, damage, visuals, targeting, ::spawnPickupSafeActor) else null
            val squad = mutableListOf<AbstractSkeleton>()
            val arrowBirths = linkedMapOf<UUID, Map<String, Any>>()
            val summons = mockk<StaffSpellSummons>(relaxed = true)
            every { summons.commandFocus(any(), any(), any()) } answers {
                val target = secondArg<LivingEntity>()
                commandEvents += mapOf("tick" to age, "command" to "focus",
                    "actor" to "target:${targets.indexOfFirst { it === target }}")
                liveSummons?.commandFocus(firstArg(), secondArg(), thirdArg()) ?: true
            }
            every { summons.commandVolley(any(), any(), any(), any()) } answers {
                val target = secondArg<LivingEntity>()
                commandEvents += mapOf("tick" to age, "command" to "volley",
                    "actor" to "target:${targets.indexOfFirst { it === target }}")
                liveSummons?.commandVolley(firstArg(), secondArg(), thirdArg(), arg(3)) ?: true
            }
            every { summons.commandRally(any(), any(), any()) } answers {
                commandEvents += mapOf("tick" to age, "command" to "rally",
                    "point" to point(relative(secondArg())))
                liveSummons?.commandRally(firstArg(), secondArg(), thirdArg()) ?: true
            }
            val conjurations = StaffSpellConjurations(settings, damage, h.effects, visuals, targeting)
            val techniques = StaffSpellTechniques(settings, damage, h.effects, visuals, targeting, summons,
                rootTarget = conjurations::rootTarget, rootActive = conjurations::rootActive,
                clearRoots = conjurations::clearRoots)
            val frames = mutableListOf<Map<String, Any>>()
            val duration = qDuration(spell, proximity)
            var throwVelocity: Vector? = null
            var throwFeet: Vector? = null
            fun activate() = techniques.cast(player, spell, cast, settings.tuning.getValue(spell)) ||
                conjurations.cast(player, spell, cast, settings.tuning.getValue(spell))
            try {
                if (liveSummons != null) {
                    // The Q acts on a pre-existing real test squad, never on extra preview-only NPCs.
                    liveSummons.start()
                    targets.first().isInvulnerable = true
                    repeat(5) {
                        check(liveSummons.castResult(player, cast, settings.tuning.getValue(spell),
                            StaffCastInput.PRIMARY, 0.0) == StaffSummonCastResult.CAST)
                    }
                    squad += world.entities.filterIsInstance<AbstractSkeleton>()
                    check(squad.size == 10) { "NECRO preview requires exactly ten existing archers" }
                    squad.forEachIndexed { index, actor ->
                        actor.teleport(Location(world, if (index % 2 == 0) -1.0 else 2.0,
                            64.0, 5.2 - (index / 2) * .8))
                    }
                    repeat(10) { h.paper.performTicks(1); h.scheduler.tick(1) }
                    targets.first().isInvulnerable = false
                    // Summoning is setup; Q evidence starts after the ten-tick birth cue has finished.
                    emissions.clear()
                    sounds.clear()
                    contacts.clear()
                }
                techniques.start()
                if (spell == StaffSpell.FROST) {
                    targets.forEach { target ->
                        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 80, 1))
                        conjurations.rememberFrostSlow(player, target, 80)
                    }
                }
                check(activate()) { "Q preview admission failed: ${spell.id}" }
                val placedAt = scenes.values.first().origin.clone()
                if (spell in setOf(StaffSpell.NOVA, StaffSpell.OSSUARY)) {
                    // NOVA aims under a real target; move it away before the first armed tick.
                    targets.first().teleport(placedAt.clone().apply { y = 64.0; x += 5.5 })
                }
                for (tick in 0..duration) {
                    age = tick
                    if (tick > 0) {
                        when (spell) {
                            StaffSpell.MARK -> targets.first().teleport(Location(world,
                                .5 + ((tick - 22) / 14.0).coerceIn(0.0, 1.0) * 5.5, 64.0, 6.5))
                            StaffSpell.NOVA, StaffSpell.OSSUARY -> targets.first().teleport(placedAt.clone().apply {
                                y = 64.0
                                x += if (tick < 28) 5.5 else 0.0
                            })
                            StaffSpell.PRISM -> targets.first().teleport(placedAt.clone().apply {
                                y = 64.0
                                x += (5.5 - (tick - 16).coerceAtLeast(0) * .55).coerceAtLeast(0.0)
                            })
                            StaffSpell.LUNAR -> player.teleport(initialFeet.clone().add(
                                (tick - 12).coerceIn(0, 16) * .12, 0.0, 0.0))
                            StaffSpell.EMBER -> player.teleport(initialFeet.clone().add(
                                sin(tick.coerceAtMost(32) * .12) * .4, 0.0, tick.coerceAtMost(32) * .25))
                            else -> Unit
                        }
                        // Both clocks advance: roots compare native Bukkit ticks and potion durations.
                        h.paper.performTicks(1)
                        h.scheduler.tick(1)
                        if (spell == StaffSpell.GRAVITY && tick == 16 ||
                            spell in setOf(StaffSpell.FAULT, StaffSpell.REQUIEM) && tick == 42 ||
                            spell == StaffSpell.EMBER && tick == 36) {
                            check(activate()) { "Q preview recast failed: ${spell.id} t$tick" }
                            lifecycle += mapOf("tick" to tick, "event" to "recast", "spell" to spell.id)
                            if (spell == StaffSpell.GRAVITY) {
                                throwVelocity = targets.first().velocity.clone()
                                throwFeet = relative(targets.first().location)
                            }
                        }
                    }
                    val productionParts = scenes.values.filter { age - it.born in 0 until it.duration }
                        .flatMap { scene -> scene.geometry(age - scene.born).mapIndexed { index, display ->
                            StaffRedesignPreviewExport.part(display, scene.key, index, relative(scene.origin))
                        } }
                    val actorRows = targets.mapIndexed { index, target ->
                        val feet = if (spell == StaffSpell.GRAVITY && tick >= 16 && throwVelocity != null)
                            requireNotNull(throwFeet).clone().add(requireNotNull(throwVelocity).clone()
                                .multiply((tick - 16).coerceAtMost(10).toDouble())) else relative(target.location)
                        actorRow("target:$index", feet, "test target", true, target.velocity,
                            if (spell == StaffSpell.GRAVITY && tick > 16) "illustrative linear path from captured launch velocity" else "mock entity location")
                    }.toMutableList()
                    squad.forEachIndexed { index, actor -> if (actor.isValid && !actor.isDead)
                        actorRows += actorRow("squad:$index", relative(actor.location),
                            "existing mock archer; model is illustrative", true, actor.velocity,
                            "mock entity location; initial formation is a test input, native locomotion is not simulated") }
                    val arrows = world.entities.filterIsInstance<Arrow>().filter { it.isValid && !it.isDead }
                    val arrowRows = arrows.map { arrow ->
                        val birth = arrowBirths.getOrPut(arrow.uniqueId) {
                            mapOf("id" to "arrow:${arrowBirths.size}", "tick" to tick,
                                "shooter" to "squad:${squad.indexOfFirst { it === arrow.shooter }}")
                        }
                        mapOf("id" to birth.getValue("id"), "point" to point(relative(arrow.location)),
                            "direction" to point(arrow.location.direction), "motionEvidence" to "actual Summons flight controller")
                    }
                    val actors = actorRows.flatMap { row ->
                        @Suppress("UNCHECKED_CAST")
                        val feet = row.getValue("feet") as Map<String, Double>
                        actorParts(row.getValue("id") as String,
                            Vector(feet.getValue("x"), feet.getValue("y"), feet.getValue("z")))
                    }
                    val arrowParts = arrows.flatMap { arrow -> projectileParts(
                        arrowBirths.getValue(arrow.uniqueId).getValue("id") as String,
                        relative(arrow.location), arrow.location.direction,
                    ) }
                    frames += mapOf("tick" to tick, "syntheticMotionOffset" to point(
                        player.location.toVector().subtract(initialFeet.toVector())),
                        "casterFeet" to point(relative(player.location)),
                        "particles" to emissions.toList(), "parts" to productionParts + actors + arrowParts,
                        "productionPartCount" to productionParts.size, "illustrativePartCount" to actors.size + arrowParts.size,
                        "actors" to actorRows, "nativeProjectiles" to arrowRows)
                    emissions.clear()
                }
                check(frames.any { (it.getValue("productionPartCount") as Int) > 0 }) { "No Q geometry: ${spell.id}" }
                check(frames.any { (it.getValue("particles") as List<*>).isNotEmpty() }) { "No native Q emissions: ${spell.id}" }
                val requiredContact = spell in setOf(StaffSpell.MARK, StaffSpell.FROST, StaffSpell.NOVA,
                    StaffSpell.OSSUARY, StaffSpell.GRAVITY, StaffSpell.LANCE, StaffSpell.PRISM, StaffSpell.TIDE)
                check(!requiredContact || contacts.isNotEmpty()) { "Missing scenario contact: ${spell.id}" }
                if (spell == StaffSpell.LANCE) check(lifecycle.any {
                    it["event"] == "spawn" && it["durationTicks"] == 14
                }) { "LANCE fixture did not pin" }
                if (liveSummons != null) {
                    check(commandEvents.any { it["command"] == "volley" }) { "NECRO Q did not request volley" }
                    check(arrowBirths.size == squad.size && arrowBirths.values.all { it["tick"] == 12 }) {
                        "NECRO Q must release one arrow per existing archer after 12 ticks: $arrowBirths"
                    }
                    check(contacts.isNotEmpty() && contacts.size <= squad.size) {
                        "NECRO Q arrows did not produce bounded fixture contacts"
                    }
                }
                result = mapOf(
                    "id" to if (proximity) "chain-q-proximity" else "${spell.id}-q",
                    "spell" to spell.id, "input" to "Q", "quick" to false, "charge" to 0,
                    "durationTicks" to duration, "tailTicks" to 0, "frameStepTicks" to 1,
                    "particleOnly" to false, "anchor" to "captured world coordinates; fixture floor normalized to Y=0",
                    "previewReachBlocks" to if (spell == StaffSpell.CHAIN) 32.0 else 18.0,
                    "casterFeet" to point(relative(initialFeet)), "casterEye" to point(relative(initialEye)),
                    "casterLook" to point(initialEye.direction), "syntheticTerrain" to wall,
                    "timelineNote" to qNote(spell, proximity) + " Реальный контроллер sampled каждый тик; displays до общей двухтиковой интерполяции, particles до audience/clearance/budget gates. Серые тела — условная геометрия; координаты mock-целей заданы тестом, velocity и контакты записаны. Native AI, физика, transport и звучание не моделируются.",
                    "sourceFunctions" to listOf("StaffSpellTechniques.cast", "StaffSpellConjurations.cast",
                        "StaffSpellDisplayEffects.custom/updateCustom", "StaffSpellVisuals.particles/cue") +
                        if (liveSummons == null) emptyList() else listOf("StaffSpellSummons.commandVolley", "StaffSpellSummons.tickFlights"),
                    "sceneLifecycle" to lifecycle.toList(), "acceptedContacts" to contacts.toList(),
                    "commandEvents" to commandEvents.toList(), "nativeProjectileBirths" to arrowBirths.values.toList(),
                    "soundCues" to sounds.toList(), "frames" to frames,
                )
            } finally {
                techniques.close()
                conjurations.close()
                liveSummons?.close()
            }
        }
        return result
    }

    private fun qDuration(spell: StaffSpell, proximity: Boolean) = when (spell) {
        StaffSpell.CHAIN -> if (proximity) 30 else 44
        StaffSpell.GALE -> STAFF_GALE_HURRICANE_TICKS + 2
        StaffSpell.ICICLE -> STAFF_ICICLE_RADIAL_TICKS + 2
        StaffSpell.LUNAR -> STAFF_LUNAR_TOTAL_TICKS + 2
        StaffSpell.MARK -> 50
        StaffSpell.FROST -> 20
        StaffSpell.LANCE -> 18
        StaffSpell.EMBER -> 66
        StaffSpell.NOVA -> 48
        StaffSpell.TIDE -> 40
        StaffSpell.REQUIEM -> 58
        StaffSpell.FAULT -> 56
        StaffSpell.PRISM -> 62
        StaffSpell.NECROMANCER -> 62
        StaffSpell.GRAVITY -> 32
        StaffSpell.OSSUARY -> 40
        StaffSpell.INFERNO -> error("Retired staff has no Q clip")
    }

    private fun qNote(spell: StaffSpell, proximity: Boolean) = when (spell) {
        StaffSpell.CHAIN -> if (proximity) "Шаровая молния: mock-цель стоит сбоку от траектории; настоящий proximity fuse определяет тик разрыва."
            else "Шаровая молния: пустая трасса до production range limit, затем полный разрыв."
        StaffSpell.MARK -> "Гравитационная привязь: mock-цель начинает отходить в t23, пересекает радиус привязи, контроллер наносит один разрыв и создаёт его aftermath."
        StaffSpell.FROST -> "Оттепель: на mock-цели установлен принадлежащий кастеру slow; Q расходует именно эту отметку."
        StaffSpell.LANCE -> "Солнечный гвоздь: mock-цель в 6 блоках, настоящая тестовая стена за ней. Controller подтверждает wall pin; velocity записана, перемещение моба физикой не исполняется."
        StaffSpell.EMBER -> "Пепельный след: mock-кастер проходит заданный 8-блочный путь за 32 тика. Повторный Q в t36 запускает настоящее последовательное поджигание записанных узлов."
        StaffSpell.NOVA -> "Живое семя: настоящее взведение, mock-цель входит в t28; срабатывание, root и bloom определяет контроллер."
        StaffSpell.ICICLE -> "Кристальный всплеск: настоящий radial Q вокруг кастера; три mock-цели показывают размер и момент контакта."
        StaffSpell.GALE -> "Око урагана: полный рост, восемь импульсов и рассеивание; mock-цели внутри поля и за его пределом. Записанные толчки не двигают условные тела."
        StaffSpell.TIDE -> "Водяной плен: mock-цель, создание капсулы, удержание и естественный release после 25 тиков. Native tick и potion clock продвигаются вместе."
        StaffSpell.REQUIEM -> "Час расплаты: колокол ждёт; повторный Q в t42 вызывает настоящий strike и его 14-тиковую сцену."
        StaffSpell.FAULT -> "Обсидиановая преграда: настоящий angled barrier; повторный Q в t42 вызывает shatter."
        StaffSpell.PRISM -> "Режущие грани: mock-цель входит в production triangle после t16; контроллер определяет единственный cut и его импульс."
        StaffSpell.NECROMANCER -> "Приказ войску: десять существующих test-лучников (пять штатных summon casts до Q), заданная начальная формация, mock-цель в 18 блоках. Настоящие command marker, 12-тиковый windup, одна стрела на лучника, source-controlled flight и hits с production-лимитом по одной цели. Серые тела и модели стрел условны; их позиции сняты с реальных mock entities, лишние NPC не нарисованы. Native AI и locomotion не исполняются."
        StaffSpell.GRAVITY -> "Подвешивание: настоящая mock-цель удерживается; повторный Q в t16 освобождает её и задаёт launch velocity. После броска серое тело следует явно условной прямой из этой velocity, максимум 10 тиков, без gravity/drag/collision simulation."
        StaffSpell.OSSUARY -> "Костяная челюсть: mock-цель входит в t28; настоящий trigger заменяет открытую ловушку отдельной closing-сценой."
        StaffSpell.LUNAR -> "Возвратный серп: настоящий outbound, turn и homing return; mock-кастер с t13 делает заданный боковой шаг, цель стоит на трассе."
        StaffSpell.INFERNO -> error("Retired staff has no Q clip")
    }

    private fun relative(at: Location) = Vector(at.x, at.y - 64.0, at.z)

    private fun actorRow(id: String, feet: Vector, role: String, mockEntity: Boolean, velocity: Vector,
        motion: String): Map<String, Any> = mapOf("id" to id, "feet" to point(feet), "role" to role,
            "mockEntity" to mockEntity, "velocity" to point(velocity), "motionEvidence" to motion)

    /** Existing cuboid renderer only; these six gray blocks are context, never spell geometry. */
    private fun actorParts(id: String, feet: Vector): List<Map<String, Any>> {
        val bodies = listOf(
            floatArrayOf(-.14f, .32f, .25f, .64f, .28f), floatArrayOf(.14f, .32f, .25f, .64f, .28f),
            floatArrayOf(0f, 1f, .56f, .74f, .30f), floatArrayOf(-.38f, 1f, .18f, .66f, .24f),
            floatArrayOf(.38f, 1f, .18f, .66f, .24f), floatArrayOf(0f, 1.58f, .44f, .44f, .44f),
        )
        return bodies.mapIndexed { index, body -> StaffRedesignPreviewExport.part(
            StaffDisplayPart(if (index == 5) Material.LIGHT_GRAY_CONCRETE else Material.GRAY_CONCRETE,
                Vector3f(body[0], body[1], 0f), Vector3f(body[2], body[3], body[4]), Quaternionf()),
            "illustrative-actor:$id", index, feet,
        ) }
    }

    /** A small arrow stand-in at captured native entity poses; not an extra production display scene. */
    private fun projectileParts(id: String, at: Vector, direction: Vector): List<Map<String, Any>> {
        val facing = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f),
            Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()).normalize())
        val body = listOf(
            StaffDisplayPart(Material.BROWN_CONCRETE, Vector3f(), Vector3f(.045f, .045f, .72f), Quaternionf()),
            StaffDisplayPart(Material.LIGHT_GRAY_CONCRETE, Vector3f(0f, 0f, .38f), Vector3f(.12f, .12f, .15f), Quaternionf()),
            StaffDisplayPart(Material.GRAY_CONCRETE, Vector3f(0f, 0f, -.27f), Vector3f(.18f, .025f, .16f), Quaternionf()),
            StaffDisplayPart(Material.GRAY_CONCRETE, Vector3f(0f, 0f, -.27f), Vector3f(.025f, .18f, .16f), Quaternionf()),
        )
        return body.mapIndexed { index, display -> StaffRedesignPreviewExport.part(display.copy(
            center = facing.transform(Vector3f(display.center)), rotation = Quaternionf(facing)),
            "illustrative-projectile:$id", index, at) }
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
