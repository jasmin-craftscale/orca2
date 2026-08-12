/**
 * The authored estate as a graph, and one container of it written as the
 * designer-JSON publish payload the compiler consumes. The model and the payload
 * assembly are here; the reader that populates the graph from stored designs is
 * deliberately absent — where 2.0 stores designer-authored workflows is an open
 * storage decision, and the validation routes take the draft in the request body
 * precisely so they need no storage at all.
 */
package com.lynxis.orca.runtime.integration.designer;
