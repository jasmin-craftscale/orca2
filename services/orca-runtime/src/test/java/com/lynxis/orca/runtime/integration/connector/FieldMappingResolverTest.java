package com.lynxis.orca.runtime.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The request body a connector sends. Each test names a behaviour the Go executor really has,
 * because the ones that look like bugs are exactly the ones a well-meaning rewrite would
 * "fix" into a different outbound request.
 */
class FieldMappingResolverTest {

    /** Records what each selector call was asked, so the uuid asymmetry can be asserted. */
    private static final class RecordingSelectors implements FieldMappingResolver.Selectors {
        private final Map<String, Object> answers;
        private final List<String> uuidsSeen = new ArrayList<>();

        RecordingSelectors(Map<String, Object> answers) {
            this.answers = answers;
        }

        @Override
        public Object resolve(String value, String selectorId, String workflowExecutionUuid) {
            uuidsSeen.add(workflowExecutionUuid);
            return answers.get(value);
        }
    }

    private static FieldMappingResolver resolver(Map<String, Object> answers) {
        return new FieldMappingResolver(new RecordingSelectors(answers), "visit-uuid");
    }

    private static Map<String, Object> resolve(String json, Map<String, Object> answers) {
        return resolver(answers).resolve(MappingField.parseAll(json));
    }

    @Test
    @DisplayName("a literal and a selector both land in the body")
    void resolvesValueAndSelectorFields() {
        Map<String, Object> body = resolve("""
                [{"key":"kind","type":"string","source":"VALUE","value":"scan"},
                 {"key":"plate","type":"string","source":"SELECTOR","value":"$.a.plate"}]
                """, Map.of("$.a.plate", "AB-123"));

        assertThat(body).containsExactly(Map.entry("kind", "scan"), Map.entry("plate", "AB-123"));
    }

    @Test
    @DisplayName("an unresolvable selector DROPS its key rather than sending null")
    void unresolvedSelectorDropsTheField() {
        Map<String, Object> body = resolve("""
                [{"key":"kind","type":"string","source":"VALUE","value":"scan"},
                 {"key":"plate","type":"string","source":"SELECTOR","value":"$.missing"}]
                """, Map.of());

        // Absent, not null: a receiving system can and does treat those differently.
        assertThat(body).containsOnlyKeys("kind");
    }

    @Test
    @DisplayName("top-level selectors resolve with an empty uuid, nested ones with the visit's")
    void reproducesTheUuidAsymmetry() {
        RecordingSelectors selectors = new RecordingSelectors(Map.of("$.x", "v", "$.y", "w"));
        new FieldMappingResolver(selectors, "visit-uuid").resolve(MappingField.parseAll("""
                [{"key":"top","type":"string","source":"SELECTOR","value":"$.x"},
                 {"key":"wrap","type":"object","fields":[
                    {"key":"inner","type":"string","source":"SELECTOR","value":"$.y"}]}]
                """));

        assertThat(selectors.uuidsSeen).containsExactly("", "visit-uuid");
    }

    @Test
    @DisplayName("an object resolves from its children only — its own selector is ignored")
    void objectIgnoresItsOwnSelector() {
        // This is the shape two estate connectors really carry. Go's switch dispatches on
        // type before it ever looks at source, so the selector never fires and the key
        // resolves to {}. It looks like an authoring mistake; reproducing it is still right,
        // because changing it changes what those two connectors send.
        Map<String, Object> body = resolve("""
                [{"key":"scan","type":"object","source":"SELECTOR","value":"$.a.scan"}]
                """, Map.of("$.a.scan", "would-have-been-this"));

        assertThat(body).containsEntry("scan", Map.of());
    }

    @Test
    @DisplayName("an array with neither items nor fields resolves to null, not []")
    void emptyArrayResolvesToNull() {
        Map<String, Object> body = resolve("""
                [{"key":"moves","type":"array","source":"SELECTOR","value":"$.a.moves"}]
                """, Map.of("$.a.moves", "ignored"));

        assertThat(body).containsKey("moves");
        assertThat(body.get("moves")).isNull();
    }

