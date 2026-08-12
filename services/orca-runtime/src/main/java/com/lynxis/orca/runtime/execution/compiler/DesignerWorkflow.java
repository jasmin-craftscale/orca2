package com.lynxis.orca.runtime.execution.compiler;

import com.lynxis.orca.runtime.execution.compiler.CompileException.Invariant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The publish payload — one workflow container as the designer authored it. This is the
 * compiler's ONLY input (D2: the definition store is the engine's, the designer's model is
 * this JSON; the live DB is never read at compile time — {@code DbReader} stays migration
 * tooling).
 *
 * <p>Shape:
 * <pre>{@code
 * {
 *   "workflow_id": 31675,
 *   "workflow_uuid": "…",
 *   "name": "PLT In-Gate",
 *   "nodes": [
 *     {"uuid": "…", "type": "START",        "name": "Start"},
 *     {"uuid": "…", "type": "INPUT_OUTPUT", "name": "Portal scan", "mode": "INPUT",  "topic": "…"},
 *     {"uuid": "…", "type": "INPUT_OUTPUT", "name": "Gate arm",    "mode": "OUTPUT", "topic": "…"},
 *     {"uuid": "…", "type": "DECISION",     "name": "Wait 30s",    "wait_seconds": 30},
 *     {"uuid": "…", "type": "MANUAL_INPUT", "name": "Clerk screen"},
 *     {"uuid": "…", "type": "PROCESS",      "name": "Best match",  "subflow_id": 31699},
 *     {"uuid": "…", "type": "TERMINATOR",   "name": "End"}
 *   ],
 *   "branches":  [{"uuid": "…", "decision_uuid": "…", "name": "loaded", "order": 1, "condition": [...]}],
 *   "responses": [{"uuid": "…", "connector_uuid": "…", "status_code": 200}],
 *   "node_links": [{"from": "…", "to": "…", "position": 0}]
 * }
 * }</pre>
 */
record DesignerWorkflow(
        long workflowId,
        String workflowUuid,
        String name,
        Map<String, Node> nodes,
        List<Link> links,
        Map<String, Branch> branches,
        Map<String, Response> responses) {

    record Node(String uuid, String type, String name, String mode, String topic,
            Integer waitSeconds, Long subflowId) {
    }

    record Link(String from, String to, int position) {
    }

    /** A DECISION_RESULT: the branch condition hanging off a DECISION. */
    record Branch(String uuid, String decisionUuid, String name, int order, String conditionJson) {
    }

    /** A CONNECTOR_RESPONSE: the per-status outcome hanging off a CONNECTOR. */
    record Response(String uuid, String connectorUuid, Integer statusCode) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static DesignerWorkflow parse(String designerJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(designerJson);
        } catch (RuntimeException e) {
            throw new CompileException(Invariant.MALFORMED_INPUT, "publish payload is not valid JSON");
        }
        if (!root.isObject() || !root.has("workflow_id") || !root.path("nodes").isArray()) {
            throw new CompileException(Invariant.MALFORMED_INPUT,
                    "publish payload must be an object with workflow_id and nodes[]");
        }

        Map<String, Node> nodes = new LinkedHashMap<>();
        for (JsonNode n : root.path("nodes")) {
            String uuid = required(n, "uuid");
            if (nodes.containsKey(uuid)) {
                throw new CompileException(Invariant.MALFORMED_INPUT, "duplicate node uuid " + uuid);
            }
            nodes.put(uuid, new Node(
                    uuid,
                    required(n, "type"),
                    n.path("name").asString(""),
                    text(n, "mode"),
                    text(n, "topic"),
                    n.has("wait_seconds") ? n.path("wait_seconds").asInt() : null,
                    n.has("subflow_id") ? n.path("subflow_id").asLong() : null));
        }

        List<Link> links = new ArrayList<>();
        for (JsonNode l : root.path("node_links")) {
            links.add(new Link(required(l, "from"), required(l, "to"), l.path("position").asInt(0)));
        }

        Map<String, Branch> branches = new LinkedHashMap<>();
        for (JsonNode b : root.path("branches")) {
            String uuid = required(b, "uuid");
            JsonNode condition = b.path("condition");
            branches.put(uuid, new Branch(
                    uuid,
                    required(b, "decision_uuid"),
                    b.path("name").asString(""),
                    b.path("order").asInt(0),
                    condition.isMissingNode() || condition.isNull() ? null : condition.toString()));
        }

        Map<String, Response> responses = new LinkedHashMap<>();
        for (JsonNode r : root.path("responses")) {
            String uuid = required(r, "uuid");
            responses.put(uuid, new Response(
                    uuid,
                    required(r, "connector_uuid"),
                    r.has("status_code") && !r.path("status_code").isNull()
                            ? r.path("status_code").asInt() : null));
        }

        // LinkedHashMaps kept as-is (not Map.copyOf): authored order IS the deterministic
        // emission order (T2), and copyOf would scramble it.
        return new DesignerWorkflow(
                root.path("workflow_id").asLong(),
                root.path("workflow_uuid").asString(""),
                root.path("name").asString(""),
                java.util.Collections.unmodifiableMap(nodes),
                List.copyOf(links),
                java.util.Collections.unmodifiableMap(branches),
                java.util.Collections.unmodifiableMap(responses));
    }

    private static String required(JsonNode node, String field) {
        String v = node.path(field).asString("");
        if (v.isBlank()) {
            throw new CompileException(Invariant.MALFORMED_INPUT,
                    "missing required field '" + field + "' in " + node);
        }
        return v;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asString("") : null;
    }
}
