package com.lynxis.orca.runtime.execution.selector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Go's fmt/strconv formatting semantics, ported byte-for-byte for the outputs the selector
 * evaluator embeds in strings. This is where a "working" port would silently diverge:
 * Java's {@code Double.toString(5.0)} is {@code "5.0"} where Go's {@code %v} is {@code "5"},
 * and every such string ends up inside selector replacements and dataset values.
 */
final class GoFmt {

    private GoFmt() {
    }

    /** fmt.Sprintf("%v", value) for the JSON-decoded value universe. */
    static String v(Object value) {
        if (value == null) {
            return "<nil>";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Double d) {
            return formatG(d);
        }
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> m) {
            // Go prints maps with sorted keys: map[k1:v1 k2:v2]
            TreeMap<String, Object> sorted = new TreeMap<>();
            m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
            StringBuilder sb = new StringBuilder("map[");
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(' ');
                }
                first = false;
                sb.append(e.getKey()).append(':').append(v(e.getValue()));
            }
            return sb.append(']').toString();
        }
        if (value instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(v(l.get(i)));
            }
            return sb.append(']').toString();
        }
        return value.toString();
    }

    /** strconv.Quote — a Go double-quoted string literal. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** fmt.Sprintf("%f", d) — fixed six decimals, Go's HandleString("string") float form. */
    static String f6(double d) {
        return String.format(Locale.ROOT, "%f", d);
    }

    /**
     * strconv.FormatFloat(d, 'g', -1, 64): the shortest round-tripping decimal, scientific
     * form only when the exponent is < -4 or >= 21. This is %v's float64 shape AND (with the
     * same digits) encoding/json's — both lean on the shortest-representation digits.
     */
    static String formatG(double d) {
        Digits dg = shortestDigits(d);
        if (dg.special != null) {
            return dg.special;
        }
        int exp = dg.decimalExponent;
        if (exp < -4 + 1 || exp > 21) {
            // Go's rule is on the exponent of the FIRST digit: x = 0.digits * 10^exp;
            // scientific when exp-1 < -4 or exp-1 >= 21.
            return sci(dg);
        }
        return fixed(dg);
    }

    /** encoding/json's float64 form: 'f' shortest unless the magnitude forces 'e'. */
    static String jsonFloat(double d) {
        double abs = Math.abs(d);
        if (abs != 0 && (abs < 1e-6 || abs >= 1e21)) {
            return sci(shortestDigits(d));
        }
        Digits dg = shortestDigits(d);
        return dg.special != null ? dg.special : fixed(dg);
    }

    private record Digits(boolean negative, String digits, int decimalExponent, String special) {
    }

    /** The shortest round-trip digits, mined from Java's own shortest repr. */
    private static Digits shortestDigits(double d) {
        if (Double.isNaN(d)) {
            return new Digits(false, "", 0, "NaN");
        }
        if (Double.isInfinite(d)) {
            return new Digits(d < 0, "", 0, d < 0 ? "-Inf" : "+Inf");
        }
        if (d == 0) {
            return new Digits(1 / d < 0, "0", 1, null);
        }
        String s = Double.toString(Math.abs(d)); // e.g. "3.14", "1.0E10", "4.9E-324"
        int ePos = s.indexOf('E');
        int javaExp = 0;
        if (ePos >= 0) {
            javaExp = Integer.parseInt(s.substring(ePos + 1));
            s = s.substring(0, ePos);
        }
        int dot = s.indexOf('.');
        String digits = s.substring(0, dot) + s.substring(dot + 1);
        int intLen = dot;
        // Strip leading zeros (e.g. "0.5" -> digits "05")
        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') {
            lead++;
            intLen--;
        }
        digits = digits.substring(lead);
        // Strip trailing zeros — Java always emits at least "x.0"
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        return new Digits(d < 0, digits, intLen + javaExp, null);
    }

    private static String fixed(Digits dg) {
        StringBuilder sb = new StringBuilder();
        if (dg.negative) {
            sb.append('-');
        }
        String digits = dg.digits;
        int exp = dg.decimalExponent;
        if (exp <= 0) {
            sb.append("0.");
            sb.append("0".repeat(-exp));
            sb.append(digits);
        } else if (exp >= digits.length()) {
            sb.append(digits);
            sb.append("0".repeat(exp - digits.length()));
        } else {
            sb.append(digits, 0, exp).append('.').append(digits, exp, digits.length());
        }
        return sb.toString();
    }

    private static String sci(Digits dg) {
        if (dg.special != null) {
            return dg.special;
        }
        StringBuilder sb = new StringBuilder();
        if (dg.negative) {
            sb.append('-');
        }
        String digits = dg.digits;
        sb.append(digits.charAt(0));
        if (digits.length() > 1) {
            sb.append('.').append(digits, 1, digits.length());
        }
        int e = dg.decimalExponent - 1;
        sb.append('e').append(e < 0 ? '-' : '+');
        int abs = Math.abs(e);
        if (abs < 10) {
            sb.append('0');
        }
        return sb.append(abs).toString();
    }

    /** strconv.ParseBool's exact accepted set. */
    static Boolean parseBool(String s) {
        return switch (s) {
            case "1", "t", "T", "true", "TRUE", "True" -> Boolean.TRUE;
            case "0", "f", "F", "false", "FALSE", "False" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** strconv.ParseFloat semantics: no surrounding whitespace, no Java 'd'/'f' suffixes. */
    static Double parseFloat(String s) {
        if (s.isEmpty()) {
            return null;
        }
        String body = s;
        boolean neg = false;
        if (body.startsWith("+") || body.startsWith("-")) {
            neg = body.startsWith("-");
            body = body.substring(1);
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.equals("inf") || lower.equals("infinity")) {
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (lower.equals("nan")) {
            return Double.NaN;
        }
        if (!body.matches("(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")) {
            return null;
        }
        double v = Double.parseDouble(body);
        return neg ? -v : v;
    }

    /** strconv.Atoi (64-bit int on the platforms ORCA ships to). */
    static Long parseInt(String s) {
        if (s.isEmpty() || !s.matches("[+-]?\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
