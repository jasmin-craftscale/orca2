/**
 * The execution module's internals behind its {@code api} surface: the facade
 * that resumes and inspects visits (admission owns starting them), the registry
 * the publish path fills, and the one writer of the visit's dataset. Nothing in
 * here is for other modules — the module wall leaves only {@code api} open.
 */
package com.lynxis.orca.runtime.execution.internal;
