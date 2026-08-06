/**
 * orca-runtime's service-level API surface.
 *
 * <p>This is <em>not</em> a sixth module. §C2 names five — execution, workitem,
 * integration, notify, readmodel — and the module wall depends on exactly those
 * names. What lives here is the service's own edge: the generated interfaces and
 * the routes that belong to the service rather than to any one module, of which
 * Phase 0 has one.
 */
package com.lynxis.orca.runtime.api;
