package com.lynxis.orca.runtime.execution.selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * encoding/json semantics for the evaluator's internal parse/serialize round-trips.
 * Parsing decodes numbers to Double (Go's interface{} unmarshal decodes to float64 — the
 * single most behavior-relevant fact of the whole port); serializing sorts object keys and
 * HTML-escapes strings, because Go does, and these bytes get embedded into selector strings.
 */
final class GoJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoJson() {
    }

    /** json.Unmarshal into interface{} — or null if the input is not valid JSON. */
    static Object parse(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return INVALID;
        }
        if (node == null || node.isMissingNode()) {
            return INVALID;
        }
        return fromNode(node);
    }

    /** Sentinel distinguishing "parsed to JSON null" from "did not parse". */
    static final Object INVALID = new Object() {
        @Override
        public String toString() {
            return "<invalid json>";
        }
    };

    static boolean isValid(String s) {
        return parse(s) != INVALID;
    }

    private static Object fromNode(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(child -> list.add(fromNode(child)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), fromNode(e.getValue())));
            return map;
        }
        return INVALID;
    }

    /** json.Marshal — sorted keys, HTML escaping, Go float formatting. */
    static String marshal(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String s) {
            writeString(sb, s);
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (value instanceof Double d) {
            sb.append(GoFmt.jsonFloat(d));
            return;
        }
        if (value instanceof Integer || value instanceof Long) {
            sb.append(value);
            return;
        }
        if (value instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, l.get(i));
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(value));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
