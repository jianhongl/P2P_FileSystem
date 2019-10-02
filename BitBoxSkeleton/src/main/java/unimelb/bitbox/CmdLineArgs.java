package unimelb.bitbox;
import org.kohsuke.args4j.Option;

public class CmdLineArgs {
    @Option(required = true, name = "-c", aliases = {"--command"}, usage = "command")
    private String command;
    @Option(required = true, name = "-s", aliases = {"--server"}, usage = "server")
    private String server;
    @Option(required = false, name = "-p", aliases = {"--peer"}, usage = "peer")
    private String peer;
    @Option(required = true, name = "-i", aliases = {"--identity"},usage = "identity")
    private String identity;

    public String getCommand() {
        return command;
    }

    public String getPeer() {
        return peer;
    }

    public String getServer() {
        return server;
    }

    public String getIdentity() {
        return identity;
    }
}
