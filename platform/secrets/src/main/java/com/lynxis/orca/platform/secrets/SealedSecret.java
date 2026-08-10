package com.lynxis.orca.platform.secrets;

/** Storage representation: generation identifier, nonce and ciphertext/tag. */
public record SealedSecret(String keyId, String nonceBase64, String ciphertextBase64) {

	@Override
	public String toString() {
		return "SealedSecret[redacted]";
	}
}
