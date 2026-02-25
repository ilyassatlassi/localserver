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
        String method = "GET";
        String path = "/";

        if (args.length >= 1) {
            configPath = args[0];
        }
        if (args.length >= 2) {
            method = args[1];
        }
        if (args.length >= 3) {
            path = args[2];
        }

        ConfigRoot root = new ConfigParser().parse(Path.of(configPath));
        ServerConfig server = pickDefaultServer(root.getServers());

        ProtocolHandler handler = new ProtocolHandler();
        Request request = new Request(method.toUpperCase(), path);
        Response response = handler.handle(request, server);

        System.out.println("Status: " + response.getStatus());
        if (response.getHeaders().containsKey("Allow")) {
            System.out.println("Allow: " + response.getHeaders().get("Allow"));
        }
        System.out.println("Body: " + response.getBody());
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
}
