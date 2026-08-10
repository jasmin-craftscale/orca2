package com.lynxis.orca.runtime.execution.compiler;

import com.lynxis.orca.runtime.execution.api.dto.ValidationFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every problem in a workflow at once, addressed to the person editing it.
 *
 * <p>The compiler refuses on the FIRST invariant it meets, which is right for a deploy and
 * useless in an editor: an author fixes one dangling link, re-saves, and is told about the
 * next. This walks the same parsed payload and collects all of them.
 *
 * <p><b>It is a second reader of the same rules, which is a drift risk, and the drift is
 * closed mechanically rather than by discipline.</b> {@code CompilerLintAgreementTest} asserts
 * over the whole estate that whenever {@link DesignerJsonCompiler} refuses with an invariant,
 * this reports that same invariant. A rule that gets stricter in the compiler and not here
 * fails that test, so the builder can never bless something that will not deploy.
 *
 * <p>It deliberately does NOT reimplement compilation. It shares the parse, the invariant
 * names and the estate's own definition of wrong; what it adds is completeness and language
 * an author can act on.
 */
public final class DesignerLint {

    private static final Pattern SAFE_UUID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private DesignerLint() {
    }

    /** Findings in a stable order: workflow-level first, then by subject. */
    public static List<ValidationFinding> inspect(String designerJson) {
        DesignerWorkflow w;
        try {
            w = DesignerWorkflow.parse(designerJson);
        } catch (CompileException malformed) {
            return List.of(new ValidationFinding(malformed.invariant().name(), null, null,
                    malformed.getMessage()));
        }
        List<ValidationFinding> findings = new ArrayList<>();
        checkIdentity(w, findings);
        checkStructure(w, findings);
        checkNodes(w, findings);
        checkFanOut(w, findings);
        checkConnectorResponses(w, findings);
        findings.sort(Comparator
                .comparing((ValidationFinding f) -> f.subjectId() == null ? "" : f.subjectId())
                .thenComparing(ValidationFinding::invariant));
        return List.copyOf(findings);
    }

    private static void checkIdentity(DesignerWorkflow w, List<ValidationFinding> out) {
        if (w.workflowId() <= 0) {
            out.add(new ValidationFinding(CompileException.Invariant.I2_SHORT_KEY.name(), null, w.name(),
                    "This workflow has no id, so the runtime has no stable name to deploy it under."));
        }
        long starts = w.nodes().values().stream().filter(n -> "START".equals(n.type())).count();
        if (starts != 1) {
            out.add(new ValidationFinding(CompileException.Invariant.MALFORMED_INPUT.name(), null, w.name(),
                    starts == 0 ? "This workflow has no START node, so nothing can begin it."
                            : "This workflow has " + starts + " START nodes; a visit can only begin in one place."));
        }
    }

    private static void checkStructure(DesignerWorkflow w, List<ValidationFinding> out) {
        for (DesignerWorkflow.Link l : w.links()) {
            for (String end : List.of(l.from(), l.to())) {
                if (!known(w, end)) {
                    out.add(new ValidationFinding(
                            CompileException.Invariant.I3_NO_DANGLING_LINKS.name(), end, null,
                            "This link points at '" + end + "', which is not a node, branch or response "
                                    + "on this workflow. The platform walks past a link like this as if "
                                    + "the step were optional; here it will not deploy."));
                }
            }
        }
        for (DesignerWorkflow.Branch b : w.branches().values()) {
            if (!w.nodes().containsKey(b.decisionUuid())) {
                out.add(new ValidationFinding(
                        CompileException.Invariant.I3_NO_DANGLING_LINKS.name(), b.uuid(), b.name(),
                        "This branch hangs off decision '" + b.decisionUuid() + "', which is not on this workflow."));
            }
        }
        for (DesignerWorkflow.Response r : w.responses().values()) {
            if (!w.nodes().containsKey(r.connectorUuid())) {
                out.add(new ValidationFinding(
                        CompileException.Invariant.I3_NO_DANGLING_LINKS.name(), r.uuid(), null,
                        "This response hangs off connector '" + r.connectorUuid() + "', which is not on this workflow."));
            }
        }
    }

