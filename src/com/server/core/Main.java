package com.server.core;

import com.server.config.ConfigParser;
import com.server.config.ConfigRoot;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = parseConfigPath(args);
            ConfigRoot root = new ConfigParser().parse(Path.of(configPath));
            startServer(root);
        } catch (Exception e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String parseConfigPath(String[] args) {
        if (args.length != 2 || !"-c".equals(args[0])) {
            System.err.println("Usage: java -jar myserver.jar -c <config-file>");
            System.exit(1);
        }
        return args[1];
    }

    private static void startServer(ConfigRoot root) {
        if (root == null || root.getServers() == null || root.getServers().isEmpty()) {
            throw new IllegalStateException("No servers configured");
        }
        new HttpEngine(root.getServers()).start();
    }
}
