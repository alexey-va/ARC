package arc.arc.network;

import arc.arc.util.SerialiseUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Message {

    private String server;
    private UUID uuid;


    public Message(String rawMessage) {
        String[] strings = rawMessage.split(SerialiseUtil.MESSAGE_METADATA_SEPARATOR);
        server = strings[0];
        uuid = UUID.fromString(strings[1]);
        parseData(strings[2]);
    }



    private void parseData(String strippedMessage) {
        String[] serialisedDataEntries = strippedMessage.split(SerialiseUtil.DATA_ENTRY_SEPARATOR);
        for (String pair : serialisedDataEntries) {
            String[] keyTypeValue = pair.split(SerialiseUtil.KEY_DATA_SEPARATOR);
            String key = keyTypeValue[0];
            String typeAndValue = keyTypeValue[1];
            //Entry entry = Entry.parseEntry(typeAndValue);
            //data.put(key, entry);
        }
    }

    public String getServer() {
        return server;
    }

    public UUID getUuid() {
        return uuid;
    }


}
