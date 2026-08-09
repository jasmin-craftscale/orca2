package com.lynxis.orca.core.domain;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.DeviceTables.ResourceConfiguration;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.ResourceConfigurationRepository;
import com.lynxis.orca.core.persistence.ResourceConfigurationRepository.Entry;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;

import lombok.RequiredArgsConstructor;

/**
 * Manages custom variables at site, area, lane and device scope.
 *
 * <p>The resource must exist before it can carry variables — resolved per
 * scope: sites from core's own table, lanes and areas through the published
 * view, devices from the registry. One honest limitation, recorded in the
 * report: an area with no lanes is invisible to the view and cannot be
 * validated until areas carry their own scope column.
 */
@RequiredArgsConstructor
public class ResourceConfigurationService {

	private final ResourceConfigurationRepository configurations;
	private final SiteDirectoryRepository sites;
	private final DeviceRepository devices;

	public record ResourceView(String scopeType, String resourceExternalId, List<ResourceConfiguration> entries) {
	}

	public ResourceView read(String scopeType, String resourceExternalId) {
		requireResource(scopeType, resourceExternalId);
		return new ResourceView(scopeType, resourceExternalId,
				configurations.activeFor(scopeType, resourceExternalId));
	}

	@Transactional
	public ResourceView replace(String scopeType, String resourceExternalId, List<Entry> entries) {
		String siteExternalId = requireResource(scopeType, resourceExternalId);
		DuplicateRequestEntryException.requireDistinct(entries, "key", Entry::key);
		configurations.replaceFor(scopeType, resourceExternalId, siteExternalId, entries);
		return new ResourceView(scopeType, resourceExternalId,
				configurations.activeFor(scopeType, resourceExternalId));
	}

	/** @return the site the resource belongs to — the row's scope value. */
	private String requireResource(String scopeType, String resourceExternalId) {
		switch (scopeType) {
			case "SITE" -> {
				if (sites.activeSiteExternalIds().contains(resourceExternalId)) {
					return resourceExternalId;
				}
			}
			case "AREA", "LANE" -> {
				var site = configurations.siteOfLaneOrArea(scopeType, resourceExternalId);
				if (site.isPresent()) {
					return site.get();
				}
			}
			case "DEVICE" -> {
				var device = devices.byExternalId(resourceExternalId)
						.filter(found -> found.retiredAt() == null);
				if (device.isPresent()) {
					return device.get().siteExternalId();
				}
			}
			default -> throw new IllegalArgumentException("Unknown scope type: " + scopeType);
		}
		throw new ResourceUnknownException(scopeType, resourceExternalId);
	}

	public static class ResourceUnknownException extends RuntimeException {
		public ResourceUnknownException(String scopeType, String resourceExternalId) {
			super("No " + scopeType + " '" + resourceExternalId + "' at this installation");
		}
	}
}
