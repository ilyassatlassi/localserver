package com.server.core;

import com.server.config.ServerConfig;
import com.server.http.HttpRequestParser;
import com.server.http.RequestProcessor;
import com.server.http.Request;
import com.server.http.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HttpEngine {
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int SELECT_TIMEOUT_MS = 1000;
    private static final long CONNECTION_TIMEOUT_MS = 30_000;
    private final List<ServerConfig> servers;
    private final HttpRequestParser requestParser;
    private final Map<ServerConfig, RequestProcessor> processors;
    private final Map<Integer, List<ServerConfig>> serversByPort;

    public HttpEngine(List<ServerConfig> servers) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("servers must not be empty");
        }
        this.servers = List.copyOf(servers);
        this.processors = new HashMap<>();
        this.serversByPort = new HashMap<>();
        long maxBodyLimit = 0L;
        for (ServerConfig cfg : this.servers) {
            this.processors.put(cfg, new RequestProcessor(cfg));
            maxBodyLimit = Math.max(maxBodyLimit, cfg.getClientBodyLimitBytes());
            for (int port : cfg.getPorts()) {
                this.serversByPort.computeIfAbsent(port, key -> new ArrayList<>()).add(cfg);
            }
        }
        this.requestParser = new HttpRequestParser(maxBodyLimit);
    }

    public void start() {
        try (Selector selector = Selector.open()) {
            int boundCount = 0;
            for (BindAddress bindAddress : uniqueBindAddresses()) {
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                try {
                    serverChannel.bind(new InetSocketAddress(bindAddress.host, bindAddress.port));
                    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                    System.out.println("[HttpEngine] Listening on " + bindAddress.host + ":" + bindAddress.port);
                    boundCount++;
                } catch (Exception ex) { // includes IOException and UnresolvedAddressException
                    System.err.println("[HttpEngine] Skipping bind " + bindAddress.host + ":" + bindAddress.port
                            + " (" + ex.getClass().getSimpleName() + (ex.getMessage() != null ? (": " + ex.getMessage()) : "") + ")");
                    try {
                        serverChannel.close();
                    } catch (IOException ignored) {
                        // ignore close errors
                    }
                }
            }
            if (boundCount == 0) {
                throw new RuntimeException("No valid listener could be started");
            }

            while (true) {
                selector.select(SELECT_TIMEOUT_MS);
                closeTimedOutConnections(selector);
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

        if (!readIntoState(client, state)) {
            closeConnection(key); // EOF — client disconnected
            return;
        }
        processAllCompleteRequests(key, state);

    }

    private void processAllCompleteRequests(SelectionKey key,
            ConnectionState state) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        while (true) {
            HttpRequestParser.ParseResult parsed = requestParser.parse(state.getInbound());

            if (parsed == null)
                break; // incomplete — wait for more data

            // ── Parser detected a protocol error (400 / 405 / 413) ───────────
            if (parsed.isError()) {
                RequestProcessor fallbackProcessor = processors.get(servers.get(0));
                Response error = fallbackProcessor.buildErrorResponse(
                        parsed.getErrorStatus(), parsed.getErrorMessage());
                state.enqueueWrite(fallbackProcessor.serialize(error, true));
                state.markCloseAfterWrite();
                break;
            }

            // ── Normal request — dispatch and enqueue response ────────────────
            boolean closeConn = parsed.shouldClose();
            Request request = parsed.getRequest();
            int localPort = resolveLocalPort(client);
            ServerConfig targetServer = selectServer(request, localPort);
            RequestProcessor targetProcessor = processors.get(targetServer);
            Response response;
            if (request.getBody().getBytes(StandardCharsets.ISO_8859_1).length > targetServer.getClientBodyLimitBytes()) {
                response = targetProcessor.buildErrorResponse(413, "Payload Too Large");
                closeConn = true;
            } else {
                response = targetProcessor.handle(request);
            }
            state.enqueueWrite(targetProcessor.serialize(response, closeConn));

            if (closeConn) {
                state.markCloseAfterWrite();
                break;
            }
        }

        if (state.hasPendingWrites()) {
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private boolean readIntoState(SocketChannel client, ConnectionState state) throws IOException {
        ByteBuffer buffer = state.getReadBuffer();
        buffer.clear();

        int bytesRead = client.read(buffer);

        if (bytesRead == -1)
            return false; // EOF — client disconnected
        if (bytesRead == 0)
            return true; // nothing yet in non-blocking mode

        buffer.flip();
        String chunk = StandardCharsets.ISO_8859_1.decode(buffer).toString();
        state.getInbound().append(chunk);
        return true;
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        state.updateLastAccessedAt();

        ByteBuffer current = state.peekWriteBuffer();
        if (current == null) {
            closeConnection(key);
            return;
        }

        client.write(current);

        if (current.hasRemaining())
            return; // partial write — come back next event

        state.popWriteBuffer();

        if (state.hasPendingWrites())
            return; // more responses queued — stay in OP_WRITE

        if (state.shouldCloseAfterWrite()) {
            closeConnection(key);
        } else {
            key.interestOps(SelectionKey.OP_READ); // keep-alive: wait for next request
        }
    }

    // =========================================================================
    // TIMEOUT & CONNECTION MANAGEMENT
    // =========================================================================

    /**
     * Close every client idle longer than CONNECTION_TIMEOUT_MS.
     * Keys collected first to avoid ConcurrentModificationException when
     * key.cancel() modifies selector.keys() during iteration.
     */
    private void closeTimedOutConnections(Selector selector) {
        long now = System.currentTimeMillis();
        List<SelectionKey> toClose = new ArrayList<>();

        for (SelectionKey key : selector.keys()) {
            if (!key.isValid())
                continue;
            if (!(key.attachment() instanceof ConnectionState))
                continue;

            ConnectionState state = (ConnectionState) key.attachment();
            if (now - state.getLastAccessedAt() > CONNECTION_TIMEOUT_MS) {
                toClose.add(key);
            }
        }

        toClose.forEach(this::closeConnection);
    }

    private void closeConnection(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ex) {
            // ignore
        }
        key.cancel();
    }

    private List<BindAddress> uniqueBindAddresses() {
        LinkedHashSet<BindAddress> unique = new LinkedHashSet<>();
        for (ServerConfig cfg : servers) {
            for (int port : cfg.getPorts()) {
                unique.add(new BindAddress(cfg.getHost(), port));
            }
        }
        return new ArrayList<>(unique);
    }

    private ServerConfig selectServer(Request request, int localPort) {
        List<ServerConfig> candidates = serversByPort.get(localPort);
        if (candidates == null || candidates.isEmpty()) {
            return servers.get(0);
        }
        String hostHeader = normalizeHostHeader(request.getHost());
        if (hostHeader != null) {
            for (ServerConfig cfg : candidates) {
                if (hostHeader.equalsIgnoreCase(cfg.getName())) {
                    return cfg;
                }
            }
        }
        for (ServerConfig cfg : candidates) {
            if (cfg.isDefault()) {
                return cfg;
            }
        }
        return candidates.get(0);
    }

    private int resolveLocalPort(SocketChannel client) {
        try {
            SocketAddress addr = client.getLocalAddress();
            if (addr instanceof InetSocketAddress) {
                return ((InetSocketAddress) addr).getPort();
            }
        } catch (IOException ignored) {
            // fall back below
        }
        return -1;
    }

    private String normalizeHostHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String value = hostHeader.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(0, colon) : value;
    }

    private static final class BindAddress {
        private final String host;
        private final int port;

        private BindAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BindAddress)) {
                return false;
            }
            BindAddress other = (BindAddress) obj;
            return port == other.port && String.valueOf(host).equals(other.host);
        }

        @Override
        public int hashCode() {
            return 31 * String.valueOf(host).hashCode() + port;
        }
    }
}
