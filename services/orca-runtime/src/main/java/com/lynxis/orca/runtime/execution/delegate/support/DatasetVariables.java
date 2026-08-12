package com.lynxis.orca.runtime.execution.delegate.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The dataset↔variable naming contract — the runtime half of the {@code orca:field}
 * sidecar (I8). Compiled conditions read {@code v_workflow_dataset_<key>}; every place a
 * dataset value enters the engine (start payload, wait signal, connector extraction) mirrors
 * it under exactly the name the compiler bound. MUST match {@code ConditionJuel}'s
 * sanitization character-for-character or conditions silently read null.
 */
public final class DatasetVariables {

    private DatasetVariables() {
    }

    /** {@code plt_bm_found} → {@code v_workflow_dataset_plt_bm_found}. */
    public static String varForDatasetKey(String datasetKey) {
        return varForSelector("$.workflow.dataset." + datasetKey);
    }

    /** ConditionJuel's var naming, verbatim. */
    public static String varForSelector(String selectorPath) {
        return "v_" + selectorPath.replaceAll("^\\$\\.", "").replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /** A dataset delta as the variable map the engine's conditions read. */
    public static Map<String, Object> mirror(Map<String, Object> dataset) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (dataset != null) {
            dataset.forEach((key, value) -> variables.put(varForDatasetKey(key), value));
        }
        return variables;
    }
}
