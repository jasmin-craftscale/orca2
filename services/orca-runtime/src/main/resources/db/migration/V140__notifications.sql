-- Operator notifications: the durable in-app list and the ticket used to open
-- the WebSocket without putting an identity-bearing query parameter on the
-- socket URL.
--
-- No is_active/is_deleted columns are copied from 1.x. This table is bounded by
-- its retention class, and unread state is derived from read_at being NULL.

CREATE TABLE notification (
	notification_id            BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_notification PRIMARY KEY,
	external_id                VARCHAR(64)    NOT NULL CONSTRAINT uq_notification_external_id UNIQUE,
	site_external_id           VARCHAR(64)    NOT NULL,
	recipient_user_external_id VARCHAR(64)    NOT NULL,
	notification_type          VARCHAR(100)   NOT NULL,
	title                      NVARCHAR(500)  NOT NULL,
	message                    NVARCHAR(2000) NOT NULL,
	payload_json               NVARCHAR(MAX)  NULL,
	read_at                    DATETIME2(3)   NULL,
	created_at                 DATETIME2(3)   NOT NULL CONSTRAINT df_notification_created DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_notification_scope_recipient_read
	ON notification (site_external_id, recipient_user_external_id, read_at, created_at)
	INCLUDE (external_id, notification_type, title);

CREATE INDEX ix_notification_scope_recipient_type
	ON notification (site_external_id, recipient_user_external_id, notification_type, created_at)
	INCLUDE (external_id, read_at, title);

CREATE TABLE notification_ws_ticket (
	notification_ws_ticket_id  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_notification_ws_ticket PRIMARY KEY,
	site_external_id           VARCHAR(64)  NOT NULL,
	recipient_user_external_id VARCHAR(64)  NOT NULL,
	ticket_hash                CHAR(64)     NOT NULL CONSTRAINT uq_notification_ws_ticket_hash UNIQUE,
	issued_at                  DATETIME2(3) NOT NULL CONSTRAINT df_notification_ws_ticket_issued DEFAULT SYSUTCDATETIME(),
	expires_at                 DATETIME2(3) NOT NULL,
	consumed_at                DATETIME2(3) NULL
);

CREATE INDEX ix_notification_ws_ticket_scope_recipient_expiry
	ON notification_ws_ticket (site_external_id, recipient_user_external_id, expires_at)
	INCLUDE (ticket_hash, consumed_at);
