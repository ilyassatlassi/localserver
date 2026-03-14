
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
    private String                 body;
    private final Map<String, String> headers = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public Response(int status) {
        this(status, "");
    }

    public Response(int status, String body) {
        this.status = status;
        this.body   = body != null ? body : "";
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
        return body;
    }

    public void setBody(String body) {
        this.body = body != null ? body : "";
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