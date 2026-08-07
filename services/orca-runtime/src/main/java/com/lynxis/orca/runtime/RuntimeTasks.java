package com.lynxis.orca.runtime;

import org.springframework.scheduling.annotation.Scheduled;

import com.lynxis.orca.platform.outbox.OutboxRelay;
import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The outbox relay's first invocation — Phase 0 built it and nothing ran it.
 *
 * <p>{@code SystemContextRule} fails the build on any {@code @Scheduled} method
 * that does not enter a {@link SystemContext}, and this is why: no user invoked
 * this, so §B6 and §D3 require it to say who it is rather than run anonymously.
 *
 * <p>⚠️ It calls {@code deliverPendingUnderCurrentIdentity} and <strong>not</strong>
 * {@code deliverPending}, and the difference is a contradiction that running this
 * found. {@code deliverPending} establishes its own system context;
 * {@link SystemContext} refuses to nest, deliberately; and the build check requires
 * this method to establish one. Those three cannot all hold, so the relay grew a
 * variant that <em>asserts</em> an identity rather than establishing one — the
 * guard is unweakened, the relay still cannot run anonymously, and the identity an
 * operator sees in the audit trail is this service's rather than a primitive's.
 *
 * <p>⚠️ <strong>No consumer is registered, and that is the honest state.</strong>
 * {@code orca.outbox.consumers} is empty, so {@code OutboxWriter} writes the fact
 * and zero delivery rows, and this relay has nothing to deliver. Nothing on-site
 * consumes {@code visit.completed} in Phase 1 — the cloud tier that would is
 * scoped later (register NEW-1b) — and registering a consumer nobody has written
 * would make retention wait for an acknowledgement that never comes. The fact is
 * still recorded, in the visit's own transaction, which is the guarantee that
 * matters; delivery arrives with a destination.
 */
@Slf4j
@RequiredArgsConstructor
public class RuntimeTasks {

	private static final String SERVICE = "orca-runtime";

	private final OutboxRelay relay;
	private final int batchSize;

	@Scheduled(fixedDelayString = "${orca.outbox.relay.interval:1s}")
	public void deliverRecordedFacts() {
		SystemContext.runAs(new SystemIdentity(SERVICE, "outbox-relay"), () -> {
			int delivered = relay.deliverPendingUnderCurrentIdentity(batchSize);
			if (delivered > 0) {
				log.debug("outbox relay delivered {} fact(s)", delivered);
			}
		});
	}
}
