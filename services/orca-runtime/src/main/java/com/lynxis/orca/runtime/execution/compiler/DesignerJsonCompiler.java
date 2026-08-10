package com.lynxis.orca.runtime.execution.compiler;

import com.lynxis.orca.runtime.execution.api.CompilationFacade;
import com.lynxis.orca.runtime.execution.api.dto.CompiledDefinition;
import com.lynxis.orca.runtime.execution.api.dto.ValidationReport;

/**
 * The compiler (D2, W3): designer JSON in, canonical BPMN out. Pure — no Spring, no
 * datasource, no clock, no randomness (ArchUnit rule 7) — because same input must mean
 * same bytes forever (T2). Every failure is a {@link CompileException} carrying its named
 * invariant; there are no warnings and no partial output.
 */
public final class DesignerJsonCompiler implements CompilationFacade {

    @Override
    public ValidationReport validate(String designerJson) {
        // The verdict comes from really compiling — never from counting findings. A lint that
        // missed something would then report "publishable" for a workflow that will not
        // deploy, which is the one failure this service must not have.
        boolean compiles;
        try {
            compile(designerJson);
            compiles = true;
        } catch (CompileException refused) {
            compiles = false;
        }
        return new ValidationReport(compiles, DesignerLint.inspect(designerJson));
    }

    @Override
    public CompiledDefinition compile(String designerJson) {
        DesignerWorkflow workflow = DesignerWorkflow.parse(designerJson);
        BpmnIr ir = new IrEmitter(workflow).emit();
        return new CompiledDefinition(workflow.workflowId(), ir.processKey(),
                CanonicalBpmnXml.serialize(ir));
    }
}
