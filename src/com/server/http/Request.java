package com.server.http;

import java.util.Collections;
import java.util.Map;


public class Request {

    private final String              method;      // GET, POST, DELETE, PUT, HEAD, OPTIONS
    private final String              path;        // URL path, query string stripped
    private final String              queryString; // everything after '?', or ""
    private final Map<String, String> headers;     // lowercase-keyed, unmodifiable
    private final String              body;        // decoded request body, or ""

    public Request(String method,
                   String path,
                   String queryString,
                   Map<String, String> headers,
                   String body) {
        this.method      = method      != null ? method      : "";
        this.path        = path        != null ? path        : "/";
        this.queryString = queryString != null ? queryString : "";
        this.headers     = headers     != null
                         ? Collections.unmodifiableMap(headers)
                         : Collections.emptyMap();
        this.body        = body        != null ? body        : "";
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getQueryString() {
        return queryString;
    }

    public String getHeader(String name) {
        if (name == null) return null;
        return headers.get(name.toLowerCase());
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }


    /** True if this request carries a non-empty body. */
    public boolean hasBody() {
        return !body.isEmpty();
    }

    public String getContentType() {
        return headers.get("content-type");
    }
    public String getHost() {
        return headers.get("host");
    }


    @Override
    public String toString() {
        return method + " " + path
                + (queryString.isEmpty() ? "" : "?" + queryString)
                + " HTTP/1.1"
                + " [body=" + body.length() + "B]";
    }
}