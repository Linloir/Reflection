package io.github.linloir.reflection.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, bounded mapping status shared by the transport and tablet UI. */
public final class MappingState {
    public static final int QUERY = 0, NEXT = 1, PREVIOUS = 2, ALL = 3, SELECT = 4;
    public final long revision;
    public final String mode, selected;
    public final Region target;
    public final List<Display> displays;
    public final boolean pending;
    private final String json;

    public static final class Region {
        public final double x, y, width, height;
        private Region(Map<String, Object> value) throws IOException {
            x = number(value, "x"); y = number(value, "y");
            width = number(value, "width"); height = number(value, "height");
            if (width <= 0 || height <= 0 || !finite(x + width) || !finite(y + height))
                throw invalid("Invalid display rectangle");
        }
    }
    public static final class Display {
        public final String id, name;
        public final Region bounds;
        public final boolean primary;
        private Display(Map<String, Object> value) throws IOException {
            id = string(value, "id"); name = string(value, "name");
            if (!validDisplayId(id) || name.getBytes(StandardCharsets.UTF_8).length > 256)
                throw invalid("Invalid display identity");
            bounds = new Region(value); primary = bool(value, "primary");
        }
    }

    private MappingState(String json, Map<String, Object> value) throws IOException {
        Object counter = value.get("revision");
        if (!(counter instanceof Long) || (Long) counter <= 0) throw invalid("Invalid mapping revision");
        revision = (Long) counter;
        mode = string(value, "mode"); selected = string(value, "selected");
        if (!mode.equals("all") && !mode.equals("display") && !mode.equals("custom"))
            throw invalid("Unknown mapping mode");
        if (!selected.isEmpty() && !validDisplayId(selected)) throw invalid("Invalid selected display");
        target = new Region(object(value.get("target")));
        pending = bool(value, "pending");
        Object entries = value.get("displays");
        if (!(entries instanceof List)) throw invalid("Missing display list");
        List<?> array = (List<?>) entries;
        if (array.isEmpty() || array.size() > 16) throw invalid("Invalid display count");
        List<Display> result = new ArrayList<>();
        boolean found = false;
        for (Object entry : array) {
            Display display = new Display(object(entry));
            for (Display other : result) if (display.id.equals(other.id)) throw invalid("Duplicate display ID");
            found |= display.id.equals(selected); result.add(display);
        }
        if (mode.equals("display") && !found) throw invalid("Selected display is unavailable");
        displays = Collections.unmodifiableList(result);
        this.json = json;
    }

