package com.lynxis.orca.runtime.execution.compiler;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FidelityCheck second oracle's INPUT half: harvest every condition group
 * actually authored in the estate (the exporter's payloads) and synthesize evaluation
 * fixtures — the authored condition verbatim plus concrete operand bindings chosen to
 * exercise match, mismatch, empty and numeric-ordering behavior.
 *
 * <p>The outputs carry no expectation. The Go decision executor records the oracle:
 *
 * <pre>
 *   ORCA_ESTATE_CORPUS=…/orca-payloads ORCA_FIDELITY_DIR=…/fidelity \
 *       ORCA_FIDELITY_GENERATE=1 ./gradlew :orca-runtime:runtime-execution:test --tests '*FidelityFixtureGenerator*'
 *   cd services/work-flow-executor-service && \
 *       ORCA_FIXTURE_UPDATE=1 ORCA_SELECTOR_FIXTURES=…/fidelity go test ./tests/unit/nodes/ -run TestSelectorConformanceFixtures
 *   ORCA_FIDELITY_DIR=…/fidelity ./gradlew :orca-runtime:runtime-execution:test --tests '*FidelityConformanceTest*'
 * </pre>
 *
 * <p>Deliberately deterministic — no randomness, so a nightly diff of the generated
 * corpus is a diff of the ESTATE, not of the generator's mood.
 */
@EnabledIfEnvironmentVariable(named = "ORCA_FIDELITY_GENERATE", matches = "1")
class FidelityFixtureGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHUNK = 500;

    @Test
    void generateEvaluationFixturesFromTheEstate() throws IOException {
        Path corpus = Path.of(System.getenv("ORCA_ESTATE_CORPUS"));
        Path out = Path.of(System.getenv("ORCA_FIDELITY_DIR"));
        Files.createDirectories(out);

        List<Path> payloads;
        try (Stream<Path> paths = Files.list(corpus)) {
            payloads = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(payloads).as("exported corpus present at " + corpus).isNotEmpty();

        List<ObjectNode> fixtures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int branches = 0;
        int unparseable = 0;

        for (Path payload : payloads) {
            JsonNode root = JSON.readTree(Files.readString(payload));
            for (JsonNode branch : root.path("branches")) {
                JsonNode conditionGroups = branch.path("condition");
                if (!conditionGroups.isArray() || conditionGroups.isEmpty()) {
                    continue;
                }
                branches++;
                int groupIdx = 0;
                for (JsonNode group : conditionGroups) {
                    groupIdx++;
                    OrcaCondition parsed;
                    try {
                        parsed = OrcaCondition.parse("[" + group.toString() + "]");
                    } catch (CompileException e) {
                        unparseable++;
                        continue; // the estate property test owns malformed conditions
                    }
                    if (parsed.isEmpty()) {
                        continue;
                    }
                    String source = payload.getFileName() + "#" + branch.path("uuid").asString()
                            + "/g" + groupIdx;
                    for (Map.Entry<String, Map<String, String>> variant
                            : variants(parsed.groups().get(0)).entrySet()) {
                        ObjectNode fixture = fixture(source + "/" + variant.getKey(),
                                group, variant.getValue());
                        if (seen.add(fixture.toString())) {
                            fixtures.add(fixture);
                        }
                    }
                }
            }
        }

        int chunk = 0;
        for (int i = 0; i < fixtures.size(); i += CHUNK) {
            ArrayNode file = JSON.createArrayNode();
            fixtures.subList(i, Math.min(i + CHUNK, fixtures.size())).forEach(file::add);
            Files.writeString(out.resolve(String.format("estate-%03d.json", ++chunk)),
                    file.toPrettyString() + "\n");
        }

        System.out.printf("fidelity generator: %d fixtures from %d branches (%d chunks, %d unparseable groups)%n",
                fixtures.size(), branches, chunk, unparseable);
        assertThat(fixtures).as("the estate authored at least one evaluable condition").isNotEmpty();
    }

    /**
     * Binding variants per group. Keys are variant names (part of the fixture source);
     * values map selector path → the concrete value both evaluators see.
     */
    private static Map<String, Map<String, String>> variants(OrcaCondition.Group group) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        out.put("match", bind(group, true, false));
        out.put("miss", bind(group, false, false));
        out.put("empty", bind(group, true, true));
        Map<String, String> numeric = numericBump(group);
        if (numeric != null) {
            out.put("numeric", numeric);
        }
        if (group.children().size() > 1) {
            Map<String, String> half = new LinkedHashMap<>(bind(group, false, false));
            half.putAll(bindChild(group.children().get(0), true, false));
            out.put("first-match", half);
        }
        return out;
    }

    private static Map<String, String> bind(OrcaCondition.Group group, boolean match, boolean empty) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (OrcaCondition.Child c : group.children()) {
            bindings.putAll(bindChild(c, match, empty));
        }
        return bindings;
    }

    private static Map<String, String> bindChild(OrcaCondition.Child c, boolean match, boolean empty) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (empty) {
            bindings.put(c.fieldPath(), "");
            if (c.rightIsVariable()) {
                bindings.put(c.rightRaw(), "");
            }
            return bindings;
        }
        if (c.rightIsVariable()) {
            bindings.put(c.fieldPath(), match ? "SAME" : "LEFT");
            bindings.put(c.rightRaw(), match ? "SAME" : "RIGHT");
        } else {
            String authored = c.rightRaw() == null ? "" : c.rightRaw();
            bindings.put(c.fieldPath(), match ? authored : miss(authored));
        }
        return bindings;
    }

    /** A value the authored constant should NOT match (and won't equal numerically). */
    private static String miss(String authored) {
        return authored.isBlank() ? "zz" : authored + "_x";
    }

    /** For numeric constants: left = constant + 1, exercising the numeric-first coercion. */
    private static Map<String, String> numericBump(OrcaCondition.Group group) {
        Map<String, String> bindings = new LinkedHashMap<>();
        boolean any = false;
        for (OrcaCondition.Child c : group.children()) {
            if (!c.rightIsVariable() && isNumeric(c.rightRaw())) {
                bindings.put(c.fieldPath(),
                        new BigDecimal(c.rightRaw().trim()).add(BigDecimal.ONE).toPlainString());
                any = true;
            } else {
                bindings.putAll(bindChild(c, true, false));
            }
        }
        return any ? bindings : null;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static ObjectNode fixture(String source, JsonNode group, Map<String, String> bindings) {
        ObjectNode fixture = JSON.createObjectNode();
        fixture.put("source", source);
        fixture.put("kind", "evaluateConditionGroup");
        ObjectNode args = fixture.putObject("args");
        ArrayNode condition = args.putArray("condition");
        condition.add(group.deepCopy());
        ObjectNode bound = args.putObject("bindings");
        bindings.forEach(bound::put);
        return fixture;
    }
}
