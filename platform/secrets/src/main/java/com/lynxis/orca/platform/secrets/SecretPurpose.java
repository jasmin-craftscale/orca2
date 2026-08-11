package com.lynxis.orca.platform.secrets;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The authenticated context a sealed value belongs to.
 *
 * <p>Components remain separate and ordered. Joining them into a string would
 * make {@code ["ab", "c"]} indistinguishable from {@code ["a", "bc"]} under
 * an unfortunate delimiter choice.
 */
public record SecretPurpose(
		String ownerNamespace,
		List<String> recordIdentityComponents,
		String credentialKind) {

	private static final int FORMAT_VERSION = 1;
	private static final String INVALID = "Secret purpose requires a nonblank owner, record identity, and kind.";

	public SecretPurpose {
		requirePart(ownerNamespace);
		if (recordIdentityComponents == null || recordIdentityComponents.isEmpty()) {
			throw new IllegalArgumentException(INVALID);
		}
		recordIdentityComponents.forEach(SecretPurpose::requirePart);
		recordIdentityComponents = List.copyOf(recordIdentityComponents);
		requirePart(credentialKind);
	}

	byte[] authenticatedData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream data = new DataOutputStream(bytes)) {
				data.writeByte(FORMAT_VERSION);
				write(data, ownerNamespace);
				data.writeInt(recordIdentityComponents.size());
				for (String component : recordIdentityComponents) {
					write(data, component);
				}
				write(data, credentialKind);
			}
			return bytes.toByteArray();
		}
		catch (IOException impossibleForMemoryBuffer) {
			throw new IllegalStateException("Could not encode secret purpose.");
		}
	}

	private static void write(DataOutputStream data, String value) throws IOException {
		byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
		data.writeInt(encoded.length);
		data.write(encoded);
	}

	private static void requirePart(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(INVALID);
		}
	}
}
