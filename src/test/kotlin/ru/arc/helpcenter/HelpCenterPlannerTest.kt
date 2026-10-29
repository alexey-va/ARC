package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class HelpCenterPlannerTest : StringSpec({
    val entries = listOf(
        HelpCenterSearchEntry(
            "privat",
            "Приват",
            "Земли и поселения",
            "земля поселение lands защита",
            HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT),
            command = "privat",
        ),
        HelpCenterSearchEntry(
            "land_delete",
            "Удалить поселение",
            "Открыть приват и выбрать поселение для удаления",
            "как удалить поселение распустить землю снести приват",
            HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT),
        ),
        HelpCenterSearchEntry(
            "land_remove_member",
            "Исключить игрока",
            "Выбрать поселение и убрать участника",
            "удалить выгнать исключить игрока участника",
            HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT),
        ),
        HelpCenterSearchEntry(
            "home_move",
            "Перенести дом",
            "Выбрать дом и заменить его сохранённую точку",
            "передвинуть переместить точку дома edithome",
            HelpCenterSearchAction.OpenPage(HelpCenterPage.TRAVEL),
        ),
        HelpCenterSearchEntry(
            "warps",
            "Варпы",
            "Публичные точки игроков",
            "телепорт точки",
            HelpCenterSearchAction.Execute("warps"),
            command = "warps",
        ),
        HelpCenterSearchEntry(
            "rank",
            "Ранги",
            "Серверный прогресс",
            "уровень развитие",
            HelpCenterSearchAction.Execute("rank dialog"),
            command = "rank",
        ),
    )

    "finds commands by label description command and configured keywords" {
        HelpCenterPlanner.search(entries, "земли", 5).map { it.id } shouldContainExactly listOf("privat")
        HelpCenterPlanner.search(entries, "/WARPS", 5).map { it.id } shouldContainExactly listOf("warps")
        HelpCenterPlanner.search(entries, "телепорт", 5).map { it.id } shouldContainExactly listOf("warps")
        HelpCenterPlanner.search(entries, "прогресс", 5).map { it.id } shouldContainExactly listOf("rank")
    }

    "ranks configured words after removing conversational filler" {
        HelpCenterPlanner.search(entries, "как удалить моё поселение", 5).first().id shouldBe "land_delete"
        HelpCenterPlanner.search(entries, "хочу перенести точку дома", 5).first().id shouldBe "home_move"
    }

    "reduces russian inflections to stable lexical roots" {
        HelpCenterLexicon.root("поселение") shouldBe HelpCenterLexicon.root("поселения")
        HelpCenterLexicon.root("земля") shouldBe HelpCenterLexicon.root("землёй")
        HelpCenterLexicon.root("домами") shouldBe HelpCenterLexicon.root("домах")
        HelpCenterLexicon.root("удаление") shouldBe HelpCenterLexicon.root("удаления")
    }

    "tolerates a small typo without turning unrelated results into matches" {
        HelpCenterPlanner.search(entries, "пириват", 5).first().id shouldBe "privat"
        HelpCenterPlanner.search(entries, "посиление удалить", 5).map { it.id } shouldContainExactly listOf("land_delete")
        HelpCenterPlanner.search(entries, "варпс", 5).first().id shouldBe "warps"
        HelpCenterPlanner.search(entries, "совершенно неизвестная штука", 5) shouldBe emptyList()
    }

    "uses character trigrams for noisy spelling" {
        (HelpCenterLexicon.trigramSimilarity("пириват", "приват") > 0.55) shouldBe true
        (HelpCenterLexicon.trigramSimilarity("посиление", "поселение") > 0.55) shouldBe true
        (HelpCenterLexicon.trigramSimilarity("поселение", "магазин") < 0.30) shouldBe true
    }

    "routes natural phrases through the configured production intents" {
        val intents = HelpCenterConfig.INTENTS.map { (id, text) ->
            HelpCenterSearchEntry(
                id = id,
                label = text.label,
                description = text.description,
                keywords = text.keywords,
                action = HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT),
            )
        }

        HelpCenterPlanner.search(intents, "как удалить моё посиление", 5).map { it.id } shouldContainExactly listOf("land-delete")
        HelpCenterPlanner.search(intents, "добавь друга в землю", 5).first().id shouldBe "land-invite"
        HelpCenterPlanner.search(intents, "переставить колокол", 5).first().id shouldBe "land-main-block"
        HelpCenterPlanner.search(intents, "сделать хом", 5).first().id shouldBe "home-create"
        HelpCenterPlanner.search(intents, "где мои деньги", 5).first().id shouldBe "my"
        HelpCenterPlanner.search(intents, "хочу написать другу", 5).first().id shouldBe "player-find"
        HelpCenterPlanner.search(intents, "я застрял", 5).first().id shouldBe "recovery"
    }

    "returns a stable bounded catalog for an empty query" {
        HelpCenterPlanner.search(entries, "   ", 2).map { it.id } shouldContainExactly listOf("privat", "land_delete")
    }

    "unknown conversational words do not bury useful configured vocabulary" {
        val local = listOf(
            HelpCenterSearchEntry("protect", "Защита территории", "Управление доступом", "чужие ломали постройку гриферы", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
            HelpCenterSearchEntry("death", "Вернуться назад", "Прошлая позиция", "умер смерть вещи", HelpCenterSearchAction.OpenPage(HelpCenterPage.TRAVEL)),
            HelpCenterSearchEntry("teams", "Кланы", "Играть вместе", "друг друзья компания", HelpCenterSearchAction.OpenPage(HelpCenterPage.PLAYERS)),
            HelpCenterSearchEntry("enchants", "Зачарования", "Чары для снаряжения", "чары магия", HelpCenterSearchAction.OpenPage(HelpCenterPage.ENCHANTMENTS)),
        )
        HelpCenterPlanner.search(local, "чтобы чужие не ломали", 4).first().id shouldBe "protect"
        HelpCenterPlanner.search(local, "вернуться туда где умер", 4).first().id shouldBe "death"
        HelpCenterPlanner.search(local, "хочу играть вместе с другом", 4).first().id shouldBe "teams"
        HelpCenterPlanner.search(local, "зачарованя", 4).first().id shouldBe "enchants"
    }

    "production catalog resolves common paraphrases locally with AI disabled" {
        val catalog = HelpCenterConfig.COMMANDS.map { (id, text) ->
            HelpCenterSearchEntry(id, text.label, text.description, text.keywords, HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT))
        } + HelpCenterConfig.INTENTS.map { (id, text) ->
            HelpCenterSearchEntry(id, text.label, text.description, text.keywords, HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT))
        }
        HelpCenterPlanner.search(catalog, "чтобы чужие не ломали", 8).first().id shouldBe "privat"
        HelpCenterPlanner.search(catalog, "вернуться туда где умер", 8).first().id shouldBe "back"
        HelpCenterPlanner.search(catalog, "хочу играть вместе с другом", 8).any { it.id == "teams" } shouldBe true
        HelpCenterPlanner.search(catalog, "зачарованя", 8).first().id shouldBe "enchants"
        HelpCenterPlanner.search(catalog, "не удалять дом", 8).any { it.id == "home-delete" } shouldBe false
    }

    "negated actions do not surface their opposite and negative toggle labels remain usable" {
        val local = entries + listOf(
            HelpCenterSearchEntry("home-delete", "Удалить дом", "Удалить сохранённую точку", "снести стереть дом", HelpCenterSearchAction.OpenPage(HelpCenterPage.TRAVEL)),
            HelpCenterSearchEntry("trails-on", "Протаптывать тропинки", "Создавать дорожки", "тропа ходьба", HelpCenterSearchAction.OpenPage(HelpCenterPage.SETTINGS)),
            HelpCenterSearchEntry("trails-off", "Не протаптывать тропинки", "Отключить дорожки", "тропа ходьба", HelpCenterSearchAction.OpenPage(HelpCenterPage.SETTINGS)),
        )
        HelpCenterPlanner.search(local, "не удалять дом", 5).any { it.id == "home-delete" } shouldBe false
        HelpCenterPlanner.search(local, "не хочу удалять дом", 5).any { it.id == "home-delete" } shouldBe false
        HelpCenterPlanner.search(local, "хочу протаптывать тропинки", 5).first().id shouldBe "trails-on"
        HelpCenterPlanner.search(local, "не хочу протаптывать тропинки", 5).first().id shouldBe "trails-off"
    }

    "builds only bounded player commands" {
        HelpCenterCommands.home("base_2") shouldBe "home base_2"
        HelpCenterCommands.createHome("Дом-2") shouldBe "sethome Дом-2"
        HelpCenterCommands.deleteHome("Дом-2") shouldBe "delhome Дом-2"
        HelpCenterCommands.relocateHome("Дом-2") shouldBe "edithome Дом-2 relocate"
        HelpCenterCommands.setHomePublic("Дом-2", true) shouldBe "huskhomes:edithome Дом-2 privacy public"
        HelpCenterCommands.setHomePublic("Дом-2", false) shouldBe "huskhomes:edithome Дом-2 privacy private"
        listOf("other.home", "bad argument", "home\nprivacy public", "/op").forEach { unsafeName ->
            runCatching { HelpCenterCommands.setHomePublic(unsafeName, true) }.isFailure shouldBe true
        }

        runCatching { HelpCenterCommands.home("bad argument") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.deleteHome("/op") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.execute("tell Alex hello") }.isFailure shouldBe true
    }

    "resolves public help pages without exposing internal command help" {
        HelpCenterPage.from("rtp") shouldBe HelpCenterPage.RTP
        HelpCenterPage.from("travel") shouldBe HelpCenterPage.TRAVEL
        HelpCenterPage.from("перемещения") shouldBe HelpCenterPage.TRAVEL
        HelpCenterPage.from("телепортация") shouldBe HelpCenterPage.TRAVEL
        HelpCenterPage.from("телепорт") shouldBe HelpCenterPage.TRAVEL
        HelpCenterPage.from("моё") shouldBe HelpCenterPage.MY
        HelpCenterPage.from("мое") shouldBe HelpCenterPage.MY
        HelpCenterPage.from("про меня") shouldBe HelpCenterPage.MY
        HelpCenterPage.from("privat") shouldBe HelpCenterPage.PRIVAT
        HelpCenterPage.from("правила") shouldBe HelpCenterPage.RULES
        HelpCenterPage.from("unknown") shouldBe null
    }

    "promotes the task hubs that belong on the universal root" {
        HelpCenterCategory.rootHubs shouldContainExactly listOf(
            HelpCenterCategory.ACTIVITIES,
            HelpCenterCategory.TRADE,
            HelpCenterCategory.TECHNOLOGY,
            HelpCenterCategory.SETTINGS,
        )
    }

    "orders a bounded personal next-action list from concrete profile gaps" {
        val profile = HelpCenterProfile(
            playerName = "NewPlayer",
            server = "survival",
            world = "classic_survival",
            x = 10,
            y = 70,
            z = -20,
            balance = "500",
            rank = "Поселенец",
            homes = HelpCenterHomes(emptyList(), 0, 3),
            lands = 0,
        )

        HelpCenterPlanner.recommendations(
            profile,
            setOf(
                HelpCenterFeature.LANDS,
                HelpCenterFeature.RANKS,
                HelpCenterFeature.EVENTS,
            ),
            limit = 4,
        ).map { it.id } shouldContainExactly listOf(
            HelpCenterRecommendationId.CREATE_HOME,
            HelpCenterRecommendationId.CREATE_LAND,
            HelpCenterRecommendationId.RANK_GOAL,
            HelpCenterRecommendationId.EVENTS,
        )
    }

    "does not invent onboarding gaps when profile data is unavailable" {
        val profile = HelpCenterProfile(
            playerName = "ExistingPlayer",
            server = "classic",
            world = "world",
            x = 0,
            y = 64,
            z = 0,
            balance = null,
            rank = null,
            homes = null,
            lands = null,
        )

        HelpCenterPlanner.recommendations(profile, setOf(HelpCenterFeature.EVENTS), 4)
            .map { it.id } shouldContainExactly listOf(HelpCenterRecommendationId.EVENTS)
    }

    "filters and bounds network players without returning the viewer" {
        val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val players = listOf(
            HelpCenterPlayer(viewer, "Viewer"),
            HelpCenterPlayer(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Zebra", "spawn"),
            HelpCenterPlayer(UUID.fromString("00000000-0000-0000-0000-000000000003"), "alice", "survival"),
            HelpCenterPlayer(UUID.fromString("00000000-0000-0000-0000-000000000004"), "Alina", "mining"),
        )

        HelpCenterPlanner.players(viewer, players, "ali", 1).map { it.name } shouldContainExactly listOf("alice")
        HelpCenterPlanner.players(viewer, players, "", 5).map { it.name } shouldContainExactly listOf("alice", "Alina", "Zebra")
    }

    "builds typed player commands and rejects command injection" {
        HelpCenterCommands.teleportRequest("Player_2") shouldBe "tpa Player_2"
        HelpCenterCommands.teleportHere("Player_2") shouldBe "tpahere Player_2"
        HelpCenterCommands.duel("Player_2") shouldBe "duel Player_2"
        HelpCenterCommands.message("Player_2", "  привет, идём в шахту?  ") shouldBe
            "msg Player_2 привет, идём в шахту?"
        HelpCenterCommands.pay("Player_2", "1500.50") shouldBe "pay Player_2 1500.5"

        runCatching { HelpCenterCommands.teleportRequest("Bad Player") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.message("Player_2", "первая строка\n/op Player_2") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.message("Player_2", " ") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.pay("Player_2", "-1") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.pay("Player_2", "1.999") }.isFailure shouldBe true
    }
})
