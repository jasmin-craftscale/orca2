/**
 * The ported selector evaluator's live binding: the data provider that answers
 * a selector's visit questions from this runtime's own tables through the scope
 * seam, and the catalog port for the configuration questions that belong to
 * other schemas — unbound until the adapter lands, refusing by name rather than
 * resolving to an empty string.
 */
package com.lynxis.orca.runtime.execution.internal.selector;
