package arc.arc.leafdecay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.block.Block;

@AllArgsConstructor
@Data
@EqualsAndHashCode
public class LeafData {
    @EqualsAndHashCode.Include
    Block block;
    @EqualsAndHashCode.Exclude
    int rand;
}
