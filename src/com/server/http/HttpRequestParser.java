package com.server.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stateless HTTP/1.1 request parser.
 *
 * Called repeatedly on a growing {@link StringBuilder} that accumulates raw
 * bytes from a single TCP connection. Each successful call consumes exactly
 * one complete request from the front of the buffer and returns a
 * {@link ParseResult}. Returns {@code null} when more data is needed.
 *
 * <pre>
 * Parsing pipeline:
 *
 *   inbound buffer
 *       │
 *       ├─ 1. Find \r\n\r\n  →  headers complete?
 *       ├─ 2. Parse request line  →  method / path / query / version
 *       ├─ 3. Validate method  →  405 if unknown
 *       ├─ 4. Validate Host header  →  400 if missing (HTTP/1.1 MUST)
 *       ├─ 5. Resolve body length  →  Content-Length or chunked
 *       ├─ 6. Enforce body size limit  →  413 if exceeded
 *       ├─ 7. Wait for full body  →  null if incomplete
 *       ├─ 8. Extract body string
 *       ├─ 9. Consume bytes from inbound
 *       └─ 10. Return ParseResult
 * </pre>
 */
public class HttpRequestParser {

    private static final String HEADER_END = "\r\n\r\n";
    private static final String CRLF       = "\r\n";

    /** HTTP/1.1 methods this server supports. */
    private static final Set<String> SUPPORTED_METHODS =
            Set.of("GET", "POST", "DELETE");

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final long maxBodyBytes;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param maxBodyBytes maximum allowed request body in bytes.
     *                     Requests with a larger Content-Length receive 413.
     */
    public HttpRequestParser(long maxBodyBytes) {
        this.maxBodyBytes = Math.max(maxBodyBytes, 0);
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Attempt to parse the next complete HTTP/1.1 request from {@code inbound}.
     *
     * <p>The buffer is decoded with ISO-8859-1 (in {@code HttpEngine}), which
     * keeps every byte value 0–255 intact through the String round-trip. Body
     * bytes can therefore be recovered losslessly.
     *
     * @param inbound accumulated raw bytes for this connection; consumed in-place
     * @return {@link ParseResult} on success or error condition,
     *         {@code null} if more data is needed
     */
    public ParseResult parse(StringBuilder inbound) {

        // ── Guard ─────────────────────────────────────────────────────────────
        if (inbound == null || inbound.isEmpty()) return null;

        // ── Step 1: wait for end of headers ───────────────────────────────────
        int headerEndIdx = inbound.indexOf(HEADER_END);
        if (headerEndIdx < 0) return null; // headers not fully received yet

        // ── Step 2: parse the request line ────────────────────────────────────
        String   headersBlock = inbound.substring(0, headerEndIdx);
        String[] lines        = headersBlock.split(CRLF, -1);

        if (lines.length == 0 || lines[0].isBlank()) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Empty request line");
        }

        String[] requestLineParts = lines[0].split(" ", 3);
        if (requestLineParts.length < 3) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Malformed request line: " + lines[0]);
        }

        String method  = requestLineParts[0].trim().toUpperCase(Locale.ROOT);
        String rawPath = requestLineParts[1].trim();
        String version = requestLineParts[2].trim().toUpperCase(Locale.ROOT);

