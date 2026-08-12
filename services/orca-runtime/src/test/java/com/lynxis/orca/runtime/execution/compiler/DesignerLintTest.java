package com.lynxis.orca.runtime.execution.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynxis.orca.runtime.execution.api.dto.ValidationFinding;
import com.lynxis.orca.runtime.execution.api.dto.ValidationReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the builder shows an author, on the shapes an author actually produces.
 *
 * <p>The estate-wide agreement property lives in {@code CompilerLintAgreementTest}; these are
 * the specific messages, including the two things the compiler does not object to and an
 * editor should.
 */
class DesignerLintTest {

    private final DesignerJsonCompiler compiler = new DesignerJsonCompiler();

    @Test
    void everyDanglingLinkIsReported_notJustTheFirstOneTheCompilerHits() {
        String json = WorkflowJson.workflow(400)
                .node("s", "START", "Start")
                .node("e", "TERMINATOR", "End")
                .link("s", "ghost_one").link("s", "ghost_two").link("ghost_three", "e")
                .json();

        ValidationReport report = compiler.validate(json);

        assertThat(report.compiles()).isFalse();
        assertThat(report.findings())
                .as("an author fixing one dangling link at a time is the workflow this replaces")
                .extracting(ValidationFinding::subjectId)
                .contains("ghost_one", "ghost_two", "ghost_three");
    }

    @Test
    void anImplicitParallelSplitSaysWhatWouldHappenToTheTruck() {
        String json = WorkflowJson.workflow(401)
                .node("s", "START", "Start")
                .node("io", "INPUT_OUTPUT", "Scan").ioNode("io2", "Scan", "INPUT", "T")
                .node("a", "TERMINATOR", "Empty")
                .node("b", "TERMINATOR", "Loaded")
                .link("s", "io2").link("io2", "a").link("io2", "b")
                .json();

        ValidationReport report = compiler.validate(json);

        assertThat(report.compiles()).isFalse();
        assertThat(report.findings())
                .filteredOn(f -> f.invariant().equals("I1_SINGLE_UNCONDITIONAL_FLOW"))
                .singleElement()
                .satisfies(f -> assertThat(f.message())
                        .contains("the same truck recorded as two different outcomes"));
    }

    /** N8 as an authoring problem: it compiles, and one of the two rows can never run. */
    @Test
    void twoResponsesForOneStatusAreFlaggedEvenThoughTheWorkflowCompiles() {
        String json = WorkflowJson.workflow(402)
                .node("s", "START", "Start")
                .node("c", "CONNECTOR", "TOS submit")
                .node("a", "TERMINATOR", "Book it")
                .node("b", "TERMINATOR", "Tell the clerk")
                .link("s", "c")
                .response("r200a", "c", 200).response("r200b", "c", 200)
                .link("r200a", "a").link("r200b", "b")
                .json();

        ValidationReport report = compiler.validate(json);

        assertThat(report.compiles())
                .as("the platform runs the first matching row and this reproduces that")
                .isTrue();
        assertThat(report.findings())
                .filteredOn(f -> f.invariant().equals("N8_DUPLICATE_RESPONSE_STATUS"))
                .singleElement()
                .satisfies(f -> assertThat(f.message())
                        .contains("which one is not something you control"));
    }

    @Test
    void anIoStepWithNoModeIsAskedToPickOne() {
        String json = WorkflowJson.workflow(403)
                .node("s", "START", "Start")
                .ioNode("io", "Ambiguous", null, "SOME_TOPIC")
                .node("e", "TERMINATOR", "End")
                .link("s", "io").link("io", "e")
                .json();

        assertThat(compiler.validate(json).findings())
                .filteredOn(f -> f.invariant().equals("I7_WAIT_EFFECT_SPLIT"))
                .singleElement()
                .satisfies(f -> assertThat(f.message()).contains("cannot guess"));
    }

    @Test
    void aCleanWorkflowSaysSoWithNothingToShow() {
        String json = WorkflowJson.workflow(404)
                .node("s", "START", "Start")
                .node("e", "TERMINATOR", "End")
                .link("s", "e").json();

        ValidationReport report = compiler.validate(json);

        assertThat(report.compiles()).isTrue();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void anUnreadablePayloadIsAFindingRatherThanAnExplosion() {
        List<ValidationFinding> findings = DesignerLint.inspect("{ not a workflow ]");

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.invariant()).isEqualTo("MALFORMED_INPUT"));
    }
}
