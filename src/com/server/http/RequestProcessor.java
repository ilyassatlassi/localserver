
package com.server.http;

import com.server.config.ServerConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Owns all HTTP-level logic that does NOT belong in the NIO engine:
 *
 *   - Routing a parsed {@link Request} to the {@link ProtocolHandler}
 *   - Building error responses (400 / 404 / 405 / 413 / 500 / …)
 *   - Loading custom error pages from disk
 *   - Serializing a {@link Response} to bytes for the NIO write queue
 *   - Providing HTTP/1.1 reason phrases
 *
 * {@link com.server.core.HttpEngine} holds one instance of this class and
 * calls it from its read/write handlers. The engine itself never builds or
 * serializes responses directly.
 */
public class RequestProcessor {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final String CRLF = "\r\n";

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final ServerConfig    server;
    private final ProtocolHandler handler;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public RequestProcessor(ServerConfig server) {
        this(server, new ProtocolHandler());
    }

    public RequestProcessor(ServerConfig server, ProtocolHandler handler) {
        if (server  == null) throw new IllegalArgumentException("server must not be null");
        if (handler == null) throw new IllegalArgumentException("handler must not be null");
        this.server  = server;
        this.handler = handler;
    }

    // =========================================================================
    // REQUEST DISPATCH
    // =========================================================================

    /**
     * Route a fully parsed request to the protocol handler.
     *
     * The request already has its query string separated and headers stored —
     * no cleaning needed here. Returns a 400 error response if request is null.
     */
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

    // =========================================================================
    // ERROR RESPONSE BUILDING
    // =========================================================================

    /**
     * Build an HTTP error response for the given status code.
     *
     * Loads a custom HTML page from disk when one is configured in the server
     * config, falls back to a plain-text body otherwise.
     *
     * @param status          HTTP status code, e.g. 404
     * @param fallbackMessage plain-text body used when no custom page exists
     */
    public Response buildErrorResponse(int status, String fallbackMessage) {
        String   body     = resolveErrorBody(status, fallbackMessage);
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

    // =========================================================================
    // RESPONSE SERIALIZATION
    // =========================================================================

    /**
     * Serialize a {@link Response} to a {@link ByteBuffer} ready for the NIO
     * write queue.
     *
     * Wire format (RFC 7230):
     * <pre>
     *   HTTP/1.1 {status} {reason}\r\n
     *   {Header}: {value}\r\n
     *   ...
     *   Content-Length: {n}\r\n
     *   Connection: keep-alive | close\r\n
     *   \r\n
     *   {body bytes}
     * </pre>
     *
     * Headers are encoded as US-ASCII (safe for all header values).
     * Body is encoded as UTF-8.
     * Both are written into one contiguous ByteBuffer to avoid two separate
     * {@code write()} system calls per response.
     *
     * @param response        the response to serialize
     * @param closeAfterWrite true  → emit "Connection: close"
     *                        false → emit "Connection: keep-alive"
     */
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

    // =========================================================================
    // REASON PHRASES
    // =========================================================================

    /** Standard HTTP/1.1 reason phrases (RFC 7231 §6). */
    public String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default:  return "Unknown";
        }
    }
}
