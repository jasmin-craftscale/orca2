package com.lynxis.orca.platform.scope.readiness;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.InitializingBean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to start the service when a published view it requires is absent.
 *
 * <p>A service that starts and then fails on its first query is far harder to
 * diagnose than one that refuses to start and names the missing view. This throws
 * during context refresh, so the process exits with the reason on the first
 * screen of the log rather than in a stack trace an hour later.
 *
 * <p>It deliberately checks for the view <em>and nothing else</em>. It does not
 * check the view's columns, its content or core's migration version: each of
 * those would be this service asserting something about another service's
 * internals, which is the coupling published views exist to prevent.
 */
@Slf4j
@RequiredArgsConstructor
public class RequiredViewsGate implements InitializingBean {

	private final DataSource dataSource;
	private final RequiredViews required;

	@Override
	public void afterPropertiesSet() throws Exception {
		List<String> names = required.getRequiredViews();
		if (names == null || names.isEmpty()) {
			log.debug("{} requires no published views", required.getService());
			return;
		}

		List<String> missing = new ArrayList<>();
		try (Connection connection = dataSource.getConnection()) {
			for (String qualified : names) {
				if (!exists(connection, qualified)) {
					missing.add(qualified);
				}
			}
		}

		if (!missing.isEmpty()) {
			throw new MissingRequiredViewException(required.getService(), missing);
		}
		log.info("{}: all {} required published view(s) are present", required.getService(), names.size());
	}

	private boolean exists(Connection connection, String qualified) throws Exception {
		int dot = qualified.indexOf('.');
		if (dot < 1 || dot == qualified.length() - 1) {
			// Refusing a bare name rather than resolving it: a bare name resolves
			// against this service's OWN default schema, which is the one schema
			// the view is certainly not in. Silently looking in the wrong place
			// would make this check report success for a view that is absent.
			throw new IllegalArgumentException(
					"Required view '" + qualified + "' is not fully qualified. Use schema.view.");
		}
		String schema = qualified.substring(0, dot);
		String view = qualified.substring(dot + 1);

		// INFORMATION_SCHEMA.VIEWS lists only what this login may see, which is
		// exactly the question being asked: not "does it exist somewhere" but
		// "can this service read it".
		String sql = "SELECT 1 FROM INFORMATION_SCHEMA.VIEWS "
				+ "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			statement.setString(2, view);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next();
			}
		}
	}
}
