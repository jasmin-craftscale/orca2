package com.lynxis.orca.runtime.integration.designer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authored estate as it is stored — every workflow and subflow, before any one of them is
 * turned into a publish payload.
 *
 * <p>Container ids are namespaced: workflow ids and subflow ids are separate sequences that
 * really do collide, so a subflow's id carries {@link NodeSource#SUBFLOW_OFFSET}. Getting this
 * wrong does not throw; it merges two unrelated workflows into one graph.
 */
final class PublishedGraph {

    record Container(long id, String uuid, String name, boolean subflow) {
    }

    record Node(String uuid, String type, String name, long container, String mode,
            Integer waitSeconds, Long subflowCall) {
    }

    record Branch(String uuid, String decisionUuid, String name, int order, String condition) {
    }

    record Response(String uuid, String connectorUuid, Integer statusCode) {
    }

    record Link(String from, String to, int position) {
    }

    final Map<Long, Container> containers = new LinkedHashMap<>();
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final Map<String, Branch> branches = new LinkedHashMap<>();
    final Map<String, Response> responses = new LinkedHashMap<>();
    final List<Link> links = new ArrayList<>();

    /**
     * Which container a uuid belongs to, following a branch or a response up to its parent —
     * those rows carry no container of their own.
     */
    long containerOf(String uuid) {
        Node n = nodes.get(uuid);
        if (n != null) {
            return n.container();
        }
        Branch b = branches.get(uuid);
        if (b != null) {
            return b.decisionUuid() == null ? -1L : containerOf(b.decisionUuid());
        }
        Response r = responses.get(uuid);
        if (r != null) {
            return r.connectorUuid() == null ? -1L : containerOf(r.connectorUuid());
        }
        return -1L;
    }

    Container containerByUuid(String workflowUuid) {
        for (Container c : containers.values()) {
            if (c.uuid() != null && c.uuid().equalsIgnoreCase(workflowUuid)) {
                return c;
            }
        }
        return null;
    }
}
