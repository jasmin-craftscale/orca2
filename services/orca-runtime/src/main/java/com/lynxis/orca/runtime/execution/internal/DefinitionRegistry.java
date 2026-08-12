package com.lynxis.orca.runtime.execution.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.lynxis.orca.runtime.execution.engine.UnknownDefinitionException;

/**
 * workflow_uuid → deployed definition key. The publish pipeline registers on
 * deploy — validate → compile → freeze version → deploy → register is one path,
 * so a uuid that resolves here is by construction a deployed definition.
 *
 * <p>In-memory is the current scope; the durable registration rides the
 * definition catalog when the designer's publish surface lands. Until then the
 * registry is rebuilt the same way it is built: by whatever deploys.
 */
public final class DefinitionRegistry {

	private final Map<String, String> definitionKeyByWorkflowUuid = new ConcurrentHashMap<>();

	public void register(String workflowUuid, String definitionKey) {
		definitionKeyByWorkflowUuid.put(workflowUuid, definitionKey);
	}

	/**
	 * workflow_uuid → workflow_id, or 0 when the uuid names nothing deployed here.
	 *
	 * <p>The registry can answer this without asking anybody because the compiler
	 * DERIVES the definition key from the workflow id and nothing else
	 * ({@code proc_<workflowId>}). That turns out to matter a great deal: 205 of
	 * the estate's 213 distinct selectors are cross-workflow
	 * ({@code $.<workflowUuid>.dataset.…}) and each one resolves that uuid to an
	 * id first. Reading it from what the runtime already deployed keeps the whole
	 * cross-workflow surface inside this schema.
	 */
	public int workflowIdFor(String workflowUuid) {
		String key = definitionKeyByWorkflowUuid.get(workflowUuid);
		if (key == null || !key.startsWith("proc_")) {
			return 0;
		}
		try {
			return Integer.parseInt(key.substring("proc_".length()));
		}
		catch (NumberFormatException notAnId) {
			return 0;
		}
	}

	/** workflow_uuid → workflow_id for everything deployed here — the cross-workflow menu. */
	public Map<String, Long> deployedWorkflows() {
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		definitionKeyByWorkflowUuid.forEach((uuid, key) -> {
			long id = workflowIdFor(uuid);
			if (id > 0) {
				out.put(uuid, id);
			}
		});
		return Map.copyOf(out);
	}

	public String definitionKeyFor(String workflowUuid) {
		String key = definitionKeyByWorkflowUuid.get(workflowUuid);
		if (key == null) {
			// The exception's own text says "no deployed definition with key …" —
			// here the thing nothing deploys is the workflow uuid itself.
			throw new UnknownDefinitionException(workflowUuid);
		}
		return key;
	}
}
