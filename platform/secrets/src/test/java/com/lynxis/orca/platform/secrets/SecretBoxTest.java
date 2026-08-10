package com.lynxis.orca.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SecretBoxTest {

	private static final String SENTINEL = "test-only-sentinel-π-密碼";
	private static final SecretPurpose PURPOSE =
			new SecretPurpose("owner", List.of("site-a", "record-a"), "password");

	@Test
	void unicodePlaintextRoundTrips() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = box.seal(SENTINEL, PURPOSE);
		assertThat(box.open(sealed, PURPOSE)).isEqualTo(SENTINEL);
		assertThat(sealed.toString()).isEqualTo("SealedSecret[redacted]")
				.doesNotContain(sealed.keyId(), sealed.nonceBase64(), sealed.ciphertextBase64());
	}

	@Test
	void everySealUsesAFreshNonce() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret first = box.seal(SENTINEL, PURPOSE);
		SealedSecret second = box.seal(SENTINEL, PURPOSE);
		assertThat(first.nonceBase64()).isNotEqualTo(second.nonceBase64());
		assertThat(first.ciphertextBase64()).isNotEqualTo(second.ciphertextBase64());
	}

	@Test
	void changedCiphertextOrTagFailsRedacted() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = box.seal(SENTINEL, PURPOSE);
		byte[] changed = Base64.getDecoder().decode(sealed.ciphertextBase64());
		changed[changed.length - 1] ^= 1;
		assertRedacted(() -> box.open(new SealedSecret(sealed.keyId(), sealed.nonceBase64(),
				Base64.getEncoder().encodeToString(changed)), PURPOSE));
	}

	@Test
	void changedNonceFailsRedacted() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = box.seal(SENTINEL, PURPOSE);
		byte[] changed = Base64.getDecoder().decode(sealed.nonceBase64());
		changed[0] ^= 1;
		assertRedacted(() -> box.open(new SealedSecret(sealed.keyId(),
				Base64.getEncoder().encodeToString(changed), sealed.ciphertextBase64()), PURPOSE));
	}

	@Test
	void aSealedValueCannotMoveToAnotherRecord() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = box.seal(SENTINEL, PURPOSE);
		SecretPurpose destination = new SecretPurpose("owner", List.of("site-a", "record-b"), "password");
		assertRedacted(() -> box.open(sealed, destination));
	}

	@Test
	void oldGenerationOpensAfterTheCurrentGenerationChanges() {
		SecretBox old = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = old.seal(SENTINEL, PURPOSE);
		SecretBox rotated = box("v2", Map.of("v1", key('a'), "v2", key('b')));
		assertThat(rotated.open(sealed, PURPOSE)).isEqualTo(SENTINEL);
	}

	@Test
	void sealAlwaysWritesTheDeclaredCurrentGeneration() {
		Map<String, String> keys = new LinkedHashMap<>();
		keys.put("v1", key('a'));
		keys.put("v2", key('b'));
		assertThat(box("v2", keys).seal(SENTINEL, PURPOSE).keyId()).isEqualTo("v2");
	}

	@Test
	void nonceMustDecodeToExactlyTwelveBytes() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = box.seal(SENTINEL, PURPOSE);
		assertRedacted(() -> box.open(new SealedSecret("v1",
				Base64.getEncoder().encodeToString(new byte[11]), sealed.ciphertextBase64()), PURPOSE));
		assertRedacted(() -> SecretBox.decodeNonce(
				Base64.getEncoder().encodeToString(new byte[11])));
	}

	@Test
	void malformedBase64UnknownKeysAndNullInputsAreRedacted() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		SealedSecret sealed = box.seal(SENTINEL, PURPOSE);
		assertRedacted(() -> box.open(new SealedSecret("v1", "not base64", "also not"), PURPOSE));
		assertRedacted(() -> box.open(new SealedSecret("missing", sealed.nonceBase64(),
				sealed.ciphertextBase64()), PURPOSE));
		assertRedacted(() -> box.open(null, PURPOSE));
		assertRedacted(() -> box.open(sealed, null));
	}

	@Test
	void nullPlaintextAndBlankPurposePartsAreDeliberatelyRefused() {
		SecretBox box = box("v1", Map.of("v1", key('a')));
		assertThatThrownBy(() -> box.seal(null, PURPOSE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Secret plaintext must not be null.");
		assertThatThrownBy(() -> box.seal(SENTINEL, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Secret purpose must not be null.");
		assertThatThrownBy(() -> new SecretPurpose(" ", List.of("record"), "kind"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Secret purpose requires a nonblank owner, record identity, and kind.");
		assertThatThrownBy(() -> new SecretPurpose("owner", List.of(" "), "kind"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SecretPurpose("owner", List.of("record"), " "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SecretPurpose("owner", java.util.Arrays.asList("record", null), "kind"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Secret purpose requires a nonblank owner, record identity, and kind.");
	}

	private static void assertRedacted(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call)
				.isInstanceOf(SecretOpenException.class)
				.hasMessage("Secret could not be opened because its sealed data, purpose, or key generation is invalid.")
				.hasMessageNotContaining(SENTINEL)
				.hasMessageNotContaining("base64")
				.hasMessageNotContaining("missing")
				.hasMessageNotContaining("record-a");
	}

	private static SecretBox box(String current, Map<String, String> keys) {
		return new SecretBox(current, keys);
	}

	private static String key(char character) {
		return Base64.getEncoder().encodeToString(
				String.valueOf(character).repeat(32).getBytes(StandardCharsets.UTF_8));
	}
}
