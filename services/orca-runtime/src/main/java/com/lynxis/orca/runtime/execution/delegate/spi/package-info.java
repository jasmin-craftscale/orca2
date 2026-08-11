/**
 * The seams the compiled delegates call through: data sinks for what a step
 * produced, the connector gateway, and the visit-identity bridge between the
 * engine's instance id and the ORCA row selectors resolve against. Adapters
 * live in the modules that own the capability; the delegates stay thin.
 */
package com.lynxis.orca.runtime.execution.delegate.spi;
