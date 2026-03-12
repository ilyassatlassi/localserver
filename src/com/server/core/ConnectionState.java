package com.server.core;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

public class ConnectionState {
    private final ByteBuffer readBuffer;
    private final StringBuilder inbound;
    private final Deque<ByteBuffer> pendingWrites;
    private boolean closeAfterWrite;
    private long lastAccessedAt;

    public ConnectionState(int readBufferSize) {
        this.readBuffer = ByteBuffer.allocate(readBufferSize);
        this.inbound = new StringBuilder();
        this.pendingWrites = new ArrayDeque<>();
        this.lastAccessedAt = System.currentTimeMillis();
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public StringBuilder getInbound() {
        return inbound;
    }

    public void enqueueWrite(ByteBuffer writeBuffer) {
        if (writeBuffer == null) {
            return;
        }
        pendingWrites.add(writeBuffer);
    }

    public ByteBuffer peekWriteBuffer() {
        return pendingWrites.peekFirst();
    }

    public void popWriteBuffer() {
        pendingWrites.pollFirst();
    }

    public boolean hasPendingWrites() {
        return !pendingWrites.isEmpty();
    }

    public void markCloseAfterWrite() {
        this.closeAfterWrite = true;
    }

    public boolean shouldCloseAfterWrite() {
        return closeAfterWrite;
    }

    public void updateLastAccessedAt() {
        this.lastAccessedAt = System.currentTimeMillis();
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }
}