    @Test
    @DisplayName("array elements come from items.fields, each wrapped in its own key")
    void resolvesArrayElements() {
        Map<String, Object> body = resolve("""
                [{"key":"moves","type":"array","items":{"type":"object","fields":[
                    {"key":"id","type":"string","source":"VALUE","value":"m1"},
                    {"key":"ref","type":"string","source":"SELECTOR","value":"$.a.ref"}]}}]
                """, Map.of("$.a.ref", "R9"));

        assertThat(body.get("moves"))
                .isEqualTo(List.of(Map.of("id", "m1"), Map.of("ref", "R9")));
    }

    @Test
    @DisplayName("omit_empty drops an empty value; without it the empty value is sent")
    void omitEmptyGatesEmptyValues() {
        String fields = """
                [{"key":"note","type":"string","source":"VALUE","value":"","omit_empty":%s}]
                """;
        assertThat(resolve(fields.formatted("true"), Map.of())).isEmpty();
        assertThat(resolve(fields.formatted("false"), Map.of())).containsEntry("note", "");
    }

    @Test
    @DisplayName("a failed conversion sends null — only a failed SELECTOR drops the key")
    void failedConversionSendsNullWhereFailedSelectorDrops() {
        // Worth pinning because the two look interchangeable and are not. Go skips a field
        // whose selector ERRORS, but a value that resolves and then fails its type conversion
        // becomes nil and is still written. So these two authoring mistakes produce different
        // outbound requests, and a connector's receiver sees the difference.
        assertThat(resolve("""
                [{"key":"count","type":"number","source":"VALUE","value":"not-a-number"}]
                """, Map.of()))
                .containsOnlyKeys("count")
                .containsEntry("count", null);

        assertThat(resolve("""
                [{"key":"count","type":"number","source":"SELECTOR","value":"$.missing"}]
                """, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Go's boolean vocabulary is accepted; anything else drops the field")
    void goBooleanParsing() {
        assertThat(FieldMappingResolver.handleString("1", "boolean")).isEqualTo(true);
        assertThat(FieldMappingResolver.handleString("F", "bool")).isEqualTo(false);
        assertThat(FieldMappingResolver.handleString("True", "boolean")).isEqualTo(true);
        // Boolean.parseBoolean would quietly answer false here; Go reports failure, so the
        // field disappears instead of arriving as a confident lie.
        assertThat(FieldMappingResolver.handleString("yes", "boolean")).isNull();
    }

    @Test
    @DisplayName("float32 cannot match a JSON number, exactly as in Go")
    void float32DoesNotMatchJsonNumbers() {
        assertThat(FieldMappingResolver.handleString(1.5d, "float32")).isNull();
        assertThat(FieldMappingResolver.handleString("1.5", "float32")).isEqualTo(1.5f);
        assertThat(FieldMappingResolver.handleString(1.5d, "float64")).isEqualTo(1.5d);
    }

    @Test
    @DisplayName("an unported shape refuses loudly instead of silently omitting a field")
    void unsupportedShapesRefuse() {
        assertThatThrownBy(() -> resolve("""
                [{"key":"a","type":"string","source":"VALUE","value":"x",
                  "include_when":{"selector":"$.b"}}]
                """, Map.of()))
                .isInstanceOf(UnsupportedConnectorShapeException.class)
                .hasMessageContaining("include_when");

        assertThatThrownBy(() -> resolve("""
                [{"key":"a","type":"cdata-xml","fields":[]}]
                """, Map.of()))
                .isInstanceOf(UnsupportedConnectorShapeException.class)
                .hasMessageContaining("CDATA");
    }

    @Test
    @DisplayName("XML attribute children become attributes plus a #text entry")
    void attributeChildrenBecomeAttributedElement() {
        Map<String, Object> body = resolve("""
                [{"key":"tag","type":"string","source":"VALUE","value":"text","fields":[
                    {"key":"-id","type":"string","source":"VALUE","value":"5"}]}]
                """, Map.of());

        assertThat(body.get("tag")).isEqualTo(Map.of("-id", "5", "#text", "text"));
    }
}
