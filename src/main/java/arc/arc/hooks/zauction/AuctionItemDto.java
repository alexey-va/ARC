package arc.arc.hooks.zauction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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
    String uuid;
    boolean exist;
    List<String> lore = new ArrayList<>();

}
