package com.server.http;

import com.server.config.ServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class RequestProcessor {
    private final ServerConfig server;
    private final ProtocolHandler handler;

    public RequestProcessor(ServerConfig server) {
        this(server, new ProtocolHandler());
    }

    public RequestProcessor(ServerConfig server, ProtocolHandler handler) {
        if (server == null) {
            throw new IllegalArgumentException("server must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.server = server;
        this.handler = handler;
    }

    public Response handle(Request request) {
        if (request == null) {
            return errorResponse(400, "Bad Request");
        }
        String path = request.getPath();
        if (path == null || path.isEmpty()) {
            return errorResponse(400, "Bad Request");
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        Request cleaned = new Request(request.getMethod(), path);
        return handler.handle(cleaned, server);
    }

    public Response errorResponse(int status, String fallbackMessage) {
        Response response = new Response(status, "");
        String body = resolveErrorBody(status, fallbackMessage);
        response.setBody(body);
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        return response;
    }

    private String resolveErrorBody(int status, String fallbackMessage) {
        String fallback = fallbackMessage == null ? "" : fallbackMessage;
        String path = server.getErrorPages().get(status);
        if (path == null || path.isEmpty()) {
            return fallback;
        }
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return fallback;
        }
    }
}
