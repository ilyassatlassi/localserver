
package com.server.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable HTTP/1.1 response value object.
 *
 * Built by {@link RequestProcessor} and serialized to bytes by
 * {@link com.server.core.HttpResponseSerializer}.
 */
public class Response {

    private int                    status;
    private byte[]                 bodyBytes;
    private final Map<String, String> headers = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    // -------------------------------------------------------------------------
    // Body
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Headers
    // -------------------------------------------------------------------------

    /**
     * Returns all response headers as an unmodifiable map.
     * Keys are stored exactly as set — use {@link #hasHeader} for
     * case-insensitive lookup.
     */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Set a response header.
     * Overwrites any previous value for the same name.
     * Silently ignores null or blank names.
     */
    public void setHeader(String name, String value) {
        if (name == null || name.isBlank()) return;
        headers.put(name, value != null ? value : "");
    }

    /**
     * Case-insensitive check for the presence of a header.
     *
     * Used by the serializer to avoid adding duplicate headers
     * (e.g. if the handler already set "Connection").
     *
     * @param name header name to look for, case-insensitive
     * @return true if a header with that name exists
     */
    public boolean hasHeader(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(lower)) return true;
        }
        return false;
    }
}
