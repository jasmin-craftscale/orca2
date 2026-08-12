package com.lynxis.orca.runtime.execution.internal;

import java.util.Map;
import java.util.Set;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.delegate.spi.StepPayloadSink;
import com.lynxis.orca.runtime.execution.delegate.spi.VisitDataSink;
import com.lynxis.orca.runtime.execution.delegate.support.PayloadJson;
import com.lynxis.orca.runtime.execution.engine.flowable.StepPayloads;
import com.lynxis.orca.runtime.execution.persistence.VisitDatasetRepository;

/**
 * The one writer of {@code visit_dataset} — the durable half of what a visit
 * knows.
 *
 * <p>Writes happen inside whatever transaction produced the data (the engine's,
 * for a delegate; the caller's, for a signal), so a visit cannot advance past
 * the step that produced a value while the value itself is missing — the same
 * write-behind contract the step recorder holds, applied to payload rather than
 * to the timeline.
 */
public final class VisitDataWriter implements VisitDataSink, StepPayloadSink {

	private final VisitDatasetRepository dataset;
	private final Scope installationScope;
	private final String siteExternalId;

	public VisitDataWriter(VisitDatasetRepository dataset, String siteExternalId) {
		this.dataset = dataset;
		this.siteExternalId = siteExternalId;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	@Override
	public void record(String rootEngineInstanceId, Map<String, Object> values) {
		if (rootEngineInstanceId == null || values == null || values.isEmpty()) {
			return;
		}
		ScopeContext.runIn(installationScope, () -> {
			long visitId = dataset.visitIdByEngineInstance(rootEngineInstanceId)
					.orElseThrow(() -> new IllegalStateException(
							"no execution row correlates engine instance '" + rootEngineInstanceId
									+ "' — dataset values would be written against no visit"));
			writeAll(visitId, values);
		});
	}

	@Override
	public void recordByExecutionUuid(String executionExternalId, Map<String, Object> values) {
		if (executionExternalId == null || values == null || values.isEmpty()) {
			return;
		}
		ScopeContext.runIn(installationScope, () -> {
			long visitId = dataset.visitIdByExternalId(executionExternalId)
					.orElseThrow(() -> new IllegalStateException(
							"no visit '" + executionExternalId
									+ "' — dataset values would be written nowhere"));
			writeAll(visitId, values);
		});
	}

	@Override
	public void offer(String executionExternalId, String nodeUuid, Map<String, Object> payload) {
		if (executionExternalId == null || nodeUuid == null) {
			return;
		}
		ScopeContext.runIn(installationScope, () ->
				dataset.engineInstanceByExternalId(executionExternalId)
						.ifPresent(instance -> StepPayloads.offer(
								instance, "n_" + nodeUuid, PayloadJson.of(payload))));
	}

	private void writeAll(long visitId, Map<String, Object> values) {
		values.forEach((key, value) -> {
			if (key == null || key.isBlank()) {
				return;
			}
			dataset.write(siteExternalId, visitId, key, value == null ? null : String.valueOf(value));
		});
	}
}
