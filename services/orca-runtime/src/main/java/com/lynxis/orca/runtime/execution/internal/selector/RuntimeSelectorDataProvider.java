package com.lynxis.orca.runtime.execution.internal.selector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.api.UnresolvableSelectorException;
import com.lynxis.orca.runtime.execution.internal.DefinitionRegistry;
import com.lynxis.orca.runtime.execution.persistence.NodeExecutionTraceRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitDatasetRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitTreeRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitTreeRepository.VisitNode;
import com.lynxis.orca.runtime.execution.selector.DtoErrorException;
import com.lynxis.orca.runtime.execution.selector.GoErrorException;
import com.lynxis.orca.runtime.execution.selector.SelectorDataProvider;

/**
 * The ported selector evaluator, bound to THIS runtime's tables — the thing that turns a
 * proven-in-tests evaluator into one that can answer questions about live visits, and
 * therefore the thing every outbound connector body is built through.
 *
 * <p>The provider spans two worlds and says so. <b>This visit</b> — its dataset, its steps,
 * its columns — is the runtime's own schema, answered here through the scope seam.
 * <b>Configuration</b> — lanes, devices, site settings, workflow aliases — belongs to other
 * schemas and goes through {@link SiteCatalog}, which is a port precisely so that execution
 * keeps owning only its own schema.
 *
 * <p>Two fidelity decisions, both taken from the platform's own repository rather than from
 * what would be tidier:
 * <ul>
 *   <li><b>A missing dataset key is an empty string, not an error.</b> The Go repository's
 *       {@code Find} into a scalar leaves it empty and returns nil, and half the estate's
 *       conditions are written expecting exactly that.</li>
 *   <li><b>A node's payload is the NEWEST row for that node in that visit.</b> A visit that
 *       re-enters a node answers with the latest pass, which is what the platform's
 *       newest-first ordering does today.</li>
 * </ul>
 *
 * <p>What is deliberately NOT ported: the payload-marker fallback. The platform stores a
 * sentinel in {@code execution_payload} for some rows and resolves it by searching backwards
 * for a payload persisted at-or-before that step. This runtime writes each step's payload
 * directly, so there is no marker to resolve — and reproducing the search would only
 * reproduce the ambiguity it exists to paper over.
 */
public final class RuntimeSelectorDataProvider implements SelectorDataProvider {

	/** A visit tree deeper than this is a cycle; fail the question rather than the process. */
	private static final int MAX_VISIT_DEPTH = 32;

	private final VisitTreeRepository visits;
	private final NodeExecutionTraceRepository trace;
	private final VisitDatasetRepository dataset;
	private final DefinitionRegistry definitions;
	private final SiteCatalog site;
	private final Scope installationScope;

