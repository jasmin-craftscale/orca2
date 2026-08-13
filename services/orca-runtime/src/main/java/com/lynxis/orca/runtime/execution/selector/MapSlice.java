package com.lynxis.orca.runtime.execution.selector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * Go's {@code []map[string]interface{}} as a distinct runtime type. The Go evaluator
 * type-switches on it separately from {@code []interface{}} (reference-data results,
 * two-argument helper outputs, formatValue) — a plain List would erase a distinction the
 * ported control flow depends on.
 */
public final class MapSlice extends ArrayList<Map<String, Object>> {

    public MapSlice() {
    }

    public MapSlice(Collection<Map<String, Object>> items) {
        super(items);
    }
}
