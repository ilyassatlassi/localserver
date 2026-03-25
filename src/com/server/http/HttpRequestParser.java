package com.server.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HttpRequestParser {

    private static final String HEADER_END = "\r\n\r\n";
    private static final String CRLF = "\r\n";

    /** HTTP/1.1 methods this server supports. */
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "DELETE");

    private final long maxBodyBytes;

    public HttpRequestParser(long maxBodyBytes) {
        this.maxBodyBytes = Math.max(maxBodyBytes, 0);
    }

    public ParseResult parse(StringBuilder inbound) {

        if (inbound == null || inbound.isEmpty())
            return null;

        int headerEndIdx = inbound.indexOf(HEADER_END);
        if (headerEndIdx < 0)
            return null; // headers not fully received yet

        String headersBlock = inbound.substring(0, headerEndIdx);
        String[] lines = headersBlock.split(CRLF, -1);

        if (lines.length == 0 || lines[0].isBlank()) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Empty request line");
        }

        String[] requestLineParts = lines[0].split(" ", 3);
        if (requestLineParts.length < 3) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Malformed request line: " + lines[0]);
        }

        String method = requestLineParts[0].trim().toUpperCase(Locale.ROOT);
        String rawPath = requestLineParts[1].trim();
        String version = requestLineParts[2].trim().toUpperCase(Locale.ROOT);

        if (!version.equals("HTTP/1.1")) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Only HTTP/1.1 is supported, got: " + version);
        }

        if (!SUPPORTED_METHODS.contains(method)) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.methodNotAllowed(method);
        }

        Map<String, String> headers = parseHeaders(lines);

        String host = headers.get("host");
        if (host == null || host.isBlank()) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.badRequest("Missing required Host header");
        }

        String path = stripQueryString(rawPath);
        String queryString = extractQueryString(rawPath);

        String transferEncoding = headers.get("transfer-encoding");
        boolean isChunked = "chunked".equalsIgnoreCase(transferEncoding);
        boolean hasContentLength = headers.containsKey("content-length");

        if (method.equals("POST") && !isChunked && !hasContentLength) {
            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.lengthRequired(method);
        }
        if (isChunked) {
            return parseChunkedBody(
                    inbound, headerEndIdx, method, path, queryString, headers);
        }

        // ── Step 9: Content-Length path ───────────────────────────────────────
        long contentLength = parseContentLength(headers.get("content-length"));

        // ── Step 10: enforce body size limit ──────────────────────────────────
        if (contentLength > maxBodyBytes) {

            consumeBytes(inbound, headerEndIdx + HEADER_END.length());
            return ParseResult.tooLarge();
        }

        // ── Step 11: wait until the full body has arrived ─────────────────────
        int bodyStart = headerEndIdx + HEADER_END.length();
        long totalBytes = bodyStart + contentLength;

        if (inbound.length() < totalBytes)
            return null; // need more data

        String body;
        if (method.equals("GET") || method.equals("DELETE") || contentLength == 0) {
            body = ""; // discard — RFC says body has no meaning for this method
        } else {
            body = inbound.substring(bodyStart, (int) totalBytes);
        }
        consumeBytes(inbound, (int) totalBytes);
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
     * 
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
            int headerEndIdx,
            String method,
            String path,
            String queryString,
            Map<String, String> headers) {
        int bodyStart = headerEndIdx + HEADER_END.length();
        StringBuilder bodyBuilder = new StringBuilder();
        int pos = bodyStart;

        while (true) {
            // Find the end of the chunk-size line
            int lineEnd = indexOf(inbound, CRLF, pos);
            if (lineEnd < 0)
                return null; // chunk size line not yet arrived

            String chunkSizeLine = inbound.substring(pos, lineEnd).trim();

            // Chunk extensions (after ';') are allowed by spec — strip them
            int semicolon = chunkSizeLine.indexOf(';');
            if (semicolon >= 0)
                chunkSizeLine = chunkSizeLine.substring(0, semicolon).trim();

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
                if (trailerEnd < 0)
                    return null; // final CRLF not yet arrived

                // Enforce body size limit on reassembled body
                if (bodyBuilder.length() > maxBodyBytes) {
                    consumeBytes(inbound, headerEndIdx + HEADER_END.length());
                    return ParseResult.tooLarge();
                }

                // Consume everything up to and including the final \r\n
                consumeBytes(inbound, trailerEnd + CRLF.length());

                boolean shouldClose = shouldClose(headers);

                boolean discardBody = method.equals("GET") || method.equals("DELETE");
                String finalBody = discardBody ? "" : bodyBuilder.toString();
                Request request = new Request(
                        method, path, queryString, headers, finalBody);
                return ParseResult.complete(request, shouldClose);
            }

            // Data chunk: need chunkSize bytes + trailing \r\n
            int dataStart = lineEnd + CRLF.length();
            int dataEnd = dataStart + chunkSize;
            int crlfEnd = dataEnd + CRLF.length();

            if (inbound.length() < crlfEnd)
                return null; // chunk data not yet arrived

            // Enforce size limit incrementally
            if (bodyBuilder.length() + chunkSize > maxBodyBytes) {
                consumeBytes(inbound, headerEndIdx + HEADER_END.length());
                return ParseResult.tooLarge();
            }

            bodyBuilder.append(inbound, dataStart, dataEnd);
            pos = crlfEnd;
        }
    }

    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new LinkedHashMap<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank())
                continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0)
                continue; // skip malformed lines silently

            String name = line.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colonIdx + 1).trim();

            // RFC 7230: multiple headers with the same name → combine with ", "
            headers.merge(name, value, (existing, next) -> existing + ", " + next);
        }

        return Collections.unmodifiableMap(headers);
    }

    private long parseContentLength(String value) {
        if (value == null || value.isBlank())
            return 0;
        try {
            return Math.max(Long.parseLong(value.trim()), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

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

    private void consumeBytes(StringBuilder sb, int count) {
        sb.delete(0, Math.min(count, sb.length()));
    }

    private int indexOf(StringBuilder sb, String target, int fromIndex) {
        int limit = sb.length() - target.length();
        outer: for (int i = Math.max(fromIndex, 0); i <= limit; i++) {
            for (int j = 0; j < target.length(); j++) {
                if (sb.charAt(i + j) != target.charAt(j))
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    public static final class ParseResult {

        private final Request request; // null on error
        private final boolean shouldClose; // send Connection: close?
        private final int errorStatus; // 0 = success, 400/405/413 = error
        private final String errorMessage; // human-readable reason, or null

        private ParseResult(Request request,
                boolean shouldClose,
                int errorStatus,
                String errorMessage) {
            this.request = request;
            this.shouldClose = shouldClose;
            this.errorStatus = errorStatus;
            this.errorMessage = errorMessage;
        }

        public static ParseResult complete(Request request, boolean shouldClose) {
            return new ParseResult(request, shouldClose, 0, null);
        }

        public static ParseResult badRequest(String reason) {
            return new ParseResult(null, true, 400, reason);
        }

        public static ParseResult methodNotAllowed(String method) {
            return new ParseResult(null, true, 405,
                    "Method not allowed: " + method);
        }

        public static ParseResult tooLarge() {
            return new ParseResult(null, true, 413, "Payload Too Large");
        }

        public Request getRequest() {
            return request;
        }

        public boolean shouldClose() {
            return shouldClose;
        }

        public boolean isError() {
            return errorStatus != 0;
        }

        public static ParseResult lengthRequired(String method) {
            return new ParseResult(null, true, 411,
                    method + " request must include Content-Length or "
                            + "Transfer-Encoding: chunked");
        }

        public int getErrorStatus() {
            return errorStatus;
        }

        /** Human-readable error reason, or {@code null} on success. */
        public String getErrorMessage() {
            return errorMessage;
        }

        @Deprecated
        public boolean isTooLarge() {
            return errorStatus == 413;
        }
    }
}