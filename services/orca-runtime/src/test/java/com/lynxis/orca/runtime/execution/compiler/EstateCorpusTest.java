package com.lynxis.orca.runtime.execution.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynxis.orca.runtime.execution.api.dto.CompiledDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The estate corpus property test: <b>every workflow in the estate
 * compiles, or fails with a named invariant — never anything else.</b> This is the assertion
 * that kills the 137-silently-dropped-nodes class: the Go-era compiler tolerated what it did
 * not understand; this one must refuse by name, and any OTHER exception is a compiler bug.
 *
 * <p>The corpus is generated, not committed (it carries authored estate content):
 * <pre>
 *   cd tools/flowable-shadow && ORCA_DB_PASS=… ./gradlew :orca-bridge:export
 *   ORCA_ESTATE_CORPUS=…/orca-bridge/build/orca-payloads ./gradlew :orca-runtime:runtime-execution:test
 * </pre>
 * Absent the env var the test is skipped — the fast tier stays hermetic.
 */
@EnabledIfEnvironmentVariable(named = "ORCA_ESTATE_CORPUS", matches = ".+")
class EstateCorpusTest {

    @Test
    void everyEstateWorkflowCompilesOrRefusesByName() throws IOException {
        Path corpus = Path.of(System.getenv("ORCA_ESTATE_CORPUS"));
        List<Path> payloads;
        try (Stream<Path> paths = Files.list(corpus)) {
            payloads = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(payloads).as("exported corpus present at " + corpus).isNotEmpty();

        DesignerJsonCompiler compiler = new DesignerJsonCompiler();
        int compiled = 0;
        Map<String, Integer> refusals = new TreeMap<>();
        Map<String, List<String>> refusalExamples = new TreeMap<>();
        List<String> silentDropClass = new ArrayList<>();

        for (Path payload : payloads) {
            String json = Files.readString(payload);
            try {
                CompiledDefinition definition = compiler.compile(json);
                assertThat(definition.bpmnXml()).isNotEmpty();
                compiled++;
            } catch (CompileException named) {
                String key = named.invariant().name();
                refusals.merge(key, 1, Integer::sum);
                refusalExamples.computeIfAbsent(key, k -> new ArrayList<>());
                if (refusalExamples.get(key).size() < 3) {
                    refusalExamples.get(key).add(payload.getFileName() + ": " + named.getMessage());
                }
            } catch (Throwable unnamed) {
                silentDropClass.add(payload.getFileName() + ": "
                        + unnamed.getClass().getSimpleName() + ": " + unnamed.getMessage());
            }
        }

        System.out.println("=".repeat(80));
        System.out.printf("ESTATE CORPUS: %d payloads — %d compiled, %d named refusals, %d UNNAMED%n",
                payloads.size(), compiled, payloads.size() - compiled - silentDropClass.size(),
                silentDropClass.size());
        System.out.println("=".repeat(80));
        refusals.forEach((invariant, n) -> {
            System.out.printf("  %-35s %4d%n", invariant, n);
            refusalExamples.get(invariant).forEach(e -> System.out.println("      " + e));
        });
        silentDropClass.forEach(e -> System.out.println("  UNNAMED  " + e));

        assertThat(silentDropClass)
                .as("every failure must be a NAMED CompileException invariant — anything else "
                        + "is the silent-drop class this test exists to kill")
                .isEmpty();
    }
}
