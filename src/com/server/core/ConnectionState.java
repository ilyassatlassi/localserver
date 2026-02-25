package com.server.core;

import java.nio.ByteBuffer;

public class ConnectionState {
    private final ByteBuffer readBuffer;
    private final StringBuilder inbound;
    private ByteBuffer writeBuffer;

    public ConnectionState(int readBufferSize) {
        this.readBuffer = ByteBuffer.allocate(readBufferSize);
        this.inbound = new StringBuilder();
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public StringBuilder getInbound() {
        return inbound;
    }

    public void setWriteBuffer(ByteBuffer writeBuffer) {
        this.writeBuffer = writeBuffer;
    }

    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }

    public boolean hasWriteBuffer() {
        return writeBuffer != null && writeBuffer.hasRemaining();
    }
}
