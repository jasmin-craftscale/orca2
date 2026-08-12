package com.lynxis.orca.runtime.integration.designer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One container of a {@link PublishedGraph}, written as the designer-JSON publish payload the
 * compiler consumes — the same assembly the migration exporter performs, so a payload built
 * here and a payload exported there describe the same workflow.
 */
final class PublishPayload {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PublishPayload() {
    }

    static String of(PublishedGraph graph, PublishedGraph.Container container) {
        ObjectNode root = JSON.createObjectNode();
        root.put("workflow_id", container.id());
        root.put("workflow_uuid", container.uuid());
        root.put("name", container.name());

        ArrayNode nodes = root.putArray("nodes");
        graph.nodes.values().stream()
                .filter(n -> n.container() == container.id())
                .forEach(n -> {
                    ObjectNode node = nodes.addObject();
                    node.put("uuid", n.uuid());
                    node.put("type", n.type());
                    node.put("name", n.name());
                    if ("INPUT_OUTPUT".equals(n.type()) && n.mode() != null && !n.mode().isBlank()) {
                        node.put("mode", n.mode());
                        // Direction-split topics: an effect and a wait must never share one,
                        // or a device's own output resumes the step that emitted it.
                        node.put("topic", "INPUT".equalsIgnoreCase(n.mode())
                                ? "orca-device-input" : "orca-device-io");
                    }
                    if (n.waitSeconds() != null) {
                        node.put("wait_seconds", n.waitSeconds());
                    }
                    if (n.subflowCall() != null) {
                        node.put("subflow_id", n.subflowCall());
                    }
                });

        ArrayNode branches = root.putArray("branches");
        graph.branches.values().stream()
                .filter(b -> graph.containerOf(b.uuid()) == container.id())
                .forEach(b -> {
                    ObjectNode branch = branches.addObject();
                    branch.put("uuid", b.uuid());
                    branch.put("decision_uuid", b.decisionUuid());
                    branch.put("name", b.name());
                    branch.put("order", b.order());
                    setCondition(branch, b.condition());
                });

        ArrayNode responses = root.putArray("responses");
        graph.responses.values().stream()
                .filter(r -> graph.containerOf(r.uuid()) == container.id())
                .forEach(r -> {
                    ObjectNode response = responses.addObject();
                    response.put("uuid", r.uuid());
                    response.put("connector_uuid", r.connectorUuid());
                    if (r.statusCode() != null) {
                        response.put("status_code", r.statusCode());
                    }
                });

        ArrayNode links = root.putArray("node_links");
        graph.links.stream()
                .filter(l -> graph.containerOf(l.from()) == container.id())
                .forEach(l -> {
                    ObjectNode link = links.addObject();
                    link.put("from", l.from());
                    link.put("to", l.to());
                    link.put("position", l.position());
                });

        return JSON.writeValueAsString(root);
    }

    /**
     * Stored condition text embeds as parsed JSON when it parses, and as the raw string when
     * it does not — so the COMPILER refuses malformed text by name. Nothing is sanitised on
     * the way through: a payload that hides a defect validates clean and deploys broken.
     */
    private static void setCondition(ObjectNode branch, String condition) {
        if (condition == null || condition.isBlank()) {
            return;
        }
        try {
            branch.set("condition", JSON.readTree(condition));
        } catch (RuntimeException notJson) {
            branch.put("condition", condition);
        }
    }
}
