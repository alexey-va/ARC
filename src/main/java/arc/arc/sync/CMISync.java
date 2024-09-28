package arc.arc.sync;

import arc.arc.ARC;
import arc.arc.configs.MainConfig;
import arc.arc.sync.base.Context;
import arc.arc.sync.base.Sync;
import arc.arc.sync.base.SyncData;
import arc.arc.sync.base.SyncRepo;
import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Containers.PlayerMail;
import com.Zrips.CMI.Modules.Kits.Kit;
import com.Zrips.CMI.Modules.PlayerOptions.PlayerOption;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;

@Log4j2
public class CMISync implements Sync {

    SyncRepo<CMIDataDTO> repo;
    String key = "arc.cmi_data";

    public CMISync() {
        this.repo = SyncRepo.builder(CMIDataDTO.class)
                .key(key)
                .redisManager(ARC.redisManager)
                .dataApplier(this::applyData)
                .dataProducer(this::produceData)
                .build();
    }

    @Override
    public void playerJoin(UUID uuid) {
        repo.loadAndApplyData(uuid, false);
    }

    @Override
    public void forceSave(UUID uuid) {
        Context context = new Context();
        context.put("uuid", uuid);
        repo.saveAndPersistData(context, false);
    }

    @Override
    public void playerQuit(UUID uuid) {
        Context context = new Context();
        context.put("uuid", uuid);
        repo.saveAndPersistData(context, false);
    }

    @SuppressWarnings("deprecation")
    public void applyData(CMIDataDTO data) {
        if (data == null) {
            log.warn("Data is null {}", key);
            return;
        }
        if (data.server().equals(MainConfig.server)) return;

        OfflinePlayer offlinePlayer = ARC.plugin.getServer().getOfflinePlayer(data.uuid());

        CMIUser user = CMI.getInstance().getPlayerManager().getUser(data.uuid());
        if (user == null) {
            log.warn("User is null for {}", data.uuid());
            return;
        }

        if (!Objects.equals(user.getPrefix(), data.prefix))
            user.setNamePlatePrefix(data.prefix);
        if (!Objects.equals(user.getSuffix(), data.suffix))
            user.setNamePlateSuffix(data.suffix);
        if (!Objects.equals(user.getNickName(), data.nick))
            user.setNickName(data.nick, true);
        if (user.isGod() != data.god && offlinePlayer instanceof Player player)
            CMI.getInstance().getNMS().changeGodMode(player, data.god);
        if (user.getOptionState(PlayerOption.acceptingMoney) != data.noPay)
            user.setOptionState(PlayerOption.acceptingMoney, data.noPay);
        if (user.getOptionState(PlayerOption.shiftSignEdit) != data.shift)
            user.setOptionState(PlayerOption.shiftSignEdit, data.shift);
        if (user.getOptionState(PlayerOption.totemBossBar) != data.totem)
            user.setOptionState(PlayerOption.totemBossBar, data.totem);
        if (user.getOptionState(PlayerOption.acceptingPM) != data.pm)
            user.setOptionState(PlayerOption.acceptingPM, data.pm);


        Character glowChar = user.getGlow() == null ? null : user.getGlow().getChar();
        if (glowChar != data.glowColor) {
            if (data.glowColor != null) user.setGlow(ChatColor.getByChar(data.glowColor), true);
            else user.setGlow(null, true);
        }

        if (user.isCMIVanished() != data.vanish) {
            user.setVanished(data.vanish);
            user.updateVanishMode();
        }

        if (data.getKitUsage() != null) {
            data.getKitUsage().forEach((kitName, usage) -> {
                Kit kit = CMI.getInstance().getKitsManager().getKit(user.getPlayer(), kitName);
                if (user.getKits().containsKey(kit)) {
                    user.getKits().get(kit).setUsedTimes(usage.getUses());
                    user.getKits().get(kit).setLastUsage(usage.getLastUse());
                } else {
                    user.addKit(kit, usage.getLastUse(), usage.getUses(), true);
                }
            });
        }

        if (!Objects.equals(user.getRank().getName(), data.rank) && data.rank != null) {
            CMIRank rank = CMI.getInstance().getRankManager().getRank(data.rank);
            if (rank != null) user.setRank(rank);
        }

        user.clearMails();
        if (data.mail != null && !data.mail.isEmpty()) {
            for (int i = 0; i < data.mail.size(); i++) {
                CMIDataDTO.Mail mail = data.mail.get(i);
                PlayerMail playerMail = new PlayerMail(mail.getSender(), mail.getTime(), mail.getMessage());
                user.addMail(playerMail, (i == data.mail.size() - 1));
            }
        }
    }

