package com.lynxis.orca.runtime.integration.connector;

import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorGateway;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorRequest;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The live outbound connector: resolve the configuration, build the request, make the call,
 * and turn the answer into a status the compiled gateway routes on plus the dataset entries
 * downstream conditions read.
 *
 * <p><b>A transport failure is not a status.</b> A refused connection has no HTTP status, and
 * inventing one — 0, or 500 — would route the visit down a response branch its author wrote
 * for a real answer. The step fails instead, which is the truthful outcome and the one the
 * engine can retry.
 */
public class HttpConnectorGateway implements ConnectorGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectorGateway.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final ConnectorCatalog catalog;
    private final ConnectorCredentials credentials;
    private final java.net.http.HttpClient http;
    private final SelectorBinder selectors;

    /** Binds the evaluator to one visit — the gateway holds no selector machinery itself. */
    @FunctionalInterface
    public interface SelectorBinder {
        FieldMappingResolver.Selectors forVisit(long executionId);
    }

    public HttpConnectorGateway(ConnectorCatalog catalog, ConnectorCredentials credentials,
            java.net.http.HttpClient http, SelectorBinder selectors) {
        this.catalog = catalog;
        this.credentials = credentials;
        this.http = http;
        this.selectors = selectors;
    }

    @Override
    public ConnectorResult call(ConnectorRequest request) {
        ConnectorDefinition definition = catalog.definitionFor(request.nodeUuid())
                .orElseThrow(() -> new IllegalStateException(
                        "no active connector configuration for node " + request.nodeUuid()));
        if (request.visit() == null) {
            throw new IllegalStateException("connector " + request.nodeUuid()
                    + " has no ORCA visit, so its selectors cannot resolve");
        }
        if (definition.isXml()) {
            throw new UnsupportedConnectorShapeException("connector '" + definition.name()
                    + "' sends XML, which needs the XML body encoder (not ported)");
        }

        FieldMappingResolver.Selectors evaluator =
                selectors.forVisit(request.visit().executionId());
        FieldMappingResolver resolver =
                new FieldMappingResolver(evaluator, request.visit().externalId());

        String url = UrlTemplate.resolve(definition.endpointTemplate(),
                resolver.resolve(MappingField.parseAll(definition.urlParameterJson())));
        // A placeholder left standing means the parameter mapping does not cover it — an
        // authoring gap. Left alone it surfaces as URI.create's "illegal character at index
        // 33", which names nothing an author can act on.
        if (url.matches(".*\\{[^}]*}.*")) {
            throw new UnsupportedConnectorShapeException("connector '" + definition.name()
                    + "' has unresolved URL placeholders in '" + definition.endpointTemplate()
                    + "' — its parameter mapping does not supply every {name}");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(CALL_TIMEOUT);
        credentials.headersFor(definition.authCipher()).forEach(builder::header);

        if (definition.isGet()) {
            builder.GET();
        } else {
            Map<String, Object> body =
                    resolver.resolve(MappingField.parseAll(definition.fieldMappingsJson()));
            builder.header("Content-Type", "application/json")
                    .method(definition.method().toUpperCase(java.util.Locale.ROOT),
                            HttpRequest.BodyPublishers.ofString(
                                    ConnectorJson.MAPPER.writeValueAsString(body)));
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new ConnectorCallFailedException(
                    "connector '" + definition.name() + "' could not be reached", unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConnectorCallFailedException(
                    "interrupted calling connector '" + definition.name() + "'", interrupted);
        }

        int status = response.statusCode();
        Map<String, Object> dataset = extract(definition, status, response.body(), evaluator);
        log.debug("connector {} answered {} with {} dataset entries",
                definition.name(), status, dataset.size());
        return new ConnectorResult(status, dataset);
    }

    /**
     * The configured {@code data_set_mapping} for the status that came back, resolved against
     * the response body.
     *
     * <p>A mapping entry is either a literal or a selector, and a selector may name the
     * response itself. Entries that resolve to nothing are omitted rather than written as
     * empty, matching the Go path — a dataset key that exists but is blank and one that was
     * never written are different things to a downstream condition.
     */
    private Map<String, Object> extract(ConnectorDefinition definition, int status,
            String responseBody, FieldMappingResolver.Selectors evaluator) {
        ConnectorDefinition.ResponseMapping mapping = definition.mappingFor(status);
        if (mapping == null || mapping.dataSetMappingJson() == null
                || mapping.dataSetMappingJson().isBlank()) {
            return Map.of();
        }
        JsonNode body = parseOrNull(responseBody);
        Map<String, Object> out = new LinkedHashMap<>();
        for (JsonNode entry : ConnectorJson.MAPPER.readTree(mapping.dataSetMappingJson())) {
            String key = entry.path("data_set_key").asString("");
            if (key.isEmpty()) {
                continue;
            }
            JsonNode value = entry.path("mapping_value");
            Object resolved;
            if ("SELECTOR".equals(value.path("source").asString(""))) {
                resolved = resolveAgainstResponse(
                        value.path("value").asString(""),
                        value.path("selector_id").asString(""), body, evaluator);
            } else {
                resolved = value.path("value").asString("");
            }
            if (!FieldMappingResolver.isEmpty(resolved)) {
                out.put(key, resolved);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * A response selector reads the body it just received, so the body is tried first and the
     * evaluator answers only what the body does not carry — the same precedence the Go
     * executor gets by writing the response into the node's execution payload before
     * resolving.
     */
    private Object resolveAgainstResponse(String selector, String selectorId, JsonNode body,
            FieldMappingResolver.Selectors evaluator) {
        Object fromBody = readPath(body, selector);
        return fromBody != null ? fromBody : evaluator.resolve(selector, selectorId, "");
    }

    /** {@code $.a.b[0].c} against the response body; null when the path is not present. */
    private static Object readPath(JsonNode body, String selector) {
        if (body == null || selector == null || !selector.startsWith("$.")) {
            return null;
        }
        JsonNode node = body;
        for (String segment : segmentsOf(selector.substring(2))) {
            if (node == null) {
                return null;
            }
            node = segment.matches("\\d+") ? node.path(Integer.parseInt(segment))
                    : node.path(segment);
            if (node.isMissingNode()) {
                return null;
            }
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.isValueNode() ? null : node.toString();
    }

    private static List<String> segmentsOf(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("\\.", -1)) {
            int bracket = part.indexOf('[');
            if (bracket < 0) {
                out.add(part);
                continue;
            }
            out.add(part.substring(0, bracket));
            for (String index : part.substring(bracket).split("\\[")) {
                String trimmed = index.replace("]", "").trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return ConnectorJson.MAPPER.readTree(body);
        } catch (RuntimeException notJson) {
            // A non-JSON body is legal — the status alone still routes the visit.
            return null;
        }
    }
}
