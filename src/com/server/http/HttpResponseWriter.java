package com.server.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HttpResponseWriter {

    public ByteBuffer serialize(Response response, boolean closeAfterWrite) {
        if (response == null) {
            response = new Response(500, "Internal Server Error");
        }
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
        if (!hasConnectionHeader(response)) {
            sb.append("Connection: ").append(closeAfterWrite ? "close" : "keep-alive").append("\r\n");
        }
        sb.append("\r\n");
        sb.append(body);
        return ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasConnectionHeader(Response response) {
        for (String name : response.getHeaders().keySet()) {
            if ("connection".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 413: return "Payload Too Large";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }
}
