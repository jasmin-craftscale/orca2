package com.lynxis.orca.runtime.integration.connector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code req_path} with its {@code {placeholder}}s filled in — a port of the Go executor's
 * {@code utils.ResolveUrlTemplate}, escaping rules included.
 *
 * <p><b>The escaping is the point, not a detail.</b> A resolved value is data, and data that
 * reaches a URL unescaped can add a path segment, a query parameter or a fragment that the
 * connector's author never wrote — a container number containing {@code ../} or {@code &} is
 * enough. So substitution is position-aware: path-escaped before the {@code ?}, query-escaped
 * after it. Keys with no matching placeholder become query parameters, sorted, so the same
 * inputs always produce the same URL.
 *
 * <p>One documented hole is kept deliberately: a placeholder at offset 0 whose value is an
 * absolute http(s) URL is substituted raw, because that is how {@code {device_url}/api}
 * templates carry a scheme and host. Narrowing it would break every device connector; it is
 * reproduced exactly and no wider.
 */
final class UrlTemplate {

    private UrlTemplate() {
    }

    static String resolve(String template, Map<String, Object> params) {
        if (template == null) {
            return "";
        }
        String result = template;
        int queryStart = template.indexOf('?');
        List<String> extraQuery = new ArrayList<>();

        for (Map.Entry<String, Object> e : params.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            int idx = template.indexOf(placeholder);
            if (idx >= 0) {
                if (idx == 0 && isAbsoluteHttpUrl(value)) {
                    result = result.replace(placeholder, value);
                    continue;
                }
                String escaped = queryStart >= 0 && idx > queryStart
                        ? queryEscape(value) : pathEscape(value);
                result = result.replace(placeholder, escaped);
            } else {
                extraQuery.add(queryEscape(e.getKey()) + "=" + queryEscape(value));
            }
        }

        if (!extraQuery.isEmpty()) {
            Collections.sort(extraQuery);
            result = result + (result.contains("?") ? "&" : "?") + String.join("&", extraQuery);
        }
        return result;
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Go's {@code url.QueryEscape}: {@code +} for space, and only {@code -_.~} exempt.
     * Java's {@link URLEncoder} disagrees on exactly two characters — it leaves {@code *}
     * alone where Go escapes it, and escapes {@code ~} where Go does not — so both are
     * corrected rather than left as a silent one-character divergence in an outbound URL.
     */
    private static String queryEscape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * Go's {@code url.PathEscape}. It differs from query escaping in ways that matter here:
     * a space becomes {@code %20} rather than {@code +}, and the sub-delimiters legal in a
     * path segment are left as themselves. {@code /} IS escaped — that is the whole defence
     * against a value inventing a path segment.
     */
    private static String pathEscape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (isPathUnescaped(c)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static boolean isPathUnescaped(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        return switch (c) {
            // unreserved, then exactly the sub-delims Go permits inside ONE path segment.
            // Note what is absent: '/', ';', ',' and '?' are escaped, which is what stops a
            // value from inventing a segment or a query.
            case '-', '_', '.', '~', '$', '&', '+', ':', '=', '@' -> true;
            default -> false;
        };
    }
}
