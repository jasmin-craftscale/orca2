package com.lynxis.orca.runtime.execution.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitReadRepository;

/**
 * Answers "what happened to this truck?" from {@code execution}'s own rows.
 *
 * <h2>Why this is not a grid, and why that distinction is worth keeping</h2>
 *
 * <p>The operator grids are pre-built projections, maintained so that a grid never
 * fans out across modules. This is the opposite thing on purpose: the module that
 * owns the table answering about its own table. That is what lets it carry
 * {@link #currentActivity} — the step the engine is parked at <em>right now</em> —
 * which a projection cannot hold without being rebuilt on every engine transition.
 *
 * <p>So: a board that must be fast and eventually consistent is a projection; a
 * support question that must be exactly true this instant is this.
 *
 * <h2>The default window</h2>
 *
 * <p>A search with no time window is the one shape this service will not perform.
 * {@code execution} is traffic-growing, so an unpredicated read is a table scan
 * whose cost rises for the life of the installation — and the seam leads every
 * statement with the site condition, so on a single-site installation that scan is
 * the whole table. A caller that gives no window gets {@link #DEFAULT_WINDOW},
 * because a default that is visibly wrong is safer than one that is invisibly
 * unbounded.
 */
public class VisitQueryService {

	/**
	 * How far back a search reaches when the caller names no window.
	 *
	 * <p>A day, because the question this route answers is almost always about a
	 * truck that is still at the gate or left recently. A caller wanting more says so.
	 *
	 * <p>⚠️ <strong>The window is computed from the JVM clock, deliberately.</strong>
	 * The rule that the database's clock decides governs state two instances must
	 * agree on — a lease's expiry, an idempotency claim, anything where disagreement
	 * changes who wins. A search window is none of those: it narrows one caller's
	 * read, nothing is written from it, and two instances answering with windows a
	 * few milliseconds apart is not a disagreement anyone can observe.
	 */
	public static final Duration DEFAULT_WINDOW = Duration.ofDays(1);

	private final VisitReadRepository visits;
	private final AdmissionRepository lanes;
	private final ProcessEngineGateway engine;

	public VisitQueryService(VisitReadRepository visits, AdmissionRepository lanes,
			ProcessEngineGateway engine) {
		this.visits = visits;
		this.lanes = lanes;
		this.engine = engine;
	}

	/**
	 * @param laneExternalId a lane in core's published vocabulary, or null for every
	 *                       lane. ⚠️ A lane this installation does not publish returns
	 *                       <em>no visits</em> rather than every visit — narrowing that
	 *                       cannot be resolved must never widen the result
	 */
	public List<VisitView> search(String laneExternalId, String status, String plate, Instant since,
			int limit) {

		Long laneId = null;
		if (laneExternalId != null && !laneExternalId.isBlank()) {
			Optional<Long> resolved = lanes.laneIdOf(laneExternalId);
			if (resolved.isEmpty()) {
				return List.of();
			}
			laneId = resolved.get();
		}

		Instant window = since != null ? since : Instant.now().minus(DEFAULT_WINDOW);

		// The live step is deliberately NOT read here. One engine call per row would
		// make a 500-row page 500 round trips to the engine, and a list is a list of
		// what happened rather than a live board.
		List<VisitReadRepository.VisitRow> rows = visits.search(laneId, status, plate, window, limit);

		// One lookup for the whole page, not one per row. Resolving lanes row by row
		// costs a query per result — measured at 67 for 66 rows — and the cost grows
		// with the page, so the largest permitted page is the worst case.
		Map<Long, String> laneNames = lanes.laneExternalIdsOf(
				rows.stream().map(VisitReadRepository.VisitRow::laneId).toList());

		return rows.stream()
				.map(row -> view(row, laneNames.get(row.laneId()), null))
				.toList();
	}

	/** One visit, with the engine's live position when it is still running. */
	public Optional<VisitView> byExternalId(String visitExternalId) {
		return visits.byExternalId(visitExternalId)
				.map(row -> view(row, lanes.laneExternalIdOf(row.laneId()).orElse(null),
						livePositionOf(row)));
	}

	/**
	 * The visit running on a lane, with the engine's live position.
	 *
	 * @throws AdmissionService.LaneNotAtThisInstallationException when this
	 *         installation does not publish the lane — refused rather than answered
	 *         "no visit", because those are different facts and an operator acts on
	 *         them differently
	 */
	public Optional<VisitView> onLane(String laneExternalId) {
		long laneId = lanes.laneIdOf(laneExternalId)
				.orElseThrow(() -> new AdmissionService.LaneNotAtThisInstallationException(laneExternalId));

		return visits.activeOnLane(laneId)
				.map(row -> view(row, laneExternalId, livePositionOf(row)));
	}

	/**
	 * The step the engine is parked at, or null once it is not running.
	 *
	 * <p>Absent rather than stale: reporting a finished visit's last step as its
	 * current one is a claim a support engineer would act on, and it would be false.
	 */
	private String livePositionOf(VisitReadRepository.VisitRow row) {
		return row.processInstanceId() == null
				? null
				: engine.currentActivity(row.processInstanceId()).orElse(null);
	}

	private static VisitView view(VisitReadRepository.VisitRow row, String laneExternalId,
			String currentActivity) {
		return new VisitView(row.externalId(), laneExternalId, row.status(), row.plate(),
				row.startedAt(), row.completedAt(), currentActivity);
	}
}
