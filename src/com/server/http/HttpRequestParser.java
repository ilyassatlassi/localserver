package com.server.http;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class HttpRequestParser {
    private final long maxBodyBytes;

    public HttpRequestParser(long maxBodyBytes) {
        this.maxBodyBytes = Math.max(maxBodyBytes, 0);
    }

    public ParseResult parse(StringBuilder inbound) {
        if (inbound == null) {
            return new ParseResult(null, true, false);
        }
        int headerEnd = inbound.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            return null;
        }

        String headersBlock = inbound.substring(0, headerEnd);
        String[] lines = headersBlock.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            inbound.delete(0, headerEnd + 4);
            return new ParseResult(null, true, false);
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            inbound.delete(0, headerEnd + 4);
            return new ParseResult(null, true, false);
        }

        String method = parts[0].trim().toUpperCase(Locale.ROOT);
        String path = parts[1].trim();
        String version = parts.length >= 3 ? parts[2].trim().toUpperCase(Locale.ROOT) : "HTTP/1.0";

        Map<String, String> headers = parseHeaders(lines);
        int contentLength = parseContentLength(headers.get("content-length"));
        if (contentLength > maxBodyBytes) {
            return new ParseResult(null, true, true);
        }

        int totalLength = headerEnd + 4 + contentLength;
        if (inbound.length() < totalLength) {
            return null;
        }

        inbound.delete(0, totalLength);

        boolean shouldClose = shouldClose(headers, version);
        Request request = new Request(method, path);
        return new ParseResult(request, shouldClose, false);
    }

    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            headers.put(name, value);
        }
        return headers;
    }

    private int parseContentLength(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean shouldClose(Map<String, String> headers, String version) {
        String connection = headers.get("connection");
        if (connection != null && connection.equalsIgnoreCase("close")) {
            return true;
        }
        if (version != null && version.startsWith("HTTP/1.0")) {
            return connection == null || !connection.equalsIgnoreCase("keep-alive");
        }
        return false;
    }

    public static final class ParseResult {
        private final Request request;
        private final boolean shouldClose;
        private final boolean tooLarge;

        private ParseResult(Request request, boolean shouldClose, boolean tooLarge) {
            this.request = request;
            this.shouldClose = shouldClose;
            this.tooLarge = tooLarge;
        }

        public Request getRequest() {
            return request;
        }

        public boolean shouldClose() {
            return shouldClose;
        }

        public boolean isTooLarge() {
            return tooLarge;
        }
    }
}
