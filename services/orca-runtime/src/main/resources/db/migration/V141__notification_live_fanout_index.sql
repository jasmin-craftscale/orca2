-- Live notification fan-out scans the durable notification table by the identity
-- sequence, under the installation's site scope. The WebSocket delivery remains
-- best-effort; the durable inbox read is authoritative on reconnect.

CREATE INDEX ix_notification_scope_live_seq
	ON notification (site_external_id, notification_id)
	INCLUDE (external_id, recipient_user_external_id, notification_type, title,
		message, payload_json, created_at);
