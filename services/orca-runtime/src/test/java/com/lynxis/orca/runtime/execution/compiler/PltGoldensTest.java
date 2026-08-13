package com.lynxis.orca.runtime.execution.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynxis.orca.runtime.execution.api.dto.CompiledDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The frozen PLT prod-close goldens: the six
 * workflows the programme's vertical slice runs on — In-Gate Portal (31653), Dispatch
 * (31654), In-Gate (31655), Out-Gate Portal (31656), Out-Gate (31657) and the Best Match
 * Reservation subflow (10000090) — compiled from their real publish payloads and pinned
 * BYTE-FOR-BYTE (T2: same input, same bytes; the definition hash depends on it).
 *
 * <p>The payloads were exported from the authored estate by
 * {@code tools/flowable-shadow :orca-bridge:export} and the BPMN reviewed by hand before
 * freezing. A red run here means the compiler's output changed for real inputs — that is
 * sometimes intended (a compiler fix), never incidental. To regenerate after an INTENDED
 * change:
 * <pre>ORCA_REGEN_GOLDENS=1 ./gradlew :orca-runtime:runtime-execution:test --tests '*PltGoldensTest*' --rerun</pre>
 * then re-review the diff by hand before committing — the golden is only as good as its
 * last review.
 */
class PltGoldensTest {

    private static final Path GOLDENS = Path.of("src", "test", "resources", "goldens");

    @TestFactory
    Stream<DynamicTest> goldens() throws IOException {
        boolean regenerate = "1".equals(System.getenv("ORCA_REGEN_GOLDENS"));
        java.util.List<Path> payloads;
        try (Stream<Path> paths = Files.list(GOLDENS)) {
            payloads = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(payloads).as("the six frozen PLT payloads").hasSize(6);

        return payloads.stream().map(payload -> DynamicTest.dynamicTest(
                payload.getFileName().toString(), () -> {
                    CompiledDefinition compiled = new DesignerJsonCompiler()
                            .compile(Files.readString(payload));
                    Path golden = Path.of(payload.toString().replace(".json", ".bpmn.xml"));
                    if (regenerate) {
                        Files.write(golden, compiled.bpmnXml());
                        return;
                    }
                    assertThat(Files.exists(golden))
                            .as("golden %s frozen (regenerate with ORCA_REGEN_GOLDENS=1, then review)",
                                    golden.getFileName())
                            .isTrue();
                    assertThat(new String(compiled.bpmnXml(), StandardCharsets.UTF_8))
                            .as("byte-identical to the hand-reviewed golden")
                            .isEqualTo(Files.readString(golden));
                }));
    }
}
