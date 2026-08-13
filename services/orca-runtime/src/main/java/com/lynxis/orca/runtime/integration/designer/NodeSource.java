package com.lynxis.orca.runtime.integration.designer;

import java.util.List;

/**
 * One table that holds ORCA nodes, and what kind of node it holds.
 *
 * <p><b>Every node type in the product must appear in {@link #ALL} — this list IS the
 * coverage.</b> The migration tooling this is ported from carries a scar worth repeating:
 * its subflow block once loaded only start, terminator, decision and connector, so every
 * other subflow node type was simply absent from the graph. Nothing failed. The emitter
 * treats an unknown link target as a pseudo-node and hops straight through it, so those
 * nodes did not stall the compiled workflow — they <b>silently vanished from it</b>, and the
 * workflow still compiled, still deployed, and quietly did less than it was drawn to do.
 *
 * <p>That is why the sources are data rather than twenty method calls: a missing type is a
 * missing row in one visible list, and {@code PublishPayloadTest} holds the list to the
 * product's full node-type set — for subflows as well as workflows.
 */
record NodeSource(String table, String uuidColumn, String ownerColumn, String nameColumn,
        String orcaType, boolean subflow) {

    static final long SUBFLOW_OFFSET = 10_000_000L;

    private static NodeSource wf(String table, String uuidColumn, String nameColumn, String type) {
        return new NodeSource(table, uuidColumn, "workflow_id", nameColumn, type, false);
    }

    private static NodeSource sub(String table, String uuidColumn, String nameColumn, String type) {
        return new NodeSource(table, uuidColumn, "process_subflow_id", nameColumn, type, true);
    }

    /**
     * Plain node tables. Decisions, connectors and process calls are read separately because
     * each needs something extra — a wait time, an id other rows point at, a subflow target.
     */
    static final List<NodeSource> ALL = List.of(
            wf("start_nodes", "start_node_uuid", null, "START"),
            wf("io_node", "io_node_uuid", "name", "INPUT_OUTPUT"),
            wf("manual_input_node", "manual_input_node_uuid", "name", "MANUAL_INPUT"),
            wf("display_nodes", "display_node_uuid", "name", "DISPLAY"),
            wf("terminator_nodes", "terminator_node_uuid", "name", "TERMINATOR"),
            wf("notification_nodes", "notification_node_uuid", "name", "NOTIFICATION"),
            wf("map_iterator_nodes", "map_iterator_node_uuid", "name", "MAP_ITERATOR"),

            sub("process_start_nodes", "process_start_node_uuid", null, "START"),
            sub("process_io_nodes", "process_io_node_uuid", "name", "INPUT_OUTPUT"),
            sub("process_manual_input_nodes", "process_manual_input_node_uuid", "name", "MANUAL_INPUT"),
            sub("process_display_nodes", "process_display_node_uuid", "name", "DISPLAY"),
            sub("process_terminator_nodes", "process_terminator_node_uuid", "name", "TERMINATOR"),
            sub("process_notification_nodes", "process_notification_node_uuid", "name", "NOTIFICATION"),
            sub("process_map_iterator_nodes", "process_map_iterator_node_uuid", "name", "MAP_ITERATOR"));

    long offset() {
        return subflow ? SUBFLOW_OFFSET : 0L;
    }
}
