/**
 * Shared web envelope and system context.
 *
 * <p>One response shape for every service, with a machine-readable code a caller can
 * branch on without reading the message. Internal detail — stack traces, exception
 * text, schema names — never reaches a caller on any path.
 *
 * <p>And every entry point that runs without a user — a relay, a scheduled job, a
 * reconciler — enters an explicit system context. No path runs with no identity.
 */
package com.lynxis.orca.platform.web;
