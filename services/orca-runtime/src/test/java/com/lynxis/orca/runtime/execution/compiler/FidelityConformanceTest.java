package com.lynxis.orca.runtime.execution.compiler;

import com.lynxis.orca.runtime.execution.engine.flowable.OrcaElFunctions;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flowable.common.engine.impl.de.odysseus.el.ExpressionFactoryImpl;
import org.flowable.common.engine.impl.de.odysseus.el.util.SimpleContext;
import org.flowable.common.engine.impl.javax.el.ExpressionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FidelityCheck second oracle's EVALUATION half: every authored estate condition
 * (fixtures from {@link FidelityFixtureGenerator}, oracle recorded by the Go decision
 * executor) is compiled by the PRODUCTION compiler ({@link ConditionJuel}) and evaluated by
 * a REAL EL engine with the PRODUCTION function bindings ({@link OrcaElFunctions}) — the
 * same expression, functions and coercion the runtime uses on a live visit. Agreement with
 * the Go recording is the pass condition; a single disagreement is a semantic drift the
 * 337-fixture regression corpus cannot see (it only covers conditions somebody wrote a Go
 * test for; this covers conditions somebody wrote a WORKFLOW for).
 */
@EnabledIfEnvironmentVariable(named = "ORCA_FIDELITY_DIR", matches = ".+")
class FidelityConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code variables:getOrDefault(name, default)} — the scope-safe read ConditionJuel
     * emits. Public class deliberately: the EL engine reaches the method reflectively and
     * refuses members of non-public classes.
     */
    public static final class Vars {
        static final ThreadLocal<Map<String, Object>> VARIABLES = new ThreadLocal<>();

        public static Object getOrDefault(String name, Object dflt) {
            Map<String, Object> vars = VARIABLES.get();
            Object v = vars == null ? null : vars.get(name);
            return v == null ? dflt : v;
        }
    }

    @Test
    void everyRecordedEstateConditionAgreesWithTheGoEvaluator() throws Exception {
        Path dir = Path.of(System.getenv("ORCA_FIDELITY_DIR"));
        List<Path> files;
        try (Stream<Path> paths = Files.list(dir)) {
            files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("generated fidelity fixtures at " + dir
                + " (run FidelityFixtureGenerator first)").isNotEmpty();

        int total = 0;
        int agree = 0;
        int unrecorded = 0;
        List<String> disagreements = new ArrayList<>();

        for (Path file : files) {
            for (JsonNode fx : JSON.readTree(Files.readString(file))) {
                total++;
                JsonNode expected = fx.path("expected").path("value");
                if (expected.isMissingNode() || expected.isNull()) {
                    unrecorded++;
                    continue;
                }
                boolean goVerdict = expected.asBoolean();
                boolean javaVerdict = evaluate(fx);
                if (goVerdict == javaVerdict) {
                    agree++;
                } else if (disagreements.size() < 25) {
                    disagreements.add(fx.path("source").asString()
                            + " → go=" + goVerdict + " java=" + javaVerdict);
                }
            }
        }

        System.out.printf("fidelity: %d fixtures, %d agree, %d disagree, %d unrecorded%n",
                total, agree, total - agree - unrecorded, unrecorded);
        assertThat(disagreements)
                .as("compiled-JUEL verdicts that diverge from the Go decision evaluator")
                .isEmpty();
        assertThat(unrecorded)
                .as("fixtures without a Go recording — run the Go recorder "
                        + "(ORCA_FIXTURE_UPDATE=1 ORCA_SELECTOR_FIXTURES=<dir> go test "
                        + "./tests/unit/nodes/ -run TestSelectorConformanceFixtures)")
                .isZero();
        assertThat(agree).isGreaterThan(0);
    }

    private boolean evaluate(JsonNode fx) throws Exception {
        JsonNode args = fx.path("args");
        OrcaCondition condition = OrcaCondition.parse(args.path("condition").toString());
        ConditionJuel.Names names = new ConditionJuel.Names();
        String juel = ConditionJuel.compile(condition, names);

        Map<String, Object> variables = new LinkedHashMap<>();
        JsonNode bindings = args.path("bindings");
        names.bindings().forEach((id, path) -> {
            JsonNode v = bindings.path(path);
            if (!v.isMissingNode() && !v.isNull()) {
                variables.put(id, v.asString());
            }
        });

        ExpressionFactory factory = new ExpressionFactoryImpl();
        SimpleContext context = new SimpleContext();
        context.setFunction("orca", "cmp", OrcaElFunctions.class.getMethod(
                "cmp", Object.class, Object.class, Object.class));
        context.setFunction("orca", "grp", OrcaElFunctions.class.getMethod(
                "grp", Object.class));
        Method getOrDefault = Vars.class.getMethod("getOrDefault", String.class, Object.class);
        context.setFunction("variables", "getOrDefault", getOrDefault);

        Vars.VARIABLES.set(variables);
        try {
            Object result = factory.createValueExpression(context, juel, Object.class)
                    .getValue(context);
            return Boolean.TRUE.equals(result);
        } finally {
            Vars.VARIABLES.remove();
        }
    }
}
