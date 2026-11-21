import java.util.*;

/**
 * Self-contained, zero-dependency JSON parser and typed accessor.
 * Strict mode conforms fully to RFC 8259 (lowercase literals only, no leading zeroes,
 * digits required after '.' and 'e'/'E', no invalid escapes, no trailing commas).
 * Lenient mode tolerates trailing commas for local config file compatibility.
 */
public class MiniJson {

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    public static class JsonObject {
        private final Map<String, Object> map;

        public JsonObject(Map<String, Object> map) {
            this.map = map != null ? map : new LinkedHashMap<>();
        }

        public boolean has(String key) {
            return map.containsKey(key);
        }

        public boolean isNull(String key) {
            return map.containsKey(key) && map.get(key) == null;
        }

        public Object get(String key) {
            return map.get(key);
        }

        public String getString(String key) {
            return getString(key, "");
        }

        public String getString(String key, String def) {
            Object val = map.get(key);
            if (val == null) return def;
            return val.toString().trim();
        }

        public boolean getBoolean(String key, boolean def) {
            Object val = map.get(key);
            if (val == null) return def;
            if (val instanceof Boolean b) return b;
            String s = val.toString().trim().toLowerCase();
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
            return def;
        }

        public boolean getStrictBoolean(String key) throws ParseException {
            Object val = map.get(key);
            if (!(val instanceof Boolean b)) {
                throw new ParseException("Field '" + key + "' must be a JSON boolean");
            }
            return b;
        }

        public int getInt(String key, int def) {
            Object val = map.get(key);
            if (val == null) return def;
            if (val instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (Exception ignored) {
                return def;
            }
        }

        public long getLong(String key, long def) {
            Object val = map.get(key);
            if (val == null) return def;
            if (val instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(val.toString().trim());
            } catch (Exception ignored) {
                return def;
            }
        }

        /**
         * Strict integer accessor for fields that must be whole-number JSON integers (e.g. manifest
         * {@code generation}).  Throws {@link ParseException} if the stored value is a fractional
         * {@code Double}, a non-numeric type, or would overflow {@code int} range.
         */
        public int getStrictInt(String key) throws ParseException {
            Object val = map.get(key);
            if (val == null) throw new ParseException("Missing required integer field: " + key);
            if (val instanceof Double d) {
                throw new ParseException("Field '" + key + "' must be a JSON integer, got fractional value: " + d);
            }
            if (val instanceof Long l) {
                if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                    throw new ParseException("Field '" + key + "' overflows int range: " + l);
                }
                return l.intValue();
            }
            if (val instanceof Integer i) return i;
            throw new ParseException("Field '" + key + "' is not a JSON integer (type: " + val.getClass().getSimpleName() + ")");
        }

        /**
         * Strict long accessor — same contract as {@link #getStrictInt} but accepts the full
         * {@code long} range.  Fractional {@code Double} values and non-numeric types are rejected.
         */
        public long getStrictLong(String key) throws ParseException {
            Object val = map.get(key);
            if (val == null) throw new ParseException("Missing required integer field: " + key);
            if (val instanceof Double d) {
                throw new ParseException("Field '" + key + "' must be a JSON integer, got fractional value: " + d);
            }
            if (val instanceof Long l) return l;
            if (val instanceof Integer i) return i.longValue();
            throw new ParseException("Field '" + key + "' is not a JSON integer (type: " + val.getClass().getSimpleName() + ")");
        }

        public double getDouble(String key, double def) {
            Object val = map.get(key);
            if (val == null) return def;
            if (val instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(val.toString().trim());
            } catch (Exception ignored) {
                return def;
            }
        }

        public Set<String> keySet() {
            return map.keySet();
        }

        public Object find(String key) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
            for (Object val : map.values()) {
                if (val instanceof JsonObject childObj) {
                    Object res = childObj.find(key);
                    if (res != null) return res;
                } else if (val instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof JsonObject itemObj) {
                            Object res = itemObj.find(key);
                            if (res != null) return res;
                        }
                    }
                }
            }
            return null;
        }

        public String findString(String key, String def) {
            Object val = find(key);
            if (val == null) return def;
            return val.toString().trim();
        }

        public boolean findBoolean(String key, boolean def) {
            Object val = find(key);
            if (val == null) return def;
            if (val instanceof Boolean b) return b;
            String s = val.toString().trim().toLowerCase();
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
            return def;
        }

        public int findInt(String key, int def) {
            Object val = find(key);
            if (val == null) return def;
            if (val instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (Exception ignored) {
                return def;
            }
        }

        public long findLong(String key, long def) {
            Object val = find(key);
            if (val == null) return def;
            if (val instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(val.toString().trim());
            } catch (Exception ignored) {
                return def;
            }
        }

        public Map<String, Object> toMap() {
            return Collections.unmodifiableMap(map);
        }

        @Override
        public String toString() {
            return map.toString();
        }
    }

    public static JsonObject parseObject(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            Object parsed = new Parser(text.trim(), false).parseTopLevel();
            return parsed instanceof JsonObject obj ? obj : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static JsonObject parseObjectStrict(String text) throws ParseException {
        if (text == null) throw new ParseException("Input is null");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) throw new ParseException("Input is empty");
        Object parsed = new Parser(trimmed, true).parseTopLevel();
        if (parsed instanceof JsonObject obj) {
            return obj;
        }
        throw new ParseException("Expected JSON object but got: " + (parsed != null ? parsed.getClass().getSimpleName() : "null"));
    }

    private static class Parser {
        private final String src;
        private final int len;
        private final boolean strict;
        private int idx = 0;

        public Parser(String src, boolean strict) {
            this.src = src;
            this.len = src.length();
            this.strict = strict;
        }

        public Object parseTopLevel() {
            skipWhitespace();
            Object val = parseValue();
            skipWhitespace();
            if (idx < len) {
                throw new ParseException("Trailing unparsed characters at position " + idx + ": '" + src.substring(idx) + "'");
            }
            return val;
        }

        private void skipWhitespace() {
            while (idx < len) {
                char c = src.charAt(idx);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    idx++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (idx >= len) return '\0';
            return src.charAt(idx);
        }

        private Object parseValue() {
            skipWhitespace();
            if (idx >= len) throw new ParseException("Unexpected end of JSON input");
            char c = src.charAt(idx);

            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (strict) {
                // RFC 8259 strict: only lowercase literal starters permitted
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
            } else {
                if (c == 't' || c == 'T' || c == 'f' || c == 'F') return parseBoolean();
                if (c == 'n' || c == 'N') return parseNull();
            }
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();

            throw new ParseException("Unexpected token at position " + idx + ": '" + c + "'");
        }

        private JsonObject parseObject() {
            idx++; // consume '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();

            if (peek() == '}') {
                idx++;
                return new JsonObject(map);
            }

            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new ParseException("Expected string key in object at position " + idx + ", got '" + peek() + "'");
                }
                String key = parseString();
                skipWhitespace();

                if (peek() != ':') {
                    throw new ParseException("Expected ':' after key '" + key + "' at position " + idx);
                }
                idx++; // consume ':'

                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();

                char next = peek();
                if (next == '}') {
                    idx++;
                    return new JsonObject(map);
                } else if (next == ',') {
                    idx++;
                    skipWhitespace();
                    if (peek() == '}') {
                        if (strict) {
                            throw new ParseException("Trailing comma in object at position " + (idx - 1));
                        }
                        idx++; // tolerate trailing comma in lenient mode
                        return new JsonObject(map);
                    }
                } else {
                    throw new ParseException("Expected ',' or '}' in object at position " + idx + ", got '" + next + "'");
                }
            }
        }

        private List<Object> parseArray() {
            idx++; // consume '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();

            if (peek() == ']') {
                idx++;
                return list;
            }

            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWhitespace();

                char next = peek();
                if (next == ']') {
                    idx++;
                    return list;
                } else if (next == ',') {
                    idx++;
                    skipWhitespace();
                    if (peek() == ']') {
                        if (strict) {
                            throw new ParseException("Trailing comma in array at position " + (idx - 1));
                        }
                        idx++; // tolerate trailing comma in lenient mode
                        return list;
                    }
                } else {
                    throw new ParseException("Expected ',' or ']' in array at position " + idx + ", got '" + next + "'");
                }
            }
        }

        private String parseString() {
            idx++; // consume opening '"'
            StringBuilder sb = new StringBuilder();

            while (idx < len) {
                char c = src.charAt(idx++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    if (strict) {
                        throw new ParseException("Unescaped control character (0x" + Integer.toHexString(c) + ") in string literal at position " + (idx - 1));
                    }
                }
                if (c == '\\') {
                    if (idx >= len) throw new ParseException("Unterminated escape sequence at end of string");
                    char esc = src.charAt(idx++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (idx + 4 > len) throw new ParseException("Incomplete unicode escape at position " + idx);
                            String hex = src.substring(idx, idx + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                idx += 4;
                            } catch (NumberFormatException nfe) {
                                throw new ParseException("Invalid unicode escape: \\u" + hex);
                            }
                        }
                        default -> {
                            if (strict) {
                                throw new ParseException("Invalid escape sequence '\\" + esc + "' at position " + (idx - 1));
                            }
                            sb.append(esc);
                        }
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException("Unterminated string literal");
        }

        private Boolean parseBoolean() {
            String sub = src.substring(idx);
            // Strict mode: only lowercase JSON boolean literals
            if (sub.startsWith("true")) { idx += 4; return Boolean.TRUE; }
            if (sub.startsWith("false")) { idx += 5; return Boolean.FALSE; }
            if (strict) {
                throw new ParseException("Invalid boolean literal (strict mode requires lowercase 'true'/'false') at position " + idx);
            }
            // Lenient: also accept mixed case
            String subLower = sub.toLowerCase();
            if (subLower.startsWith("true")) { idx += 4; return Boolean.TRUE; }
            if (subLower.startsWith("false")) { idx += 5; return Boolean.FALSE; }
            throw new ParseException("Invalid boolean token at position " + idx);
        }

        private Object parseNull() {
            String sub = src.substring(idx);
            // Strict mode: only lowercase 'null'
            if (sub.startsWith("null")) { idx += 4; return null; }
            if (strict) {
                throw new ParseException("Invalid null literal (strict mode requires lowercase 'null') at position " + idx);
            }
            String subLower = sub.toLowerCase();
            if (subLower.startsWith("null")) { idx += 4; return null; }
            throw new ParseException("Invalid null token at position " + idx);
        }

        private Number parseNumber() {
            int start = idx;
            boolean negative = peek() == '-';
            if (negative) idx++;

            // Strict: reject bare '-' with no digits
            if (strict && (idx >= len || !Character.isDigit(src.charAt(idx)))) {
                throw new ParseException("Invalid number: expected digit after '-' at position " + idx);
            }

            // Consume integer digits
            int intStart = idx;
            while (idx < len && Character.isDigit(src.charAt(idx))) {
                idx++;
            }

            // Strict: reject leading zeroes (e.g. 026)
            if (strict) {
                int intLen = idx - intStart;
                if (intLen > 1 && src.charAt(intStart) == '0') {
                    throw new ParseException("Invalid number: leading zero not permitted at position " + intStart);
                }
            }

            boolean isFloat = false;
            if (peek() == '.') {
                isFloat = true;
                idx++; // consume '.'
                int fracStart = idx;
                while (idx < len && Character.isDigit(src.charAt(idx))) {
                    idx++;
                }
                // Strict: require at least one digit after '.'
                if (strict && idx == fracStart) {
                    throw new ParseException("Invalid number: at least one digit required after '.' at position " + (idx - 1));
                }
            }

            if (peek() == 'e' || peek() == 'E') {
                isFloat = true;
                idx++; // consume 'e'/'E'
                if (peek() == '+' || peek() == '-') idx++;
                int expStart = idx;
                while (idx < len && Character.isDigit(src.charAt(idx))) {
                    idx++;
                }
                // Strict: require at least one digit in exponent
                if (strict && idx == expStart) {
                    throw new ParseException("Invalid number: at least one digit required in exponent at position " + (idx - 1));
                }
            }

            String numStr = src.substring(start, idx);
            try {
                if (isFloat) {
                    return Double.parseDouble(numStr);
                } else {
                    return Long.parseLong(numStr);
                }
            } catch (NumberFormatException e) {
                throw new ParseException("Invalid number format: " + numStr);
            }
        }
    }
}
