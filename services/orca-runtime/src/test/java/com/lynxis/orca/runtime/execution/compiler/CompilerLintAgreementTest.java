package com.lynxis.orca.runtime.execution.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynxis.orca.runtime.execution.api.dto.ValidationFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>The anti-drift guarantee, made mechanical.</b>
 *
 * <p>The builder needs every problem at once; the compiler refuses on the first. That is two
 * readers of one set of rules, and two readers drift — the frontend blesses a workflow, the
 * deploy rejects it, and an author is told the tool lied. The usual defence is discipline,
 * which fails quietly over a release or two.
 *
 * <p>So it is a test instead, over the estate's own content: <b>whenever the compiler refuses
 * a payload with invariant X, the lint must report X for that payload.</b> A rule tightened in
 * the compiler and forgotten here fails immediately, with the workflow named.
 *
 * <p>The converse is deliberately NOT asserted. The lint may report more than the compiler
 * reached — that is the whole point of collecting rather than throwing, and a workflow with
 * four dangling links should show all four rather than the one the compiler happened to hit.
 */
@EnabledIfEnvironmentVariable(named = "ORCA_ESTATE_CORPUS", matches = ".+")
class CompilerLintAgreementTest {

    @Test
    void whateverTheCompilerRefusesTheLintAlsoReports() throws IOException {
        Path corpus = Path.of(System.getenv("ORCA_ESTATE_CORPUS"));
        List<Path> payloads;
        try (Stream<Path> paths = Files.list(corpus)) {
            payloads = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(payloads).as("estate corpus at " + corpus).isNotEmpty();

        DesignerJsonCompiler compiler = new DesignerJsonCompiler();
        List<String> silentDisagreements = new ArrayList<>();
        TreeMap<String, Integer> refusedBy = new TreeMap<>();
        TreeMap<String, Integer> beyondCompiler = new TreeMap<>();
        int compiled = 0;
        int cleanLint = 0;

        for (Path payload : payloads) {
            String json = Files.readString(payload);
            List<ValidationFinding> findings = DesignerLint.inspect(json);
            String reported = findings.stream()
                    .map(ValidationFinding::invariant)
                    .collect(Collectors.joining(","));
            try {
                compiler.compile(json);
                compiled++;
                if (findings.isEmpty()) {
                    cleanLint++;
                } else {
                    // Compiles, but the lint has something to say — the value this service adds
                    // over "would it deploy". Named, so a false alarm here is visible.
                    findings.forEach(f -> beyondCompiler.merge(f.invariant(), 1, Integer::sum));
                }
            } catch (CompileException refused) {
                String invariant = refused.invariant().name();
                refusedBy.merge(invariant, 1, Integer::sum);
                boolean linted = findings.stream()
                        .anyMatch(f -> f.invariant().equals(invariant));
                if (!linted) {
                    silentDisagreements.add(payload.getFileName() + ": compiler refused "
                            + invariant + " but the lint reported [" + reported + "]");
                }
            }
        }

        System.out.printf("LINT AGREEMENT: %d payloads — %d compile (%d with a clean lint), "
                        + "%d refused%n",
                payloads.size(), compiled, cleanLint, payloads.size() - compiled);
        refusedBy.forEach((invariant, n) -> System.out.printf("    %-32s %4d%n", invariant, n));
        System.out.println("  findings on workflows that DO compile (what the builder adds):");
        beyondCompiler.forEach((invariant, n) -> System.out.printf("    %-32s %4d%n", invariant, n));

        assertThat(silentDisagreements)
                .as("a workflow the compiler refuses must be a workflow the builder marked — "
                        + "otherwise the tool blesses what the deploy rejects")
                .isEmpty();
    }
}
