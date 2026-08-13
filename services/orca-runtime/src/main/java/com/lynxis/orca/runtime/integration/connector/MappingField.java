package com.lynxis.orca.runtime.integration.connector;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One entry of a connector's {@code mapped_req_payload} — the authored shape of a request
 * body, before anything is resolved.
 *
 * <p>Parsed by hand rather than bound, because the stored JSON is not stable: {@code fields}
 * and {@code items} are sometimes absent, sometimes {@code null}, sometimes {@code []}, and a
 * binder that treats those three as different produces three different resolved bodies.
 */
record MappingField(
        String key,
        String type,
        String source,
        String value,
        String selectorId,
        boolean omitEmpty,
        boolean hasIncludeGate,
        List<MappingField> fields,
        Items items) {

    /** The {@code items} block of an array field: its own type and its element fields. */
    record Items(String type, List<MappingField> fields) {
    }

    static List<MappingField> parseAll(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root = ConnectorJson.MAPPER.readTree(json);
        return fieldsOf(root);
    }

    private static List<MappingField> fieldsOf(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<MappingField> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(of(node));
        }
        return List.copyOf(out);
    }

    private static MappingField of(JsonNode node) {
        JsonNode itemsNode = node.path("items");
        Items items = itemsNode.isObject()
                ? new Items(itemsNode.path("type").asString(""), fieldsOf(itemsNode.path("fields")))
                : null;
        return new MappingField(
                node.path("key").asString(""),
                node.path("type").asString(""),
                node.path("source").asString(""),
                node.path("value").asString(""),
                node.path("selector_id").asString(""),
                node.path("omit_empty").asBoolean(false),
                node.has("include_when") && !node.path("include_when").isNull(),
                fieldsOf(node.path("fields")),
                items);
    }

    /** XML attribute children — keys carrying the {@code -} marker. */
    List<MappingField> attributeChildren() {
        List<MappingField> out = new ArrayList<>();
        for (MappingField f : fields) {
            if (f.key().startsWith(ConnectorJson.ATTRIBUTE_PREFIX)) {
                out.add(f);
            }
        }
        return out;
    }
}
