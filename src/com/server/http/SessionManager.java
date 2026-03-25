package com.server.http;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private static final String COOKIE_NAME = "session_id";

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> sessions =
            new ConcurrentHashMap<>();

    public String getOrCreateSession(Request request) {
        String cookieHeader = request.getHeader("cookie");
        String sessionId = extractSessionId(cookieHeader);

        if (sessionId != null && sessions.containsKey(sessionId)) {
            return sessionId;
        }

        // Create a new session
        sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ConcurrentHashMap<>());
        return sessionId;
    }

    /**
     * Check if the request already carries a valid session cookie.
     */
    public boolean hasValidSession(Request request) {
        String cookieHeader = request.getHeader("cookie");
        String sessionId = extractSessionId(cookieHeader);
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * Get session data map for a given session ID.
     */
    public Map<String, String> getSessionData(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Remove a session (logout).
     */
    public void destroySession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /**
     * Build the Set-Cookie header value for a given session ID.
     */
    public String buildSetCookieHeader(String sessionId) {
        return COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly";
    }

    /**
     * Get the cookie name used for sessions.
     */
    public String getCookieName() {
        return COOKIE_NAME;
    }

    private String extractSessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        // Cookie header format: "name1=value1; name2=value2; ..."
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.startsWith(COOKIE_NAME + "=")) {
                String value = trimmed.substring(COOKIE_NAME.length() + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
