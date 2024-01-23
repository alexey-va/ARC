package arc.arc.board;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class BoardItem {

    public ItemStack stack;
    public BoardEntry entry;

}
