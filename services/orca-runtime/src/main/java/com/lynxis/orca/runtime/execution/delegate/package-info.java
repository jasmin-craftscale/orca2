/**
 * The delegates compiled definitions bind to by name — {@code orcaConnectorDelegate},
 * {@code orcaDeviceEffectDelegate}, {@code orcaDisplayDelegate},
 * {@code orcaNotificationDelegate}. The names are load-bearing: the compiler emits them
 * into every process it produces, so a rename breaks every published definition at
 * runtime, not at compile time.
 *
 * <p>Each delegate is thin over a seam in {@code spi/}; the adapters live in the modules
 * that own the capability, and the unconfigured defaults refuse loudly. A concurrency
 * scar carried in from the reference implementation: Flowable field injection on Spring
 * singletons is a data race — parameters are read off the cached BPMN model instead.
 */
package com.lynxis.orca.runtime.execution.delegate;
