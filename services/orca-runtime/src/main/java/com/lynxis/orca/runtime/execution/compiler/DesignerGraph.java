package com.lynxis.orca.runtime.execution.compiler;

import com.lynxis.orca.runtime.execution.api.dto.SelectorNamespace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph questions about a draft, asked on behalf of the builder rather than the compiler.
 *
 * <p>The one that matters is ancestry: <b>which steps genuinely precede this one</b>. A step
 * whose data will already exist is the one an author usually means, so that set is what a
 * builder should offer first — a ranking signal, not a restriction ({@link #nodesFor}).
 *
 * <p>Links are followed through branches and responses, the same hops
 * {@code IrEmitter.resolve} walks, so "precedes" means what it means at runtime and not what
 * it looks like on a canvas.
 */
public final class DesignerGraph {

    private DesignerGraph() {
    }

    /**
     * Every step in the draft, each marked with whether it runs BEFORE {@code nodeUuid}.
     *
     * <p><b>Marked, never filtered.</b> A step whose data will already exist is the one an
     * author almost always wants, and a builder should put those first — but a graph is a
     * static picture of a running system and there are real reasons to name a step it cannot
     * prove precedes you: a loop back, an attach-to-running visit, a workflow whose shape is
     * still being drawn. Hiding those would make the tool feel broken and teach people to work
     * around it. So this ranks; it does not police. The runtime still refuses a selector that
     * cannot resolve, which is where a genuine mistake is caught.
     */
    public static List<SelectorNamespace.NodeRef> nodesFor(String designerJson, String nodeUuid) {
        DesignerWorkflow w = DesignerWorkflow.parse(designerJson);
        Set<String> before = nodeUuid == null || nodeUuid.isBlank()
                ? w.nodes().keySet()
                : ancestorsOf(w, nodeUuid);
        List<SelectorNamespace.NodeRef> out = new ArrayList<>();
        w.nodes().forEach((uuid, node) -> out.add(new SelectorNamespace.NodeRef(
                uuid, node.name(), node.type(), "$." + uuid + ".dataset", before.contains(uuid))));
        return List.copyOf(out);
    }

    /** Every {@code $.workflow.dataset.<key>} some condition in this draft already reads. */
    public static Set<String> authoredDatasetKeys(String designerJson) {
        Set<String> out = new LinkedHashSet<>();
        for (String selector : authoredSelectors(DesignerWorkflow.parse(designerJson))) {
            String key = datasetKeyOf(selector);
            if (key != null) {
                out.add(key);
            }
        }
        return out;
    }

    public static long workflowIdOf(String designerJson) {
        return DesignerWorkflow.parse(designerJson).workflowId();
    }

    /** {@code $.workflow.dataset.plt_bm_found} → {@code plt_bm_found}; anything else → null. */
    private static String datasetKeyOf(String selector) {
        String prefix = "$.workflow.dataset.";
        if (selector == null || !selector.startsWith(prefix)) {
            return null;
        }
        String tail = selector.substring(prefix.length());
        int dot = tail.indexOf('.');
        int bracket = tail.indexOf('[');
        int end = Math.min(dot < 0 ? tail.length() : dot, bracket < 0 ? tail.length() : bracket);
        return end <= 0 ? null : tail.substring(0, end);
    }

    /** Node uuids from which {@code target} is reachable — every step that can precede it. */
    static Set<String> ancestorsOf(DesignerWorkflow w, String target) {
        if (target == null || !w.nodes().containsKey(target)) {
            return Set.of();
        }
        Map<String, List<String>> forward = nodeEdges(w);
        Map<String, List<String>> backward = new LinkedHashMap<>();
        forward.forEach((from, tos) -> tos.forEach(
                to -> backward.computeIfAbsent(to, k -> new ArrayList<>()).add(from)));

        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(backward.getOrDefault(target, List.of()));
        while (!queue.isEmpty()) {
            String node = queue.poll();
            // A loop back to the target makes the target its own ancestor; that is true of the
            // graph and useless to an author, so it is dropped.
            if (node.equals(target) || !ancestors.add(node)) {
                continue;
            }
            queue.addAll(backward.getOrDefault(node, List.of()));
        }
        return ancestors;
    }

    /**
     * step → steps, with branch and response hops collapsed away.
     *
     * <p>Two of the edges are implicit and easy to miss: a decision reaches its branches
     * through {@code decision_uuid} and a connector its responses through
     * {@code connector_uuid} — there is no link row for either. Reading only the links makes
     * every branched step look unreachable, and ancestry then stops at the first decision in
     * the workflow, which is most of them.
     */
    private static Map<String, List<String>> nodeEdges(DesignerWorkflow w) {
        Map<String, List<DesignerWorkflow.Link>> outIndex = new LinkedHashMap<>();
        for (DesignerWorkflow.Link l : w.links()) {
            outIndex.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (String node : w.nodes().keySet()) {
            List<String> targets = new ArrayList<>();
            for (DesignerWorkflow.Link l : outIndex.getOrDefault(node, List.of())) {
                addAll(targets, resolve(w, outIndex, l.to(), new LinkedHashSet<>()));
            }
            for (DesignerWorkflow.Branch b : w.branches().values()) {
                if (node.equals(b.decisionUuid())) {
                    addAll(targets, resolve(w, outIndex, b.uuid(), new LinkedHashSet<>()));
                }
            }
            for (DesignerWorkflow.Response r : w.responses().values()) {
                if (node.equals(r.connectorUuid())) {
                    addAll(targets, resolve(w, outIndex, r.uuid(), new LinkedHashSet<>()));
                }
            }
            edges.put(node, targets);
        }
        return edges;
    }

    private static void addAll(List<String> targets, List<String> found) {
        for (String t : found) {
            if (!targets.contains(t)) {
                targets.add(t);
            }
        }
    }

    private static List<String> resolve(DesignerWorkflow w,
            Map<String, List<DesignerWorkflow.Link>> outIndex, String uuid, Set<String> seen) {
        if (w.nodes().containsKey(uuid)) {
            return List.of(uuid);
        }
        if (!seen.add(uuid)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (DesignerWorkflow.Link l : outIndex.getOrDefault(uuid, List.of())) {
            out.addAll(resolve(w, outIndex, l.to(), seen));
        }
        return out;
    }

    /** Every {@code $.…} selector this workflow's conditions already read. */
    static Set<String> authoredSelectors(DesignerWorkflow w) {
        Set<String> out = new LinkedHashSet<>();
        for (DesignerWorkflow.Branch b : w.branches().values()) {
            OrcaCondition condition = OrcaCondition.parse(b.conditionJson());
            out.addAll(condition.variables());
        }
        return out;
    }

}
