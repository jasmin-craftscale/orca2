package com.lynxis.orca.core.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.TeamTables.BreakTemplate;
import com.lynxis.orca.core.domain.TeamTables.BreakTiming;
import com.lynxis.orca.core.domain.TeamTables.ShiftTemplate;
import com.lynxis.orca.core.persistence.BreakTemplateRepository;
import com.lynxis.orca.core.persistence.ShiftTemplateRepository;
import com.lynxis.orca.core.persistence.TeamRepository;

import lombok.RequiredArgsConstructor;

/**
 * Manages the shift and break template catalogs.
 *
 * <p>The overnight/duration semantics live here, in one place: a shift whose
 * end is at or before its start crosses midnight, equal times are the 24-hour
 * shift, and duration is <em>computed</em> — 1.x stored a duration in a TIME
 * column, and the translation is not a better column type but no column.
 */
@RequiredArgsConstructor
public class TemplateAdminService {

	private final ShiftTemplateRepository shiftTemplates;
	private final BreakTemplateRepository breakTemplates;
	private final TeamRepository teams;

	// --- the semantics, stated once -----------------------------------------

	/** end at or before start crosses midnight; equal is the 24-hour shift. */
	public static boolean overnight(LocalTime start, LocalTime end) {
		return !end.isAfter(start);
	}

	/** Minutes on the clock face from start to end, crossing midnight when overnight. */
	public static int durationMinutes(LocalTime start, LocalTime end) {
		if (end.isAfter(start)) {
			return (int) Duration.between(start, end).toMinutes();
		}
		return (int) (24 * 60 - Duration.between(end, start).toMinutes());
	}

	// --- shift templates ----------------------------------------------------

	public List<ShiftTemplate> listShiftTemplates() {
		return shiftTemplates.all();
	}

	@Transactional
	public ShiftTemplate createShiftTemplate(String name, String timeZone,
			LocalTime start, LocalTime end) {
		requireKnownZone(timeZone);
		if (shiftTemplates.nameInUse(name, 0)) {
			throw new TemplateNameInUseException(name);
		}
		String externalId = "shf-" + UUID.randomUUID();
		try {
			shiftTemplates.insert(externalId, name, timeZone, start, end, overnight(start, end));
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new TemplateNameInUseException(name);
		}
		return shiftTemplate(externalId);
	}

	@Transactional
	public ShiftTemplate updateShiftTemplate(String externalId, String name, String timeZone,
			LocalTime start, LocalTime end, Boolean retired) {
		ShiftTemplate existing = shiftTemplates.byExternalId(externalId)
				.orElseThrow(() -> new ShiftTemplateUnknownException(externalId));
		if (timeZone != null) {
			requireKnownZone(timeZone);
		}
		if (name != null && shiftTemplates.nameInUse(name, existing.shiftTemplateId())) {
			throw new TemplateNameInUseException(name);
		}
		if (Boolean.TRUE.equals(retired)
				&& teams.anyActiveTeamUsesShiftTemplate(existing.shiftTemplateId())) {
			throw new TemplateInUseException(externalId);
		}
		// The flag travels with the times: changing either recomputes it against
		// the value that will actually be stored, so the database CHECK and the
		// stored flag can never disagree.
		LocalTime effectiveStart = start != null ? start : existing.startTime();
		LocalTime effectiveEnd = end != null ? end : existing.endTime();
		Boolean recomputed = (start != null || end != null)
				? overnight(effectiveStart, effectiveEnd)
				: null;
		try {
			shiftTemplates.update(externalId, name, timeZone, start, end, recomputed, retired);
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new TemplateNameInUseException(name);
		}
		return shiftTemplate(externalId);
	}

	// --- break templates ----------------------------------------------------

	public record BreakTemplateView(BreakTemplate template, List<BreakTiming> timings) {
	}