	public RuntimeSelectorDataProvider(VisitTreeRepository visits,
			NodeExecutionTraceRepository trace, VisitDatasetRepository dataset,
			DefinitionRegistry definitions, SiteCatalog site, String siteExternalId) {
		this.visits = visits;
		this.trace = trace;
		this.dataset = dataset;
		this.definitions = definitions;
		this.site = site;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	// ------------------------------------------------------------------ this visit

	/**
	 * ABSENT, not unresolvable: a key nothing has written yet is a legitimate empty, and half
	 * the estate's conditions are authored expecting exactly that.
	 */
	@Override
	public Object fetchDataSets(String key, int workflowExecutionId) {
		return scoped(() -> dataset.value(workflowExecutionId, key)
				.map(value -> value == null ? "" : value)
				.orElse(""));
	}

	@Override
	public Map<String, Object> fetchNodeExecution(int workflowId, String nodeUuid, int workflowExecutionId) {
		if (safeUuid(nodeUuid) == null) {
			return Map.of();
		}
		// Newest pass wins — see the class note. The map keys are the platform's own
		// column spellings; the evaluator reads them by exactly these names.
		return scoped(() -> trace.latestStep(workflowExecutionId, nodeUuid)
				.map(step -> {
					Map<String, Object> row = new LinkedHashMap<String, Object>();
					row.put("NodeExecutionID", step.nodeExecutionId());
					row.put("ExecutionPayload",
							step.executionPayload() == null ? "" : step.executionPayload());
					row.put("ExecutionStartTime", step.enteredAt());
					return row;
				})
				.orElseGet(Map::of));
	}

	/**
	 * A column off the visit row. The estate authors the PLATFORM's column names, so the
	 * labels below are the platform's and the answers are this schema's — a rename is not a
	 * missing answer. An unknown column can never hold a value: authoring error, not missing
	 * data, and the visit stops instead of branching on an empty read.
	 */
	@Override
	public Object fetchWorkflowExecutionData(String columnName, int workflowExecutionId) {
		VisitNode visit = scoped(() -> visits.byId(workflowExecutionId)).orElse(null);
		if (visit == null) {
			// The evaluator probes with an unresolved id (0) on its way through the alias
			// path; the platform answers "" and so does this. Absent, not unresolvable.
			return "";
		}
		if (columnName == null || columnName.isBlank()) {
			throw new UnresolvableSelectorException("", "a visit-column selector names no column");
		}
		return switch (columnName.toLowerCase(java.util.Locale.ROOT)) {
			case "workflow_execution_id" -> visit.executionId();
			// The estate says "uuid"; this schema's stable identifier is external_id.
			case "workflow_execution_uuid", "execution_uuid" -> visit.externalId();
			// Null for visits of the hand-written gate process — absent, not unresolvable.
			case "workflow_id" -> visit.workflowId() == null ? "" : visit.workflowId();
			case "lane_id" -> visit.laneId();
			// Same rename: the estate authors site_uuid; the site's identifier here is
			// site_external_id, carrying the same value the platform keys the site by.
			case "site_uuid" -> visit.siteExternalId();
			// The estate authors `execution_status`; this schema calls the same value
			// `status`.
			case "status", "execution_status" -> visit.status();
			// The platform's visit_id groups the executions of one truck's visit. Here that
			// grouping IS the parent-child tree, so the visit is its root — the same
			// identity the same-visit sibling lookup uses.
			case "visit_id" -> scoped(() -> rootOf(visit)).executionId();
			case "created_at", "started_at" -> visit.startedAt();
			case "completed_at" -> visit.completedAt() == null ? "" : visit.completedAt();
			default -> throw new UnresolvableSelectorException(columnName,
					"'" + columnName + "' is not a column of the visit");
		};
	}

	/**
	 * <b>Not what its name says.</b> The platform's method is called
	 * "getPreviousWorkflowExecutionID" and its SQL resolves something else entirely: the
	 * execution of <em>workflow {@code workflowId}</em> that shares a visit with
	 * {@code workflowExecutionId} — a SIBLING lookup, not a history one. It is what every
	 * cross-workflow selector runs through ({@code $.<alias>.dataset.…}, 141 of them in the
	 * estate), and reading the name instead of the query is how you get an empty answer that
	 * silently routes a truck down the wrong branch.
	 *
	 * <p>A visit here is the parent-child execution tree rather than a {@code visit_id}
	 * column: walk to the root, then find the member running that workflow. Same question,
	 * by the identity this schema actually has.
	 */
	@Override
	public int getPreviousWorkflowExecutionId(int workflowId, int workflowExecutionId) {
		return scoped(() -> {
			VisitNode from = visits.byId(workflowExecutionId).orElse(null);
			if (from == null) {
				return 0;
			}
			return memberRunning(rootOf(from), workflowId, 0)
					.map(member -> (int) member.executionId())
					.orElse(0);
		});
	}

	/** Bounded: a cycle in parent links would otherwise hang a visit rather than fail it. */
	private VisitNode rootOf(VisitNode visit) {
		VisitNode current = visit;
		for (int hop = 0; hop < MAX_VISIT_DEPTH && current.parentExecutionId() != null; hop++) {
			VisitNode parent = visits.byId(current.parentExecutionId()).orElse(null);
			if (parent == null) {
				return current;
			}
			current = parent;
		}
		return current;
	}

	/** Depth-first from the root, in key order — deterministic, like the platform's First(). */
	private Optional<VisitNode> memberRunning(VisitNode visit, int workflowId, int depth) {
		if (visit.workflowId() != null && visit.workflowId() == workflowId) {
			return Optional.of(visit);
		}
		if (depth >= MAX_VISIT_DEPTH) {
			return Optional.empty();
		}
		for (VisitNode child : visits.childrenOf(visit.executionId())) {
			Optional<VisitNode> found = memberRunning(child, workflowId, depth + 1);
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	@Override
	public Object fetchNodeConfigurationDetails() {
		// The platform returns nil here — the method exists and answers nothing. Ported
		// as-is rather than invented, because a selector that reached it would be relying
		// on nil.
		return null;
	}

	// ------------------------------------------------------------------ configuration

	@Override
	public String getResourceConfigurations(int resourceId, String resourceType, String configKey) {
		return site.resourceConfiguration(resourceId, resourceType, configKey);
	}

	@Override
	public String extractLanePropertiesByLaneCode(String laneCode, String field) {
		return site.laneProperty(laneCode, field);
	}

	@Override
	public String fetchDeviceUrlForLane(String laneCode, String deviceAlias, String column) {
		return site.deviceUrlForLane(laneCode, deviceAlias, column);
	}

	/**
	 * Two of the tables the evaluator names are the runtime's own, wearing the platform's
	 * names: {@code workflow_executions} (the visit identifier it was handed → the int key
	 * every other call takes) and {@code workflow} (a cross-workflow selector's target).
	 * Both are answered here. Anything else is site configuration and goes to the catalog.
	 */
	@Override
	public int getPrimaryKeyByUuid(String tableName, String idColumnName, String uuidColumnName,
			String uuidValue) throws GoErrorException {
		if ("workflow_executions".equalsIgnoreCase(tableName)
				|| "workflow_execution".equalsIgnoreCase(tableName)
				|| "execution".equalsIgnoreCase(tableName)) {
			if (safeUuid(uuidValue) == null) {
				// NOT a dangling reference — a token that is not a uuid is how the evaluator
				// detects "this is an alias" and falls through to the alias path. Answering 0
				// is that control flow, so throwing here would break resolution rather than
				// catch a defect.
				return 0;
			}
			return scoped(() -> visits.byExternalId(uuidValue))
					.map(row -> (int) row.executionId())
					.orElseThrow(() -> new UnresolvableSelectorException(uuidValue,
							"no visit " + uuidValue));
		}
		// The cross-workflow family — 205 of the estate's 213 distinct selectors — asks for
		// this one, and the runtime knows it: the compiler derives proc_<workflowId> from the
		// uuid, so what is deployed IS the mapping. No catalog needed.
		if ("workflow".equalsIgnoreCase(tableName)) {
			if (safeUuid(uuidValue) == null) {
				return 0; // an alias, not a uuid — the evaluator's own fall-through
			}
			int workflowId = definitions.workflowIdFor(uuidValue);
			if (workflowId == 0) {
				// The cross-workflow family's first hop. Answering 0 here is what makes the
				// whole selector resolve empty three calls later, with nothing to read.
				throw new UnresolvableSelectorException(uuidValue,
						"'" + uuidValue + "' names no workflow deployed on this runtime");
			}
			return workflowId;
		}
		return site.primaryKeyByUuid(tableName, idColumnName, uuidColumnName, uuidValue);
	}

	@Override
	public WorkflowRef getWorkflowIdByAliasName(String workflowAliasName, int workflowExecutionId) {
		SiteCatalog.WorkflowAlias alias = site.workflowByAlias(workflowAliasName, workflowExecutionId);
		return new WorkflowRef(alias.id(), alias.uuid());
	}

	@Override
	public String getNodeUuidByAliasName(int workflowId, String nodeAliasName) {
		return site.nodeUuidByAlias(workflowId, nodeAliasName);
	}

	/**
	 * REFUSED, and flagged for a decision rather than implemented.
	 *
	 * <p>The platform runs this SQL <em>as authored content</em>: the text comes from a
	 * selector somebody typed into the designer and goes to the database unparameterised.
	 * Reproducing it would hand every designer user arbitrary SQL against the runtime's
	 * credentials, which is not something to do quietly. Whether the estate needs
	 * reference-data selectors at all, and if so what the safe shape is (a named view? a
	 * whitelist?), is a product decision — so this refuses by name and says why.
	 */
	@Override
	public Object executeQuery(String query) throws DtoErrorException {
		throw new UnsupportedOperationException(
				"reference-data selectors run authored SQL unparameterised against the "
						+ "runtime's credentials — refused pending a ruling on a safe shape "
						+ "(parameterised queries only)");
	}

	private <T> T scoped(Callable<T> work) {
		return ScopeContext.callIn(installationScope, work);
	}

	private static UUID safeUuid(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException notAUuid) {
			return null;
		}
	}
}
