package com.lynxis.orca.runtime.integration.connector;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opens {@code connector_config.auth} and turns it into request headers.
 *
 * <p><b>NOT WIRED. This class requires human sign-off before it runs against real
 * configuration (ruling 5, 2026-08-09).</b> Nothing constructs it; {@link NoAuthCredentials}
 * is the wired default. Wiring it is a deliberate act, not a default that happens to be on.
 *
 * <h2>What a reviewer is being asked to confirm</h2>
 *
 * <p><b>1. The key and its rotations.</b> The Go executor decrypts with
 * {@code DB_ENCRYPTION_KEY[:32]} and, on failure, retries with every rotation key the
 * preflight holder carries. That fallback list is a constructor argument here rather than
 * ambient state, so what this can decrypt is visible at the wiring site instead of depending
 * on a global that may or may not have been populated.
 *
 * <p><b>2. CFB has no authentication, and that is inherited, not chosen.</b> GCM is tried
 * first and fails cleanly on a wrong key. The legacy CFB path cannot fail — a wrong key
 * yields plausible-looking garbage, which then fails JSON parsing a few lines later. So "the
 * key was wrong" and "the stored config was malformed" arrive as the same error. Reproduced
 * because rows encrypted before the GCM migration still exist and must keep opening; worth
 * knowing before this runs anywhere real.
 *
 * <p><b>3. OAuth tokens are fetched per call and never cached.</b> That matches Go. It means
 * a busy connector hits its identity provider once per visit, which is a load characteristic
 * somebody should agree to rather than discover.
 *
 * <p><b>4. Credentials are sent over whatever scheme {@code req_path} specifies</b>, http
 * included. This deliberately matches the Go behaviour rather than refusing plaintext
 * endpoints — changing it is a product decision that was explicitly taken elsewhere.
 *
 * <p>Nothing decrypted here is logged, returned in an error message, or retained past the
 * call.
 */
public final class EncryptedConnectorCredentials implements ConnectorCredentials {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_BLOCK_BYTES = 16;

    private final List<byte[]> keys;
    private final HttpClient http;

    /**
     * @param primaryKey {@code DB_ENCRYPTION_KEY}; only its first 32 bytes are used, as in Go
     * @param rotationKeys older keys still able to open rows written before a rotation, tried
     *     in order after the primary fails — pass empty when there are none rather than
     *     relying on a global holder
     */
    public EncryptedConnectorCredentials(byte[] primaryKey, List<byte[]> rotationKeys,
            HttpClient http) {
        List<byte[]> all = new ArrayList<>();
        all.add(truncate(primaryKey));
        for (byte[] k : rotationKeys) {
            if (k != null && k.length >= 32) {
                all.add(truncate(k));
            }
        }
        this.keys = List.copyOf(all);
        this.http = http;
    }

    private static byte[] truncate(byte[] key) {
        if (key.length <= 32) {
            return key.clone();
        }
        byte[] out = new byte[32];
        System.arraycopy(key, 0, out, 0, 32);
        return out;
    }

    @Override
    public Map<String, String> headersFor(String authCipher) {
        if (authCipher == null || authCipher.isBlank()) {
            return Map.of();
        }
        JsonNode config = JSON.readTree(decrypt(authCipher));
        String type = config.path("auth_type").asString("");
        JsonNode payload = config.path("payload");

        Map<String, String> headers = new LinkedHashMap<>();
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "oauth" -> headers.put("Authorization", "Bearer " + oauthToken(payload));
            case "baseauth" -> {
                String user = require(payload, "username", "BaseAuth needs a username");
                String pass = require(payload, "password", "BaseAuth needs a password");
                headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
            }
            case "privatekey" -> headers.put(
                    require(payload, "privateKey", "PrivateKey needs a header name"),
                    require(payload, "privateValue", "PrivateKey needs a header value"));
            case "noauth" -> { }
            default -> throw new IllegalStateException(
                    "unsupported connector authentication type: " + type);
        }
        return headers;
    }

    /** The message names the missing field only — never what any other field contains. */
    private static String require(JsonNode payload, String field, String message) {
        String value = payload.path(field).asString("");
        if (value.isEmpty()) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private String oauthToken(JsonNode payload) {
        String authUrl = require(payload, "authUrl", "OAuth needs an authUrl");
        String clientId = require(payload, "clientId", "OAuth needs a clientId");
        String clientSecret = require(payload, "clientSecret", "OAuth needs a clientSecret");

        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        form.append("&client_id=").append(formEncode(clientId));
        form.append("&client_secret=").append(formEncode(clientSecret));
        String scope = payload.path("scope").asString("");
        if (!scope.isEmpty()) {
            form.append("&scope=").append(formEncode(scope));
        }
        String tenant = payload.path("tenantId").asString("");
        if (!tenant.isEmpty()) {
            form.append("&tenant_id=").append(formEncode(tenant));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failed) {
            // The URL is safe to name; the form body is not, so it never reaches the message.
            throw new IllegalStateException("OAuth token request to " + authUrl + " failed", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted fetching an OAuth token", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "OAuth token request returned HTTP " + response.statusCode());
        }
        String token = JSON.readTree(response.body()).path("access_token").asString("");
        if (token.isEmpty()) {
            throw new IllegalStateException("OAuth response carried no access_token");
        }
        return token;
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * GCM under each key in turn, then CFB under each key in turn — the same order Go tries
     * them, so a row that opens there opens here.
     */
    private String decrypt(String ciphertext) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("connector auth is not base64", notBase64);
        }
        for (byte[] key : keys) {
            String opened = tryGcm(key, bytes);
            if (opened != null) {
                return unquote(opened);
            }
        }
        for (byte[] key : keys) {
            String opened = tryCfb(key, bytes);
            if (opened != null) {
                return unquote(opened);
            }
        }
        throw new IllegalStateException("connector auth could not be decrypted with any key");
    }

    private static String tryGcm(byte[] key, byte[] bytes) {
        if (bytes.length <= GCM_NONCE_BYTES) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_NONCE_BYTES));
            byte[] plain = cipher.doFinal(bytes, GCM_NONCE_BYTES, bytes.length - GCM_NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception wrongKeyOrTampered) {
            return null;
        }
    }

    private static String tryCfb(byte[] key, byte[] bytes) {
        if (bytes.length < AES_BLOCK_BYTES) {
            return null;
        }
        try {
            // CFB128 explicitly: Go's NewCFBDecrypter is full-block, and leaving the segment
            // size to the provider's default would quietly produce a different plaintext.
            Cipher cipher = Cipher.getInstance("AES/CFB128/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(bytes, 0, AES_BLOCK_BYTES));
            byte[] plain = cipher.doFinal(bytes, AES_BLOCK_BYTES, bytes.length - AES_BLOCK_BYTES);
            String text = new String(plain, StandardCharsets.UTF_8);
            // CFB authenticates nothing, so a wrong key "succeeds" and returns noise. The only
            // available sanity check is that the result looks like the JSON it must be.
            return text.trim().startsWith("{") ? text : null;
        } catch (Exception notCfb) {
            return null;
        }
    }

    /** Go strips a wrapping pair of quotes after decrypting; a stored value may carry them. */
    private static String unquote(String value) {
        return value.length() > 1 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"'
                ? value.substring(1, value.length() - 1) : value;
    }
}