	public List<BreakTemplateView> listBreakTemplates() {
		Map<Long, List<BreakTiming>> timingsByTemplate = breakTemplates.activeTimings().stream()
				.collect(Collectors.groupingBy(BreakTiming::breakTemplateId));
		return breakTemplates.all().stream()
				.map(template -> new BreakTemplateView(template,
						timingsByTemplate.getOrDefault(template.breakTemplateId(), List.of())))
				.toList();
	}

	@Transactional
	public BreakTemplateView createBreakTemplate(String name, String description,
			List<BreakTiming> timings) {
		requireDistinctStarts(timings);
		if (breakTemplates.nameInUse(name, 0)) {
			throw new TemplateNameInUseException(name);
		}
		String externalId = "brk-" + UUID.randomUUID();
		long templateId;
		try {
			templateId = breakTemplates.insert(externalId, name, description);
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new TemplateNameInUseException(name);
		}
		if (timings != null && !timings.isEmpty()) {
			breakTemplates.replaceTimings(templateId, timings);
		}
		return breakTemplate(externalId);
	}

	@Transactional
	public BreakTemplateView updateBreakTemplate(String externalId, String name, String description,
			List<BreakTiming> timings, Boolean retired) {
		BreakTemplate existing = breakTemplates.byExternalId(externalId)
				.orElseThrow(() -> new BreakTemplateUnknownException(externalId));
		requireDistinctStarts(timings);
		if (name != null && breakTemplates.nameInUse(name, existing.breakTemplateId())) {
			throw new TemplateNameInUseException(name);
		}
		if (Boolean.TRUE.equals(retired)
				&& teams.anyActiveTeamUsesBreakTemplate(existing.breakTemplateId())) {
			throw new TemplateInUseException(externalId);
		}
		try {
			breakTemplates.update(externalId, name, description, retired);
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new TemplateNameInUseException(name);
		}
		if (timings != null) {
			breakTemplates.replaceTimings(existing.breakTemplateId(), timings);
		}
		return breakTemplate(externalId);
	}

	// ------------------------------------------------------------------------

	private ShiftTemplate shiftTemplate(String externalId) {
		return shiftTemplates.byExternalId(externalId)
				.orElseThrow(() -> new ShiftTemplateUnknownException(externalId));
	}

	private BreakTemplateView breakTemplate(String externalId) {
		return listBreakTemplates().stream()
				.filter(view -> view.template().externalId().equals(externalId))
				.findFirst()
				.orElseThrow(() -> new BreakTemplateUnknownException(externalId));
	}

	/** Two breaks cannot start at the same instant — refused before any row, not by the index. */
	private static void requireDistinctStarts(List<BreakTiming> timings) {
		if (timings != null) {
			DuplicateRequestEntryException.requireDistinct(timings, "startTime",
					timing -> timing.breakStartTime().toString());
		}
	}

	private static void requireKnownZone(String timeZone) {
		if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
			throw new TimeZoneUnknownException(timeZone);
		}
	}

	public static class TimeZoneUnknownException extends RuntimeException {
		private final String timeZone;

		public TimeZoneUnknownException(String timeZone) {
			super("Not an IANA zone this platform knows: '" + timeZone + "'");
			this.timeZone = timeZone;
		}

		public String timeZone() {
			return timeZone;
		}
	}

	public static class TemplateNameInUseException extends RuntimeException {
		public TemplateNameInUseException(String name) {
			super("An active template is already named '" + name + "'");
		}
	}

	public static class TemplateInUseException extends RuntimeException {
		public TemplateInUseException(String externalId) {
			super("Active teams still reference template '" + externalId + "'");
		}
	}

	public static class ShiftTemplateUnknownException extends RuntimeException {
		public ShiftTemplateUnknownException(String externalId) {
			super("No shift template '" + externalId + "'");
		}
	}

	public static class BreakTemplateUnknownException extends RuntimeException {
		public BreakTemplateUnknownException(String externalId) {
			super("No break template '" + externalId + "'");
		}
	}
}
