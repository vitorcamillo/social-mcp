package io.github.vitorcamillo.socialmcp;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--cli")) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            ApprovalCli.run(rest);
            return;
        }
        McpServer.run();
    }
}
