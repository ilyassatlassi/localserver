
package com.server.http;

import com.server.config.ServerConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class RequestProcessor {

    private static final String CRLF = "\r\n";

    private final ServerConfig server;
    private final ProtocolHandler handler;

    public RequestProcessor(ServerConfig server) {
        this(server, new ProtocolHandler());
    }

    public RequestProcessor(ServerConfig server, ProtocolHandler handler) {
        if (server == null)
            throw new IllegalArgumentException("server must not be null");
        if (handler == null)
            throw new IllegalArgumentException("handler must not be null");
        this.server = server;
        this.handler = handler;
    }

    public Response handle(Request request) {
        if (request == null) {
            return buildErrorResponse(400, "Bad Request");
        }
        Response response = handler.handle(request, server);
        if (response.getStatus() >= 400) {
            String body = resolveErrorBody(response.getStatus(), response.getBody());
            response.setBody(body);
            response.setHeader("Content-Type", "text/html; charset=utf-8");
        }
        return response;
    }

    public Response buildErrorResponse(int status, String fallbackMessage) {
        String body = resolveErrorBody(status, fallbackMessage);
        Response response = new Response(status, body);
        response.setHeader("Content-Type", "text/html; charset=utf-8");
        return response;
    }

    /** Load a custom error page from disk, or return the fallback text. */
    private String resolveErrorBody(int status, String fallbackMessage) {
        String pagePath = server.getErrorPages().get(status);
        if (pagePath == null || pagePath.isBlank()) {
            return fallbackMessage != null ? fallbackMessage : "";
        }
        try {
            return Files.readString(Path.of(pagePath), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return fallbackMessage != null ? fallbackMessage : "";
        }
    }

    public ByteBuffer serialize(Response response, boolean closeAfterWrite) {
        byte[] bodyBytes = response.getBodyBytes();

        StringBuilder sb = new StringBuilder(256);

        // ── Status line ───────────────────────────────────────────────────────
        sb.append("HTTP/1.1 ")
                .append(response.getStatus()).append(' ')
                .append(reasonPhrase(response.getStatus()))
                .append(CRLF);

        // ── Handler-provided headers ──────────────────────────────────────────
        for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append(CRLF);
        }

        // ── Content-Length — always required in HTTP/1.1 ─────────────────────
        sb.append("Content-Length: ").append(bodyBytes.length).append(CRLF);

        // ── Connection — only if handler did not set it already ───────────────
        if (!response.hasHeader("connection")) {
            sb.append("Connection: ")
                    .append(closeAfterWrite ? "close" : "keep-alive")
                    .append(CRLF);
        }

        // ── Blank line separating headers from body ───────────────────────────
        sb.append(CRLF);

        // ── Combine into one ByteBuffer ───────────────────────────────────────
        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(headerBytes.length + bodyBytes.length);
        out.put(headerBytes);
        out.put(bodyBytes);
        out.flip();
        return out;
    }

    /** Standard HTTP/1.1 reason phrases (RFC 7231 §6). */
    public String reasonPhrase(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 201:
                return "Created";
            case 204:
                return "No Content";
            case 301:
                return "Moved Permanently";
            case 302:
                return "Found";
            case 304:
                return "Not Modified";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 500:
                return "Internal Server Error";
            default:
                return "Unknown";
        }
    }
}