    private static void checkNodes(DesignerWorkflow w, List<ValidationFinding> out) {
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            if (!SAFE_UUID.matcher(n.uuid()).matches()) {
                out.add(new ValidationFinding(CompileException.Invariant.I5_NCNAME_IDS.name(),
                        n.uuid(), n.name(), "This node's id cannot become a valid BPMN element id."));
            }
            switch (n.type()) {
                case "MAP_ITERATOR" -> out.add(new ValidationFinding(
                        CompileException.Invariant.I9_MAP_ITERATOR_REJECTED.name(), n.uuid(), n.name(),
                        "Map iterator does not run on the new runtime. Re-author this as a "
                                + "multi-instance step (decision D-2026-08-07-6)."));
                case "PROCESS" -> {
                    if (n.subflowId() == null || n.subflowId() <= 0) {
                        out.add(new ValidationFinding(
                                CompileException.Invariant.I6_SHORT_KEY_REFERENCES.name(), n.uuid(), n.name(),
                                "This process step names no subflow, so there is nothing for it to call."));
                    }
                }
                case "INPUT_OUTPUT" -> {
                    String mode = n.mode() == null ? "" : n.mode().toUpperCase();
                    if (!mode.equals("INPUT") && !mode.equals("OUTPUT")) {
                        out.add(new ValidationFinding(
                                CompileException.Invariant.I7_WAIT_EFFECT_SPLIT.name(), n.uuid(), n.name(),
                                "This IO step is neither an input (something the lane waits for) nor an "
                                        + "output (something it does). Pick one — the runtime decides at "
                                        + "deploy time which it is, and cannot guess."));
                    }
                }
                case "START", "TERMINATOR", "DECISION", "MANUAL_INPUT", "CONNECTOR", "DISPLAY",
                        "NOTIFICATION" -> {
                    // the supported set
                }
                default -> out.add(new ValidationFinding(
                        CompileException.Invariant.MALFORMED_INPUT.name(), n.uuid(), n.name(),
                        "'" + n.type() + "' is not a step type this runtime knows how to run."));
            }
        }
    }

    /**
     * I1 — the implicit parallel split: a truck recorded as both empty and loaded.
     *
     * <p>Counted the way the compiler counts it, which is not the way it looks on a canvas.
     * A link may land on a branch or a response rather than on a step, so targets are RESOLVED
     * through those hops to the steps they reach and then DEDUPLICATED: a connector whose two
     * responses both continue to the same terminator has one destination, not two. Counting
     * raw links instead flags every connector in the estate, which is how a validator teaches
     * authors to ignore it.
     */
    private static void checkFanOut(DesignerWorkflow w, List<ValidationFinding> out) {
        Map<String, List<DesignerWorkflow.Link>> outIndex = new LinkedHashMap<>();
        for (DesignerWorkflow.Link l : w.links()) {
            outIndex.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
        }
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            String from = n.uuid();
            // A connector's outflow is its responses' and a decision's is its branches';
            // both are checked on their own terms below and in checkConnectorResponses.
            if ("CONNECTOR".equals(n.type())
                    || ("DECISION".equals(n.type()) && n.waitSeconds() == null)) {
                continue;
            }
            List<String> to = resolvedTargets(w, outIndex, from);
            if (to.size() <= 1) {
                continue;
            }
            boolean mayFanOut = "MANUAL_INPUT".equals(n.type());
            if (!mayFanOut) {
                out.add(new ValidationFinding(
                        CompileException.Invariant.I1_SINGLE_UNCONDITIONAL_FLOW.name(), from, n.name(),
                        "This step has " + to.size() + " outgoing paths and no condition to choose "
                                + "between them, so a visit would take all of them at once — the same "
                                + "truck recorded as two different outcomes. Only a decision's branches "
                                + "or a screen's buttons may split."));
            }
        }
        for (DesignerWorkflow.Branch b : w.branches().values()) {
            long count = resolvedTargets(w, outIndex, b.uuid()).size();
            if (count > 1) {
                out.add(new ValidationFinding(
                        CompileException.Invariant.I1_SINGLE_UNCONDITIONAL_FLOW.name(), b.uuid(), b.name(),
                        "This branch leads to " + count + " steps; an exclusive branch takes one path."));
            }
        }
    }

    private static void checkConnectorResponses(DesignerWorkflow w, List<ValidationFinding> out) {
        Map<String, List<DesignerWorkflow.Link>> outIndex = new LinkedHashMap<>();
        for (DesignerWorkflow.Link l : w.links()) {
            outIndex.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
        }
        Map<String, List<DesignerWorkflow.Response>> byConnector = new LinkedHashMap<>();
        for (DesignerWorkflow.Response r : w.responses().values()) {
            byConnector.computeIfAbsent(r.connectorUuid(), k -> new ArrayList<>()).add(r);
            long onwards = resolvedTargets(w, outIndex, r.uuid()).size();
            if (onwards > 1) {
                out.add(new ValidationFinding(
                        CompileException.Invariant.I1_SINGLE_UNCONDITIONAL_FLOW.name(), r.uuid(), null,
                        "This response leads to " + onwards + " steps; a response takes one path."));
            }
        }
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            if (!"CONNECTOR".equals(n.type())) {
                continue;
            }
            List<DesignerWorkflow.Response> responses =
                    byConnector.getOrDefault(n.uuid(), List.of());
            if (responses.isEmpty()) {
                out.add(new ValidationFinding(
                        CompileException.Invariant.MALFORMED_INPUT.name(), n.uuid(), n.name(),
                        "This connector has no response, so whatever it answers routes nowhere."));
                continue;
            }
            // N8, as an authoring problem rather than a runtime coin flip: the platform's
            // response loop stops at the first row matching a status, so a second row for the
            // same status can never run — and which of the two is "first" is not something the
            // author controls.
            Map<Integer, Long> perStatus = new LinkedHashMap<>();
            responses.forEach(r -> perStatus.merge(r.statusCode() == null ? -1 : r.statusCode(),
                    1L, Long::sum));
            perStatus.forEach((status, count) -> {
                if (count > 1 && status >= 0) {
                    out.add(new ValidationFinding("N8_DUPLICATE_RESPONSE_STATUS", n.uuid(), n.name(),
                            "This connector has " + count + " responses for status " + status
                                    + ". Only one of them can ever run, and which one is not "
                                    + "something you control — give them distinct statuses."));
                }
            });
        }
    }

    /** {@code IrEmitter.targetsOf}, verbatim in behaviour: hop through non-steps, dedupe. */
    private static List<String> resolvedTargets(DesignerWorkflow w,
            Map<String, List<DesignerWorkflow.Link>> outIndex, String uuid) {
        List<String> out = new ArrayList<>();
        for (DesignerWorkflow.Link l : outIndex.getOrDefault(uuid, List.of())) {
            for (String t : resolveTo(w, outIndex, l.to(), new java.util.LinkedHashSet<>())) {
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static List<String> resolveTo(DesignerWorkflow w,
            Map<String, List<DesignerWorkflow.Link>> outIndex, String uuid, java.util.Set<String> seen) {
        if (w.nodes().containsKey(uuid)) {
            return List.of(uuid);
        }
        if (!seen.add(uuid)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (DesignerWorkflow.Link l : outIndex.getOrDefault(uuid, List.of())) {
            out.addAll(resolveTo(w, outIndex, l.to(), seen));
        }
        return out;
    }

    private static boolean known(DesignerWorkflow w, String uuid) {
        return w.nodes().containsKey(uuid) || w.branches().containsKey(uuid)
                || w.responses().containsKey(uuid);
    }
}
