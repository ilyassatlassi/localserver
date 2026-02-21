package com.server.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonParser {
    private final String input;
    private int index;

    public JsonParser(String input) {
        this.input = input;
        this.index = 0;
    }

    public Object parse() {
        skipWhitespace();
        Object value = parseValue();
        skipWhitespace();
        if (index != input.length()) {
            throw error("Unexpected trailing characters");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (eof()) {
            throw error("Unexpected end of input");
        }
        char c = peek();
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return parseString();
        }
        if (c == '-' || isDigit(c)) {
            return parseNumber();
        }
        if (startsWith("true")) {
            index += 4;
            return Boolean.TRUE;
        }
        if (startsWith("false")) {
            index += 5;
            return Boolean.FALSE;
        }
        if (startsWith("null")) {
            index += 4;
            return null;
        }
        throw error("Unexpected character '" + c + "'");
    }

    private Map<String, Object> parseObject() {
        expect('{');
        skipWhitespace();
        Map<String, Object> obj = new LinkedHashMap<>();
        if (peek() == '}') {
            index++;
            return obj;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object value = parseValue();
            obj.put(key, value);
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
                continue;
            }
            if (c == '}') {
                index++;
                break;
            }
            throw error("Expected ',' or '}'");
        }
        return obj;
    }

    private List<Object> parseArray() {
        expect('[');
        skipWhitespace();
        List<Object> arr = new ArrayList<>();
        if (peek() == ']') {
            index++;
            return arr;
        }
        while (true) {
            Object value = parseValue();
            arr.add(value);
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
                continue;
            }
            if (c == ']') {
                index++;
                break;
            }
            throw error("Expected ',' or ']'");
        }
        return arr;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (!eof()) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (eof()) {
                    throw error("Unterminated escape sequence");
                }
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append(parseUnicode());
                        break;
                    default:
                        throw error("Invalid escape character '" + e + "'");
                }
                continue;
            }
            sb.append(c);
        }
        throw error("Unterminated string");
    }

    private char parseUnicode() {
        if (index + 4 > input.length()) {
            throw error("Invalid unicode escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char c = input.charAt(index++);
            code <<= 4;
            if (c >= '0' && c <= '9') {
                code += c - '0';
            } else if (c >= 'a' && c <= 'f') {
                code += 10 + (c - 'a');
            } else if (c >= 'A' && c <= 'F') {
                code += 10 + (c - 'A');
            } else {
                throw error("Invalid unicode escape");
            }
        }
        return (char) code;
    }

    private Number parseNumber() {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        if (eof()) {
            throw error("Invalid number");
        }
        if (peek() == '0') {
            index++;
        } else if (isDigit(peek())) {
            while (!eof() && isDigit(peek())) {
                index++;
            }
        } else {
            throw error("Invalid number");
        }
        if (!eof() && peek() == '.') {
            index++;
            if (eof() || !isDigit(peek())) {
                throw error("Invalid fraction");
            }
            while (!eof() && isDigit(peek())) {
                index++;
            }
        }
        if (!eof() && (peek() == 'e' || peek() == 'E')) {
            index++;
            if (!eof() && (peek() == '+' || peek() == '-')) {
                index++;
            }
            if (eof() || !isDigit(peek())) {
                throw error("Invalid exponent");
            }
            while (!eof() && isDigit(peek())) {
                index++;
            }
        }
        String num = input.substring(start, index);
        if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
            return Double.valueOf(num);
        }
        try {
            return Long.valueOf(num);
        } catch (NumberFormatException ex) {
            return Double.valueOf(num);
        }
    }

    private void skipWhitespace() {
        while (!eof()) {
            char c = peek();
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                index++;
                continue;
            }
            break;
        }
    }

    private boolean startsWith(String s) {
        return input.startsWith(s, index);
    }

    private char peek() {
        if (eof()) {
            return '\0';
        }
        return input.charAt(index);
    }

    private char next() {
        return input.charAt(index++);
    }

    private void expect(char c) {
        if (eof() || input.charAt(index) != c) {
            throw error("Expected '" + c + "'");
        }
        index++;
    }

    private boolean eof() {
        return index >= input.length();
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private ConfigException error(String message) {
        return new ConfigException(message + " at position " + index);
    }
}
