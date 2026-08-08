package com.lynxis.orca.core.domain;

import java.util.List;
import java.util.Locale;

import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.RoleAdminService.SiteUnknownException;
import com.lynxis.orca.core.domain.WorkspaceTables.SiteColor;
import com.lynxis.orca.core.domain.WorkspaceTables.SiteLanguage;
import com.lynxis.orca.core.persistence.SiteBrandingRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;

import lombok.RequiredArgsConstructor;

/**
 * Site branding and localization — the narrow slice of §C1's
 * {@code PATCH /sites/{id}} this phase owns (colors and languages; full site
 * CRUD is not in Phase 2's scope and is recorded as absent, not implied).
 *
 * <p>Hex values are normalized here to {@code #RRGGBBAA} — 1.x mixed 6- and
 * 8-digit values; the CHECK constraint holds the invariant and this is the one
 * place that establishes it.
 */
@RequiredArgsConstructor
public class SiteBrandingService {

	private final SiteBrandingRepository branding;
	private final SiteDirectoryRepository sites;
	private final AuditTrail audit;

	public record BrandingView(String siteExternalId, List<SiteColor> colors, List<SiteLanguage> languages) {
	}

	public BrandingView branding(String siteExternalId) {
		requireSite(siteExternalId);
		return new BrandingView(siteExternalId,
				branding.activeColors(siteExternalId), branding.activeLanguages(siteExternalId));
	}

	/** Replaces whichever sets are present; {@code null} leaves a set unchanged. */
	@Transactional
	public BrandingView replace(String siteExternalId, List<SiteColor> colors, List<SiteLanguage> languages) {
		requireSite(siteExternalId);
		if (colors != null) {
			DuplicateRequestEntryException.requireDistinct(colors, "code", SiteColor::code);
		}
		if (languages != null) {
			DuplicateRequestEntryException.requireDistinct(languages, "code", SiteLanguage::code);
		}
		if (colors != null) {
			branding.replaceColors(siteExternalId, colors.stream()
					.map(color -> new SiteColor(0, siteExternalId, color.code(),
							normalizeHex(color.code(), color.hexValue()), null, null))
					.toList());
			audit.record("SITE", siteExternalId, "REPLACED", "branding colors");
		}
		if (languages != null) {
			branding.replaceLanguages(siteExternalId, languages);
			audit.record("SITE", siteExternalId, "REPLACED", "localization languages");
		}
		return branding(siteExternalId);
	}

	private void requireSite(String siteExternalId) {
		if (!sites.activeSiteExternalIds().contains(siteExternalId)) {
			throw new SiteUnknownException(siteExternalId);
		}
	}

	/** {@code #RRGGBB} gains an opaque alpha; case is folded; anything else is refused. */
	static String normalizeHex(String code, String raw) {
		if (raw == null) {
			throw new ColorInvalidException(code, "a hex value is required");
		}
		String value = raw.trim().toUpperCase(Locale.ROOT);
		if (!value.startsWith("#")) {
			value = "#" + value;
		}
		if (value.matches("#[0-9A-F]{6}")) {
			value = value + "FF";
		}
		if (!value.matches("#[0-9A-F]{8}")) {
			throw new ColorInvalidException(code, "not a #RRGGBB or #RRGGBBAA hex value: " + raw);
		}
		return value;
	}

	public static class ColorInvalidException extends RuntimeException {
		private final String code;
		private final String reason;

		public ColorInvalidException(String code, String reason) {
			super("Color '" + code + "': " + reason);
			this.code = code;
			this.reason = reason;
		}

		public String code() {
			return code;
		}

		public String reason() {
			return reason;
		}
	}
}