        // ── Step 3: HTTP version must be HTTP/1.1 ─────────────────────────────
        if (!version.equals("HTTP/1.1")) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Only HTTP/1.1 is supported, got: " + version);
        }

        // ── Step 4: validate method ───────────────────────────────────────────
        if (!SUPPORTED_METHODS.contains(method)) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.methodNotAllowed(method);
        }

        // ── Step 5: parse headers ─────────────────────────────────────────────
        Map<String, String> headers = parseHeaders(lines);

        // ── Step 6: Host header is REQUIRED in HTTP/1.1 (RFC 7230 §5.4) ───────
        String host = headers.get("host");
        if (host == null || host.isBlank()) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Missing required Host header");
        }

        // ── Step 7: split path and query string ───────────────────────────────
        String path        = stripQueryString(rawPath);
        String queryString = extractQueryString(rawPath);

        // ── Step 8: resolve body length ───────────────────────────────────────
        String transferEncoding = headers.get("transfer-encoding");
        boolean isChunked = "chunked".equalsIgnoreCase(transferEncoding);

        if (isChunked) {
            return parseChunkedBody(
                    inbound, headerEndIdx, method, path, queryString, headers);
        }

        // ── Step 9: Content-Length path ───────────────────────────────────────
        long contentLength = parseContentLength(headers.get("content-length"));

        // ── Step 10: enforce body size limit ──────────────────────────────────
        if (contentLength > maxBodyBytes) {
            // Consume only the headers so the engine can send 413 and close.
            // We do NOT consume the body — the connection will be closed anyway.
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.tooLarge();
        }

        // ── Step 11: wait until the full body has arrived ─────────────────────
        int bodyStart  = headerEndIdx + HEADER_END.length();
        long totalBytes = bodyStart + contentLength;

        if (inbound.length() < totalBytes) return null; // need more data

        // ── Step 12: extract body ─────────────────────────────────────────────
        String body = contentLength > 0
                ? inbound.substring(bodyStart, (int) totalBytes)
                : "";

        // ── Step 13: consume exactly this request's bytes ─────────────────────
        consumeBytes(inbound, (int) totalBytes);

        // ── Step 14: decide keep-alive / close ────────────────────────────────
        // HTTP/1.1 default is keep-alive.
        // Only close if the client explicitly sends "Connection: close".
        boolean shouldClose = shouldClose(headers);

        Request request = new Request(method, path, queryString, headers, body);
        return ParseResult.complete(request, shouldClose);
    }

    // =========================================================================
    // CHUNKED BODY PARSING
    // =========================================================================

    /**
     * Parse a chunked Transfer-Encoding body (RFC 7230 §4.1).
     *
     * Chunked format:
     * <pre>
     *   {hex-size}\r\n
     *   {chunk-data}\r\n
     *   ...
     *   0\r\n          ← terminator
     *   \r\n
     * </pre>
     *
     * Returns {@code null} if the full chunked body has not arrived yet.
     */
    private ParseResult parseChunkedBody(StringBuilder inbound,
                                         int           headerEndIdx,
                                         String        method,
                                         String        path,
                                         String        queryString,
                                         Map<String, String> headers) {
        int bodyStart = headerEndIdx + HEADER_END.length();
        StringBuilder bodyBuilder = new StringBuilder();
        int pos = bodyStart;

        while (true) {
            // Find the end of the chunk-size line
            int lineEnd = indexOf(inbound, CRLF, pos);
            if (lineEnd < 0) return null; // chunk size line not yet arrived

            String chunkSizeLine = inbound.substring(pos, lineEnd).trim();

            // Chunk extensions (after ';') are allowed by spec — strip them
            int semicolon = chunkSizeLine.indexOf(';');
            if (semicolon >= 0) chunkSizeLine = chunkSizeLine.substring(0, semicolon).trim();

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(chunkSizeLine, 16); // size in hex
            } catch (NumberFormatException ex) {
                consumeBytes(inbound, headerEndIdx + HEADER_END.length());
                return ParseResult.badRequest("Invalid chunk size: " + chunkSizeLine);
            }

            if (chunkSize < 0) {
                consumeBytes(inbound, headerEndIdx + HEADER_END.length());
                return ParseResult.badRequest("Negative chunk size");
            }

            // Terminator chunk
            if (chunkSize == 0) {
                // After the 0\r\n there must be a final \r\n
                int trailerEnd = indexOf(inbound, CRLF, lineEnd + CRLF.length());
                if (trailerEnd < 0) return null; // final CRLF not yet arrived

                // Enforce body size limit on reassembled body
                if (bodyBuilder.length() > maxBodyBytes) {
                    consumeBytes(inbound, headerEndIdx + HEADER_END.length());
                    return ParseResult.tooLarge();
                }

                // Consume everything up to and including the final \r\n
                consumeBytes(inbound, trailerEnd + CRLF.length());

                boolean shouldClose = shouldClose(headers);
                Request request = new Request(
                        method, path, queryString, headers, bodyBuilder.toString());
                return ParseResult.complete(request, shouldClose);
            }

            // Data chunk: need chunkSize bytes + trailing \r\n
            int dataStart = lineEnd + CRLF.length();
            int dataEnd   = dataStart + chunkSize;
            int crlfEnd   = dataEnd + CRLF.length();

            if (inbound.length() < crlfEnd) return null; // chunk data not yet arrived

            // Enforce size limit incrementally
            if (bodyBuilder.length() + chunkSize > maxBodyBytes) {
                consumeBytes(inbound, headerEndIdx + HEADER_END.length());
                return ParseResult.tooLarge();
            }

            bodyBuilder.append(inbound, dataStart, dataEnd);
            pos = crlfEnd;
        }
    }

    // =========================================================================
    // HEADER PARSING
    // =========================================================================

    /**
     * Parse HTTP headers into a lowercase-keyed map.
     * Keys are lowercased per RFC 7230 §3.2 (header names are case-insensitive).
     * Multi-value headers (e.g. multiple Cookie lines) are joined with ", ".
     */
    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new LinkedHashMap<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) continue; // skip malformed lines silently

            String name  = line.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colonIdx + 1).trim();

            // RFC 7230: multiple headers with the same name → combine with ", "
            headers.merge(name, value, (existing, next) -> existing + ", " + next);
        }

        return Collections.unmodifiableMap(headers);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Parse Content-Length safely.
     * Uses {@code long} to avoid integer overflow on large values (e.g. 3 GB).
     * Returns 0 for missing, blank, or non-numeric values.
     */
    private long parseContentLength(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(Long.parseLong(value.trim()), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * HTTP/1.1 default is keep-alive.
     * Close only when the client explicitly sends "Connection: close".
     */
    private boolean shouldClose(Map<String, String> headers) {
        String connection = headers.get("connection");
        return connection != null && connection.equalsIgnoreCase("close");
    }

    /** Return the path portion of a URL, excluding "?" and everything after. */
    private String stripQueryString(String rawPath) {
        int q = rawPath.indexOf('?');
        return q >= 0 ? rawPath.substring(0, q) : rawPath;
    }

    /** Return the query string (after "?"), or empty string if absent. */
    private String extractQueryString(String rawPath) {
        int q = rawPath.indexOf('?');
        return q >= 0 ? rawPath.substring(q + 1) : "";
    }

    /** Remove the first {@code count} characters from the buffer. */
    private void consumeBytes(StringBuilder sb, int count) {
        sb.delete(0, Math.min(count, sb.length()));
    }

    /**
     * Like {@link String#indexOf(String, int)} but on a {@link StringBuilder}.
     * Returns -1 if {@code target} is not found at or after {@code fromIndex}.
     */
    private int indexOf(StringBuilder sb, String target, int fromIndex) {
        int limit = sb.length() - target.length();
        outer:
        for (int i = Math.max(fromIndex, 0); i <= limit; i++) {
            for (int j = 0; j < target.length(); j++) {
                if (sb.charAt(i + j) != target.charAt(j)) continue outer;
            }
            return i;
        }
        return -1;
    }

    // =========================================================================
    // INNER CLASS — ParseResult
    // =========================================================================

    /**
     * The outcome of one parse attempt.
     *
     * Uses named factory methods so call sites are self-documenting:
     * <pre>
     *   ParseResult.complete(request, shouldClose)
     *   ParseResult.badRequest("reason")
     *   ParseResult.methodNotAllowed("PATCH")
     *   ParseResult.tooLarge()
     * </pre>
     *
     * The {@code errorStatus} field carries the HTTP status code for error
     * results (400, 405, 413). It is 0 for successful parses.
     */
    public static final class ParseResult {

        // ── Fields ────────────────────────────────────────────────────────────

        private final Request request;      // null on error
        private final boolean shouldClose;  // send Connection: close?
        private final int     errorStatus;  // 0 = success, 400/405/413 = error
        private final String  errorMessage; // human-readable reason, or null

        // ── Private constructor ───────────────────────────────────────────────

        private ParseResult(Request request,
                            boolean shouldClose,
                            int     errorStatus,
                            String  errorMessage) {
            this.request      = request;
            this.shouldClose  = shouldClose;
            this.errorStatus  = errorStatus;
            this.errorMessage = errorMessage;
        }

        // ── Factory methods ───────────────────────────────────────────────────

        /** A fully parsed, valid HTTP/1.1 request. */
        public static ParseResult complete(Request request, boolean shouldClose) {
            return new ParseResult(request, shouldClose, 0, null);
        }

        /**
         * Malformed request line or missing required header.
         * The engine should respond with 400 Bad Request.
         */
        public static ParseResult badRequest(String reason) {
            return new ParseResult(null, true, 400, reason);
        }

        /**
         * Unrecognised HTTP method.
         * The engine should respond with 405 Method Not Allowed.
         */
        public static ParseResult methodNotAllowed(String method) {
            return new ParseResult(null, true, 405,
                    "Method not allowed: " + method);
        }

        /**
         * Content-Length exceeded the server's body size limit.
         * The engine should respond with 413 Payload Too Large.
         */
        public static ParseResult tooLarge() {
            return new ParseResult(null, true, 413, "Payload Too Large");
        }

        // ── Accessors ─────────────────────────────────────────────────────────

        /** The parsed request. {@code null} when {@link #isError()} is true. */
        public Request getRequest() { return request; }

        /** True if the connection must close after the response is sent. */
        public boolean shouldClose() { return shouldClose; }

        /** True if the parse produced an HTTP error (400 / 405 / 413). */
        public boolean isError() { return errorStatus != 0; }

        /**
         * HTTP status code for error results (400, 405, 413).
         * Returns 0 for successful parses — always check {@link #isError()} first.
         */
        public int getErrorStatus() { return errorStatus; }

        /** Human-readable error reason, or {@code null} on success. */
        public String getErrorMessage() { return errorMessage; }

        // ── Convenience shorthands kept for backwards compatibility ───────────

        /** @deprecated Use {@code isError() && getErrorStatus() == 413} */
        @Deprecated
        public boolean isTooLarge() { return errorStatus == 413; }
    }
}