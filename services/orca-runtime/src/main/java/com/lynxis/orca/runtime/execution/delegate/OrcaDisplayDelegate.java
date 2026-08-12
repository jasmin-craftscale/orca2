package com.lynxis.orca.runtime.execution.delegate;

import com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeCommand;

/**
 * DISPLAY nodes — lane screens and operator messaging (the compiled
 * {@code ${orcaDisplayDelegate}}). The authored name IS the operator-facing message intent;
 * it travels on the command.
 */
public final class OrcaDisplayDelegate extends EdgeEffectDelegate {

    public OrcaDisplayDelegate(EdgeClient edge) {
        super(edge, EdgeCommand.EdgeKind.DISPLAY);
    }
}
