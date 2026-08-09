package com.lynxis.orca.edge.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.lynxis.orca.platform.lease.Lease;
import com.lynxis.orca.platform.lease.LeaseManager;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.extern.slf4j.Slf4j;

/**
 * Which lanes this instance owns right now.
 *
 * <p>Cameras and device hosts each address a single endpoint. So while
 * everything else runs on every instance, device ingestion for a given lane is
 * owned by one instance at a time. This is the one place the platform is not
 * symmetric, and the reason is the hardware contract rather than the design.
 *
 * <p><strong>Per lane, not per site</strong>: the frozen
 * contracts are per lane, and one stuck owner must not idle a whole site. The
 * lease name is {@code edge.ingest:lane:<lane>} — per-lane scope rides in the
 * lease name, which is how one mechanism covers a retention job, a feed reader and
 * this alike.
 *
 * <p><strong>Holding a lease is not permission to write.</strong> Ownership says
 * which lane's traffic this instance handles; the fence token is what makes a
 * write safe, and every buffer write goes through {@code FencedWrite} carrying the
 * lease returned here. A stalled instance that wakes up still holding a
 * {@link Lease} object has an old token, and the database refuses its write.
 */
@Slf4j
public class LaneOwnership {

	/** The holder identity read by operators in the lease table. */
	public static final String LEASE_PREFIX = "edge.ingest:lane:";

	private final LeaseManager leaseManager;
	private final ScopeSeam seam;
	private final String holderId;
	private final Duration leaseDuration;
	private final String siteExternalId;

	/** The leases currently held, by lane. Replaced wholesale on every cycle. */
	private final Map<String, Lease> held = new ConcurrentHashMap<>();

	public LaneOwnership(LeaseManager leaseManager, ScopeSeam seam, String holderId,
			Duration leaseDuration, String siteExternalId) {
		this.leaseManager = leaseManager;
		this.seam = seam;
		this.holderId = holderId;
		this.leaseDuration = leaseDuration;
		this.siteExternalId = siteExternalId;
	}

	/**
	 * Acquires or renews this instance's claim on every lane the site has.
	 *
	 * <p>Called on a schedule, under an explicit system identity and an explicit
	 * scope — both established by the caller, because neither is implied by being
	 * background work.
	 */
	public void reconcile() {
		for (String lane : lanesAtThisSite()) {
			Lease current = held.get(lane);
			Optional<Lease> renewed = current == null
					? leaseManager.acquire(LEASE_PREFIX + lane, holderId, leaseDuration)
					: leaseManager.renew(current, leaseDuration);

			if (renewed.isPresent()) {
				if (current == null) {
					log.info("edge instance {} took ownership of lane {} (fence {})",
							holderId, lane, renewed.get().fenceToken());
				}
				held.put(lane, renewed.get());
			}
			else if (current != null) {
				// Lost it — a stall, a pause, a network partition. Stop serving this
				// lane immediately. The successor's fence token is already higher, so
				// any write still in flight from here will be refused anyway; giving
				// up promptly is what keeps the two from both answering a camera.
				log.warn("edge instance {} LOST ownership of lane {} — another instance holds it now",
						holderId, lane);
				held.remove(lane);
			}
			else {
				// Somebody else owns it. Ordinary, and not worth a log line per cycle.
				log.debug("lane {} is owned elsewhere", lane);
			}
		}
	}

	/** Whether this instance may serve that lane's cameras and write its captures. */
	public boolean owns(String laneExternalId) {
		return held.containsKey(laneExternalId);
	}

	/** The lease to present with every write for that lane, if this instance holds it. */
	public Optional<Lease> leaseFor(String laneExternalId) {
		return Optional.ofNullable(held.get(laneExternalId));
	}

	public List<String> ownedLanes() {
		return List.copyOf(held.keySet());
	}

	/** Gives up every lane. For a clean shutdown, so a restart does not wait out an expiry. */
	public void releaseAll() {
		held.keySet().forEach(lane -> log.info("edge instance {} releasing lane {}", holderId, lane));
		held.clear();
	}

	/**
	 * The site's lanes, read from core's published view through the seam.
	 *
	 * <p>Reads core's published view in edge's own transaction, with no network hop; nothing
	 * core has not published. Out-of-service lanes are still owned — a lane an
	 * operator has taken out of service still has a camera that may connect, and
	 * refusing to own it would leave that camera talking to nobody.
	 */
	public List<String> lanesAtThisSite() {
		return seam.select(ScopedSelect.from("core.topology_lane")
						.columns("lane_external_id")
						.scopedBy("site_external_id")
						.orderBy("lane_external_id"),
				(rs, row) -> rs.getString("lane_external_id"));
	}

	public String siteExternalId() {
		return siteExternalId;
	}
}
