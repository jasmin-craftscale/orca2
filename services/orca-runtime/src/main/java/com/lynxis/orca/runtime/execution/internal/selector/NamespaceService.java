package com.lynxis.orca.runtime.execution.internal.selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.api.dto.SelectorNamespace;
import com.lynxis.orca.runtime.execution.compiler.DesignerGraph;
import com.lynxis.orca.runtime.execution.internal.DefinitionRegistry;
import com.lynxis.orca.runtime.execution.persistence.VisitDatasetRepository;

/**
 * What the builder may offer at a point in a draft.
 *
 * <p>Assembled from three sources of different strength, and the answer says which is which
 * rather than blending them:
 *
 * <ul>
 *   <li><b>The graph</b> — a step's ancestors are computable from the draft alone, so every
 *       step can be marked with whether its data will already exist at the point being edited.
 *       That is a ranking signal and not a rule: the builder offers everything and puts the
 *       likely answers first.</li>
 *   <li><b>Observed writes</b> — the dataset keys visits of this workflow really produced,
 *       read from the newest visits. Strong evidence, and it grows as the runtime runs; empty
 *       for a workflow that has never run here, which is the honest answer rather than a
 *       guess.</li>
 *   <li><b>Authored reads</b> — keys some condition in this draft already names. Weaker: it
 *       proves the key is part of the vocabulary, not that anything writes it.</li>
 * </ul>
 *
 * <p><b>What this deliberately does not claim.</b> Per-key producer scoping — "only the keys
 * some UPSTREAM step writes" — needs each step's field and dataset mappings, and the publish
 * payload this runtime compiles does not carry them. Offering keys the whole workflow knows,
 * labelled by provenance, is the true answer available today; claiming ancestry for them
 * would be a guess dressed as a guarantee.
 */
public final class NamespaceService {

	/** The evaluator's helper functions, as an author writes them. */
	private static final List<String> HELPERS = List.of(
			"$.helper.count(…)", "$.helper.exists(…)", "$.helper.increment(…)",
			"$.helper.decrement(…)", "$.helper.removespaces(…)", "$.helper.parse(…)",
			"$.helper.toUpper(…)");

	/** How many of the newest visits carry the observed vocabulary — see the repository note. */
	private static final int OBSERVED_VISIT_SAMPLE = 100;

	private final VisitDatasetRepository dataset;
	private final DefinitionRegistry definitions;
	private final Scope installationScope;

	public NamespaceService(VisitDatasetRepository dataset, DefinitionRegistry definitions,
			String siteExternalId) {
		this.dataset = dataset;
		this.definitions = definitions;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	/**
	 * @param nodeUuid the step being edited, used only to mark which steps precede it; when
	 *     null there is no editing point to be upstream of, so every step is marked as
	 *     preceding and the ranking is simply flat
	 */
	public SelectorNamespace forDraft(String designerJson, String nodeUuid) {
		long workflowId = DesignerGraph.workflowIdOf(designerJson);
		List<SelectorNamespace.NodeRef> nodes = DesignerGraph.nodesFor(designerJson, nodeUuid);

		// Provenance, not a merged list: an author choosing between two keys should be able to
		// see which one this workflow has actually been seen to produce.
		Map<String, String> provenance = new LinkedHashMap<>();
		for (String key : DesignerGraph.authoredDatasetKeys(designerJson)) {
			provenance.put(key, "AUTHORED");
		}
		Set<String> observed = ScopeContext.callIn(installationScope,
				() -> dataset.observedKeysOfNewestVisits(workflowId, OBSERVED_VISIT_SAMPLE));
		for (String key : observed) {
			provenance.merge(key, "OBSERVED", (authored, alsoObserved) -> "AUTHORED,OBSERVED");
		}
		List<SelectorNamespace.DatasetKey> keys = new ArrayList<>();
		new TreeSet<>(provenance.keySet()).forEach(key -> keys.add(new SelectorNamespace.DatasetKey(
				key, "$.workflow.dataset." + key, provenance.get(key))));

		List<SelectorNamespace.WorkflowRef> workflows = new ArrayList<>();
		definitions.deployedWorkflows().forEach((uuid, id) -> {
			if (id != workflowId) {
				workflows.add(new SelectorNamespace.WorkflowRef(uuid, id, null,
						"$." + uuid + ".dataset"));
			}
		});

		return new SelectorNamespace(List.copyOf(nodes), List.copyOf(keys),
				List.copyOf(workflows), HELPERS);
	}
}
