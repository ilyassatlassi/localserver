package com.server.config;

import java.util.Collections;
import java.util.List;

public class ConfigRoot {
    private final List<ServerConfig> servers;

    public ConfigRoot(List<ServerConfig> servers) {
        this.servers = servers == null ? Collections.emptyList() : Collections.unmodifiableList(servers);
    }

    public List<ServerConfig> getServers() {
        return servers;
    }
}
