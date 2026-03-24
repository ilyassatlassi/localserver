package com.server.core;

import com.server.config.ConfigParser;
import com.server.config.ConfigRoot;
import com.server.config.ServerConfig;
import com.server.http.Request;
import com.server.http.RequestProcessor;
import com.server.http.Response;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = parseConfigPath(args);
            ConfigRoot root = new ConfigParser().parse(Path.of(configPath));
            startAllServers(root.getServers());
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

    private static void startAllServers(List<ServerConfig> servers) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalStateException("No servers configured");
        }
        for (ServerConfig server : servers) {
            Thread t = new Thread(() -> new HttpEngine(server).start());
            t.setName("HttpEngine-" + server.getName());
            t.setDaemon(false);
            t.start();
        }
    }
}
