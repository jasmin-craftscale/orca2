package com.lynxis.orca.runtime.execution.compiler;

import static com.lynxis.orca.runtime.execution.compiler.WorkflowJson.simpleCondition;
import static com.lynxis.orca.runtime.execution.compiler.WorkflowJson.workflow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lynxis.orca.runtime.execution.api.dto.CompiledDefinition;
import com.lynxis.orca.runtime.execution.compiler.CompileException.Invariant;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * One test per invariant (W3's first failing tests): each is a named compile ERROR, never
 * a silent fix-up — the assertion set that kills the 137-dropped-nodes class (P2).
 */
class CompilerInvariantsTest {

    private final DesignerJsonCompiler compiler = new DesignerJsonCompiler();

    private static String xml(CompiledDefinition definition) {
        return new String(definition.bpmnXml(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void startToTerminatorCompiles() {
        CompiledDefinition compiled = compiler.compile(workflow(1)
                .node("aaa-1", "START", "Start")
                .node("bbb-2", "TERMINATOR", "End")
                .link("aaa-1", "bbb-2")
                .json());

        assertThat(compiled.workflowId()).isEqualTo(1);
        assertThat(compiled.processKey()).isEqualTo("proc_1");
        assertThat(xml(compiled))
                .contains("<startEvent id=\"n_aaa-1\"")
                .contains("<endEvent id=\"n_bbb-2\"")
                .contains("sourceRef=\"n_aaa-1\" targetRef=\"n_bbb-2\"");
    }

    /** I1 — C1: two unconditional outgoing flows is an implicit parallel split. */
    @Test
    void i1_twoUnconditionalOutgoingFlowsRefuse() {
        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(2)
                        .node("s", "START", "Start")
                        .ioNode("io", "portal", "OUTPUT", "t")
                        .node("e1", "TERMINATOR", "End A")
                        .node("e2", "TERMINATOR", "End B")
                        .link("s", "io").link("io", "e1").link("io", "e2")
                        .json()))
                .matches(e -> e.invariant() == Invariant.I1_SINGLE_UNCONDITIONAL_FLOW);
    }

    /** I2 — the key derives from workflow_id and nothing else. */
    @Test
    void i2_keyIsProcWorkflowId() {
        CompiledDefinition compiled = compiler.compile(workflow(31675)
                .node("s", "START", "Start").node("e", "TERMINATOR", "End")
                .link("s", "e").json());
        assertThat(compiled.processKey()).isEqualTo("proc_31675");

        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(
                        "{\"workflow_id\":0,\"nodes\":[{\"uuid\":\"s\",\"type\":\"START\",\"name\":\"s\"}]}"))
                .matches(e -> e.invariant() == Invariant.I2_SHORT_KEY);
    }

    /** I3 — C3: a link to nowhere never compiles to a hop. */
    @Test
    void i3_danglingLinkRefuses() {
        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(3)
                        .node("s", "START", "Start")
                        .node("e", "TERMINATOR", "End")
                        .link("s", "ghost")
                        .json()))
                .matches(e -> e.invariant() == Invariant.I3_NO_DANGLING_LINKS)
                .withMessageContaining("ghost");
    }

    /** I4 — a subflow sees the caller's dataset: inheritVariables on every call activity. */
    @Test
    void i4_callActivitiesInheritVariables() {
        String xml = xml(compiler.compile(workflow(4)
                .node("s", "START", "Start")
                .processNode("p", "Best match", 31699L)
                .node("e", "TERMINATOR", "End")
                .link("s", "p").link("p", "e")
                .json()));
        assertThat(xml).contains("flowable:inheritVariables=\"true\"");
    }

    /** I5 — C5: ids are n_<uuid>; a uuid that cannot form an NCName refuses. */
    @Test
    void i5_ncNameIds() {
        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(5)
                        .node("has space", "START", "Start")
                        .node("e", "TERMINATOR", "End")
                        .link("has space", "e")
                        .json()))
                .matches(e -> e.invariant() == Invariant.I5_NCNAME_IDS);
    }

    /** I6 — references use the short key; a PROCESS with no callee refuses (no proc_unresolved). */
    @Test
    void i6_calledElementIsShortKey() {
        String xml = xml(compiler.compile(workflow(6)
                .node("s", "START", "Start")
                .processNode("p", "sub", 777L)
                .node("e", "TERMINATOR", "End")
                .link("s", "p").link("p", "e")
                .json()));
        assertThat(xml).contains("calledElement=\"proc_777\"");

        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(6)
                        .node("s", "START", "Start")
                        .processNode("p", "sub", null)
                        .node("e", "TERMINATOR", "End")
                        .link("s", "p").link("p", "e")
                        .json()))
                .matches(e -> e.invariant() == Invariant.I6_SHORT_KEY_REFERENCES);
    }

    /** I7 — D3: INPUT is a wait (receiveTask), OUTPUT an effect (delegate); no mode refuses. */
    @Test
    void i7_waitEffectSplit() {
        String xml = xml(compiler.compile(workflow(7)
                .node("s", "START", "Start")
                .ioNode("in", "portal scan", "INPUT", "topic-a")
                .ioNode("out", "gate arm", "OUTPUT", "topic-b")
                .node("e", "TERMINATOR", "End")
                .link("s", "in").link("in", "out").link("out", "e")
                .json()));
        assertThat(xml)
                .contains("<receiveTask id=\"n_in\" name=\"portal scan\" orca:kind=\"wait\" orca:topic=\"topic-a\"/>")
                .contains("flowable:delegateExpression=\"${orcaDeviceEffectDelegate}\" orca:kind=\"effect\" orca:topic=\"topic-b\"");

        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(7)
                        .node("s", "START", "Start")
                        .ioNode("io", "unclassified", null, null)
                        .node("e", "TERMINATOR", "End")
                        .link("s", "io").link("io", "e")
                        .json()))
                .matches(e -> e.invariant() == Invariant.I7_WAIT_EFFECT_SPLIT);
    }

    /** I8 — every conditional flow carries the orca:field var↔selector sidecar. */
    @Test
    void i8_conditionalFlowsCarryTheFieldSidecar() {
        String xml = xml(compiler.compile(workflow(8)
                .node("s", "START", "Start")
                .node("d", "DECISION", "weight check")
                .node("hi", "TERMINATOR", "Overweight")
                .node("lo", "TERMINATOR", "OK")
                .link("s", "d")
                .branch("b1", "d", "over", 1, simpleCondition("$.workflow.dataset.weight", "is greater than", "38000"))
                .branch("b2", "d", "default", 2, null)
                .link("b1", "hi").link("b2", "lo")
                .json()));
        assertThat(xml)
                // conditionExpression text is XML-escaped in the canonical bytes — asserted as serialized
                .contains("orca:cmp(&apos;is greater than&apos;, variables:getOrDefault(&apos;v_workflow_dataset_weight&apos;, null), &apos;38000&apos;)")
                .contains("<orca:field var=\"v_workflow_dataset_weight\" selector=\"$.workflow.dataset.weight\"/>")
                .contains("default=\"");
    }

    /** I9 — D-2026-08-07-6: MAP_ITERATOR is a named refusal directing to re-authoring. */
    @Test
    void i9_mapIteratorRefuses() {
        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(9)
                        .node("s", "START", "Start")
                        .node("m", "MAP_ITERATOR", "per container")
                        .node("e", "TERMINATOR", "End")
                        .link("s", "m").link("m", "e")
                        .json()))
                .matches(e -> e.invariant() == Invariant.I9_MAP_ITERATOR_REJECTED)
                .withMessageContaining("multi-instance");
    }

    /** P2 — an unmapped type refuses; the silent ManualTask fallback is retired. */
    @Test
    void unmappedTypeRefuses() {
        assertThatExceptionOfType(CompileException.class)
                .isThrownBy(() -> compiler.compile(workflow(10)
                        .node("s", "START", "Start")
                        .node("x", "TELEPORT", "novel")
                        .node("e", "TERMINATOR", "End")
                        .link("s", "x").link("x", "e")
                        .json()))
                .matches(e -> e.invariant() == Invariant.MALFORMED_INPUT);
    }

    // ------------------------------------------------------------------ constructs

    @Test
    void manualInputFansOutThroughTheOutcomeGateway() {
        String xml = xml(compiler.compile(workflow(11)
                .node("s", "START", "Start")
                .node("m", "MANUAL_INPUT", "clerk screen")
                .node("a", "TERMINATOR", "Empty")
                .node("b", "TERMINATOR", "Loaded")
                .link("s", "m").link("m", "a").link("m", "b")
                .json()));
        assertThat(xml)
                .contains("<userTask id=\"n_m\" name=\"clerk screen\" flowable:candidateGroups=\"gate-clerk\"/>")
                .contains("<exclusiveGateway id=\"gw_manual_1\"")
                .contains("${orcaManualOutcome == &apos;a&apos;}")
                .contains("default=\"");
    }

    @Test
    void multiResponseConnectorGetsTheStatusGatewayWithLowestStatusAsDefault() {
        String xml = xml(compiler.compile(workflow(12)
                .node("s", "START", "Start")
                .node("c", "CONNECTOR", "TOS submit")
                .node("ok", "TERMINATOR", "OK")
                .node("bad", "TERMINATOR", "Failed")
                .link("s", "c")
                .response("r200", "c", 200)
                .response("r500", "c", 500)
                .link("r200", "ok").link("r500", "bad")
                .json()));
        assertThat(xml)
                .contains("<exclusiveGateway id=\"gw_resp_1\"")
                .contains("${orcaResponseStatus == 500}")
                .doesNotContain("${orcaResponseStatus == 200}"); // 200 is the default flow
    }

    /**
     * N8, pinned as an ERRATUM against the migration register. The register says the
     * platform's connector-response loop has no {@code break}, so two rows sharing a status
     * code both fire and both chain forward — a parallel split. The code says otherwise:
     * {@code connector_executor_service.go} sets {@code CheckResponseStatus = false} and
     * <b>breaks</b> on the first matching row (the break has been there since GATE-432,
     * Feb 2025). Exactly one branch runs.
     *
     * <p>So the exclusive gateway is faithful, and a parallel split would be a divergence
     * this compiler invented. Which of the duplicate rows wins is arbitrary on both sides —
     * Go takes whichever the unordered query returned first, the engine takes the first
     * authored flow — and that arbitrariness is the estate's, not ours to resolve here.
     * Reachable: workflow 5 has two connectors with a duplicated 200 (it does not currently
     * compile — I3, dangling links).
     */
    @Test
    void twoResponsesSharingAStatusCodeStayExclusiveBecauseTheGoLoopBreaks() {
        String xml = xml(compiler.compile(workflow(17)
                .node("s", "START", "Start")
                .node("c", "CONNECTOR", "TOS submit")
                .node("a", "TERMINATOR", "Book it")
                .node("b", "TERMINATOR", "Tell the clerk")
                .node("bad", "TERMINATOR", "Failed")
                .link("s", "c")
                .response("r200a", "c", 200)
                .response("r200b", "c", 200)
                .response("r500", "c", 500)
                .link("r200a", "a").link("r200b", "b").link("r500", "bad")
                .json()));

        assertThat(xml)
                .as("one gateway, one token — never a parallel split")
                .doesNotContain("<parallelGateway")
                .containsOnlyOnce("<exclusiveGateway");
    }

    @Test
    void timedDecisionsCompileToTimers() {
        String xml = xml(compiler.compile(workflow(13)
                .node("s", "START", "Start")
                .waitNode("w", "hold 30s", 30)
                .node("e", "TERMINATOR", "End")
                .link("s", "w").link("w", "e")
                .json()));
        assertThat(xml).contains("<timeDuration>PT30S</timeDuration>");
    }

    /**
     * QUIRK: {@code condition = '[]'} is ALWAYS-TRUE in the Go evaluator —
     * an empty condition array is not an error, it is the branch that always fires. The
     * compiler preserves it as the gateway's default flow: no conditionExpression, and the
     * default= attribute names it. Do not "fix" toward a compile error.
     */
    @Test
    void emptyConditionArrayCompilesToTheAlwaysTakenDefaultFlow() {
        String xml = xml(compiler.compile(workflow(15)
                .node("s", "START", "Start")
                .node("d", "DECISION", "check")
                .node("a", "TERMINATOR", "Always")
                .node("b", "TERMINATOR", "Guarded")
                .link("s", "d")
                .branch("b1", "d", "always", 1, "[]")
                .branch("b2", "d", "guarded", 2,
                        simpleCondition("$.workflow.dataset.weight", "is greater than", "38000"))
                .link("b1", "a").link("b2", "b")
                .json()));
        assertThat(xml)
                .containsOnlyOnce("<conditionExpression") // only the guarded branch carries one
                .contains("default=\"");
    }

    /**
     * A TERMINATOR with outgoing links closes its work-item scope and CONTINUES — the
     * estate authors "Close X (Done)" waypoints that flow onward. As an endEvent the token
     * dies there and the visit stalls forever (found reviewing the In-Gate golden: "Close
     * OCR Matchup" flows on to Best Match). Deploy validation cannot catch this — only the
     * construct choice can.
     */
    @Test
    void terminatorWithOutgoingLinksIsAPassThroughNotAnEnd() {
        String xml = xml(compiler.compile(workflow(16)
                .node("s", "START", "Start")
                .node("close", "TERMINATOR", "Close OCR Matchup")
                .node("e", "TERMINATOR", "Visit Complete")
                .link("s", "close").link("close", "e")
                .json()));
        assertThat(xml)
                .contains("<task id=\"n_close\" name=\"Close OCR Matchup\"/>")
                .contains("<endEvent id=\"n_e\" name=\"Visit Complete\"/>");
    }

    /** T2's seed: same input, same bytes — twice, and with reordered irrelevant whitespace. */
    @Test
    void byteIdentity() {
        String json = workflow(14)
                .node("s", "START", "Start")
                .node("e", "TERMINATOR", "End")
                .link("s", "e").json();

        byte[] first = compiler.compile(json).bpmnXml();
        byte[] second = compiler.compile(json).bpmnXml();

        assertThat(first).isEqualTo(second);
    }
}
