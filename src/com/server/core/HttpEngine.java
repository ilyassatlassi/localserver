package com.server.core;

import com.server.config.ServerConfig;
import com.server.http.ProtocolHandler;
import com.server.http.Request;
import com.server.http.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HttpEngine {
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int SELECT_TIMEOUT_MS = 1000;
    private static final long CONNECTION_TIMEOUT_MS = 30_000;
    private final ServerConfig server;
    private final ProtocolHandler handler;

    public HttpEngine(ServerConfig server) {
        this.server = server;
        this.handler = new ProtocolHandler();
    }

    public void start() {
        try (Selector selector = Selector.open()) {
            for (int port : server.getPorts()) {
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.bind(new InetSocketAddress(server.getHost(), port));
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            }

            while (true) {
                selector.select(SELECT_TIMEOUT_MS);
                checkTimeouts(selector);
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isAcceptable()) {
                            handleAccept(selector, key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException ex) {
                        closeConnection(key);
                    } catch (RuntimeException ex) {
                        closeConnection(key);
                    }
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Server failed to start", ex);
        }
    }

    private void handleAccept(Selector selector, SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel client = serverChannel.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        ConnectionState state = new ConnectionState(READ_BUFFER_SIZE);
        client.register(selector, SelectionKey.OP_READ, state);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        state.updateLastAccessedAt();
        ByteBuffer buffer = state.getReadBuffer();
        int read = client.read(buffer);
        if (read == -1) {
            client.close();
            return;
        }
        if (read == 0) {
            return;
        }
        buffer.flip();
        String chunk = StandardCharsets.US_ASCII.decode(buffer).toString();
        buffer.clear();
        state.getInbound().append(chunk);
        while (true) {
            ParsedRequest parsed = parseRequest(state.getInbound());
            if (parsed == null) {
                return;
            }
            if (parsed.tooLarge) {
                Response response = buildErrorResponse(413, "Payload Too Large");
                ByteBuffer out = serializeResponse(response, true);
                state.enqueueWrite(out);
                state.markCloseAfterWrite();
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
            Response response = buildResponse(parsed.request);
            boolean closeAfter = parsed.shouldClose;
            ByteBuffer out = serializeResponse(response, closeAfter);
            state.enqueueWrite(out);
            if (closeAfter) {
                state.markCloseAfterWrite();
            }
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        state.updateLastAccessedAt();
        ByteBuffer out = state.peekWriteBuffer();
        if (out == null) {
            client.close();
            return;
        }
        client.write(out);
        if (!out.hasRemaining()) {
            state.popWriteBuffer();
            if (!state.hasPendingWrites()) {
                if (state.shouldCloseAfterWrite()) {
                    client.close();
                    return;
                }
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    private Response buildResponse(Request request) {
        if (request == null) {
            return buildErrorResponse(400, "Bad Request");
        }
        String path = request.getPath();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        Request cleaned = new Request(request.getMethod(), path);
        return handler.handle(cleaned, server);
    }

    private Response buildErrorResponse(int status, String fallbackMessage) {
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

    private ByteBuffer serializeResponse(Response response, boolean closeAfterWrite) {
        String reason = reasonPhrase(response.getStatus());
        StringBuilder sb = new StringBuilder();
        String body = response.getBody();
        if (body == null) {
            body = "";
        }

        sb.append("HTTP/1.1 ").append(response.getStatus()).append(" ").append(reason).append("\r\n");
        for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        if (!hasConnectionHeader(response)) {
            sb.append("Connection: ").append(closeAfterWrite ? "close" : "keep-alive").append("\r\n");
        }
        sb.append("\r\n");
        sb.append(body);
        return ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasConnectionHeader(Response response) {
        for (String name : response.getHeaders().keySet()) {
            if ("connection".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 413: return "Payload Too Large";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private void checkTimeouts(Selector selector) {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) {
                continue;
            }
            Object att = key.attachment();
            if (!(att instanceof ConnectionState)) {
                continue;
            }
            ConnectionState state = (ConnectionState) att;
            if (now - state.getLastAccessedAt() > CONNECTION_TIMEOUT_MS) {
                closeConnection(key);
            }
        }
    }

    private void closeConnection(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ex) {
            // ignore
        }
        key.cancel();
    }

    private ParsedRequest parseRequest(StringBuilder inbound) {
        int headerEnd = inbound.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            return null;
        }

        String headersBlock = inbound.substring(0, headerEnd);
        String[] lines = headersBlock.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            inbound.delete(0, headerEnd + 4);
            return new ParsedRequest(null, true, false);
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            inbound.delete(0, headerEnd + 4);
            return new ParsedRequest(null, true, false);
        }

        String method = parts[0].trim().toUpperCase(Locale.ROOT);
        String path = parts[1].trim();
        String version = parts.length >= 3 ? parts[2].trim().toUpperCase(Locale.ROOT) : "HTTP/1.0";

        Map<String, String> headers = parseHeaders(lines);
        int contentLength = parseContentLength(headers.get("content-length"));
        if (contentLength > server.getClientBodyLimitBytes()) {
            return new ParsedRequest(null, true, true);
        }

        int totalLength = headerEnd + 4 + contentLength;
        if (inbound.length() < totalLength) {
            return null;
        }

        inbound.delete(0, totalLength);

        boolean shouldClose = shouldClose(headers, version);
        Request request = new Request(method, path);
        return new ParsedRequest(request, shouldClose, false);
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

    private static final class ParsedRequest {
        private final Request request;
        private final boolean shouldClose;
        private final boolean tooLarge;

        private ParsedRequest(Request request, boolean shouldClose, boolean tooLarge) {
            this.request = request;
            this.shouldClose = shouldClose;
            this.tooLarge = tooLarge;
        }
    }
}
