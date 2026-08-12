package com.lynxis.orca.runtime.execution.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * The designer-JSON test DSL — what makes the twelfth compiler test get
 * written. Builds the publish payload the way a test reads: nodes, links, branches,
 * responses, then {@code json()}.
 */
final class WorkflowJson {

    private final long workflowId;
    private final List<String> nodes = new ArrayList<>();
    private final List<String> links = new ArrayList<>();
    private final List<String> branches = new ArrayList<>();
    private final List<String> responses = new ArrayList<>();

    private WorkflowJson(long workflowId) {
        this.workflowId = workflowId;
    }

    static WorkflowJson workflow(long workflowId) {
        return new WorkflowJson(workflowId);
    }

    WorkflowJson node(String uuid, String type, String name) {
        nodes.add("{\"uuid\":\"%s\",\"type\":\"%s\",\"name\":\"%s\"}".formatted(uuid, type, name));
        return this;
    }

    WorkflowJson ioNode(String uuid, String name, String mode, String topic) {
        nodes.add(("{\"uuid\":\"%s\",\"type\":\"INPUT_OUTPUT\",\"name\":\"%s\""
                + (mode == null ? "" : ",\"mode\":\"" + mode + "\"")
                + (topic == null ? "" : ",\"topic\":\"" + topic + "\"") + "}")
                .formatted(uuid, name));
        return this;
    }

    WorkflowJson waitNode(String uuid, String name, int seconds) {
        nodes.add("{\"uuid\":\"%s\",\"type\":\"DECISION\",\"name\":\"%s\",\"wait_seconds\":%d}"
                .formatted(uuid, name, seconds));
        return this;
    }

    WorkflowJson processNode(String uuid, String name, Long subflowId) {
        nodes.add(("{\"uuid\":\"%s\",\"type\":\"PROCESS\",\"name\":\"%s\""
                + (subflowId == null ? "" : ",\"subflow_id\":" + subflowId) + "}")
                .formatted(uuid, name));
        return this;
    }

    WorkflowJson link(String from, String to) {
        links.add("{\"from\":\"%s\",\"to\":\"%s\"}".formatted(from, to));
        return this;
    }

    WorkflowJson branch(String uuid, String decisionUuid, String name, int order, String conditionJson) {
        branches.add(("{\"uuid\":\"%s\",\"decision_uuid\":\"%s\",\"name\":\"%s\",\"order\":%d"
                + (conditionJson == null ? "" : ",\"condition\":" + conditionJson) + "}")
                .formatted(uuid, decisionUuid, name, order));
        return this;
    }

    WorkflowJson response(String uuid, String connectorUuid, Integer statusCode) {
        responses.add(("{\"uuid\":\"%s\",\"connector_uuid\":\"%s\""
                + (statusCode == null ? "" : ",\"status_code\":" + statusCode) + "}")
                .formatted(uuid, connectorUuid));
        return this;
    }

    /**
     * A condition array with one group of one comparison — the common estate shape. The
     * field side carries a selector_id: that is what makes it a VARIABLE read (a blank
     * selector_id would make the stored text a literal operand — Go's else branch).
     */
    static String simpleCondition(String selectorPath, String operator, String literal) {
        return """
                [{"logical_operator":"","child_condition":[{"logical_operator":"",\
                "comparison_operator":"%s",\
                "field":{"value":"%s","selector_id":"sel-1"},\
                "value":{"value":"%s","selector_id":""}}]}]"""
                .formatted(operator, selectorPath, literal);
    }

    String json() {
        return """
                {"workflow_id":%d,"workflow_uuid":"wf-%d","name":"test workflow %d",
                 "nodes":[%s],
                 "node_links":[%s],
                 "branches":[%s],
                 "responses":[%s]}"""
                .formatted(workflowId, workflowId, workflowId,
                        String.join(",", nodes), String.join(",", links),
                        String.join(",", branches), String.join(",", responses));
    }
}
