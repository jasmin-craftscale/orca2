package com.lynxis.orca.edge.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.edge.api.generated.InternalBufferApi;
import com.lynxis.orca.edge.api.generated.model.BufferStats;
import com.lynxis.orca.edge.api.generated.model.BufferStatsEnvelope;
import com.lynxis.orca.edge.api.generated.model.LaneBufferStats;
import com.lynxis.orca.edge.domain.BufferStatsService;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;

/**
 * §C3's capture-buffer diagnostics, hand-written over a generated interface
 * (ADR-014).
 *
 * <p><strong>Scope comes from configuration, never from the request</strong>, for
 * the same reason it does on {@code /internal/commands/v1}: the credential on
 * {@code /internal/**} is a per-installation shared secret (ADR-011) and cannot
 * prove which peer is calling, so a site identifier on the wire would be a value the
 * caller chose.
 */
@RestController
public class BufferStatsController implements InternalBufferApi {

	private final BufferStatsService stats;
	private final String siteExternalId;

	public BufferStatsController(BufferStatsService stats, String siteExternalId) {
		this.stats = stats;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<BufferStatsEnvelope> bufferStats() {
		BufferStatsService.Stats read = ScopeContext.callIn(installationScope(), stats::read);

		BufferStats data = new BufferStats()
				.siteExternalId(read.siteExternalId())
				.observedAt(at(read.observedAt()))
				.totalDepth(read.totalDepth())
				.totalDead(read.totalDead());
		read.lanes().forEach(lane -> data.addLanesItem(new LaneBufferStats()
				.laneExternalId(lane.laneExternalId())
				.ownedByThisInstance(lane.ownedByThisInstance())
				.depth(lane.depth())
				.dead(lane.dead())
				.oldestUndeliveredAt(at(lane.oldestUndeliveredAt()))
				.oldestUndeliveredAgeSeconds(lane.oldestUndeliveredAgeSeconds())));

		return ResponseEntity.ok(new BufferStatsEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(data));
	}

	private static OffsetDateTime at(java.time.Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}
}
