package com.server.config;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class RouteConfig {
    private final String path;
    private final Set<String> methods;
    private final String root;
    private final String index;
    private final boolean autoIndex;
    private final String uploadDir;
    private final Map<String, String> cgi;
    private final RedirectConfig redirect;

    public RouteConfig(
            String path,
            Set<String> methods,
            String root,
            String index,
            boolean autoIndex,
            String uploadDir,
            Map<String, String> cgi,
            RedirectConfig redirect
    ) {
        this.path = path;
        this.methods = methods == null ? Collections.emptySet() : Collections.unmodifiableSet(methods);
        this.root = root;
        this.index = index;
        this.autoIndex = autoIndex;
        this.uploadDir = uploadDir;
        this.cgi = cgi == null ? Collections.emptyMap() : Collections.unmodifiableMap(cgi);
        this.redirect = redirect;
    }

    public String getPath() {
        return path;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public String getRoot() {
        return root;
    }

    public String getIndex() {
        return index;
    }

    public boolean isAutoIndex() {
        return autoIndex;
    }

    public String getUploadDir() {
        return uploadDir;
    }

    public Map<String, String> getCgi() {
        return cgi;
    }

    public RedirectConfig getRedirect() {
        return redirect;
    }
}
