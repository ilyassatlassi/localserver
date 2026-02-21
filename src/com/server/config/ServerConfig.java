package com.server.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ServerConfig {
    private final String name;
    private final String host;
    private final List<Integer> ports;
    private final boolean isDefault;
    private final long clientBodyLimitBytes;
    private final Map<Integer, String> errorPages;
    private final List<RouteConfig> routes;

    public ServerConfig(
            String name,
            String host,
            List<Integer> ports,
            boolean isDefault,
            long clientBodyLimitBytes,
            Map<Integer, String> errorPages,
            List<RouteConfig> routes) {
        this.name = name;
        this.host = host;
        this.ports = ports == null ? Collections.emptyList() : Collections.unmodifiableList(ports);
        this.isDefault = isDefault;
        this.clientBodyLimitBytes = clientBodyLimitBytes;
        this.errorPages = errorPages == null ? Collections.emptyMap() : Collections.unmodifiableMap(errorPages);
        this.routes = routes == null ? Collections.emptyList() : Collections.unmodifiableList(routes);
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public long getClientBodyLimitBytes() {
        return clientBodyLimitBytes;
    }

    public Map<Integer, String> getErrorPages() {
        return errorPages;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }
}
