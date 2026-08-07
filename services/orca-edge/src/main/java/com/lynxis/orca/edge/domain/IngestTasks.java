package com.lynxis.orca.edge.domain;

import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The two background loops that keep a lane's traffic moving, and the one place
 * edge's identity and scope are established.
 *
 * <p><strong>Both are deliberate acts, and neither is implied by the other.</strong>
 *
 * <ul>
 *   <li>{@link SystemContext} gives the work an <em>identity</em> — §B6 and §D3:
 *       no path runs with no identity, and {@code SystemContextRule} fails the
 *       build on any {@code @Scheduled} method that does not enter one.</li>
 *   <li>{@link ScopeContext} gives it an <em>entitlement</em> — the installation's
 *       own site, from configuration. The scope seam grants background work
 *       nothing by default: a loop that entered the system context and forgot the
 *       scope reads zero rows and its writes are refused, which is the intended
 *       outcome rather than a gap.</li>
 * </ul>
 *
 * <p>Wiring the two together — "system work automatically gets the installation's
 * scope" — would be convenient and would be the one bypass nobody ever notices,
 * because system work has no user to notice on its behalf. So it is written out
 * here, once, where it can be read.
 */
@Slf4j
@RequiredArgsConstructor
public class IngestTasks {

	private static final String SERVICE = "orca-edge";

	private final LaneOwnership ownership;
	private final DeliveryPump pump;
	private final String siteExternalId;

	/**
	 * Takes and renews this instance's per-lane leases.
	 *
	 * <p>Runs more often than the lease is long — {@code LeaseConfigurationValidator}
	 * refuses to start a service where that is not true, because a renewal that
	 * always arrives after expiry means two instances can both believe they own a
	 * lane and nothing says so until they both write.
	 */
	@Scheduled(fixedDelayString = "${orca.edge.ownership.interval:5s}")
	public void reconcileLaneOwnership() {
		SystemContext.runAs(new SystemIdentity(SERVICE, "lane-ownership"), () ->
				ScopeContext.runIn(installationScope(), ownership::reconcile));
	}

	/** Drains every owned lane's buffer to runtime, in order. */
	@Scheduled(fixedDelayString = "${orca.edge.pump.interval:1s}")
	public void drainBuffer() {
		SystemContext.runAs(new SystemIdentity(SERVICE, "delivery-pump"), () ->
				ScopeContext.runIn(installationScope(), () -> {
					int acked = pump.drainOnce();
					if (acked > 0) {
						log.debug("delivery pump acknowledged {} event(s)", acked);
					}
				}));
	}

	/**
	 * The installation's own site.
	 *
	 * <p>Configuration, not derivation. This is an appliance: which site it is
	 * belongs to the installation (§C1 — exactly one site is primary, and it is the
	 * one the licence binds to), and deriving it per cycle from whatever happens to
	 * be in the database would make a mis-seeded world model silently redirect a
	 * gate's traffic.
	 */
	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}
}
