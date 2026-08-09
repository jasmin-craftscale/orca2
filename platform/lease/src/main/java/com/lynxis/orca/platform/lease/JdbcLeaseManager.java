package com.lynxis.orca.platform.lease;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import lombok.RequiredArgsConstructor;

/**
 * The one implementation of {@link LeaseManager}.
 *
 * <p>Every statement here is guarded and server-side. There is no {@code SELECT}
 * followed by an {@code UPDATE} anywhere in this class, and there is no
 * comparison against a JVM clock: {@code SYSUTCDATETIME()} appears inside each
 * guarded statement so that the database — the one clock all instances share —
 * decides expiry.
 */
@RequiredArgsConstructor
public class JdbcLeaseManager implements LeaseManager {

	/**
	 * The whole acquisition, in one statement.
	 *
	 * <p>The {@code WHERE} is the guard: take it only if it has expired, or if this
	 * holder already has it (a re-acquire, which must not skip the token). The
	 * {@code OUTPUT} returns the new token in the same round trip, so nothing has
	 * to read the row back and risk reading somebody else's acquisition.
	 */
	private static final String ACQUIRE_EXISTING = """
			UPDATE service_lease
			SET holder_id = ?,
			    fence_token = fence_token + 1,
			    acquired_at = SYSUTCDATETIME(),
			    renewed_at = SYSUTCDATETIME(),
			    expires_at = DATEADD(millisecond, ?, SYSUTCDATETIME())
			OUTPUT inserted.fence_token, inserted.expires_at
			WHERE service = ? AND lease_name = ?
			  AND (expires_at <= SYSUTCDATETIME() OR holder_id = ?)
			""";

	private static final String INSERT_NEW = """
			INSERT INTO service_lease
			    (service, lease_name, holder_id, fence_token, acquired_at, renewed_at, expires_at)
			OUTPUT inserted.fence_token, inserted.expires_at
			VALUES (?, ?, ?, 1, SYSUTCDATETIME(), SYSUTCDATETIME(),
			        DATEADD(millisecond, ?, SYSUTCDATETIME()))
			""";

	/**
	 * Renewal verifies the token INSIDE the statement, never in a preceding check.
	 * An instance that lost and regained the lease has a different token, and
	 * renewing with the old one must not succeed.
	 */
	private static final String RENEW = """
			UPDATE service_lease
			SET renewed_at = SYSUTCDATETIME(),
			    expires_at = DATEADD(millisecond, ?, SYSUTCDATETIME())
			OUTPUT inserted.fence_token, inserted.expires_at
			WHERE service = ? AND lease_name = ?
			  AND holder_id = ? AND fence_token = ?
			  AND expires_at > SYSUTCDATETIME()
			""";

	/**
	 * Release expires the lease; it does not delete the row. The row carries the
	 * fence token, and deleting it would let the next acquisition start from a
	 * lower number — the one thing a fence token must never do.
	 */
	private static final String RELEASE = """
			UPDATE service_lease
			SET expires_at = SYSUTCDATETIME()
			WHERE service = ? AND lease_name = ? AND holder_id = ? AND fence_token = ?
			""";

	private static final String FIND = """
			SELECT service, lease_name, holder_id, fence_token, expires_at
			FROM service_lease
			WHERE service = ? AND lease_name = ?
			""";

	private final JdbcTemplate jdbc;
	private final String service;

	@Override
	public Optional<Lease> acquire(String leaseName, String holderId, Duration duration) {
		validate(leaseName, holderId, duration);
		long millis = duration.toMillis();

		List<Lease> taken = jdbc.query(ACQUIRE_EXISTING,
				grant(leaseName, holderId), holderId, millis, service, leaseName, holderId);
		if (!taken.isEmpty()) {
			return Optional.of(taken.getFirst());
		}

		// No row was updated. Either somebody holds it, or it has never existed.
		// The insert distinguishes the two: a duplicate key means it exists and is
		// held, which is a lost race rather than an error.
		try {
			List<Lease> created = jdbc.query(INSERT_NEW,
					grant(leaseName, holderId), service, leaseName, holderId, millis);
			return created.isEmpty() ? Optional.empty() : Optional.of(created.getFirst());
		}
		catch (org.springframework.dao.DuplicateKeyException e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<Lease> renew(Lease lease, Duration duration) {
		validate(lease.leaseName(), lease.holderId(), duration);
		List<Lease> renewed = jdbc.query(RENEW,
				grant(lease.leaseName(), lease.holderId()),
				duration.toMillis(), service, lease.leaseName(), lease.holderId(), lease.fenceToken());
		return renewed.isEmpty() ? Optional.empty() : Optional.of(renewed.getFirst());
	}

	@Override
	public void release(Lease lease) {
		jdbc.update(RELEASE, service, lease.leaseName(), lease.holderId(), lease.fenceToken());
	}

	@Override
	public Optional<Lease> find(String leaseName) {
		List<Lease> found = jdbc.query(FIND, (rs, rowNum) -> new Lease(
				rs.getString("service"),
				rs.getString("lease_name"),
				rs.getString("holder_id"),
				rs.getLong("fence_token"),
				utcInstant(rs, "expires_at")), service, leaseName);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
	}

	private RowMapper<Lease> grant(String leaseName, String holderId) {
		return (rs, rowNum) -> new Lease(service, leaseName, holderId,
				rs.getLong("fence_token"), utcInstant(rs, "expires_at"));
	}

	/**
	 * Reads a timestamp column the DATABASE wrote, as the UTC instant it holds.
	 *
	 * <p><strong>Why this is not {@code rs.getTimestamp(column).toInstant()}.</strong>
	 * A {@code java.sql.Timestamp} carries no zone, so that call reads the stored
	 * value as wall-clock time <em>in whatever zone the JVM happens to be set to</em>.
	 * For a value Java itself wrote the error cancels out on the way back; for one the
	 * database wrote it does not. Every timestamp on this table is written by SQL
	 * Server — {@code SYSUTCDATETIME()} and {@code DATEADD} — so they are all of the
	 * second kind.
	 *
	 * <p>The same mistake in {@code orca-edge} made an event buffered one second
	 * earlier report as two hours old on a machine at UTC+2.
	 *
	 * <p><strong>This does not change how expiry is decided</strong>, and that is worth
	 * being clear about. Whether a lease has expired is settled inside the guarded SQL
	 * statement, against the database's own clock, and never against a JVM clock — see
	 * the comments on the statements above. The instant read here is <em>reported</em>
	 * to a caller, not compared. Fixing it means a caller that ever does compare it
	 * gets a true answer instead of one skewed by the machine's offset.
	 */
	private static java.time.Instant utcInstant(ResultSet rs, String column) throws SQLException {
		LocalDateTime stored = rs.getObject(column, LocalDateTime.class);
		return stored == null ? null : stored.toInstant(ZoneOffset.UTC);
	}

	private static void validate(String leaseName, String holderId, Duration duration) {
		if (leaseName == null || leaseName.isBlank()) {
			throw new IllegalArgumentException("leaseName must not be blank");
		}
		if (holderId == null || holderId.isBlank()) {
			throw new IllegalArgumentException("holderId must not be blank");
		}
		if (duration == null || duration.isZero() || duration.isNegative()) {
			// A zero-length lease is expired the instant it is taken, so every
			// instance would believe it holds it. Refused rather than clamped.
			throw new IllegalArgumentException("Lease duration must be positive");
		}
	}
}
