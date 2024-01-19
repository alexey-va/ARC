package arc.arc.network.entries;

import arc.arc.util.SerialiseUtil;

public class CommandData extends ArcSerialisable {

    ExecutorType executorType;
    String command;


    public CommandData(ExecutorType executorType, String command) {
        super("COMMAND", CommandData.class);
        this.executorType = executorType;
        this.command = command;
    }

    public CommandData(String serialisedCommand){
        super("COMMAND", CommandData.class);
        command = serialisedCommand.split(SerialiseUtil.COMMAND_EXECUTOR_SEPARATOR)[0];
        executorType = ExecutorType.valueOf(serialisedCommand.split(SerialiseUtil.COMMAND_EXECUTOR_SEPARATOR)[1]);
    }

    public String serialise(){
        return command+SerialiseUtil.COMMAND_EXECUTOR_SEPARATOR+executorType;
    }

    public enum ExecutorType {
        CONSOLE, PLAYER
    }

}
