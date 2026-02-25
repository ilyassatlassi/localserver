package com.server.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Response {
    private int status;
    private final Map<String, String> headers;
    private String body;

    public Response(int status) {
        this(status, "");
    }

    public Response(int status, String body) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.headers = new LinkedHashMap<>();
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public void setHeader(String name, String value) {
        if (name == null || name.isEmpty()) {
            return;
        }
        headers.put(name, value == null ? "" : value);
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body == null ? "" : body;
    }
}
