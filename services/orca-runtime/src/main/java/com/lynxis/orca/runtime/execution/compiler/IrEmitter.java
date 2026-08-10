package com.lynxis.orca.runtime.execution.compiler;

import com.lynxis.orca.runtime.execution.compiler.CompileException.Invariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Designer workflow → BPMN IR. The graph semantics are the shadow rig's proven
 * {@code BpmnEmitter} (live PLT parity, 7 Aug 2026); the emission targets are W3's:
 *
 * <ul>
 *   <li>effects are {@code serviceTask} + {@code flowable:delegateExpression} — in-process
 *       delegates, no external-worker acquisition machinery (D-2026-08-07-3);</li>
 *   <li>waits are {@code receiveTask} — engine waits the adapter resumes via
 *       {@code RuntimeService.trigger} (D3 classifies every IO node at compile time, I7);</li>
 *   <li>MANUAL_INPUT is a native {@code userTask} with the outcome gateway keyed on
 *       {@code orcaManualOutcome} (D-2026-08-07-1) — exactly the construct the live mirror
 *       drives today;</li>
 *   <li>MAP_ITERATOR does not compile (D-2026-08-07-6): re-author as multi-instance.</li>
 * </ul>
 *
 * <p>Where the shadow noted and tolerated, this compiler REFUSES with a named invariant:
 * the 137-silently-dropped-nodes class (P2) dies here, not in comparison.
 */
final class IrEmitter {

    /** Delegate bean names the runtime provides (W4's eight delegates). */
    static final String DELEGATE_DEVICE_EFFECT = "orcaDeviceEffectDelegate";
    static final String DELEGATE_CONNECTOR = "orcaConnectorDelegate";
    static final String DELEGATE_DISPLAY = "orcaDisplayDelegate";
    static final String DELEGATE_NOTIFICATION = "orcaNotificationDelegate";

    static final String MANUAL_CANDIDATE_GROUP = "gate-clerk";

    private static final Pattern SAFE_UUID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final DesignerWorkflow w;
    private final BpmnIr ir;
    private final Map<String, List<DesignerWorkflow.Link>> outIndex = new LinkedHashMap<>();
    private int synth;
    private int flowSeq;

    IrEmitter(DesignerWorkflow workflow) {
        this.w = workflow;
        this.ir = new BpmnIr(processKey(workflow), workflow.name());
    }

    /**
     * I2 — the process key, derived from {@code workflow_id} and nothing else (D1: names
     * collide; 19 did). By construction {@code key:version:uuid} stays inside the engine's
     * 64-char id column; the adapter re-asserts the round-trip on every deploy.
     */
    static String processKey(DesignerWorkflow w) {
        if (w.workflowId() <= 0) {
            throw new CompileException(Invariant.I2_SHORT_KEY,
                    "workflow_id must be a positive id, got " + w.workflowId());
        }
        return "proc_" + w.workflowId();
    }

