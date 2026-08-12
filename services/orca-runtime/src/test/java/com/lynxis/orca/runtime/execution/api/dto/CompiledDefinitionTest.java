package com.lynxis.orca.runtime.execution.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CompiledDefinitionTest {

    private static final byte[] BPMN = "<definitions/>".getBytes(StandardCharsets.UTF_8);

    @Test
    void carriesIdentityAndBytes() {
        CompiledDefinition compiled = new CompiledDefinition(31675L, "proc_31675", BPMN);

        assertThat(compiled.workflowId()).isEqualTo(31675L);
        assertThat(compiled.processKey()).isEqualTo("proc_31675");
        assertThat(compiled.bpmnXml()).isEqualTo(BPMN);
        assertThat(compiled.toString()).contains("proc_31675").contains(BPMN.length + " bytes");
    }

    @Test
    void bytesAreDefensivelyCopiedBothWays() {
        byte[] source = BPMN.clone();
        CompiledDefinition compiled = new CompiledDefinition(1L, "proc_1", source);

        source[0] = '!';
        assertThat(compiled.bpmnXml()).isEqualTo(BPMN);

        compiled.bpmnXml()[0] = '?';
        assertThat(compiled.bpmnXml()).isEqualTo(BPMN);
    }

    @Test
    void equalityIsByContent() {
        CompiledDefinition a = new CompiledDefinition(1L, "proc_1", BPMN.clone());
        CompiledDefinition b = new CompiledDefinition(1L, "proc_1", BPMN.clone());
        CompiledDefinition c = new CompiledDefinition(1L, "proc_1", "<other/>".getBytes(StandardCharsets.UTF_8));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a).isNotEqualTo("proc_1");
    }

    @Test
    void rejectsMissingParts() {
        assertThatNullPointerException().isThrownBy(() -> new CompiledDefinition(1L, null, BPMN));
        assertThatNullPointerException().isThrownBy(() -> new CompiledDefinition(1L, "proc_1", null));
    }
}
