package com.lynxis.orca.runtime.execution.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles an ORCA condition tree to the faithful JUEL form — explicit left-associative
 * parentheses (ORCA folds left-to-right with no precedence) with every comparison routed
 * through {@code orca:cmp}, so ORCA's numeric-first-then-string coercion survives. Ported
 * from the shadow rig's {@code Juel.compileFaithful}, which measured 100% fidelity against
 * the reference evaluator over the estate corpus.
 *
 * <p>JUEL identifiers cannot hold a selector path ({@code $.workflow.dataset.x}), so
 * variables are sanitised through {@link Names} and the mapping is returned — the caller
 * writes it as the {@code orca:field} sidecar (invariant I8), without which BPMN → UI
 * cannot restore the field binding.
 */
final class ConditionJuel {

    /** Bidirectional selector-path ↔ JUEL identifier mapping — the I8 sidecar's content. */
    static final class Names {
        private final Map<String, String> toId = new LinkedHashMap<>();
        private final Map<String, String> toPath = new LinkedHashMap<>();

        String id(String path) {
            return toId.computeIfAbsent(path, p -> {
                String base = "v_" + p.replaceAll("^\\$\\.", "").replaceAll("[^A-Za-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
                if (base.equals("v_")) {
                    base = "v_x";
                }
                String candidate = base;
                int n = 1;
                while (toPath.containsKey(candidate)) {
                    candidate = base + "_" + (++n);
                }
                toPath.put(candidate, p);
                return candidate;
            });
        }

        /** id → selector path, in first-use order. */
        Map<String, String> bindings() {
            return Map.copyOf(toPath);
        }

        /** first-use order preserved for deterministic serialization. */
        List<Map.Entry<String, String>> orderedBindings() {
            return List.copyOf(toPath.entrySet());
        }
    }

    private ConditionJuel() {
    }

    static String compile(OrcaCondition condition, Names names) {
        if (condition.isEmpty()) {
            return "${true}";
        }
        List<String> groups = new ArrayList<>();
        List<String> groupJoiners = new ArrayList<>();
        for (OrcaCondition.Group g : condition.groups()) {
            if (g.children().isEmpty()) {
                continue;
            }
            List<String> children = new ArrayList<>();
            List<String> childJoiners = new ArrayList<>();
            for (OrcaCondition.Child c : g.children()) {
                // Either side is a variable read ONLY when its selector_id is set — a blank
                // selector makes the stored text the operand itself (Go's else branch; the
                // the fidelity oracle caught left-side literals compiled as variable reads).
                String left = c.leftIsVariable() ? varRef(names.id(c.fieldPath())) : literal(c.fieldPath());
                String right = c.rightIsVariable() ? varRef(names.id(c.rightRaw())) : literal(c.rightRaw());
                children.add("orca:cmp(" + literal(c.op()) + ", " + left + ", " + right + ")");
                childJoiners.add(c.joiner());
            }
            groups.add("orca:grp(" + foldLeft(children, childJoiners) + ")");
            groupJoiners.add(g.joiner());
        }
        if (groups.isEmpty()) {
            return "${true}";
        }
        return "${" + foldLeft(groups, groupJoiners) + "}";
    }

    /**
     * A scope-safe variable read: a bare identifier makes Flowable throw
     * {@code Unknown property used in expression} when the variable was never set, which
     * kills the instance; ORCA treats a missing selector as false.
     */
    private static String varRef(String id) {
        return "variables:getOrDefault('" + id + "', null)";
    }

    /** Left-associative folding; an unknown joiner drops the operand exactly as ORCA does. */
    private static String foldLeft(List<String> parts, List<String> joiners) {
        String acc = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            String j = joiners.get(i) == null ? "" : joiners.get(i).trim().toLowerCase();
            if (!j.equals("and") && !j.equals("or")) {
                continue; // operand discarded, matching the Go default: branch
            }
            acc = "(" + acc + " " + j + " " + parts.get(i) + ")";
        }
        return acc;
    }

    private static String literal(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }
}
