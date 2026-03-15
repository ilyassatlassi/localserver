package com.server.http;

import com.server.config.RouteConfig;
import com.server.config.ServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
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

        if (method.equals("GET")) {
            return handleGet(request, matched);
        } else if (method.equals("DELETE")) {
            return handleDelete(request, matched);
        }

        return new Response(200, "OK");
    }

    private Response handleGet(Request request, RouteConfig route) {
        Path resolved = resolveSafePath(request.getPath(), route);
        if (resolved == null) {
            return new Response(403, "Forbidden or Bad Path");
        }
        
        if (!Files.exists(resolved)) {
            return new Response(404, "Not Found");
        }

        if (Files.isDirectory(resolved)) {
            String indexFileName = route.getIndex();
            if (indexFileName != null && !indexFileName.isEmpty()) {
                Path indexPath = resolved.resolve(indexFileName);
                if (Files.exists(indexPath) && !Files.isDirectory(indexPath)) {
                    resolved = indexPath;
                } else if (route.isAutoIndex()) {
                    return generateAutoIndex(resolved, request.getPath());
                } else {
                    return new Response(403, "Forbidden");
                }
            } else if (route.isAutoIndex()) {
                return generateAutoIndex(resolved, request.getPath());
            } else {
                return new Response(403, "Forbidden");
            }
        }

        try {
            byte[] data = Files.readAllBytes(resolved);
            Response resp = new Response(200, new String(data, java.nio.charset.StandardCharsets.UTF_8));
            resp.setHeader("Content-Type", determineContentType(resolved));
            return resp;
        } catch (java.io.IOException e) {
            return new Response(500, "Internal Server Error");
        }
    }

    private Response generateAutoIndex(Path directory, String requestPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><title>Index of ").append(requestPath).append("</title></head><body>");
        sb.append("<h1>Index of ").append(requestPath).append("</h1><hr><pre>");
        
        if (!requestPath.equals("/")) {
            String parentStr = requestPath;
            if (parentStr.endsWith("/")) parentStr = parentStr.substring(0, parentStr.length() - 1);
            int lastSlash = parentStr.lastIndexOf('/');
            if (lastSlash >= 0) {
                String parent = parentStr.substring(0, lastSlash + 1);
                sb.append("<a href=\"").append(parent).append("\">../</a>\n");
            }
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    name += "/";
                }
                String href = requestPath;
                if (!href.endsWith("/")) {
                    href += "/";
                }
                href += name;
                sb.append("<a href=\"").append(href).append("\">").append(name).append("</a>\n");
            }
        } catch (java.io.IOException e) {
            return new Response(500, "Error generating directory listing");
        }

        sb.append("</pre><hr></body></html>");
        Response resp = new Response(200, sb.toString());
        resp.setHeader("Content-Type", "text/html; charset=utf-8");
        return resp;
    }

    private Response handleDelete(Request request, RouteConfig route) {
        Path resolved = resolveSafePath(request.getPath(), route);
        if (resolved == null) {
            return new Response(403, "Forbidden");
        }
        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            return new Response(404, "Not Found");
        }
        try {
            Files.delete(resolved);
            return new Response(204, "");
        } catch (java.io.IOException e) {
            return new Response(500, "Internal Server Error");
        }
    }

    private Path resolveSafePath(String requestPath, RouteConfig route) {
        String root = route.getRoot();
        if (root == null || root.isEmpty()) {
            return null; // Route has no root mapped
        }
        
        // Match the request path against the route path prefix to find the relative part
        String routePath = route.getPath();
        String relativePath = "";
        
        if (requestPath.length() > routePath.length()) {
            relativePath = requestPath.substring(routePath.length());
        }
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        try {
            Path rootPath = java.nio.file.Path.of(root).toAbsolutePath().normalize();
            Path targetPath = rootPath.resolve(relativePath).normalize();

            // Directory Traversal Prevention: Ensure target is inside rootPath
            if (!targetPath.startsWith(rootPath)) {
                return null;
            }
            return targetPath;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private String determineContentType(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html; charset=utf-8";
        if (filename.endsWith(".css")) return "text/css; charset=utf-8";
        if (filename.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (filename.endsWith(".json")) return "application/json; charset=utf-8";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
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
