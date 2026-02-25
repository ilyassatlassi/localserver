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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class HttpEngine {
    private static final int READ_BUFFER_SIZE = 8192;
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
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        handleAccept(selector, key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
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

        if (!headersComplete(state.getInbound())) {
            return;
        }

        Response response = buildResponse(state.getInbound().toString());
        ByteBuffer out = serializeResponse(response);
        state.setWriteBuffer(out);
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        ByteBuffer out = state.getWriteBuffer();
        if (out == null) {
            client.close();
            return;
        }
        client.write(out);
        if (!out.hasRemaining()) {
            client.close();
        }
    }

    private boolean headersComplete(StringBuilder inbound) {
        return inbound.indexOf("\r\n\r\n") >= 0;
    }

    private Response buildResponse(String rawRequest) {
        String[] lines = rawRequest.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            return new Response(400, "Bad Request");
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            return new Response(400, "Bad Request");
        }
        String method = parts[0].trim().toUpperCase();
        String path = parts[1].trim();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        Request request = new Request(method, path);
        return handler.handle(request, server);
    }

    private ByteBuffer serializeResponse(Response response) {
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
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        sb.append(body);
        return ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }
}
