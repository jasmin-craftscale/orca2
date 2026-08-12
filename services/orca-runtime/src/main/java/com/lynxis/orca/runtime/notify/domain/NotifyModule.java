package com.lynxis.orca.runtime.notify.domain;

/**
 * Names the notify module now that Stream 2 owns it.
 *
 * <p>The durable in-app record and WebSocket ticket live here. Cross-instance
 * fan-out, email and push remain open product decisions and are deliberately not
 * hidden behind this marker.
 */
public final class NotifyModule {

	private NotifyModule() {
	}
}
