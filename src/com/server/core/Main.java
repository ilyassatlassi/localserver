package com.server.core;

import com.server.config.ConfigParser;
import com.server.config.ConfigRoot;
import com.server.config.ServerConfig;
import com.server.http.ProtocolHandler;
import com.server.http.Request;
import com.server.http.Response;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        String configPath = "config.json";
        if (args.length >= 1) {
            configPath = args[0];
        }

        ConfigRoot root = new ConfigParser().parse(Path.of(configPath));
        ServerConfig server = pickDefaultServer(root.getServers());

        if (args.length >= 3 && looksLikeMethod(args[1])) {
            String method = args[1];
            String path = args[2];
            ProtocolHandler handler = new ProtocolHandler();
            Request request = new Request(method.toUpperCase(), path);
            Response response = handler.handle(request, server);
            System.out.println("Status: " + response.getStatus());
            if (response.getHeaders().containsKey("Allow")) {
                System.out.println("Allow: " + response.getHeaders().get("Allow"));
            }
            System.out.println("Body: " + response.getBody());
            return;
        }

        HttpEngine engine = new HttpEngine(server);
        engine.start();
    }

    private static ServerConfig pickDefaultServer(List<ServerConfig> servers) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalStateException("No servers configured");
        }
        for (ServerConfig server : servers) {
            if (server.isDefault()) {
                return server;
            }
        }
        return servers.get(0);
    }

    private static boolean looksLikeMethod(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toUpperCase();
        return v.equals("GET") || v.equals("POST") || v.equals("DELETE");
    }
}
