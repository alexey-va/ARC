package ru.arc.leafdecay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.Location;

@AllArgsConstructor
@Data
@EqualsAndHashCode
public class LeafData {
    @EqualsAndHashCode.Include
    Location block;
}
