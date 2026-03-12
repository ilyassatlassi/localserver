package com.server.core;

import com.server.config.ServerConfig;
import com.server.http.HttpRequestParser;
import com.server.http.HttpResponseWriter;
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
import java.util.Iterator;
import java.util.Set;

public class HttpEngine {
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int SELECT_TIMEOUT_MS = 1000;
    private static final long CONNECTION_TIMEOUT_MS = 30_000;
    private final ServerConfig server;
    private final HttpRequestParser requestParser;
    private final RequestProcessor requestProcessor;
    private final HttpResponseWriter responseWriter;

    public HttpEngine(ServerConfig server) {
        this.server = server;
        this.requestParser = new HttpRequestParser(server.getClientBodyLimitBytes());
        this.requestProcessor = new RequestProcessor(server);
        this.responseWriter = new HttpResponseWriter();
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
        String chunk = StandardCharsets.UTF_8.decode(buffer).toString();
        buffer.clear();
        state.getInbound().append(chunk);
        while (true) {
            HttpRequestParser.ParseResult parsed = requestParser.parse(state.getInbound());
            if (parsed == null) {
                return;
            }
            if (parsed.isTooLarge()) {
                Response response = requestProcessor.errorResponse(413, "Payload Too Large");
                ByteBuffer out = responseWriter.serialize(response, true);
                state.enqueueWrite(out);
                state.markCloseAfterWrite();
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
            Response response = requestProcessor.handle(parsed.getRequest());
            boolean closeAfter = parsed.shouldClose();
            ByteBuffer out = responseWriter.serialize(response, closeAfter);
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

}
