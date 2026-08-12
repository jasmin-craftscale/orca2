package com.lynxis.orca.runtime.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The escaping rules, which are the whole reason this is a port rather than a string replace.
 */
class UrlTemplateTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("a value cannot invent a path segment")
    void pathSeparatorIsEscaped() {
        String url = UrlTemplate.resolve("https://tos/api/visit/{id}/moves",
                params("id", "../../admin/delete"));

        assertThat(url).isEqualTo("https://tos/api/visit/..%2F..%2Fadmin%2Fdelete/moves");
    }

    @Test
    @DisplayName("a value cannot invent a query parameter")
    void queryDelimitersAreEscaped() {
        String url = UrlTemplate.resolve("https://tos/api?container={cn}",
                params("cn", "ABC&admin=true"));

        assertThat(url).isEqualTo("https://tos/api?container=ABC%26admin%3Dtrue");
    }

    @Test
    @DisplayName("escaping follows position: space is %20 in a path and + in a query")
    void escapingIsPositionAware() {
        assertThat(UrlTemplate.resolve("https://tos/{a}", params("a", "x y")))
                .isEqualTo("https://tos/x%20y");
        assertThat(UrlTemplate.resolve("https://tos?a={a}", params("a", "x y")))
                .isEqualTo("https://tos?a=x+y");
    }

    @Test
    @DisplayName("Go escapes * and leaves ~ alone; Java's URLEncoder does the opposite")
    void goQueryEscapingIsMatched() {
        assertThat(UrlTemplate.resolve("https://tos?a={a}", params("a", "*~")))
                .isEqualTo("https://tos?a=%2A~");
    }

    @Test
    @DisplayName("a base-URL placeholder at offset 0 keeps its scheme and host")
    void absoluteBaseUrlIsSubstitutedRaw() {
        String url = UrlTemplate.resolve("{device_url}/api/scan",
                params("device_url", "https://lane-3.local:8443"));

        assertThat(url).isEqualTo("https://lane-3.local:8443/api/scan");
    }

    @Test
    @DisplayName("an absolute URL anywhere BUT offset 0 is still escaped")
    void theRawSubstitutionHoleIsNoWiderThanGoMakesIt() {
        String url = UrlTemplate.resolve("https://tos/{target}", params("target", "https://evil"));

        // The colon survives — Go's PathEscape leaves ':' alone inside a segment. The slashes
        // are what matter: escaped, so the value stays one segment and cannot become a host.
        assertThat(url).isEqualTo("https://tos/https:%2F%2Fevil");
    }

    @Test
    @DisplayName("unmatched keys become sorted query parameters, so the URL is deterministic")
    void unmatchedKeysAppendInSortedOrder() {
        assertThat(UrlTemplate.resolve("https://tos/api", params("z", "1", "a", "2")))
                .isEqualTo("https://tos/api?a=2&z=1");
        assertThat(UrlTemplate.resolve("https://tos/api?x=0", params("z", "1", "a", "2")))
                .isEqualTo("https://tos/api?x=0&a=2&z=1");
    }
}
