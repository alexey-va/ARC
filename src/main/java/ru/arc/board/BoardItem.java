package ru.arc.board;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.inventory.ItemStack;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class BoardItem {

    public ItemStack stack;
    public BoardEntry entry;

}
