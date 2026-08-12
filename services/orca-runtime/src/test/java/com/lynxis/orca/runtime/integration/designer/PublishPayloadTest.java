package com.lynxis.orca.runtime.integration.designer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The payload assembly, and the coverage guarantee around it.
 *
 * <p>The assembly is easy; the coverage is the point. The bug this port exists to avoid is a
 * node type with no table entry, which does not fail — the emitter hops through the missing
 * node and the workflow compiles smaller than it was drawn.
 */
class PublishPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every node type the product has. A new one must be added here AND to NodeSource. */
    private static final Set<String> EVERY_NODE_TYPE = Set.of(
            "START", "INPUT_OUTPUT", "MANUAL_INPUT", "DISPLAY", "TERMINATOR", "NOTIFICATION",
            "MAP_ITERATOR", "DECISION", "CONNECTOR", "PROCESS");

    /** The types read outside {@link NodeSource#ALL}, each because it needs something extra. */
    private static final Set<String> READ_SEPARATELY = Set.of("DECISION", "CONNECTOR", "PROCESS");

    @Test
    @DisplayName("every node type is sourced — a missing one would vanish silently, not fail")
    void everyNodeTypeHasASource() {
        Set<String> sourced = new HashSet<>(READ_SEPARATELY);
        NodeSource.ALL.forEach(s -> sourced.add(s.orcaType()));

        assertThat(sourced).containsExactlyInAnyOrderElementsOf(EVERY_NODE_TYPE);
    }

    @Test
    @DisplayName("every plain node type is sourced for subflows as well as workflows")
    void subflowsAreCoveredToo() {
        // The exact regression the migration reader carried: subflows loaded only start,
        // terminator, decision and connector, so display, IO, manual-input, notification and
        // map-iterator nodes were absent from every subflow in the estate — and nothing failed.
        Set<String> workflowTypes = typesOf(false);
        Set<String> subflowTypes = typesOf(true);

        assertThat(subflowTypes).containsExactlyInAnyOrderElementsOf(workflowTypes);
    }

    private static Set<String> typesOf(boolean subflow) {
        Set<String> out = new HashSet<>();
        NodeSource.ALL.stream().filter(s -> s.subflow() == subflow)
                .forEach(s -> out.add(s.orcaType()));
        return out;
    }

    @Test
    @DisplayName("subflow container ids are offset, so the two id sequences cannot collide")
    void subflowIdsAreNamespaced() {
        // workflow 5 and subflow 5 are different containers; without the offset their nodes
        // merge into one graph and the payload silently describes both.
        PublishedGraph g = new PublishedGraph();
        g.containers.put(5L, new PublishedGraph.Container(5L, "wf-uuid", "Workflow", false));
        g.containers.put(5L + NodeSource.SUBFLOW_OFFSET, new PublishedGraph.Container(
                5L + NodeSource.SUBFLOW_OFFSET, "sub-uuid", "Subflow", true));
        g.nodes.put("a", new PublishedGraph.Node("a", "START", "s", 5L, null, null, null));
        g.nodes.put("b", new PublishedGraph.Node("b", "START", "s",
                5L + NodeSource.SUBFLOW_OFFSET, null, null, null));

        assertThat(uuidsIn(PublishPayload.of(g, g.containerByUuid("wf-uuid")))).containsExactly("a");
        assertThat(uuidsIn(PublishPayload.of(g, g.containerByUuid("sub-uuid")))).containsExactly("b");
    }

    @Test
    @DisplayName("an IO node carries its mode and a direction-split topic")
    void ioNodesCarryModeAndTopic() {
        PublishedGraph g = graphWith(
                new PublishedGraph.Node("in", "INPUT_OUTPUT", "Scan", 1L, "INPUT", null, null),
                new PublishedGraph.Node("out", "INPUT_OUTPUT", "Light", 1L, "OUTPUT", null, null));

        JsonNode payload = JSON.readTree(PublishPayload.of(g, g.containerByUuid("wf")));

        // An effect and a wait sharing one topic means a device's own output resumes the step
        // that emitted it, so the split is asserted rather than assumed.
        assertThat(payload.path("nodes").path(0).path("topic").asString())
                .isEqualTo("orca-device-input");
        assertThat(payload.path("nodes").path(1).path("topic").asString())
                .isEqualTo("orca-device-io");
    }

    @Test
    @DisplayName("a malformed condition travels as raw text so the compiler refuses it by name")
    void malformedConditionIsNotSanitised() {
        PublishedGraph g = graphWith(
                new PublishedGraph.Node("d", "DECISION", "Check", 1L, null, null, null));
        g.branches.put("b1", new PublishedGraph.Branch("b1", "d", "yes", 0, "{not json"));
        g.branches.put("b2", new PublishedGraph.Branch("b2", "d", "no", 1,
                "{\"conditions\":[]}"));

        JsonNode payload = JSON.readTree(PublishPayload.of(g, g.containerByUuid("wf")));

        assertThat(payload.path("branches").path(0).path("condition").asString())
                .isEqualTo("{not json");
        assertThat(payload.path("branches").path(1).path("condition").isObject()).isTrue();
    }

    @Test
    @DisplayName("branches and responses follow their parent into the right container")
    void branchesAndResponsesFollowTheirParent() {
        PublishedGraph g = graphWith(
                new PublishedGraph.Node("d", "DECISION", "Check", 1L, null, null, null),
                new PublishedGraph.Node("c", "CONNECTOR", "TOS", 1L, null, null, null));
        g.containers.put(2L, new PublishedGraph.Container(2L, "other", "Other", false));
        g.nodes.put("d2", new PublishedGraph.Node("d2", "DECISION", "Elsewhere", 2L,
                null, null, null));
        g.branches.put("b1", new PublishedGraph.Branch("b1", "d", "yes", 0, null));
        g.branches.put("b2", new PublishedGraph.Branch("b2", "d2", "no", 0, null));
        g.responses.put("r1", new PublishedGraph.Response("r1", "c", 200));

        JsonNode payload = JSON.readTree(PublishPayload.of(g, g.containerByUuid("wf")));

        assertThat(payload.path("branches")).hasSize(1);
        assertThat(payload.path("branches").path(0).path("uuid").asString()).isEqualTo("b1");
        assertThat(payload.path("responses")).hasSize(1);
        assertThat(payload.path("responses").path(0).path("status_code").asInt()).isEqualTo(200);
    }

    private static PublishedGraph graphWith(PublishedGraph.Node... nodes) {
        PublishedGraph g = new PublishedGraph();
        g.containers.put(1L, new PublishedGraph.Container(1L, "wf", "Workflow", false));
        for (PublishedGraph.Node n : nodes) {
            g.nodes.put(n.uuid(), n);
        }
        return g;
    }

    private static List<String> uuidsIn(String payload) {
        return JSON.readTree(payload).path("nodes").valueStream()
                .map(n -> n.path("uuid").asString()).toList();
    }
}
