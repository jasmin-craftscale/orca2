package com.lynxis.orca.runtime.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The seam's value types validate at the boundary, so the engine never sees a half-built ref. */
class EngineSeamTypesTest {

    @Test
    void tenantRefRequiresASite() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantRef(" "));
        assertThat(new TenantRef("site-1").siteUuid()).isEqualTo("site-1");
    }

    @Test
    void deploymentVersionsStartAtOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EngineDeployment("proc_1", 0));
        assertThat(new EngineDeployment("proc_1", 1).version()).isEqualTo(1);
    }

    @Test
    void instanceRefsAreNeverBlank() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EngineInstanceRef(""));
        assertThat(new EngineInstanceRef("i-1").value()).isEqualTo("i-1");
    }

    @Test
    void snapshotWaitPointIsPresentExactlyWhenWaiting() {
        TenantRef tenant = new TenantRef("site-1");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstanceSnapshot(InstanceState.WAITING, Optional.empty(), tenant));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstanceSnapshot(InstanceState.COMPLETED, Optional.of("w"), tenant));

        assertThat(InstanceSnapshot.running(tenant).state()).isEqualTo(InstanceState.RUNNING);
        assertThat(InstanceSnapshot.waitingAt("w", tenant).waitPoint()).contains("w");
        assertThat(InstanceSnapshot.completed(tenant).waitPoint()).isEmpty();
        assertThat(InstanceSnapshot.cancelled(tenant).state()).isEqualTo(InstanceState.CANCELLED);
    }

    @Test
    void exceptionsNameTheOffender() {
        assertThat(new UnknownDefinitionException("proc_x")).hasMessageContaining("proc_x");
        assertThat(new UnknownInstanceException(new EngineInstanceRef("i-9"))).hasMessageContaining("i-9");
        assertThat(new InvalidSignalException("nope")).hasMessage("nope");
    }
}