    public static MappingState decode(byte[] utf8) throws IOException {
        if (utf8.length == 0 || utf8.length > Wire.MAX_FRAME - 34) throw invalid("Invalid mapping status size");
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException error) { throw invalid("Invalid mapping UTF-8"); }
        return new MappingState(json, object(new Parser(json).parse()));
    }

    public static byte[] request(long sequence, int action, String display) {
        if (sequence <= 0 || action < QUERY || action > SELECT) throw new IllegalArgumentException("Invalid mapping request");
        String id = display == null ? "" : display;
        if (action == SELECT ? !validDisplayId(id) : !id.isEmpty())
            throw new IllegalArgumentException("A display ID is required only for select");
        byte[] identity = id.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(9 + identity.length).putLong(sequence).put((byte) action).put(identity).array();
    }

    public String json() { return json; }
    public String label() {
        String label = mode.equals("all") ? "All displays" : mode.equals("custom") ? "Custom region" : selected;
        if (mode.equals("display")) for (Display display : displays)
            if (display.id.equals(selected)) { label = display.name; break; }
        return label + (pending ? " · lift pen to switch" : "");
    }

    private static boolean validDisplayId(String id) {
        int bytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > 128) return false;
        for (int i = 0; i < id.length(); i++) if (id.charAt(i) < 32 || id.charAt(i) == 127) return false;
        return true;
    }
    private static boolean finite(double value) { return !Double.isInfinite(value) && !Double.isNaN(value); }
    private static IOException invalid(String message) { return new IOException(message); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw invalid("Expected mapping object");
        return (Map<String, Object>) value;
    }
    private static String string(Map<String, Object> value, String key) throws IOException {
        Object item = value.get(key);
        if (!(item instanceof String)) throw invalid("Invalid mapping field: " + key);
        return (String) item;
    }
    private static boolean bool(Map<String, Object> value, String key) throws IOException {
        Object item = value.get(key);
        if (!(item instanceof Boolean)) throw invalid("Invalid mapping field: " + key);
        return (Boolean) item;
    }
    private static double number(Map<String, Object> value, String key) throws IOException {
        Object item = value.get(key);
        if (!(item instanceof Number) || !finite(((Number) item).doubleValue())) throw invalid("Invalid mapping coordinate: " + key);
        return ((Number) item).doubleValue();
    }

    /** Small strict JSON reader, kept free of Android classes for transport regression tests. */
    private static final class Parser {
        private final String text;
        private int offset;
        Parser(String text) { this.text = text; }
        Object parse() throws IOException {
            Object result = value(0); whitespace();
            if (offset != text.length()) throw invalid("Trailing mapping JSON");
            return result;
        }
        private Object value(int depth) throws IOException {
            if (depth > 12) throw invalid("Mapping JSON is too deeply nested");
            whitespace(); char c = peek();
            if (c == '{') {
                offset++; Map<String, Object> result = new LinkedHashMap<>(); whitespace();
                if (take('}')) return result;
                do {
                    whitespace(); String key = quoted(); whitespace(); require(':');
                    if (result.containsKey(key)) throw invalid("Duplicate mapping JSON field");
                    result.put(key, value(depth + 1)); whitespace();
                    if (take('}')) return result;
                    require(',');
                } while (true);
            }
            if (c == '[') {
                offset++; List<Object> result = new ArrayList<>(); whitespace();
                if (take(']')) return result;
                do {
                    if (result.size() >= 128) throw invalid("Mapping JSON array is too large");
                    result.add(value(depth + 1)); whitespace();
                    if (take(']')) return result;
                    require(',');
                } while (true);
            }
            if (c == '"') return quoted();
            if (text.startsWith("true", offset)) { offset += 4; return true; }
            if (text.startsWith("false", offset)) { offset += 5; return false; }
            if (text.startsWith("null", offset)) { offset += 4; return null; }
            int start = offset;
            take('-');
            if (!take('0')) { if (!digit19(peek())) throw invalid("Invalid mapping JSON number"); while (digit(peek())) offset++; }
            boolean integral = true;
            if (take('.')) { integral = false; if (!digit(peek())) throw invalid("Invalid mapping JSON number"); while (digit(peek())) offset++; }
            if (take('e') || take('E')) {
                integral = false; if (!take('+')) take('-');
                if (!digit(peek())) throw invalid("Invalid mapping JSON number"); while (digit(peek())) offset++;
            }
            try {
                String raw = text.substring(start, offset);
                if (integral) return Long.parseLong(raw);
                double result = Double.parseDouble(raw);
                if (!finite(result)) throw invalid("Nonfinite mapping JSON number");
                return result;
            } catch (NumberFormatException error) { throw invalid("Invalid mapping JSON number"); }
        }
        private String quoted() throws IOException {
            require('"'); StringBuilder result = new StringBuilder();
            while (offset < text.length()) {
                char c = text.charAt(offset++);
                if (c == '"') {
                    for (int i = 0; i < result.length(); i++) {
                        char unit = result.charAt(i);
                        if (Character.isHighSurrogate(unit)) {
                            if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i)))
                                throw invalid("Invalid mapping JSON Unicode");
                        } else if (Character.isLowSurrogate(unit)) throw invalid("Invalid mapping JSON Unicode");
                    }
                    return result.toString();
                }
                if (c < 32) throw invalid("Invalid mapping JSON string");
                if (c == '\\') {
                    c = peek(); offset++;
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (offset + 4 > text.length()) throw invalid("Invalid mapping JSON escape");
                            for (int i = offset; i < offset + 4; i++) {
                                char hex = text.charAt(i);
                                if (!(digit(hex) || hex >= 'a' && hex <= 'f' || hex >= 'A' && hex <= 'F'))
                                    throw invalid("Invalid mapping JSON escape");
                            }
                            try { c = (char) Integer.parseInt(text.substring(offset, offset + 4), 16); }
                            catch (NumberFormatException error) { throw invalid("Invalid mapping JSON escape"); }
                            offset += 4; break;
                        default: throw invalid("Invalid mapping JSON escape");
                    }
                }
                result.append(c);
            }
            throw invalid("Unterminated mapping JSON string");
        }
        private char peek() { return offset < text.length() ? text.charAt(offset) : '\0'; }
        private boolean take(char c) { if (peek() != c) return false; offset++; return true; }
        private void require(char c) throws IOException { if (!take(c)) throw invalid("Invalid mapping JSON"); }
        private void whitespace() { while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') offset++; }
        private static boolean digit(char c) { return c >= '0' && c <= '9'; }
        private static boolean digit19(char c) { return c >= '1' && c <= '9'; }
    }
}
