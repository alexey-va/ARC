package arc.arc.audit;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class Transaction {
    @SerializedName("t")
    Type type;
    @SerializedName("a")
    double amount;
    @SerializedName("c")
    String comment;
    @SerializedName("ts")
    long timestamp = System.currentTimeMillis();
    @SerializedName("ts2")
    long timestamp2 = System.currentTimeMillis();

    public Transaction(Type type, double amount, String comment) {
        this.type = type;
        this.amount = amount;
        this.comment = comment;
    }
}

