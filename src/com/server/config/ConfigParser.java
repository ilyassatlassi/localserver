package com.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigParser {
    private static final long DEFAULT_BODY_LIMIT = 1_048_576L;
    private static final String DEFAULT_INDEX = "index.html";

    public ConfigRoot parse(Path path) {
        String raw = readFile(path);
        Object value = new JsonParser(raw).parse();
        if (!(value instanceof Map)) {
            throw new ConfigException("Root of config must be a JSON object");
        }
        Map<?, ?> root = (Map<?, ?>) value;
        List<ServerConfig> servers = parseServers(root.get("servers"));
        if (servers.isEmpty()) {
            throw new ConfigException("Config must contain at least one server");
        }
        ensureSingleDefault(servers);
        return new ConfigRoot(servers);
    }

    private List<ServerConfig> parseServers(Object serversVal) {
        List<Object> serverObjs = asList(serversVal, "servers");
        List<ServerConfig> servers = new ArrayList<>();
        for (Object obj : serverObjs) {
            Map<?, ?> map = asMap(obj, "server");
            String name = asString(map.get("name"), "server.name", true);
            String host = asString(map.get("host"), "server.host", true);
            List<Integer> ports = parsePorts(map.get("ports"));
            boolean isDefault = asBoolean(map.get("default"), "server.default", false);
            long bodyLimit = asLong(map.get("clientBodyLimitBytes"), "server.clientBodyLimitBytes", DEFAULT_BODY_LIMIT);
            Map<Integer, String> errorPages = parseErrorPages(map.get("errorPages"));
            List<RouteConfig> routes = parseRoutes(map.get("routes"));
            servers.add(new ServerConfig(name, host, ports, isDefault, bodyLimit, errorPages, routes));
        }
        return servers;
    }

    private List<RouteConfig> parseRoutes(Object routesVal) {
        List<Object> routeObjs = asList(routesVal, "server.routes");
        List<RouteConfig> routes = new ArrayList<>();
        for (Object obj : routeObjs) {
            Map<?, ?> map = asMap(obj, "route");
            String path = asString(map.get("path"), "route.path", true);
            if (!path.startsWith("/")) {
                throw new ConfigException("route.path must start with '/'");
            }
            Set<String> methods = parseMethods(map.get("methods"));
            String root = asString(map.get("root"), "route.root", false);
            String index = asString(map.get("index"), "route.index", false);
            if (index == null) {
                index = DEFAULT_INDEX;
            }
            boolean autoIndex = asBoolean(map.get("autoIndex"), "route.autoIndex", false);
            String uploadDir = asString(map.get("uploadDir"), "route.uploadDir", false);
            Map<String, String> cgi = parseCgi(map.get("cgi"));
            RedirectConfig redirect = parseRedirect(map.get("redirect"));
            routes.add(new RouteConfig(path, methods, root, index, autoIndex, uploadDir, cgi, redirect));
        }
        return routes;
    }

    private Map<Integer, String> parseErrorPages(Object errorPagesVal) {
        if (errorPagesVal == null) {
            return new HashMap<>();
        }
        Map<?, ?> map = asMap(errorPagesVal, "server.errorPages");
        Map<Integer, String> errorPages = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            int code;
            try {
                code = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                throw new ConfigException("errorPages keys must be HTTP status codes");
            }
            String path = asString(entry.getValue(), "server.errorPages[" + key + "]", true);
            errorPages.put(code, path);
        }
        return errorPages;
    }

    private Map<String, String> parseCgi(Object cgiVal) {
        if (cgiVal == null) {
            return new HashMap<>();
        }
        Map<?, ?> map = asMap(cgiVal, "route.cgi");
        Map<String, String> cgi = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String ext = String.valueOf(entry.getKey());
            if (!ext.startsWith(".")) {
                throw new ConfigException("cgi keys must be file extensions (ex: .py)");
            }
            String bin = asString(entry.getValue(), "route.cgi[" + ext + "]", true);
            cgi.put(ext, bin);
        }
        return cgi;
    }

    private RedirectConfig parseRedirect(Object redirectVal) {
        if (redirectVal == null) {
            return null;
        }
        Map<?, ?> map = asMap(redirectVal, "route.redirect");
        int status = (int) asLong(map.get("status"), "route.redirect.status", 301L);
        String to = asString(map.get("to"), "route.redirect.to", true);
        if (status != 301 && status != 302 && status != 307 && status != 308) {
            throw new ConfigException("route.redirect.status must be 301, 302, 307, or 308");
        }
        return new RedirectConfig(status, to);
    }

    private List<Integer> parsePorts(Object portsVal) {
        List<Object> values = asList(portsVal, "server.ports");
        if (values.isEmpty()) {
            throw new ConfigException("server.ports must contain at least one port");
        }
        List<Integer> ports = new ArrayList<>();
        for (Object value : values) {
            long port = asLong(value, "server.ports", -1L);
            if (port < 1 || port > 65535) {
                throw new ConfigException("Invalid port: " + port);
            }
            ports.add((int) port);
        }
        return ports;
    }

    private Set<String> parseMethods(Object methodsVal) {
        if (methodsVal == null) {
            Set<String> defaults = new HashSet<>();
            defaults.add("GET");
            return defaults;
        }
        List<Object> values = asList(methodsVal, "route.methods");
        Set<String> methods = new HashSet<>();
        for (Object value : values) {
            String m = asString(value, "route.methods", true);
            methods.add(m.toUpperCase());
        }
        return methods;
    }

    private void ensureSingleDefault(List<ServerConfig> servers) {
        int defaultCount = 0;
        for (ServerConfig server : servers) {
            if (server.isDefault()) {
                defaultCount++;
            }
        }
        if (defaultCount == 0) {
            ServerConfig first = servers.get(0);
            servers.set(0, new ServerConfig(
                    first.getName(),
                    first.getHost(),
                    first.getPorts(),
                    true,
                    first.getClientBodyLimitBytes(),
                    first.getErrorPages(),
                    first.getRoutes()
            ));
            return;
        }
        if (defaultCount > 1) {
            throw new ConfigException("Only one server can be marked as default");
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ConfigException("Failed to read config file: " + path, ex);
        }
    }

    private Map<?, ?> asMap(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new ConfigException(name + " must be a JSON object");
        }
        return (Map<?, ?>) value;
    }

    private List<Object> asList(Object value, String name) {
        if (!(value instanceof List)) {
            throw new ConfigException(name + " must be a JSON array");
        }
        return (List<Object>) value;
    }

    private String asString(Object value, String name, boolean required) {
        if (value == null) {
            if (required) {
                throw new ConfigException(name + " is required");
            }
            return null;
        }
        if (!(value instanceof String)) {
            throw new ConfigException(name + " must be a string");
        }
        String s = (String) value;
        if (required && s.trim().isEmpty()) {
            throw new ConfigException(name + " cannot be empty");
        }
        return s;
    }

    private boolean asBoolean(Object value, String name, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean)) {
            throw new ConfigException(name + " must be a boolean");
        }
        return (Boolean) value;
    }

    private long asLong(Object value, String name, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Double) {
            return ((Double) value).longValue();
        }
        throw new ConfigException(name + " must be a number");
    }
}
