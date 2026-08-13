package com.lynxis.orca.platform.secrets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned AES-256-GCM sealing with binary, purpose-bound authenticated data.
 * Immutable and thread-safe; every operation creates its own {@link Cipher}.
 */
public final class SecretBox {

	static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final String OPEN_FAILED =
			"Secret could not be opened because its sealed data, purpose, or key generation is invalid.";

	private final String currentKeyId;
	private final Map<String, byte[]> keys;
	private final SecureRandom random;

	/** Direct construction receives the same validation as Spring configuration. */
	public SecretBox(String currentKeyId, Map<String, String> encodedKeys) {
		this(currentKeyId, validateAndDecode(currentKeyId, encodedKeys), new SecureRandom());
	}

	SecretBox(String currentKeyId, Map<String, byte[]> decodedKeys, SecureRandom random) {
		this.currentKeyId = currentKeyId;
		Map<String, byte[]> copy = new LinkedHashMap<>();
		decodedKeys.forEach((id, key) -> copy.put(id, key.clone()));
		this.keys = java.util.Collections.unmodifiableMap(copy);
		this.random = random;
	}

	public SealedSecret seal(String plaintext, SecretPurpose purpose) {
		if (plaintext == null) {
			throw new IllegalArgumentException("Secret plaintext must not be null.");
		}
		if (purpose == null) {
			throw new IllegalArgumentException("Secret purpose must not be null.");
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key(currentKeyId), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(purpose.authenticatedData());
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return new SealedSecret(currentKeyId,
					Base64.getEncoder().encodeToString(nonce),
					Base64.getEncoder().encodeToString(ciphertext));
		}
		catch (GeneralSecurityException unavailableCipher) {
			throw new IllegalStateException("The configured secret cipher is unavailable.");
		}
	}

	public String open(SealedSecret sealed, SecretPurpose purpose) {
		if (sealed == null || purpose == null) {
			throw failure();
		}
		try {
			byte[] nonce = decodeNonce(sealed.nonceBase64());
			byte[] ciphertext = Base64.getDecoder().decode(sealed.ciphertextBase64());
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(sealed.keyId()), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(purpose.authenticatedData());
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		}
		catch (SecretOpenException redacted) {
			throw redacted;
		}
		catch (RuntimeException | GeneralSecurityException rejected) {
			throw failure();
		}
	}

	static byte[] decodeNonce(String encoded) {
		try {
			byte[] nonce = Base64.getDecoder().decode(encoded);
			if (nonce.length != NONCE_BYTES) {
				throw failure();
			}
			return nonce;
		}
		catch (SecretOpenException redacted) {
			throw redacted;
		}
		catch (RuntimeException malformed) {
			throw failure();
		}
	}

	public String currentKeyId() {
		return currentKeyId;
	}

	private SecretKeySpec key(String keyId) {
		byte[] material = keys.get(keyId);
		if (material == null) {
			throw failure();
		}
		return new SecretKeySpec(material.clone(), "AES");
	}

	private static SecretOpenException failure() {
		return new SecretOpenException(OPEN_FAILED);
	}

	static Map<String, byte[]> validateAndDecode(String currentKeyId, Map<String, String> encodedKeys) {
		if (currentKeyId == null || currentKeyId.isBlank()) {
			throw new SecretConfigurationException("orca.secrets.current-key-id is missing or blank.");
		}
		if (encodedKeys == null || encodedKeys.isEmpty()) {
			throw new SecretConfigurationException("orca.secrets.keys is missing or empty.");
		}
		Map<String, byte[]> decoded = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : encodedKeys.entrySet()) {
			String id = entry.getKey();
			if (id == null || !KEY_ID.matcher(id).matches()) {
				throw new SecretConfigurationException("orca.secrets.keys contains an invalid key id.");
			}
			byte[] material;
			try {
				material = Base64.getDecoder().decode(entry.getValue());
			}
			catch (RuntimeException malformed) {
				throw new SecretConfigurationException("orca.secrets.keys['" + id + "'] is not valid Base64.");
			}
			if (material.length != KEY_BYTES) {
				throw new SecretConfigurationException(
						"orca.secrets.keys['" + id + "'] must decode to exactly 32 bytes.");
			}
			decoded.put(id, material.clone());
		}
		if (!decoded.containsKey(currentKeyId)) {
			throw new SecretConfigurationException(
					"orca.secrets.current-key-id does not name a configured key.");
		}
		return decoded;
	}
}
