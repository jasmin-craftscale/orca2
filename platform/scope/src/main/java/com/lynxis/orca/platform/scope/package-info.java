/**
 * The scope seam — one place where a query acquires its scope predicate, and no
 * way around it.
 *
 * <p>The requirement is settled; the mechanism is not. This package is the seam,
 * its default-deny behaviour, and nothing more. It deliberately does not implement
 * database row-level security — that choice belongs to the security design and has
 * a named owner. See {@code README.md} in this module for the connection-pool trap
 * that awaits whoever implements it.
 */
package com.lynxis.orca.platform.scope;
