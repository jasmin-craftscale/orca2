package com.lynxis.orca.platform.scope.readiness;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * The published views this service requires before it will serve.
 *
 * <p>{@code orca-core} publishes read-only views onto its world model, and other
 * services read them in their own transaction (§C1, ADR-009). That creates a
 * deployment ordering: core must have migrated before a service that reads its
 * views starts.
 *
 * <p><strong>The ordering is a deployment concern, not a build one.</strong> It
 * is handled where it belongs — deployment order, plus this check. A shared
 * migrations module was the alternative and was rejected: it would centralise
 * schema definition away from the services that own it, and it would not remove
 * the dependency, only hide it behind one runner.
 *
 * <p>Names are fully qualified — {@code schema.view} — because a bare view name
 * resolves against the caller's own default schema, which is the one schema the
 * view is certainly not in.
 *
 * <p><strong>Empty in Phase 0.</strong> orca-core publishes no views yet, because
 * Phase 0 builds no world model. The check exists now rather than later because a
 * service that starts and then fails on its first query is far harder to diagnose
 * than one that refuses to start and says why — and because wiring it after the
 * consumers exist means wiring it into six services instead of one.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "orca")
public class RequiredViews {

	/** This service's own name, used in the failure message so the log says who refused. */
	private String service = "unknown";

	/** Fully qualified {@code schema.view} names. Empty means this service depends on none. */
	private List<String> requiredViews = List.of();
}
