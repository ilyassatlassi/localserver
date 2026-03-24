package com.server.core;

import com.server.config.ServerConfig;
import com.server.http.HttpRequestParser;
import com.server.http.RequestProcessor;
import com.server.http.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class HttpEngine {
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int SELECT_TIMEOUT_MS = 1000;
    private static final long CONNECTION_TIMEOUT_MS = 30_000;
    private final List<ServerConfig> servers;
    private final HttpRequestParser requestParser;
    private final RequestProcessor requestProcessor;
    public HttpEngine(List<ServerConfig> servers) {
        this.servers = servers;
        this.requestParser = new HttpRequestParser();
        this.requestProcessor = new RequestProcessor(servers);
    }

    public void start() {
        try (Selector selector = Selector.open()) {
            Set<InetSocketAddress> boundAddresses = new java.util.HashSet<>();
            for (ServerConfig server : servers) {
                for (int port : server.getPorts()) {
                    InetSocketAddress address = new InetSocketAddress(server.getHost(), port);
                    if (boundAddresses.add(address)) {
                        ServerSocketChannel serverChannel = ServerSocketChannel.open();
                        serverChannel.configureBlocking(false);
                        serverChannel.bind(address);
                        serverChannel.register(selector, SelectionKey.OP_ACCEPT, address);
                        System.out.println("[HttpEngine] Listening on " + address);
                    }
                }
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
        InetSocketAddress localAddress = (InetSocketAddress) key.attachment();
        SocketChannel client = serverChannel.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        ConnectionState state = new ConnectionState(READ_BUFFER_SIZE, localAddress);
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
        long maxBodyBytesForPort = extractMaxBodyBytes(state.getLocalAddress());

        while (true) {
            HttpRequestParser.ParseResult parsed = requestParser.parse(state.getInbound(), maxBodyBytesForPort);

            if (parsed == null)
                break; // incomplete — wait for more data

            // ── Parser detected a protocol error (400 / 405 / 413) ───────────
            if (parsed.isError()) {
                Response error = requestProcessor.buildErrorResponse(
                        parsed.getErrorStatus(), parsed.getErrorMessage(), getDefaultServer(state.getLocalAddress()));
                state.enqueueWrite(requestProcessor.serialize(error, true));
                state.markCloseAfterWrite();
                break;
            }

            // ── Normal request — dispatch and enqueue response ────────────────
            boolean closeConn = parsed.shouldClose();
            Response response = requestProcessor.handle(parsed.getRequest(), state.getLocalAddress());
            state.enqueueWrite(requestProcessor.serialize(response, closeConn));

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

    private long extractMaxBodyBytes(InetSocketAddress localAddress) {
        long max = 0;
        for (ServerConfig srv : servers) {
            if (srv.getPorts().contains(localAddress.getPort())) {
                max = Math.max(max, srv.getClientBodyLimitBytes());
            }
        }
        return max;
    }

    private ServerConfig getDefaultServer(InetSocketAddress localAddress) {
        ServerConfig fallback = servers.get(0);
        for (ServerConfig srv : servers) {
            if (srv.getPorts().contains(localAddress.getPort())) {
                fallback = srv;
                if (srv.isDefault()) {
                    return srv;
                }
            }
        }
        return fallback;
    }
}
