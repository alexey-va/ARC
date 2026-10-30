package ru.arc.helpcenter

/** One identity for the special-item catalogue, global search and acquisition links. */
internal enum class HelpCenterEnchantmentGuideTopic(
    val id: String,
    private val configKey: String,
    val keywords: String,
    val container: Boolean = false,
) {
    WHITE_SCROLL("whitescroll", "white", "белый свиток защита разрушение whitescroll white scroll"),
    BLACK_SCROLL("blackscroll", "black", "черный чёрный свиток снять извлечь зачарование blackscroll black scroll"),
    MAGIC_DUST("magic", "magic-dust", "магическая пыль шанс успех разрушение magic dust"),
    HOLY_WHITE_SCROLL("holywhitescroll", "holy-white", "святой белый свиток легендарный камень возврата смерть сохранить holywhitescroll holy white scroll"),
    SECRET_DUST("secret", "secret-dust", "секретная пыль тинкер переработка secret dust"),
    MYSTERY_DUST("mystery", "mystery-dust", "мистическая загадочная пыль остаток mystery dust"),
    RANDOMIZER("randomizer", "randomizer", "книга рандома свиток рандомизации переброс шанс randomizer randomization"),
    TRANSMOG("transmog", "transmog", "свиток трансмогрификации сортировать порядок transmog"),
    SOUL_GEM("soulgem", "soul-gem", "камень душ самоцвет заряд извлечь withdrawsouls soulgem soul gem"),
    SOUL_TRACKER("soultracker", "soul-tracker", "счетчик счётчик душ накопить заряд souls soultracker"),
    WEAPON_ORB("orb_weapon", "orb-weapon", "сфера зачарования оружия слоты слотов меч топор лук булава orb weapon"),
    ARMOR_ORB("orb_armor", "orb-armor", "сфера зачарования брони слоты слотов шлем нагрудник поножи ботинки orb armor"),
    TOOL_ORB("orb_tool", "orb-tool", "сфера зачарования инструмента слоты слотов кирка лопата мотыга удочка orb tool"),
    SLOT_INCREASER("slotincreaser", "slot-increaser", "увеличитель слотов сфера увеличения количества слотов увеличение количество добавить лимит зачарований slotincreaser slot increaser"),
    BLOCK_TRACKER("blocktrak", "block-tracker", "счетчик счётчик блоков сломано статистика blocktrak"),
    PLAYER_TRACKER("stattrak", "player-tracker", "счетчик счётчик убийств игроков pvp статистика stattrak"),
    MOB_TRACKER("mobtrak", "mob-tracker", "счетчик счётчик мобов убийств статистика mobtrak"),
    FISH_TRACKER("fishtrak", "fish-tracker", "счетчик счётчик рыбы улов рыбалка удочка статистика fishtrak"),
    ITEM_NAME_TAG("itemnametag", "item-name-tag", "бирка название предмета переименовать имя itemnametag name tag"),
    BASE_BOX("ae_lootbox", "box-base", "таинственная шкатулка чар базовая ящик сундук лутбокс ae lootbox", true),
    ENCHANTED_BOX("ae_lootbox_enchanted", "box-enchanted", "зачарованная шкатулка чар ящик книги ae enchanted lootbox", true),
    MASTER_BOX("ae_lootbox_master", "box-master", "мастерская шкатулка чар ящик книги ae master lootbox", true),
    ENCHANT_SUPPLY("enchant_supply", "box-supply", "припасы чародея ежедневное задание пыль книги enchant supply", true),
    BOSS_SUPPLY("enchant_boss_supply", "box-boss-supply", "трофей чародея босс припасы пыль книги enchant boss supply", true),
    ;

    val labelKey get() = "ui.special-$configKey-label"
    val summaryKey get() = "ui.special-$configKey-summary"
    val bodyKey get() = "ui.special-$configKey-body"

    companion object {
        val byId = entries.associateBy { it.id }
    }
}
