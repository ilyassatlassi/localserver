package com.server.http;

import com.server.config.RouteConfig;
import com.server.config.ServerConfig;
import java.util.Set;

public class ProtocolHandler {

    public Response handle(Request request, ServerConfig server) {
        if (request == null || server == null) {
            return new Response(500, "Internal Server Error");
        }

        RouteConfig matched = null;
        int bestLen = -1;
        for (RouteConfig route : server.getRoutes()) {
            if (route == null) {
                continue;
            }
            String routePath = route.getPath();
            if (pathMatches(routePath, request.getPath())) {
                int len = routePath == null ? -1 : routePath.length();
                if (len > bestLen) {
                    bestLen = len;
                    matched = route;
                }
            }
        }

        if (matched == null) {
            return new Response(404, "Not Found");
        }

        String method = request.getMethod();
        Set<String> allowed = matched.getMethods();
        if (!allowed.isEmpty() && (method == null || !allowed.contains(method))) {
            Response resp = new Response(405, "Method Not Allowed");
            resp.setHeader("Allow", String.join(", ", allowed));
            return resp;
        }

        return new Response(200, "OK");
    }

    private boolean pathMatches(String routePath, String requestPath) {
        if (routePath == null || requestPath == null) {
            return false;
        }
        // Matching rule examples for route "/uploads":
        // match: "/uploads", "/uploads/", "/uploads/file.txt", "/uploads/images/photo.png"
        // no:   "/uploadsss", "/upload", "/uploadsx/thing"
        if (routePath.equals("/")) {
            return requestPath.equals("/");
        }
        if (requestPath.equals(routePath)) {
            return true;
        }
        if (!requestPath.startsWith(routePath)) {
            return false;
        }
        int nextIndex = routePath.length();
        if (requestPath.length() <= nextIndex) {
            return false;
        }
        return requestPath.charAt(nextIndex) == '/';
    }
}
