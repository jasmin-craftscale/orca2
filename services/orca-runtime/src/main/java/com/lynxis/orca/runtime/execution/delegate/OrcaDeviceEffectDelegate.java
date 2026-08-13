package com.lynxis.orca.runtime.execution.delegate;

import com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeCommand;

/**
 * OUTPUT IO nodes — gate arms, printers, lamps (the compiled
 * {@code ${orcaDeviceEffectDelegate}}). The effect half of device IO: something acts on
 * this now.
 */
public final class OrcaDeviceEffectDelegate extends EdgeEffectDelegate {

    public OrcaDeviceEffectDelegate(EdgeClient edge) {
        super(edge, EdgeCommand.EdgeKind.DEVICE_EFFECT);
    }
}
