-- The contents of the permission catalog: every application, module, sub-module
-- and action item the gate console can gate a button on. The empty tables were
-- created by V103__identity.sql; this fills them.
--
-- One branch only — the one rooted at GATE, which is the console operators and
-- administrators use: 1 application, 3 modules, 30 sub-modules, 174 action items.
--
-- ⚠️ GENERATED, NOT HAND-TRANSCRIBED. Every row below was produced by a script
-- that parses docs/entitlement-catalog-from-1x.md — itself extracted by script
-- from the seed data of the Go system in production today — and that asserts the
-- expected counts (3 / 30 / 174) before it will emit a single line. Editing a row
-- here by hand breaks that chain of custody. Regenerate instead.
--
-- HOW EACH ROW IS IDENTIFIED, AND WHY IT IS NOT RANDOM
--   * `external_id` is computed, not generated at random: a version-5 UUID,
--     derived by hashing the node's code inside a fixed namespace. That means
--     regenerating this file produces byte-identical identifiers, and two clean
--     installations end up with the same catalog rather than two catalogs that
--     merely look alike. The old system minted a fresh random UUID per install,
--     which is why nothing there could reference the catalog by identifier.
--   * `code` is a stable machine string built from the node's position in the
--     tree — GATE.ADMIN.ROLE_MANAGEMENT.ADD_ROLE. This is the contract. Anything
--     matching on the catalog matches on the code.
--   * `name` is for display only. It may be corrected without consequence.
--   * `licence_route` is the old system's licence-check string, carried through
--     verbatim so an existing licence keeps meaning the same thing. It repeats
--     across the tree; it is not an identifier.
--
-- ⚠️ THE CATALOG REALLY DOES CONTAIN A COLLISION. Two different sub-modules under
-- GATE · Admin are both named "Event Data" in the source system — distinct rows,
-- distinct identifiers, identical display names. They are disambiguated here in
-- the order they appear in the source, as EVENT_DATA and EVENT_DATA_2, rather
-- than merged. Merging them would silently drop whichever grants hung off the
-- second one.
--
-- WHAT IS DELIBERATELY NOT SEEDED
-- The source system has a second branch, for the driver-facing web app. It
-- belongs to orca-portal, which is an empty skeleton until work on the hosted
-- cloud tier begins, so seeding its permissions now would create a catalog
-- describing screens that do not exist. It is preserved in the extracted document
-- for that later work.
--
-- WHAT MIGHT SURPRISE YOU
-- The rows carry no explicit scope column: `config_realm` is defaulted to
-- 'INSTALLATION' by the column default V103__identity.sql gave it. And the
-- insert order is load-bearing in a small way — the identity keys ascend in
-- document order, so ordering by key reproduces the menu order the console
-- displays.

