package com.lynxis.orca.runtime.execution.api;

import com.lynxis.orca.runtime.execution.api.dto.CompiledDefinition;
import com.lynxis.orca.runtime.execution.api.dto.ValidationReport;

/**
 * Compilation as an internal API of this module (a deliberate
 * choice): the compiler lives here, not in orca-core's design module, because its output is
 * validated by the engine's own validator — orca-core calls this at publish time instead of
 * holding an engine dependency.
 *
 * <p>Implementation lands in W3. Its invariants are non-negotiable and each is a compile
 * error, never a silent fix-up:
 * no activity with more than one unconditional outgoing flow · definition ids round-trip as
 * {@code key:version:uuid} · a dangling {@code node_links} endpoint fails the compile ·
 * call activities inherit variables · BPMN ids are {@code n_<uuid>}-prefixed NCNames.
 */
public interface CompilationFacade {

    CompiledDefinition compile(String designerJson);

    /**
     * Every problem in a draft at once, for the builder to show while somebody is editing.
     *
     * <p>Distinct from {@link #compile} on purpose: compilation refuses on the first invariant
     * it meets, which is correct for a deploy and hostile in an editor. The verdict —
     * {@code compiles} — is still answered by actually compiling, so validation and deployment
     * cannot disagree about whether a workflow is publishable.
     */
    ValidationReport validate(String designerJson);
}
