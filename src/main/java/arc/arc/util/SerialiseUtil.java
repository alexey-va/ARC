package arc.arc.util;

import arc.arc.network.entries.CommandData;
import arc.arc.network.entries.CrossServerLocation;

public class SerialiseUtil {

    public static final String TYPE_VALUE_SEPARATOR = "<TypeValueSeparator>";
    public static final String MESSAGE_METADATA_SEPARATOR = "<MessageMetadataSeparator>";
    public static final String KEY_DATA_SEPARATOR = "<KeyValueSeparator>";
    public static final String DATA_ENTRY_SEPARATOR = "<DataEntrySeparator>";
    public static final String COMMAND_EXECUTOR_SEPARATOR = "<CommandExecutorSeparator>";

    public static String serialiseInteger(Integer integer){
        return "INT"+TYPE_VALUE_SEPARATOR+integer;
    }

    public static String serialiseString(String s){
        return "STRING"+TYPE_VALUE_SEPARATOR+s;
    }

    public static String serialiseDouble(Double d){
        return "DOUBLE"+TYPE_VALUE_SEPARATOR+d;
    }

    public static String serialiseLocation(CrossServerLocation crossServerLocation){
        return "LOCATION"+TYPE_VALUE_SEPARATOR+crossServerLocation.serialise();
    }

    public static String serialiseCommand(CommandData commandData){
        return "COMMAND"+TYPE_VALUE_SEPARATOR+commandData.serialise();
    }


}
