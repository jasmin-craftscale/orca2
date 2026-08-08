package com.lynxis.orca.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.SettingsTables.SettingDefinition;
import com.lynxis.orca.core.domain.SettingsTables.SettingValue;
import com.lynxis.orca.core.persistence.SettingRepository;

import lombok.RequiredArgsConstructor;

/**
 * §C1's settings, in its own words: a registry of known keys, validated
 * writes, history appended, secrets rejected.
 *
 * <p>The secret check runs BEFORE the registry lookup, deliberately: a
 * secret-shaped key must be refused as a secret even when it is also unknown,
 * because "unknown key" invites adding it to the registry and "secrets never
 * enter this table" is the rule that must not erode (sheet rule 8 — 1.x kept
 * Keycloak client secrets, SMTP passwords and VAPID keys in this table).
 */
@RequiredArgsConstructor
public class SettingsService {

	/**
	 * The shapes of the 1.x keys rule 8 excludes: {@code *_CLIENT_SECRET},
	 * {@code CLUSTER_PASSWORD}, {@code SMTP_PASSWORD}, {@code AZURE_*_KEY},
	 * {@code VAPID_PRIVATE_KEY} — generalised to the suffix families, so the
	 * next secret-shaped key is refused without a list edit.
	 */
	private static final Set<String> SECRET_SUFFIXES = Set.of(
			"_PASSWORD", "_SECRET", "_PRIVATE_KEY", "_KEY", "_TOKEN", "_CREDENTIAL");

	private final SettingRepository settings;
	private final AuditTrail audit;

	public record SettingView(SettingDefinition definition, SettingValue current) {
	}

	public List<SettingView> list() {
		Map<Long, SettingValue> byDefinition = settings.values().stream()
				.collect(Collectors.toMap(SettingValue::settingDefinitionId, value -> value));
		return settings.definitions().stream()
				.map(definition -> new SettingView(definition,
						byDefinition.get(definition.settingDefinitionId())))
				.toList();
	}

	@Transactional
	public SettingView write(String settingKey, String value) {
		requireNotSecretShaped(settingKey);
		SettingDefinition definition = settings.definitionByKey(settingKey)
				.orElseThrow(() -> new SettingUnknownException(settingKey));
		validate(definition, value);
		String oldValue = settings.valueOf(definition.settingDefinitionId())
				.map(SettingValue::settingValue)
				.orElse(null);
		String actor = audit.currentActor();
		settings.write(definition.settingDefinitionId(), oldValue, value, actor);
		audit.record("SETTING", settingKey, oldValue == null ? "CREATED" : "UPDATED",
				"value changed"); // never the value itself — a setting may be sensitive without being a secret
		return new SettingView(definition,
				settings.valueOf(definition.settingDefinitionId()).orElseThrow());
	}

	private static void requireNotSecretShaped(String settingKey) {
		String upper = settingKey == null ? "" : settingKey.toUpperCase(java.util.Locale.ROOT);
		for (String suffix : SECRET_SUFFIXES) {
			if (upper.endsWith(suffix)) {
				throw new SecretSettingRejectedException(settingKey);
			}
		}
	}

	private static void validate(SettingDefinition definition, String value) {
		if (value == null || value.isBlank()) {
			throw new SettingInvalidException(definition.settingKey(), "a value is required");
		}
		switch (definition.valueType()) {
			case "INTEGER" -> {
				try {
					Long.parseLong(value.trim());
				}
				catch (NumberFormatException notANumber) {
					throw new SettingInvalidException(definition.settingKey(), "must be an integer");
				}
			}
			case "BOOLEAN" -> {
				if (!"true".equals(value) && !"false".equals(value)) {
					throw new SettingInvalidException(definition.settingKey(), "must be true or false");
				}
			}
			case "STRING" -> {
				if (value.length() > 2000) {
					throw new SettingInvalidException(definition.settingKey(), "too long");
				}
			}
			default -> throw new IllegalStateException(
					"Unhandled value type in the registry: " + definition.valueType());
		}
	}

	public static class SettingUnknownException extends RuntimeException {
		private final String settingKey;

		public SettingUnknownException(String settingKey) {
			super("No setting '" + settingKey + "' in the registry");
			this.settingKey = settingKey;
		}

		public String settingKey() {
			return settingKey;
		}
	}

	public static class SecretSettingRejectedException extends RuntimeException {
		private final String settingKey;

		public SecretSettingRejectedException(String settingKey) {
			super("Secret-shaped key refused: '" + settingKey + "'");
			this.settingKey = settingKey;
		}

		public String settingKey() {
			return settingKey;
		}
	}

	public static class SettingInvalidException extends RuntimeException {
		private final String settingKey;
		private final String reason;

		public SettingInvalidException(String settingKey, String reason) {
			super("Setting '" + settingKey + "': " + reason);
			this.settingKey = settingKey;
			this.reason = reason;
		}

		public String settingKey() {
			return settingKey;
		}

		public String reason() {
			return reason;
		}
	}
}
