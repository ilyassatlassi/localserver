
package com.server.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Response {

    private int                    status;
    private byte[]                 bodyBytes;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public Response(int status) {
        this(status, "");
    }

    public Response(int status, String body) {
        this.status = status;
        setBody(body);
    }

    public Response(int status, byte[] bodyBytes) {
        this.status = status;
        setBodyBytes(bodyBytes);
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getBody() {
        return bodyBytes == null ? "" : new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void setBody(String body) {
        if (body == null) {
            this.bodyBytes = new byte[0];
        } else {
            this.bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public byte[] getBodyBytes() {
        return bodyBytes != null ? bodyBytes : new byte[0];
    }

    public void setBodyBytes(byte[] bodyBytes) {
        this.bodyBytes = bodyBytes != null ? bodyBytes : new byte[0];
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public void setHeader(String name, String value) {
        if (name == null || name.isBlank()) return;
        headers.put(name, value != null ? value : "");
    }

    public boolean hasHeader(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(lower)) return true;
        }
        return false;
    }
}
