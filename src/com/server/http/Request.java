package com.server.http;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable value object representing a parsed HTTP/1.1 request.
 *
 * Built by {@link HttpRequestParser} and consumed by the protocol handler.
 * Every field the handler could ever need is stored here.
 */
public class Request {

    private final String              method;      // GET, POST, DELETE, PUT, HEAD, OPTIONS
    private final String              path;        // URL path, query string stripped
    private final String              queryString; // everything after '?', or ""
    private final Map<String, String> headers;     // lowercase-keyed, unmodifiable
    private final String              body;        // decoded request body, or ""

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Full constructor used by the parser.
     *
     * @param method      HTTP method, already uppercased
     * @param path        URL path with query string stripped
     * @param queryString raw query string (after '?'), or empty string
     * @param headers     parsed request headers, lowercase-keyed
     * @param body        request body decoded as ISO-8859-1 string, or ""
     */
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

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** HTTP method in uppercase — e.g. {@code "GET"}, {@code "POST"}. */
    public String getMethod() {
        return method;
    }

    /**
     * URL path with query string stripped.
     * Example: request for {@code /search?q=hello} → path is {@code /search}.
     */
    public String getPath() {
        return path;
    }

    /**
     * Raw query string, without the leading {@code ?}.
     * Example: request for {@code /search?q=hello&page=2} → {@code "q=hello&page=2"}.
     * Returns empty string if no query string was present.
     */
    public String getQueryString() {
        return queryString;
    }

    /**
     * Get a single request header by name (case-insensitive).
     *
     * @param name header name, e.g. {@code "Content-Type"} or {@code "content-type"}
     * @return header value, or {@code null} if not present
     */
    public String getHeader(String name) {
        if (name == null) return null;
        return headers.get(name.toLowerCase());
    }

    /**
     * Returns all request headers as an unmodifiable lowercase-keyed map.
     * Useful for iterating all headers.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * The request body as a string.
     * Encoded as ISO-8859-1 (the raw byte values are preserved).
     * For binary uploads, convert back to bytes via:
     * <pre>
     *   byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
     * </pre>
     * Returns empty string for requests without a body (GET, DELETE, etc.).
     */
    public String getBody() {
        return body;
    }

    // -------------------------------------------------------------------------
    // Convenience helpers
    // -------------------------------------------------------------------------

    /** True if this request carries a non-empty body. */
    public boolean hasBody() {
        return !body.isEmpty();
    }

    /**
     * Convenience: get the {@code Content-Type} header value, or {@code null}.
     * Saves the handler from calling {@code getHeader("content-type")} manually.
     */
    public String getContentType() {
        return headers.get("content-type");
    }

    /**
     * Convenience: get the {@code Host} header value.
     * This is guaranteed to be present for HTTP/1.1 requests — the parser
     * rejects requests without it with 400.
     */
    public String getHost() {
        return headers.get("host");
    }

    // -------------------------------------------------------------------------
    // Object
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return method + " " + path
                + (queryString.isEmpty() ? "" : "?" + queryString)
                + " HTTP/1.1"
                + " [body=" + body.length() + "B]";
    }
}