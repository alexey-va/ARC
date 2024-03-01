package arc.arc.hooks.zauction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data @AllArgsConstructor
@NoArgsConstructor
@Builder
public class AuctionItemDto {

    String display;
    String seller;
    String price;
    long expire;
    String category;
    int amount;
    int priority;
    UUID uuid;
    boolean exist;
    List<String> lore = new ArrayList<>();

}
