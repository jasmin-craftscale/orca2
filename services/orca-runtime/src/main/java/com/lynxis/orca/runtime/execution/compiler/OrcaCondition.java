package com.lynxis.orca.runtime.execution.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The ORCA decision-condition model, ported verbatim from the shadow rig's {@code Cond}
 * (which mirrors {@code decision_executor_service.go}):
 *
 * <ul>
 *   <li>an empty condition array is {@code true};</li>
 *   <li>groups and children fold strictly left-to-right with NO operator precedence;</li>
 *   <li>a blank/unknown logical operator silently discards its operand (Go's
 *       {@code default:} branch) — the compiled JUEL reproduces exactly that.</li>
 * </ul>
 */
final class OrcaCondition {

    /**
     * left OP right, plus the joiner that folds this child onto the accumulator.
     *
     * <p>BOTH operands are literal-vs-variable-aware: the Go evaluator resolves a side as a
     * selector only when its {@code selector_id} is set; a blank selector id makes the
     * stored text the operand ITSELF (a constant comparison). The fidelity oracle
     * caught six authored conditions where compiling the left side as a variable read
     * flipped the verdict.
     */
    record Child(String joiner, String fieldPath, String leftSelector, String op,
            String rightRaw, String rightSelector) {
        boolean leftIsVariable() {
            return leftSelector != null && !leftSelector.isBlank();
        }

        boolean rightIsVariable() {
            return rightSelector != null && !rightSelector.isBlank();
        }
    }

    record Group(String joiner, List<Child> children) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Group> groups;

    private OrcaCondition(List<Group> groups) {
        this.groups = groups;
    }

    List<Group> groups() {
        return groups;
    }

    boolean isEmpty() {
        return groups.stream().allMatch(g -> g.children().isEmpty());
    }

    /** Every selector path this condition reads, left- and right-hand side. */
    Set<String> variables() {
        Set<String> out = new LinkedHashSet<>();
        for (Group g : groups) {
            for (Child c : g.children()) {
                if (c.leftIsVariable() && c.fieldPath() != null && !c.fieldPath().isBlank()) {
                    out.add(c.fieldPath());
                }
                if (c.rightIsVariable()) {
                    out.add(c.rightRaw());
                }
            }
        }
        return out;
    }

    static OrcaCondition empty() {
        return new OrcaCondition(List.of());
    }

    /**
     * Parses the stored condition JSON.
     *
     * @throws CompileException when the text is not a condition array — the Go executor's
     *     silent {@code return nil} on an unreadable condition is exactly the P2 class this
     *     compiler exists to kill
     */
    static OrcaCondition parse(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        String t = json.trim();
        if (t.equals("null") || t.equals("[]") || t.equals("{}")) {
            return empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(t);
        } catch (RuntimeException e) {
            throw new CompileException(CompileException.Invariant.MALFORMED_INPUT,
                    "condition is not valid JSON: " + abbreviate(t));
        }
        if (!root.isArray()) {
            throw new CompileException(CompileException.Invariant.MALFORMED_INPUT,
                    "condition is not a condition array: " + abbreviate(t));
        }
        List<Group> gs = new ArrayList<>();
        for (JsonNode gn : root) {
            List<Child> cs = new ArrayList<>();
            JsonNode kids = gn.path("child_condition");
            if (kids.isArray()) {
                for (JsonNode cn : kids) {
                    cs.add(new Child(
                            cn.path("logical_operator").asString(""),
                            cn.path("field").path("value").asString(""),
                            cn.path("field").path("selector_id").asString(""),
                            cn.path("comparison_operator").asString(""),
                            cn.path("value").path("value").asString(""),
                            cn.path("value").path("selector_id").asString("")));
                }
            }
            gs.add(new Group(gn.path("logical_operator").asString(""), cs));
        }
        return new OrcaCondition(List.copyOf(gs));
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }
}
