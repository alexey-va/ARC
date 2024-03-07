package arc.arc.network.repos;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString
@Data
public abstract class RepoData {

    @Getter @Setter
    transient boolean dirty = true;
/*    @Getter @Setter
    long lastUpdated;*/

    public abstract String id();
    public abstract boolean isRemove();
}
