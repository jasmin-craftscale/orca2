package com.lynxis.orca.core.api;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The API boundary's time conversions, once — this package's counterpart to
 * {@code persistence.Utc}, and consolidated for the same reason: the identical
 * two-line helper had been copied into nine controllers before a review exposed
 * the duplication.
 */
final class ApiTime {

	private ApiTime() {
	}

	/** Domain {@link Instant} → the contract's {@code date-time}, in UTC. Null-safe. */
	static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/** The contract's {@code date-time} → domain {@link Instant}. Null-safe. */
	static Instant instant(OffsetDateTime offsetDateTime) {
		return offsetDateTime == null ? null : offsetDateTime.toInstant();
	}

	/**
	 * The contract's wall-clock string — {@code HH:mm} and {@code HH:mm:ss} are
	 * both legal — as a {@link LocalTime}. Null-safe, because PATCH bodies omit
	 * unchanged fields.
	 */
	static LocalTime parseLocalTime(String time) {
		if (time == null) {
			return null;
		}
		return LocalTime.parse(time.length() == 5 ? time + ":00" : time);
	}
}