    public CMIDataDTO produceData(Context context) {
        UUID uuid = context.get("uuid");
        if (uuid == null) {
            log.error("Could not extract uuid for cmi sync: {}", context);
            return null;
        }

        CMIUser user = CMI.getInstance().getPlayerManager().getUser(uuid);
        if (user == null) {
            log.error("Could not find user for cmi sync: {}", uuid);
            return null;
        }

        Map<String, CMIDataDTO.KitUsage> kitUsageMap = new HashMap<>();
        user.getKits().forEach((kit, usage) -> kitUsageMap.put(kit.getConfigName(), CMIDataDTO.KitUsage.builder()
                .uses(usage.getUsedTimes())
                .lastUse(usage.getLastUsage())
                .build()));

        return CMIDataDTO.builder()
                .timestamp(System.currentTimeMillis())
                .server(MainConfig.server)
                .uuid(uuid)
                .kitUsage(kitUsageMap)
                .prefix(user.getPrefix())
                .suffix(user.getSuffix())
                .rank(user.getRank() == null ? null : user.getRank().getName())
                .nick(user.getNickName())
                .god(user.isGod())
                .glowColor(user.getGlow() == null ? null : user.getGlow().getChar())
                .noPay(user.getOptionState(PlayerOption.acceptingMoney))
                .shift(user.getOptionState(PlayerOption.shiftSignEdit))
                .totem(user.getOptionState(PlayerOption.totemBossBar))
                .pm(user.getOptionState(PlayerOption.acceptingPM))
                .vanish(user.isCMIVanished())
                .mail(user.getMails() == null ? List.of() :
                        user.getMails().stream()
                                .map(mail -> CMIDataDTO.Mail.builder()
                                        .sender(mail.getSender())
                                        .time(mail.getTime())
                                        .message(mail.getMessage())
                                        .build())
                                .toList())
                .build();

    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CMIDataDTO implements SyncData {
        @SerializedName("ts")
        long timestamp;
        @SerializedName("s")
        String server;
        @SerializedName("u")
        UUID uuid;

        @SerializedName("p")
        String prefix;
        @SerializedName("sf")
        String suffix;
        @SerializedName("r")
        String rank;
        @SerializedName("n")
        String nick;
        @SerializedName("g")
        boolean god;
        @SerializedName("gc")
        Character glowColor;
        @SerializedName("np")
        boolean noPay;
        @SerializedName("sh")
        boolean shift;
        @SerializedName("t")
        boolean totem;
        @SerializedName("pm")
        boolean pm;
        @SerializedName("v")
        boolean vanish;
        @SerializedName("m")
        List<Mail> mail;
        @SerializedName("ku")
        Map<String, KitUsage> kitUsage;

        @Data
        @Builder
        @AllArgsConstructor
        @NoArgsConstructor
        static class Mail {
            @SerializedName("s")
            String sender;
            @SerializedName("t")
            long time;
            @SerializedName("m")
            String message;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        static class KitUsage {
            @SerializedName("ku")
            int uses;
            @SerializedName("kl")
            long lastUse;
        }

        @Override
        public long timestamp() {
            return timestamp;
        }

        @Override
        public String server() {
            return server;
        }

        @Override
        public UUID uuid() {
            return uuid;
        }
    }

}
