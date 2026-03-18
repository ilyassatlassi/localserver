package com.server.http;

import com.server.config.RouteConfig;
import com.server.config.ServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class ProtocolHandler {

    private final SessionManager sessionManager = new SessionManager();

    public Response handle(Request request, ServerConfig server) {
        if (request == null || server == null) {
            return new Response(500, "Internal Server Error");
        }

        boolean isNewSession = !sessionManager.hasValidSession(request);
        String sessionId = sessionManager.getOrCreateSession(request);

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
            return attachSession(new Response(404, "Not Found"), sessionId, isNewSession);
        }

        if (matched.getRedirect() != null) {
            Response redirectResponse = new Response(matched.getRedirect().getStatus(), "Moved");
            redirectResponse.setHeader("Location", matched.getRedirect().getTo());
            return attachSession(redirectResponse, sessionId, isNewSession);
        }

        String method = request.getMethod();
        Set<String> allowed = matched.getMethods();
        if (!allowed.isEmpty() && (method == null || !allowed.contains(method))) {
            Response resp = new Response(405, "Method Not Allowed");
            resp.setHeader("Allow", String.join(", ", allowed));
            return attachSession(resp, sessionId, isNewSession);
        }

        Response response;
        if (method.equals("GET")) {
            response = handleGet(request, matched);
        } else if (method.equals("POST")) {
            response = handlePost(request, matched);
        } else if (method.equals("DELETE")) {
            response = handleDelete(request, matched);
        } else {
            response = new Response(200, "OK");
        }

        return attachSession(response, sessionId, isNewSession);
    }

    private Response attachSession(Response response, String sessionId, boolean isNewSession) {
        if (isNewSession) {
            response.setHeader("Set-Cookie", sessionManager.buildSetCookieHeader(sessionId));
        }
        return response;
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

        String cgiBinary = getCgiBinary(resolved, route);
        if (cgiBinary != null) {
            return executeCgi(request, resolved, cgiBinary);
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

    private Response handlePost(Request request, RouteConfig route) {
        Path resolved = resolveSafePath(request.getPath(), route);
        if (resolved != null && Files.exists(resolved) && !Files.isDirectory(resolved)) {
            String cgiBinary = getCgiBinary(resolved, route);
            if (cgiBinary != null) {
                return executeCgi(request, resolved, cgiBinary);
            }
        }

        Path uploadPath = resolveUploadPath(request.getPath(), route);
        if (uploadPath != null) {
            try {
                Path parent = uploadPath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                
                String body = request.getBody();
                if (body != null) {
                    Files.write(uploadPath, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    Files.write(uploadPath, new byte[0]);
                }
                return new Response(201, "Created");
            } catch (java.io.IOException e) {
                return new Response(500, "Failed to upload file");
            }
        }

        return new Response(405, "Method Not Allowed");
    }
    
    private Path resolveUploadPath(String requestPath, RouteConfig route) {
        String uploadDir = route.getUploadDir();
        if (uploadDir == null || uploadDir.isEmpty()) {
            return null; // Route doesn't support uploads
        }
        
        String routePath = route.getPath();
        String relativePath = "";
        
        if (requestPath.length() > routePath.length()) {
            relativePath = requestPath.substring(routePath.length());
        }
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        if (relativePath.isEmpty()) {
            relativePath = "upload_" + System.currentTimeMillis() + ".tmp";
        }

        try {
            Path rootPath = java.nio.file.Path.of(uploadDir).toAbsolutePath().normalize();
            Path targetPath = rootPath.resolve(relativePath).normalize();

            if (!targetPath.startsWith(rootPath)) {
                return null; // Traversal Attempt
            }
            return targetPath;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private Response executeCgi(Request request, Path scriptPath, String binaryPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, scriptPath.toAbsolutePath().toString());
            
            // Set CGI Environment Variables (RFC 3875)
            java.util.Map<String, String> env = pb.environment();
            env.clear(); // Ensure clean environment
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("PATH_INFO", request.getPath());
            env.put("QUERY_STRING", request.getQueryString() != null ? request.getQueryString() : "");
            
            if (request.getBody() != null && !request.getBody().isEmpty()) {
                byte[] bodyBytes = request.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                env.put("CONTENT_LENGTH", String.valueOf(bodyBytes.length));
            }
            
            String contentType = request.getHeaders().get("content-type");
            if (contentType != null) {
                env.put("CONTENT_TYPE", contentType);
            }
            
            String cookie = request.getHeaders().get("cookie");
            if (cookie != null) {
                env.put("HTTP_COOKIE", cookie);
            }

            Process process = pb.start();

            // Pipe HTTP Body into script standard input
            if (request.getBody() != null && !request.getBody().isEmpty()) {
                try (java.io.OutputStream os = process.getOutputStream()) {
                    os.write(request.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }

            // Read CGI execution standard output
            byte[] outputBytes = process.getInputStream().readAllBytes();
            
            // Optional: log errors from standard error here
            // byte[] errorBytes = process.getErrorStream().readAllBytes();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return new Response(500, "CGI Process exited with error code: " + exitCode);
            }

            return parseCgiResponse(outputBytes);

        } catch (Exception e) {
            e.printStackTrace();
            return new Response(500, "Internal Server Error: " + e.getMessage());
        }
    }

    private Response parseCgiResponse(byte[] outputBytes) {
        String output = new String(outputBytes, java.nio.charset.StandardCharsets.UTF_8);
        int headerEndIndex = output.indexOf("\r\n\r\n");
        if (headerEndIndex == -1) {
            // No headers detected, dump as plain body
            return new Response(200, output);
        }

        String headersPart = output.substring(0, headerEndIndex);
        String bodyPart = output.substring(headerEndIndex + 4);

        Response response = new Response(200, bodyPart);
        for (String line : headersPart.split("\r\n")) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String val = line.substring(colonIndex + 1).trim();
                if (key.equalsIgnoreCase("Status")) {
                    try {
                        response = new Response(Integer.parseInt(val.split(" ")[0]), bodyPart);
                    } catch (NumberFormatException ignored) {}
                } else {
                    response.setHeader(key, val);
                }
            }
        }
        return response;
    }

    private String getCgiBinary(Path resolved, RouteConfig route) {
        if (route.getCgi() == null || route.getCgi().isEmpty()) return null;
        String filename = resolved.getFileName().toString();
        for (java.util.Map.Entry<String, String> entry : route.getCgi().entrySet()) {
            if (filename.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
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