    BpmnIr emit() {
        validate();
        for (DesignerWorkflow.Link l : w.links()) {
            outIndex.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
        }
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            ir.add(element(n));
        }
        plainFlows();
        decisionFlows();
        connectorFlows();
        return ir;
    }

    // ------------------------------------------------------------------ validation

    private void validate() {
        long starts = w.nodes().values().stream().filter(n -> n.type().equals("START")).count();
        if (starts != 1) {
            throw new CompileException(Invariant.MALFORMED_INPUT,
                    "a workflow has exactly one START node, found " + starts);
        }
        // I5 — every uuid must survive the n_ prefix as an NCName.
        for (String uuid : w.nodes().keySet()) {
            if (!SAFE_UUID.matcher(uuid).matches()) {
                throw new CompileException(Invariant.I5_NCNAME_IDS,
                        "node uuid '" + uuid + "' cannot form an NCName element id");
            }
        }
        // I3 — every link endpoint exists. The Go executor walked past a dangling link as
        // if the hop were optional; here it never compiles.
        for (DesignerWorkflow.Link l : w.links()) {
            for (String end : List.of(l.from(), l.to())) {
                if (!w.nodes().containsKey(end) && !w.branches().containsKey(end)
                        && !w.responses().containsKey(end)) {
                    throw new CompileException(Invariant.I3_NO_DANGLING_LINKS,
                            "link endpoint '" + end + "' is not a node, branch or response");
                }
            }
        }
        for (DesignerWorkflow.Branch b : w.branches().values()) {
            if (!w.nodes().containsKey(b.decisionUuid())) {
                throw new CompileException(Invariant.I3_NO_DANGLING_LINKS,
                        "branch '" + b.name() + "' hangs off unknown decision " + b.decisionUuid());
            }
        }
        for (DesignerWorkflow.Response r : w.responses().values()) {
            if (!w.nodes().containsKey(r.connectorUuid())) {
                throw new CompileException(Invariant.I3_NO_DANGLING_LINKS,
                        "response " + r.statusCode() + " hangs off unknown connector " + r.connectorUuid());
            }
        }
    }

    // ------------------------------------------------------------------ elements

    private BpmnIr.Element element(DesignerWorkflow.Node n) {
        String id = id(n.uuid());
        return switch (n.type()) {
            case "START" -> new BpmnIr.StartEvent(id, n.name());
            // A TERMINATOR closes its work-item scope. One with NO outgoing links ends the
            // visit; one WITH outgoing links continues it — the estate authors these as
            // "close the item, keep going" waypoints. Emitting the latter as endEvent would
            // consume the token and kill the visit mid-flight (found reviewing the In-Gate
            // golden: "Close OCR Matchup" flowed onward to Best Match).
            case "TERMINATOR" -> outIndex.containsKey(n.uuid())
                    ? new BpmnIr.PassThroughTask(id, n.name())
                    : new BpmnIr.EndEvent(id, n.name());
            case "DECISION" -> isWait(n)
                    ? new BpmnIr.TimerCatchEvent(id, n.name(),
                            "PT" + Math.max(0, n.waitSeconds()) + "S")
                    : new BpmnIr.ExclusiveGateway(id, n.name(), null);
            case "MANUAL_INPUT" -> new BpmnIr.UserTask(id, n.name(), MANUAL_CANDIDATE_GROUP);
            case "INPUT_OUTPUT" -> ioElement(n, id);
            case "CONNECTOR" -> new BpmnIr.ServiceTask(id, n.name(), DELEGATE_CONNECTOR,
                    Map.of("kind", "effect"));
            case "DISPLAY" -> new BpmnIr.ServiceTask(id, n.name(), DELEGATE_DISPLAY,
                    Map.of("kind", "effect"));
            case "NOTIFICATION" -> new BpmnIr.ServiceTask(id, n.name(), DELEGATE_NOTIFICATION,
                    Map.of("kind", "effect"));
            case "PROCESS" -> callActivity(n, id);
            case "MAP_ITERATOR" -> throw new CompileException(Invariant.I9_MAP_ITERATOR_REJECTED,
                    "MAP_ITERATOR '" + n.name() + "' does not compile — re-author as BPMN "
                            + "multi-instance (decision D-2026-08-07-6)");
            default -> throw new CompileException(Invariant.MALFORMED_INPUT,
                    "unmapped ORCA node type '" + n.type() + "' on '" + n.name()
                            + "' — the silent ManualTask fallback is retired (P2)");
        };
    }

    /**
     * I7 — D3's compile-time classification. An OUTPUT is an effect something acts on now;
     * an INPUT is a wait the token parks at until the device reports in. One topic for both
     * forced speculative job acquisition on the Go side; here the classes are different
     * BPMN constructs and cannot be confused again.
     */
    private BpmnIr.Element ioElement(DesignerWorkflow.Node n, String id) {
        String mode = n.mode() == null ? "" : n.mode().toUpperCase();
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("kind", mode.equals("INPUT") ? "wait" : "effect");
        if (n.topic() != null && !n.topic().isBlank()) {
            attrs.put("topic", n.topic());
        }
        return switch (mode) {
            case "INPUT" -> new BpmnIr.ReceiveTask(id, n.name(), attrs);
            case "OUTPUT" -> new BpmnIr.ServiceTask(id, n.name(), DELEGATE_DEVICE_EFFECT, attrs);
            default -> throw new CompileException(Invariant.I7_WAIT_EFFECT_SPLIT,
                    "IO node '" + n.name() + "' has mode '" + n.mode()
                            + "' — every IO node classifies as INPUT (wait) or OUTPUT (effect)");
        };
    }

    private BpmnIr.Element callActivity(DesignerWorkflow.Node n, String id) {
        if (n.subflowId() == null || n.subflowId() <= 0) {
            // I6 — the C2 fix applied to references: no "proc_unresolved" placeholders.
            throw new CompileException(Invariant.I6_SHORT_KEY_REFERENCES,
                    "PROCESS node '" + n.name() + "' names no subflow_id — a call activity "
                            + "must reference the callee's short key");
        }
        return new BpmnIr.CallActivity(id, n.name(), "proc_" + n.subflowId());
    }

    // ------------------------------------------------------------------ flows

    /** Everything except DECISION branch fans and CONNECTOR response fans. */
    private void plainFlows() {
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            boolean branchPoint = n.type().equals("DECISION") && !isWait(n);
            if (branchPoint || n.type().equals("CONNECTOR")) {
                continue;
            }
            List<String> targets = targetsOf(n.uuid());
            if (n.type().equals("MANUAL_INPUT") && targets.size() > 1) {
                manualOutcomeGateway(n, targets);
                continue;
            }
            if (targets.size() > 1) {
                // I1 — C1: more than one unconditional outgoing flow is an implicit
                // parallel split; a truck recorded as both empty and loaded.
                throw new CompileException(Invariant.I1_SINGLE_UNCONDITIONAL_FLOW,
                        "'" + n.name() + "' (" + n.type() + ") has " + targets.size()
                                + " unconditional outgoing links — only MANUAL_INPUT outcomes "
                                + "and DECISION branches may fan out");
            }
            for (String t : targets) {
                flow(id(n.uuid()), id(t), null, null);
            }
        }
    }

    private void decisionFlows() {
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            if (!n.type().equals("DECISION") || isWait(n)) {
                continue;
            }
            List<DesignerWorkflow.Branch> branches = w.branches().values().stream()
                    .filter(b -> n.uuid().equals(b.decisionUuid()))
                    .sorted(Comparator.comparingInt(DesignerWorkflow.Branch::order))
                    .toList();
            if (branches.isEmpty()) {
                List<String> direct = targetsOf(n.uuid());
                if (direct.isEmpty()) {
                    throw new CompileException(Invariant.MALFORMED_INPUT,
                            "decision '" + n.name() + "' has no branches and no links");
                }
                // Authored with direct links instead of branch rows: legal, wired plainly.
                for (String t : direct) {
                    flow(id(n.uuid()), id(t), null, null);
                }
                continue;
            }
            for (DesignerWorkflow.Branch b : branches) {
                OrcaCondition condition = OrcaCondition.parse(b.conditionJson());
                boolean isDefault = condition.isEmpty() || branches.size() == 1;
                String expression = null;
                ConditionJuel.Names names = new ConditionJuel.Names();
                if (!isDefault) {
                    expression = ConditionJuel.compile(condition, names);
                }
                List<String> targets = targetsOf(b.uuid());
                if (targets.size() > 1) {
                    throw new CompileException(Invariant.I1_SINGLE_UNCONDITIONAL_FLOW,
                            "branch '" + b.name() + "' of decision '" + n.name() + "' resolves to "
                                    + targets.size() + " targets — an exclusive branch takes one path");
                }
                String target = targets.isEmpty()
                        ? synthEnd("dangling branch: " + b.name())
                        : id(targets.get(0));
                BpmnIr.SequenceFlow sf = flow(id(n.uuid()), target,
                        isDefault ? null : expression, isDefault ? null : names);
                markDefault(id(n.uuid()), isDefault, sf);
            }
        }
    }

    private void connectorFlows() {
        for (DesignerWorkflow.Node n : w.nodes().values()) {
            if (!n.type().equals("CONNECTOR")) {
                continue;
            }
            List<DesignerWorkflow.Response> responses = w.responses().values().stream()
                    .filter(r -> n.uuid().equals(r.connectorUuid()))
                    .toList();
            if (responses.isEmpty()) {
                throw new CompileException(Invariant.MALFORMED_INPUT,
                        "connector '" + n.name() + "' has no response node — its outcome routes nowhere");
            }
            if (responses.size() == 1) {
                List<String> targets = targetsOf(responses.get(0).uuid());
                if (targets.size() > 1) {
                    throw new CompileException(Invariant.I1_SINGLE_UNCONDITIONAL_FLOW,
                            "connector '" + n.name() + "' single response resolves to " + targets.size()
                                    + " targets");
                }
                for (String t : targets) {
                    flow(id(n.uuid()), id(t), null, null);
                }
                continue;
            }

            String gwId = "gw_resp_" + (++synth);
            ir.add(new BpmnIr.ExclusiveGateway(gwId, "status of " + n.name(), null));
            flow(id(n.uuid()), gwId, null, null);

            // The lowest status code becomes the default, so an unmatched status still
            // routes instead of deadlocking the visit (ported shadow behaviour).
            Integer fallback = responses.stream().map(DesignerWorkflow.Response::statusCode)
                    .filter(java.util.Objects::nonNull)
                    .min(Integer::compareTo).orElse(null);
            boolean defaulted = false;
            for (DesignerWorkflow.Response r : responses) {
                String condition = r.statusCode() == null ? null
                        : "${orcaResponseStatus == " + r.statusCode() + "}";
                boolean isFallback = condition == null
                        || (!defaulted && fallback != null && fallback.equals(r.statusCode()));
                List<String> targets = targetsOf(r.uuid());
                if (targets.size() > 1) {
                    throw new CompileException(Invariant.I1_SINGLE_UNCONDITIONAL_FLOW,
                            "response " + r.statusCode() + " of connector '" + n.name()
                                    + "' resolves to " + targets.size() + " targets");
                }
                String target = targets.isEmpty()
                        ? synthEnd("dangling response " + r.statusCode())
                        : id(targets.get(0));
                BpmnIr.SequenceFlow sf = flow(gwId, target, isFallback ? null : condition, null);
                if (isFallback && !defaulted) {
                    markDefault(gwId, true, sf);
                    defaulted = true;
                }
            }
        }
    }

    /**
     * A MANUAL_INPUT node's outgoing links are the buttons on the clerk's screen; the
     * operator presses exactly one. Routed through an exclusive gateway keyed on
     * {@code orcaManualOutcome} — the variable is the target node's uuid, because the link
     * is what ORCA stores. The last outcome carries the default flow so an unanticipated
     * selection still routes instead of deadlocking the visit.
     */
    private void manualOutcomeGateway(DesignerWorkflow.Node n, List<String> targets) {
        String gwId = "gw_manual_" + (++synth);
        ir.add(new BpmnIr.ExclusiveGateway(gwId, "outcome of " + n.name(), null));
        flow(id(n.uuid()), gwId, null, null);
        for (int i = 0; i < targets.size(); i++) {
            String target = targets.get(i);
            boolean last = i == targets.size() - 1;
            BpmnIr.SequenceFlow sf = flow(gwId, id(target),
                    last ? null : "${orcaManualOutcome == '" + target + "'}", null);
            if (last) {
                markDefault(gwId, true, sf);
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private String id(String uuid) {
        return "n_" + uuid;
    }

    private boolean isWait(DesignerWorkflow.Node n) {
        return n.type().equals("DECISION") && n.waitSeconds() != null;
    }

    /**
     * Real targets of a node's links, DECISION_RESULT / CONNECTOR_RESPONSE hops resolved —
     * ORCA allows a link straight into another decision's branch row, and those rows
     * collapse away in BPMN (ported shadow {@code resolve}).
     */
    private List<String> targetsOf(String uuid) {
        List<String> out = new ArrayList<>();
        for (DesignerWorkflow.Link l : outIndex.getOrDefault(uuid, List.of())) {
            for (String t : resolve(l.to(), new LinkedHashSet<>())) {
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private List<String> resolve(String uuid, Set<String> seen) {
        if (w.nodes().containsKey(uuid)) {
            return List.of(uuid);
        }
        if (!seen.add(uuid)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (DesignerWorkflow.Link l : outIndex.getOrDefault(uuid, List.of())) {
            out.addAll(resolve(l.to(), seen));
        }
        return out;
    }

    private void markDefault(String gatewayId, boolean isDefault, BpmnIr.SequenceFlow sf) {
        if (!isDefault) {
            return;
        }
        if (ir.get(gatewayId) instanceof BpmnIr.ExclusiveGateway gw && gw.defaultFlowId() == null) {
            ir.replace(new BpmnIr.ExclusiveGateway(gw.id(), gw.name(), sf.id()));
        }
    }

    /** A synthetic end for a branch that leads nowhere — the visit ends, by authorship. */
    private String synthEnd(String why) {
        String id = "end_synth_" + (++synth);
        ir.add(new BpmnIr.EndEvent(id, why));
        return id;
    }

    private BpmnIr.SequenceFlow flow(String from, String to, String condition, ConditionJuel.Names names) {
        BpmnIr.SequenceFlow sf = new BpmnIr.SequenceFlow(
                "flow_" + (++flowSeq), from, to, condition,
                names == null ? List.of() : names.orderedBindings());
        ir.add(sf);
        return sf;
    }
}
