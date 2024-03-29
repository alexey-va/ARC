package arc.arc.hooks.jobs;

import lombok.*;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobsBoost {
    double boost;
    Type type;
    String jobName;
    long expires;
    @EqualsAndHashCode.Include
    UUID boostUuid;
    String id;

    public long expiresInMillis(){
        return expires - System.currentTimeMillis();
    }

    @RequiredArgsConstructor
    public enum Type {
        MONEY("Деньги"), EXP("Опыт"), POINTS("Очки"), ALL("Все");
        @Getter
        final String display;
    }
}