INSERT INTO entitlement_application (external_id, code, name) VALUES ('b8589e83-01a2-5c85-8b53-4468a5770a86', 'GATE', N'GATE');
INSERT INTO entitlement_module (application_id, external_id, code, name)
SELECT application_id, '87530fed-a72e-5116-b2d6-1bbf1d985da1', 'GATE.INSIGHTS', N'Insights' FROM entitlement_application WHERE code = 'GATE';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'd4a0c398-132f-5415-95a3-4377eaf33d37', 'GATE.INSIGHTS.LANE_MONITORS', N'Lane Monitors' FROM entitlement_module WHERE code = 'GATE.INSIGHTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e155e40e-0562-5806-930c-b45ebba8d752', 'GATE.INSIGHTS.LANE_MONITORS.EDIT_OPTION_LANE', N'Edit Option Lane', 'EditOptionsLane' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_MONITORS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '8fd854ac-244c-5402-9802-486e29ce6bf9', 'GATE.INSIGHTS.LANE_MONITORS.EDIT_CONNECT_LANE', N'Edit Connect Lane', 'EditConnectLane' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_MONITORS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '945724a0-c4c7-5c9b-b87a-4be2524ad1ca', 'GATE.INSIGHTS.LANE_MONITORS.VIEW_LANE_MONITORS', N'View Lane Monitors', 'ViewLaneMonitors' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_MONITORS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'df1e7137-bb28-5f93-bbda-878a97209a06', 'GATE.INSIGHTS.LANE_MONITORS.LANE_MONITORS_TAKE_WORKITEM', N'Lane Monitors Take Workitem', 'LaneMonitorsTakeWorkitem' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_MONITORS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'c7c6abef-564b-557b-97b9-3b0e99c32545', 'GATE.INSIGHTS.LANE_MONITORS.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_MONITORS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'ee11d99b-463f-558a-b7f1-b30597fdcacd', 'GATE.INSIGHTS.COMPLETED_WORK_ITEMS', N'Completed Work Items' FROM entitlement_module WHERE code = 'GATE.INSIGHTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '05c57a03-09b2-5217-84a5-7ba4a244c6e1', 'GATE.INSIGHTS.COMPLETED_WORK_ITEMS.VIEW_COMPLETED_WORK_ITEMS', N'View Completed Work Items', 'ViewWorkItems' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.COMPLETED_WORK_ITEMS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '33d18ca3-6fb6-5045-b512-456980d47207', 'GATE.INSIGHTS.COMPLETED_WORK_ITEMS.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.COMPLETED_WORK_ITEMS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '0adbcca5-1325-5994-bc4d-ebd603c77d7e', 'GATE.INSIGHTS.LANE_ALERTS', N'Lane Alerts' FROM entitlement_module WHERE code = 'GATE.INSIGHTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '94dfc76e-9930-5fbd-b757-8d87e61253b6', 'GATE.INSIGHTS.LANE_ALERTS.VIEW_LANE_ALERTS', N'View Lane Alerts', 'ViewLaneAlerts' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_ALERTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2c985e56-9e42-5a28-b0d5-081e3bf26024', 'GATE.INSIGHTS.LANE_ALERTS.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.LANE_ALERTS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '28ee1446-4483-5390-a86b-9ed52e9a21be', 'GATE.INSIGHTS.SITE_MONITOR', N'Site Monitor' FROM entitlement_module WHERE code = 'GATE.INSIGHTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e7da0cc6-6db6-56bb-8ce7-94bd45d8e27b', 'GATE.INSIGHTS.SITE_MONITOR.LANE_OPTIONS', N'Lane Options', 'LaneOptions' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.SITE_MONITOR';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'fd1ef57a-a81c-5607-832e-5ca2a91003ae', 'GATE.INSIGHTS.SITE_MONITOR.SITE_MONITORS_TAKE_WORKITEM', N'Site Monitors Take Workitem', 'SiteMonitorsTakeWorkitem' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.SITE_MONITOR';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '17a674bf-82c1-5aee-aff7-92e94f1dd41a', 'GATE.INSIGHTS.SITE_MONITOR.SITE_MONITORS_TAKE_OVER_WORKITEM', N'Site Monitors Take Over Workitem', 'SiteMonitorsTakeOverWorkitem' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.SITE_MONITOR';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'db59ffec-0a30-57c4-87ee-9804f13d5d35', 'GATE.INSIGHTS.SITE_MONITOR.CONNECT_TO_LANE', N'Connect To Lane', 'ConnectToLane' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.SITE_MONITOR';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'ae0654db-d929-5671-ad8f-b432155db214', 'GATE.INSIGHTS.SITE_MONITOR.VIEW_SITE_MONITOR', N'View Site Monitor', 'ViewSiteMonitor' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.SITE_MONITOR';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '29f148df-d3e7-5929-8765-c562395db618', 'GATE.INSIGHTS.SITE_MONITOR.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.SITE_MONITOR';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'f0cb5ba6-2c7e-59ee-b11b-bd6171507dfb', 'GATE.INSIGHTS.EVENT_DATA', N'Event Data' FROM entitlement_module WHERE code = 'GATE.INSIGHTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e3c3705e-fe1f-500d-9ab6-165d919ca503', 'GATE.INSIGHTS.EVENT_DATA.VIEW_INSIGHTS_EVENT_DATA', N'View Insights Event Data', 'ViewEventData' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.EVENT_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '87cb73fa-1bd1-566a-af22-43c25ad62f98', 'GATE.INSIGHTS.EVENT_DATA.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.INSIGHTS.EVENT_DATA';
INSERT INTO entitlement_module (application_id, external_id, code, name)
SELECT application_id, '2c2b1034-7523-5a06-8389-64232f9823a1', 'GATE.OPERATIONS', N'Operations' FROM entitlement_application WHERE code = 'GATE';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '442abb77-e2a3-591f-ab30-a21c6fc4fc14', 'GATE.OPERATIONS.WORK_ITEM_QUEUE', N'Work Item Queue' FROM entitlement_module WHERE code = 'GATE.OPERATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'aa089cd8-72a9-5934-9031-afb8ab3a25d6', 'GATE.OPERATIONS.WORK_ITEM_QUEUE.TAKE_WORKITEM', N'Take workitem', 'TakeWorkItems' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.WORK_ITEM_QUEUE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6aa7643e-6b04-5f7c-ad5f-b8dc4da88a4f', 'GATE.OPERATIONS.WORK_ITEM_QUEUE.TAKE_OVER_WORKITEM', N'Take over workitem', 'TakeOverWorkitems' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.WORK_ITEM_QUEUE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '67cbc9b6-039a-5a2c-8e50-0b5eeeb74faf', 'GATE.OPERATIONS.WORK_ITEM_QUEUE.ASSIGN_WORKITEM', N'Assign workitem', 'AssignWorkItems' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.WORK_ITEM_QUEUE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '55f06a32-d742-5749-9452-6a784cf4b94b', 'GATE.OPERATIONS.WORK_ITEM_QUEUE.VIEW_WORK_ITEM_QUEUE', N'View Work Item Queue', 'ViewWorkItems' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.WORK_ITEM_QUEUE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '49026ee9-f8d8-5a46-8028-2b4236acc847', 'GATE.OPERATIONS.WORK_ITEM_QUEUE.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.WORK_ITEM_QUEUE';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '4e5281d4-4eb9-5fcf-ba32-b7ea355ad519', 'GATE.OPERATIONS.USER_ACTIVITY', N'User Activity' FROM entitlement_module WHERE code = 'GATE.OPERATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '0bd73287-cff8-549f-ab31-e69cf4b746c1', 'GATE.OPERATIONS.USER_ACTIVITY.SET_TO_DND', N'Set to DND', 'SetToDnd' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.USER_ACTIVITY';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '0f38bc7d-65cf-549f-b2b7-2c5f5f41bfa4', 'GATE.OPERATIONS.USER_ACTIVITY.VIEW_USER_ACTIVITY', N'View User Activity', 'ViewUserActivity' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.USER_ACTIVITY';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a5d60bb4-0d42-5f52-83e0-c0cd2bcf638b', 'GATE.OPERATIONS.USER_ACTIVITY.LIST_GATE_NOTIFICATIONS', N'List Gate Notifications', 'ListGateNotifications' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.USER_ACTIVITY';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '22cf3e45-222b-5020-bf77-22cc1caeb581', 'GATE.OPERATIONS.USER_ACTIVITY.VIEW_GATE_NOTIFICATIONS_UNREAD_COUNT', N'View Gate Notifications Unread Count', 'ViewGateNotificationsUnread' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.USER_ACTIVITY';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6e1cb495-c37c-5609-9dbb-85914cf68876', 'GATE.OPERATIONS.USER_ACTIVITY.MARK_GATE_NOTIFICATION_READ', N'Mark Gate Notification Read', 'MarkGateNotificationRead' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.USER_ACTIVITY';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '0548d215-7752-563d-98f2-7b477b292734', 'GATE.OPERATIONS.USER_ACTIVITY.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.USER_ACTIVITY';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '7c932364-24dd-5c88-956b-343c9f62e807', 'GATE.OPERATIONS.OPERATOR_CONSOLE', N'Operator Console' FROM entitlement_module WHERE code = 'GATE.OPERATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '7898adbd-8540-5430-9492-aa924c91c47b', 'GATE.OPERATIONS.OPERATOR_CONSOLE.UPDATE_USER_STATUS', N'Update User Status', 'UpdateUserStatus' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.OPERATOR_CONSOLE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '51240d44-561f-54f7-aeae-4cd72cf94a37', 'GATE.OPERATIONS.OPERATOR_CONSOLE.TAKE_WORKITEM', N'Take workitem', 'TakeWorkItems' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.OPERATOR_CONSOLE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '3046bd52-0095-56bd-b351-21497047f45c', 'GATE.OPERATIONS.OPERATOR_CONSOLE.VIEW_OPERATOR_CONSOLE', N'View Operator Console', 'ViewOperatorConsole' FROM entitlement_sub_module WHERE code = 'GATE.OPERATIONS.OPERATOR_CONSOLE';
INSERT INTO entitlement_module (application_id, external_id, code, name)
SELECT application_id, 'e7de60ad-2fa3-53d4-88ec-4dc259fcc0a3', 'GATE.ADMIN', N'Admin' FROM entitlement_application WHERE code = 'GATE';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '1715c5de-bf47-51bc-b3a4-bbc6bcb77f1f', 'GATE.ADMIN.USER_MANAGEMENT', N'User Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a6284227-ecab-5a1f-bd2f-f7fc0f9065a1', 'GATE.ADMIN.USER_MANAGEMENT.ADD_USER', N'Add user', 'AddNewUser' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.USER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a6065a0c-606b-52a8-b8e4-69c78a952f22', 'GATE.ADMIN.USER_MANAGEMENT.EDIT_USER', N'Edit user', 'UpdateUser' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.USER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '03e3eb3a-622b-50dd-9a3b-bdb75cb7870d', 'GATE.ADMIN.USER_MANAGEMENT.DELETE_USER', N'Delete user', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.USER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b95f19fc-6bb5-5f11-9bbe-62a9dee31344', 'GATE.ADMIN.USER_MANAGEMENT.VIEW_USER_MANAGEMENT', N'View User Management', 'ViewUserManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.USER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b7ebfdfa-ab7f-567b-9a85-96642a971c33', 'GATE.ADMIN.USER_MANAGEMENT.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.USER_MANAGEMENT';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'b3aadcd4-f75e-5230-a912-66e5a7d86cc7', 'GATE.ADMIN.ROLE_MANAGEMENT', N'Role Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'deb824b3-81cc-570d-beb1-b42ec144564b', 'GATE.ADMIN.ROLE_MANAGEMENT.ADD_ROLE', N'Add role', 'AddNewRole' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.ROLE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '021bf7e3-d933-5498-9088-4e472f461134', 'GATE.ADMIN.ROLE_MANAGEMENT.EDIT_ROLE', N'Edit role', 'UpdateRole' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.ROLE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '503b72b8-af1f-5ecf-90eb-2a2c81c6eb44', 'GATE.ADMIN.ROLE_MANAGEMENT.DELETE_ROLE', N'Delete role', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.ROLE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '11a01fb3-e47a-5ced-90a1-384cf08614be', 'GATE.ADMIN.ROLE_MANAGEMENT.VIEW_ROLE_MANAGEMENT', N'View Role Management', 'ViewRoleManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.ROLE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b082874d-1671-5186-8065-92f1af5a09e0', 'GATE.ADMIN.ROLE_MANAGEMENT.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.ROLE_MANAGEMENT';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '7003a73b-fff3-5590-b224-da8a57b7cebc', 'GATE.ADMIN.GROUP_MANAGEMENT', N'Group Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e0df9e22-7fc8-52cc-b838-869b3cd0aa5f', 'GATE.ADMIN.GROUP_MANAGEMENT.ADD_GROUP', N'Add Group', 'AddNewGroup' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.GROUP_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'd75fb1fb-82fa-5633-b0e9-6259bd113975', 'GATE.ADMIN.GROUP_MANAGEMENT.EDIT_GROUP', N'Edit Group', 'EditGroup' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.GROUP_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'aaccf5a5-2e58-57f7-825b-3ca68946e339', 'GATE.ADMIN.GROUP_MANAGEMENT.DELETE_GROUP', N'Delete Group', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.GROUP_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '3bfa8096-b6a6-57f6-9c4c-c57e6290a51f', 'GATE.ADMIN.GROUP_MANAGEMENT.VIEW_GROUP_MANAGEMENT', N'View Group Management', 'ViewGroupManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.GROUP_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '4cdb683a-2c5d-5406-956a-069da65b6bba', 'GATE.ADMIN.GROUP_MANAGEMENT.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.GROUP_MANAGEMENT';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '117e6e47-bbfa-5a56-a761-a156fe44eae3', 'GATE.ADMIN.SITE_MANAGEMENT', N'Site Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'adcce2f9-e5dc-5986-b332-84da73d10a4c', 'GATE.ADMIN.SITE_MANAGEMENT.EDIT_SITE', N'Edit Site', 'EditSite' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6e81e4a4-cc66-5ab2-a36f-259153493c55', 'GATE.ADMIN.SITE_MANAGEMENT.ADD_AREA', N'Add Area', 'AddArea' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'fd8ce172-5e40-5ceb-9af5-4636f4044063', 'GATE.ADMIN.SITE_MANAGEMENT.EDIT_AREA', N'Edit Area', 'EditArea' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '7789c269-4263-5e00-81fd-647af6a4f492', 'GATE.ADMIN.SITE_MANAGEMENT.DELETE_AREA', N'Delete Area', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'bed184d8-4882-5c17-9911-61b3c7d10b78', 'GATE.ADMIN.SITE_MANAGEMENT.ADD_LANE', N'Add Lane', 'AddLane' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '49fc1dfb-a0c5-51b5-976f-333ce9b9de22', 'GATE.ADMIN.SITE_MANAGEMENT.EDIT_LANE', N'Edit Lane', 'EditLane' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2887e2d3-1384-5b9e-ad83-c80cde314f68', 'GATE.ADMIN.SITE_MANAGEMENT.DELETE_LANE', N'Delete Lane', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'de989f88-525f-5dad-acde-612011a9ddf2', 'GATE.ADMIN.SITE_MANAGEMENT.ADD_DEVICE', N'Add Device', 'AddDevice' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6b110b93-9391-5b27-b758-c272af83ac30', 'GATE.ADMIN.SITE_MANAGEMENT.EDIT_DEVICE', N'Edit Device', 'EditDevice' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'ab1179fe-f903-5299-b4ff-04efc6a27b15', 'GATE.ADMIN.SITE_MANAGEMENT.DELETE_DEVICE', N'Delete Device', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '458b55d9-b17a-5f4c-815a-f322c7daf62d', 'GATE.ADMIN.SITE_MANAGEMENT.AREA_CONFIGURATION', N'Area Configuration', 'AddConfiguration' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2c8fff84-0ae5-5018-8b31-05ebdd53702c', 'GATE.ADMIN.SITE_MANAGEMENT.LANE_CONFIGURATION', N'Lane Configuration', 'AddConfiguration' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'd47da309-4bdf-51f3-b8c4-abc2aa362430', 'GATE.ADMIN.SITE_MANAGEMENT.SITE_CONFIGURATION', N'Site Configuration', 'AddConfiguration' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '171b776f-b5ce-542a-a032-3ff4b4dc4727', 'GATE.ADMIN.SITE_MANAGEMENT.DEVICE_CONFIGURATION', N'Device Configuration', 'AddConfiguration' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '78a4579f-bd10-5cca-b358-07c75d5f9492', 'GATE.ADMIN.SITE_MANAGEMENT.CUSTOM_VARIABLES', N'Custom Variables', 'AddCustomVariables' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '450e8c1b-5e87-5aa8-9c66-68ef44d17ca9', 'GATE.ADMIN.SITE_MANAGEMENT.EDIT_COLOR_CONFIGURATION', N'Edit Color Configuration', 'EditColorConfiguration' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'ed40503f-437e-57e5-9e62-162092d73fc9', 'GATE.ADMIN.SITE_MANAGEMENT.VIEW_SITE_MANAGEMENT', N'View Site Management', 'ViewSiteManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '5e860fee-c802-5aa3-aeb6-59330e83c581', 'GATE.ADMIN.SITE_MANAGEMENT.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'fedcdc6c-c1d3-51dc-8457-fbf0223a9e49', 'GATE.ADMIN.SITE_MANAGEMENT.VIEW_LOCALIZATION', N'View Localization', 'ViewLocalization' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '572ac7ef-1aac-5c32-acca-2b9f20e41b9a', 'GATE.ADMIN.SITE_MANAGEMENT.EDIT_LOCALIZATION', N'Edit Localization', 'EditLocalization' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'adb5320b-df33-5d36-8187-7f40be1a831d', 'GATE.ADMIN.SITE_MANAGEMENT.ADD_LOCALIZATION', N'Add Localization', 'AddLocalization' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '4b5469f1-2105-544f-a9af-c4696f880191', 'GATE.ADMIN.SITE_MANAGEMENT.DELETE_LOCALIZATION', N'Delete Localization', 'DeleteLocalization' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9c1138f5-5325-5005-b61d-8dbb68a8eea8', 'GATE.ADMIN.SITE_MANAGEMENT.UPLOAD_LICENSE', N'Upload License', 'UploadLicense' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b5c942a2-1ef5-547d-8c81-4cf7b1e2bf4a', 'GATE.ADMIN.SITE_MANAGEMENT.DELETE_SITE', N'Delete Site', 'DeleteSite' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'de553f92-afac-50cc-a299-930fb44c61d5', 'GATE.ADMIN.SITE_MANAGEMENT.DELETE_CUSTOMER', N'Delete Customer', 'DeleteCustomer' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a442cdd1-36fc-5511-966a-3fb1e2d59a95', 'GATE.ADMIN.SITE_MANAGEMENT.VIEW_SITE_MONITOR_JSON', N'View Site Monitor JSON', 'ViewSiteMonitorJSON' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'cc21407f-8150-587b-850b-af103d8424da', 'GATE.ADMIN.SITE_MANAGEMENT.SAVE_SITE_MONITOR_JSON', N'Save Site Monitor JSON', 'SaveSiteMonitorJSON' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SITE_MANAGEMENT';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '52497380-7a14-5d98-a82b-7927d1c8f79d', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS', N'Workflow Configurations' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '8a9be5a6-94f9-57a7-9bb9-b1a5e75b4e6c', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.ADD_IO', N'Add IO', 'AddIO' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '695ff37a-1757-5fef-8921-8da31e7fc2e5', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EDIT_IO', N'Edit IO', 'EditIO' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '48a156a4-253a-5f9d-b840-1ae0f40c0204', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.DELETE_IO', N'Delete IO', 'DeleteIO' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'c996502e-1d72-5ee7-a5a0-c97695cff6df', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.IMPORT_IO', N'Import IO', 'ImportIO' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '21a360ff-e506-5479-bfa3-3fc35249b116', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EXPORT_IO_JSON', N'Export IO JSON', 'ExportIOJSON' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '659e559f-a539-552d-b81c-4cf946f38871', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.ADD_PROCESS', N'Add Process', 'AddProcess' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '8d1b1d3e-7b3e-5e33-be9d-6404268e8cb8', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EDIT_PROCESS', N'Edit Process', 'EditProcess' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '817c7e5c-0327-5542-a8b6-f1d6103dcada', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.DELETE_PROCESS', N'Delete Process', 'DeleteProcess' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '3cb3cd25-95f8-5756-8bf8-6b3f8113d81f', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EXPORT_PROCESS_JSON', N'Export Process JSON', 'ExportProcessJSON' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2570d29c-2ff2-598c-b890-929da0c2680b', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.ADD_CONNECTOR', N'Add Connector', 'AddConnector' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '64b3c49b-d7e5-50a7-91dd-53ed35dc0c6f', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EDIT_CONNECTOR', N'Edit Connector', 'EditConnector' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e48e53b2-7a1c-50bb-9dca-f43893aeb8c0', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.DELETE_CONNECTOR', N'Delete Connector', 'DeleteConnector' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9c4195fb-e703-587a-a42f-034438cd6d48', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.IMPORT_CONNECTOR', N'Import Connector', 'ImportConnector' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '361d4a26-db4b-5f8b-9672-a3854e618859', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EXPORT_CONNECTOR_JSON', N'Export Connector JSON', 'ExportConnectorJSON' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '12795aad-6338-5dfc-afd3-1913df660ce5', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.VIEW_WORKFLOW_CONFIGURATIONS', N'View Workflow Configurations', 'ViewWorkflowConfigurations' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '34b77a9c-9cb8-5d09-b5b0-f3bd1226de67', 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONFIGURATIONS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '225dd5d4-01b8-5099-9121-01c508b7a8bc', 'GATE.ADMIN.SYNC_CONFIGURATIONS', N'Sync Configurations' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '43539ea6-b626-5683-bc1c-c04b6724a010', 'GATE.ADMIN.SYNC_CONFIGURATIONS.EDIT_SITE', N'Edit Site', 'EditSite' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYNC_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'd698a450-29ac-5ba5-9c55-d009ac62dad8', 'GATE.ADMIN.SYNC_CONFIGURATIONS.EDIT_CUSTOMER_CLOUD', N'Edit Customer Cloud', 'EditCustomerCloud' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYNC_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'd6821254-5346-5ca3-874e-af771b59425a', 'GATE.ADMIN.SYNC_CONFIGURATIONS.EDIT_LYNXIS_CLOUD', N'Edit Lynxis Cloud', 'EditLynxisCloud' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYNC_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a888a032-d507-51d9-8f28-3d05adb812f9', 'GATE.ADMIN.SYNC_CONFIGURATIONS.VIEW_SYNC_CONFIGURATIONS', N'View Sync Configurations', 'ViewSyncConfigurations' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYNC_CONFIGURATIONS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '3be0a3f3-0f99-55a8-90f1-853c7064fdd1', 'GATE.ADMIN.REFERENCE_DATA', N'Reference Data' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9daa5b3a-ad00-5e94-ac30-898fdf93e8f6', 'GATE.ADMIN.REFERENCE_DATA.ADD_REFERENCE_DATA', N'Add Reference Data', 'AddReferenceData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '820fe9a9-9b5f-51ab-a00c-d9b8d7e00d96', 'GATE.ADMIN.REFERENCE_DATA.EDIT_REFERENCE_DATA', N'Edit Reference Data', 'EditReferenceData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6efb947d-dc7d-51ed-8711-4496319823dc', 'GATE.ADMIN.REFERENCE_DATA.DELETE_REFERENCE_DATA', N'Delete Reference Data', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e0aa7d03-15a8-5907-8957-2aade214984b', 'GATE.ADMIN.REFERENCE_DATA.ADD_REFERENCE_DATA_DETAILS', N'Add Reference Data Details', 'AddReferenceDataDetails' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '12fdf29d-6a6e-5dea-8d8f-f5aa64700f9b', 'GATE.ADMIN.REFERENCE_DATA.EDIT_REFERENCE_DATA_DETAILS', N'Edit Reference Data Details', 'EditReferenceDataDetails' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '80cba990-8dec-56e9-880f-e4741e98fccb', 'GATE.ADMIN.REFERENCE_DATA.DELETE_REFERENCE_DATA_DETAILS', N'Delete Reference Data Details', 'DeleteReferenceDataDetails' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '48e0b4c9-74c7-5742-be47-1872175d07f9', 'GATE.ADMIN.REFERENCE_DATA.ADD_CONFIGURATION', N'Add Configuration', 'AddReferenceDataConfiguration' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'c9de8a52-2a64-5fb3-99fa-3d1d5a3121e9', 'GATE.ADMIN.REFERENCE_DATA.VIEW_REFERENCE_DATA', N'View Reference Data', 'ViewReferenceData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '57c444fb-175a-55ee-a7f8-5ac547bbaafa', 'GATE.ADMIN.REFERENCE_DATA.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.REFERENCE_DATA';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '40b629f4-3aec-532d-99f4-4a9540c6dde5', 'GATE.ADMIN.EVENT_DATA', N'Event Data' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '81ded5e5-92de-557f-9e1a-1bb8fd3ac6e1', 'GATE.ADMIN.EVENT_DATA.ADD_EVENT_DATA_RECORDS', N'Add Event Data Records', 'AddEventDataRecords' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '646c16b5-445f-5186-95cb-eb9801378e1a', 'GATE.ADMIN.EVENT_DATA.ADD_NEW_EVENT_DATA', N'Add New Event Data', 'AddNewEventData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6117a843-cddc-5f87-8f0a-f6d1967ce72e', 'GATE.ADMIN.EVENT_DATA.EDIT_EVENT_DATA', N'Edit Event Data', 'UpdateEventData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'dec9c31c-dd9c-5dfd-8445-fb95efa45bb7', 'GATE.ADMIN.EVENT_DATA.DELETE_EVENT_DATA', N'Delete Event Data', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a91b244b-bc90-5275-ba72-696aad7eef01', 'GATE.ADMIN.EVENT_DATA.VIEW_EVENT_DATA', N'View Event Data', 'ViewEventData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'a3d3514c-221e-5b9c-9f0e-180bdc067316', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE', N'Shift & Break Template' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '936eef60-a969-52ba-ac67-a0e1a6a0ab86', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.CREATE_SHIFT', N'Create Shift', 'CreateShift' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a312b8d7-9fad-5ad7-a67b-26b09f65e4a0', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.EDIT_SHIFT', N'Edit Shift', 'EditShift' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '320d4750-e66f-5189-9b3b-324c548fc865', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.DELETE_SHIFT', N'Delete Shift', 'DeleteShift' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '0ed75b04-d411-502f-b29f-a8843e3785a7', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.CREATE_BREAK', N'Create Break', 'CreateBreak' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2da0d077-9c5b-5c3b-96d9-c26d7291dc6c', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.EDIT_BREAK', N'Edit Break', 'EditBreak' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2087f502-16c4-5655-993b-7d524a3e8c56', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.DELETE_BREAK', N'Delete Break', 'DeleteBreak' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9e531a23-2c55-5a97-87e5-fea6469708ab', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.VIEW_SHIFT_AND_BREAK_TEMPLATE', N'View Shift & Break Template', 'ViewShift&BreakTemplate' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '8a18562a-9482-5d91-9236-f60273f08bec', 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SHIFT_AND_BREAK_TEMPLATE';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'a5ce08f5-6918-5c7d-8038-aeb4bfecfe2f', 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS', N'Workflow Deployments' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '872f9813-5160-5a3e-9c6a-9002523c252b', 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS.DEPLOY_WORKFLOW', N'Deploy Workflow', 'AddWorkflow' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '1b52dce8-c57f-544b-ab74-d4e7c007906a', 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS.VIEW_WORKFLOW_DEPLOYMENTS', N'View Workflow Deployments', 'ViewWorkflowDeployments' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '757fc9f0-d055-5cf5-b5a7-279a7c9c6987', 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_DEPLOYMENTS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '3eb0c1d5-37bb-5989-a7ce-32458c7cc4e1', 'GATE.ADMIN.WORKFLOW_SIMULATOR', N'Workflow Simulator' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b1c4b4a4-c727-5b53-a14f-db906553b49e', 'GATE.ADMIN.WORKFLOW_SIMULATOR.VIEW_WORKFLOW_SIMULATOR', N'View Workflow Simulator', 'ViewSimulator' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_SIMULATOR';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '9e00f941-35bc-56cc-bbe4-9a044c23afa0', 'GATE.ADMIN.MANAGE_WORKFLOWS', N'Manage Workflows' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a3f86edf-779f-5aec-ae82-52a9e677965f', 'GATE.ADMIN.MANAGE_WORKFLOWS.ASSIGN_WORKFLOW', N'Assign Workflow', 'AssignWorkflow' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '5cb59937-ed25-5fa7-851d-a7f8a4901ef2', 'GATE.ADMIN.MANAGE_WORKFLOWS.ADD_WORKFLOW', N'Add Workflow', 'AddWorkflow' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'c1954adb-641d-5b25-be69-fe9d595e39ad', 'GATE.ADMIN.MANAGE_WORKFLOWS.DELETE_WORKFLOW', N'Delete Workflow', 'DeleteWorkflow' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '841fc9b9-40dc-53ab-8345-177366a6a1c2', 'GATE.ADMIN.MANAGE_WORKFLOWS.UPDATE_WORKFLOW', N'Update Workflow', 'UpdateWorkflow' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a080173d-bf64-55d0-ac7e-c96ac6fdee46', 'GATE.ADMIN.MANAGE_WORKFLOWS.SAVE_PROCESS_SUBFLOW', N'Save Process Subflow', 'SaveProcessSubflow' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'af138426-b270-56a1-be77-66a86bbf0377', 'GATE.ADMIN.MANAGE_WORKFLOWS.VIEW_MANAGE_WORKFLOWS', N'View Manage Workflows', 'ViewManageWorkflows' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '52ea9a52-5cc8-5939-9bab-0da694c76aec', 'GATE.ADMIN.MANAGE_WORKFLOWS.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_WORKFLOWS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'c583f5ef-6cfb-55bb-ac9d-9b1627d5f127', 'GATE.ADMIN.WORKFLOW_CONNECTION', N'Workflow Connection' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a066d24b-5fba-5b8f-a90c-8d2f8f69c24c', 'GATE.ADMIN.WORKFLOW_CONNECTION.SUBMIT_EVENT', N'Submit Event', 'SubmitEvent' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '27207b1d-6526-53c2-99ff-2c0b8eac224c', 'GATE.ADMIN.WORKFLOW_CONNECTION.BULK_SUBMIT_EVENT', N'Bulk Submit Event', 'BulkSubmitEvent' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '05213aca-707a-5d29-b327-b8a520f9dea6', 'GATE.ADMIN.WORKFLOW_CONNECTION.CALLBACK', N'Callback', 'ForwardCallback' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'e2789be0-8faf-5097-8d9c-de94970e289f', 'GATE.ADMIN.WORKFLOW_CONNECTION.GET_LATEST_EVENT', N'Get Latest Event', 'GetLatestEvent' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'cec6952f-2548-55f1-88bf-4268ea4f1b13', 'GATE.ADMIN.WORKFLOW_CONNECTION.LIST_EVENTS', N'List Events', 'ListEvents' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'ce2dba71-29f6-5f50-bb3d-72270c2e21f4', 'GATE.ADMIN.WORKFLOW_CONNECTION.NEXT_EVENT', N'Next Event', 'NextEvent' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '340a760a-e396-5f66-bde2-201f76ecb3ae', 'GATE.ADMIN.WORKFLOW_CONNECTION.GET_EVENT', N'Get Event', 'GetEventByUUID' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '34cce3db-6834-5c55-b6aa-4202ae3da277', 'GATE.ADMIN.WORKFLOW_CONNECTION.UPDATE_EVENT_STATUS', N'Update Event Status', 'UpdateEventStatus' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '0739b3fa-95af-5a09-a357-0da73c028cb9', 'GATE.ADMIN.WORKFLOW_CONNECTION.REPLAY_EVENT', N'Replay Event', 'ReplayEvent' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.WORKFLOW_CONNECTION';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '0a583d9f-c12e-5858-9100-7815d5d3a69e', 'GATE.ADMIN.EVENT_DATA_2', N'Event Data' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6f04dd0a-7985-5f72-99b9-f7e92068a1e7', 'GATE.ADMIN.EVENT_DATA_2.ADD_NEW_EVENT_DATA', N'Add New Event Data', 'AddNewEventData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA_2';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9a97cf62-c61a-5791-923e-59b11d110c62', 'GATE.ADMIN.EVENT_DATA_2.EDIT_EVENT_DATA', N'Edit Event Data', 'UpdateEventData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA_2';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '56e0ca46-7477-5c43-b4fc-0fa4f31fffdf', 'GATE.ADMIN.EVENT_DATA_2.DELETE_EVENT_DATA', N'Delete Event Data', 'DeleteRecord' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA_2';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '7ec20661-f04f-5f8b-9d26-f9f2c55e08f2', 'GATE.ADMIN.EVENT_DATA_2.VIEW_EVENT_DATA', N'View Event Data', 'ViewEventData' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA_2';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'c80fc694-11ff-5528-8640-455494f0426f', 'GATE.ADMIN.EVENT_DATA_2.ADD_NEW_EVENT_DATA_RECORDS', N'Add New Event Data Records', 'AddEventDataRecords' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA_2';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '0c4b7192-7cae-5f3a-baed-c17ed98c92b5', 'GATE.ADMIN.EVENT_DATA_2.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.EVENT_DATA_2';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'abdde304-e3b8-5529-a34a-6d5a4f471ee2', 'GATE.ADMIN.MANAGE_SCREENS', N'Manage Screens' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b38948f4-3234-5dda-a878-d816cc18e893', 'GATE.ADMIN.MANAGE_SCREENS.CREATE_SCREEN', N'Create Screen', 'CreateScreen' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_SCREENS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'd2959498-74cb-5e1a-83fa-cb72ee83d5f5', 'GATE.ADMIN.MANAGE_SCREENS.DELETE_SCREEN', N'Delete Screen', 'DeleteScreen' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_SCREENS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6efebe97-73d9-55b6-b49c-78bf2c631069', 'GATE.ADMIN.MANAGE_SCREENS.UPDATE_SCREEN', N'Update Screen', 'UpdateScreen' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_SCREENS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '716182ad-345f-5af4-8bbb-8a5ec97aa04d', 'GATE.ADMIN.MANAGE_SCREENS.VIEW_MANAGE_SCREENS', N'View Manage Screens', 'ViewManageScreens' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_SCREENS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6813e33d-0910-58b9-aa58-5e6002599447', 'GATE.ADMIN.MANAGE_SCREENS.VIEW_SCREEN_BUILDER', N'View Screen Builder', 'ViewScreenBuilder' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MANAGE_SCREENS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'a8540b9d-b8b7-5d08-a74e-9729446bb22c', 'GATE.ADMIN.SYSTEM_CONFIGURATIONS', N'System Configurations' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '55716d50-c8db-53e4-be7f-b03b95200af1', 'GATE.ADMIN.SYSTEM_CONFIGURATIONS.VIEW_SYSTEM_CONFIGURATIONS', N'View System Configurations', 'ViewSystemConfigurations' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYSTEM_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '26d468a3-0b37-55a5-8c58-cd0bbdc0bafa', 'GATE.ADMIN.SYSTEM_CONFIGURATIONS.VIEW_SYSTEM_CONFIGURATION_DETAILS', N'View System Configuration Details', 'ViewSystemConfigDetails' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYSTEM_CONFIGURATIONS';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a6da0c7f-d01b-5593-9323-6bff6addcb03', 'GATE.ADMIN.SYSTEM_CONFIGURATIONS.EDIT_SYSTEM_CONFIGURATION_DETAIL', N'Edit System Configuration Detail', 'EditSystemConfigDetail' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.SYSTEM_CONFIGURATIONS';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '8d760320-96e4-5780-abfc-31aa318c0cac', 'GATE.ADMIN.AUDIT_HISTORY', N'Audit History' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a75a4ff4-6615-5926-8e64-601ab0b7cb54', 'GATE.ADMIN.AUDIT_HISTORY.VIEW_AUDIT_HISTORY', N'View Audit History', 'ViewAuditHistory' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.AUDIT_HISTORY';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '714226f4-e8ec-51b7-aa15-124e16f392d7', 'GATE.ADMIN.LICENSE_MODULES', N'License Modules' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '1d251332-e7af-5835-8d7f-ee5e698858f5', 'GATE.ADMIN.LICENSE_MODULES.ADD_LICENSE_MODULE', N'Add License Module', 'AddLicenseModule' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MODULES';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9e80883c-98c5-5178-97af-5059bc04ac34', 'GATE.ADMIN.LICENSE_MODULES.EDIT_LICENSE_MODULE', N'Edit License Module', 'EditLicenseModule' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MODULES';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '743563ad-01dd-5677-b7c7-4bf4da090468', 'GATE.ADMIN.LICENSE_MODULES.DELETE_LICENSE_MODULE', N'Delete License Module', 'DeleteLicenseModule' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MODULES';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'dff6cbc5-1383-5e54-871f-8ed67170d770', 'GATE.ADMIN.LICENSE_MODULES.VIEW_LICENSE_MODULES', N'View License Modules', 'ViewLicenseModules' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MODULES';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '6b02394f-fedf-5367-90dc-36b4087f3283', 'GATE.ADMIN.LICENSE_MODULES.EXPORT_LICENSE_EXCEL', N'Export License Excel', 'ExportLicenseExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MODULES';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'aea9b28f-eb00-5eca-a738-85c7d7cee74c', 'GATE.ADMIN.LICENSE_MANAGEMENT', N'License Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '1b28c2ed-2108-5390-ad99-09363e8186b3', 'GATE.ADMIN.LICENSE_MANAGEMENT.ADD_LICENSE', N'Add License', 'AddLicense' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'a8150c2c-90a5-57b5-83d7-d953373c9776', 'GATE.ADMIN.LICENSE_MANAGEMENT.EDIT_LICENSE', N'Edit License', 'EditLicense' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '4cda217a-b45d-5219-b9a1-35b075b9e08a', 'GATE.ADMIN.LICENSE_MANAGEMENT.DELETE_LICENSE', N'Delete License', 'DeleteLicense' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '59cd9ef9-2bb7-587e-a637-bfcadd87bf80', 'GATE.ADMIN.LICENSE_MANAGEMENT.REVOKE_LICENSE', N'Revoke License', 'RevokeLicense' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '19ecec4c-43d8-5e0e-a9b3-410c7ca176e0', 'GATE.ADMIN.LICENSE_MANAGEMENT.VIEW_LICENSE_MANAGEMENT', N'View License Management', 'ViewLicenseManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '7c811b96-90a9-5dba-a486-65275c63a4d6', 'GATE.ADMIN.LICENSE_MANAGEMENT.DOWNLOAD_LICENSE_CERTIFICATE', N'Download License Certificate', 'DownloadLicenseCertificate' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '9f7be564-5cd5-5c11-b99f-d63fb46e9913', 'GATE.ADMIN.LICENSE_MANAGEMENT.EXPORT_LICENSE_MODULES_EXCEL', N'Export License Modules Excel', 'ExportLicenseModulesExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.LICENSE_MANAGEMENT';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, 'ddaeff0e-c50e-5191-9ac3-36818201fcef', 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT', N'Customer License Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '1c6a7860-17b9-59c4-a8ed-a375ba9c9fe2', 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT.UPLOAD_LICENSE', N'Upload License', 'UploadLicense' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'ff8e5388-3fed-5641-942d-f42a7ff37594', 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT.VIEW_CUSTOMER_LICENSE_MANAGEMENT', N'View Customer License Management', 'ViewCustomerLicenseManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'b662b305-997b-5166-a061-aebfa32d5c36', 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT.EXPORT_EXCEL', N'Export Excel', 'ExportExcel' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_LICENSE_MANAGEMENT';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '10294d40-4981-51f6-8de6-978620aca0b2', 'GATE.ADMIN.MOBILE_LAYOUT_MANAGER', N'Mobile Layout Manager' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '753f0cb7-1e2b-51b4-8f42-3579795cbbca', 'GATE.ADMIN.MOBILE_LAYOUT_MANAGER.VIEW_MOBILE_LAYOUT_MANAGER', N'View Mobile Layout Manager', 'ViewMobileLayoutManager' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MOBILE_LAYOUT_MANAGER';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '2af8c13e-9641-5da8-9e58-aa1c5b19b617', 'GATE.ADMIN.MOBILE_LAYOUT_MANAGER.UPDATE_MOBILE_LAYOUT_MANAGER', N'Update Mobile Layout Manager', 'UpdateMobileLayoutManager' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.MOBILE_LAYOUT_MANAGER';
INSERT INTO entitlement_sub_module (module_id, external_id, code, name)
SELECT module_id, '062a4e27-ef9f-5a22-a369-b419c503c7d7', 'GATE.ADMIN.CUSTOMER_MANAGEMENT', N'Customer Management' FROM entitlement_module WHERE code = 'GATE.ADMIN';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '8e41550a-1f74-5524-92d2-9ec3a5681e4f', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.VIEW_CUSTOMER_MANAGEMENT', N'View Customer Management', 'ViewCustomerManagement' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '7a5a9da3-496d-5870-8d03-43c0827b1d1f', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.ADD_CUSTOMER', N'Add Customer', 'AddCustomer' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '07b38190-a33f-5521-aba0-2a1049f15ed8', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.EDIT_CUSTOMER', N'Edit Customer', 'EditCustomer' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '7e17df59-7dcb-5a48-bd87-467d05766590', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.DELETE_CUSTOMER', N'Delete Customer', 'DeleteCustomer' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '5a295d31-3523-53d3-930e-83715381a122', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.ADD_SITE', N'Add Site', 'AddSite' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, 'c179eca5-7357-558d-96aa-cbf83e91c566', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.EDIT_SITE', N'Edit Site', 'EditSite' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)
SELECT sub_module_id, '48449037-1473-5988-9d8e-e51e0eca1d98', 'GATE.ADMIN.CUSTOMER_MANAGEMENT.DELETE_SITE', N'Delete site', 'DeleteSite' FROM entitlement_sub_module WHERE code = 'GATE.ADMIN.CUSTOMER_MANAGEMENT';
