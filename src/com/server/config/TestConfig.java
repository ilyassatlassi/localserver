package com.server.config;
import java.nio.file.Paths;
public class TestConfig {
    public static void main(String[] args) {
        try {
            ConfigRoot root = new ConfigParser().parse(Paths.get("/home/walid/Desktop/localserver/config.json"));
            System.out.println("Success! Servers: " + root.getServers().size());
            for (ServerConfig sc : root.getServers()) {
                System.out.println("Server: " + sc.getName() + " on " + sc.getHost());
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
