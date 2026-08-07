package com.lynxis.orca.edge.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.edge.persistence.EventBufferRepository;

/**
 * What an operator asks when a truck went through and nothing happened.
 *
 * <p>§C3 names {@code /internal/buffer/stats} and Phase 1 did not build it, so a
 * {@code DEAD} event — one nobody could deliver, and the single event a site
 * operator most needs to see — was visible only to somebody with a database login.
 * {@code phase-1-report.md} §3 records that as operator blindness. This closes it.
 *
 * <h2>Why the age is here and not a later refinement</h2>
 *
 * <p><strong>Depth alone cannot tell a severed link from a busy morning.</strong> A
 * depth of 4 that is nine hours old is an outage; a depth of 400 that is four
 * seconds old is traffic. Those are opposite operational conclusions from numbers
 * that sort the same way, and an endpoint that reported only depth would invite the
 * wrong one.
 *
 * <h2>Why ownership is on the answer</h2>
 *
 * <p>§C3 gives one instance ownership of a lane at a time. An operator reading these
 * numbers from an instance that does <em>not</em> own a lane is reading a backlog
 * nothing at that address is draining — the same figures meaning two different
 * things depending on which host was called, with nothing on the response saying
 * which. So it says which.
 *
 * <h2>What this is not</h2>
 *
 * <p><strong>Not a consistent snapshot.</strong> The lanes are read one at a time
 * and not inside a transaction, so a lane counted early and a lane counted late are
 * a fraction of a second apart. For a diagnostic that answers "how far behind are
 * we", that is the right trade — holding a read transaction over every lane's buffer
 * to make a number tidy would put a diagnostic endpoint in the path of the thing it
 * is diagnosing. {@code observedAt} is on the response so the reader knows what they
 * have.
 */
public class BufferStatsService {

	private final EventBufferRepository buffer;
	private final LaneOwnership ownership;
	private final String siteExternalId;

	public BufferStatsService(EventBufferRepository buffer, LaneOwnership ownership,
			String siteExternalId) {
		this.buffer = buffer;
		this.ownership = ownership;
		this.siteExternalId = siteExternalId;
	}

	/**
	 * Reads every lane this site publishes, not only the ones with traffic.
	 *
	 * <p>A lane missing from the answer would be indistinguishable from a lane whose
	 * buffer is empty, and those are very different things when the question is "why
	 * did nothing happen".
	 */
	public Stats read() {
		Instant observedAt = Instant.now();
		List<LaneStats> lanes = new ArrayList<>();

		for (String lane : ownership.lanesAtThisSite()) {
			Optional<Instant> oldest = buffer.oldestUndeliveredAt(lane);
			lanes.add(new LaneStats(
					lane,
					ownership.owns(lane),
					buffer.depthOf(siteExternalId, lane),
					buffer.countByStatusOnLane(lane, BufferedEvent.DEAD),
					oldest.orElse(null),
					oldest.map(at -> Math.max(0, Duration.between(at, observedAt).toSeconds()))
							.orElse(null)));
		}

		return new Stats(siteExternalId, observedAt,
				lanes.stream().mapToLong(LaneStats::depth).sum(),
				lanes.stream().mapToLong(LaneStats::dead).sum(),
				List.copyOf(lanes));
	}

	/**
	 * @param observedAt this instance's clock. See the class Javadoc: a timestamp for
	 *                   a diagnostic view, not a snapshot boundary
	 */
	public record Stats(String siteExternalId, Instant observedAt, long totalDepth, long totalDead,
			List<LaneStats> lanes) {
	}

	/**
	 * @param oldestUndeliveredAt      null when the lane's buffer is empty — which is
	 *                                 a different fact from "zero seconds old" and is
	 *                                 kept distinguishable on the wire
	 * @param oldestUndeliveredAgeSeconds clamped at zero: the row's timestamp is the
	 *                                 database's and {@code observedAt} is this
	 *                                 process's, and a diagnostic must not report a
	 *                                 negative age because two clocks disagree by
	 *                                 milliseconds
	 */
	public record LaneStats(String laneExternalId, boolean ownedByThisInstance, long depth, long dead,
			Instant oldestUndeliveredAt, Long oldestUndeliveredAgeSeconds) {
	}
}
