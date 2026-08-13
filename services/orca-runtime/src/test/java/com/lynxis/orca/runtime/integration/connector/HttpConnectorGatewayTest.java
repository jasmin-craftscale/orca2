package com.lynxis.orca.runtime.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorRequest;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorResult;
import com.lynxis.orca.runtime.execution.delegate.spi.VisitIdentity;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gateway against a real loopback server. A stubbed {@link HttpClient} would prove the
 * gateway calls a mock; a socket proves it produces a request another process can read, which
 * is the thing that has to be right.
 */
class HttpConnectorGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private int status = 200;
    private String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** A catalog answering one fixed definition — where it is stored is not what this test is about. */
    private static ConnectorCatalog catalogOf(ConnectorDefinition definition) {
        return nodeUuid -> Optional.ofNullable(definition);
    }

    private static ConnectorDefinition definition(String endpoint, String method,
            String fieldMappings, List<ConnectorDefinition.ResponseMapping> responses) {
        return definition(endpoint, method, fieldMappings, null, responses);
    }

    private static ConnectorDefinition definition(String endpoint, String method,
            String fieldMappings, String urlParameters,
            List<ConnectorDefinition.ResponseMapping> responses) {
        return new ConnectorDefinition("TOS lookup", "SITE-1", endpoint, method, "JSON",
                null, null, null, "OUTBOUND", null, fieldMappings, urlParameters, responses);
    }

    private HttpConnectorGateway gateway(ConnectorDefinition definition,
            Map<String, Object> selectorAnswers) {
        return new HttpConnectorGateway(catalogOf(definition), new NoAuthCredentials(),
                HttpClient.newHttpClient(),
                executionId -> (value, id, uuid) -> selectorAnswers.get(value));
    }

    private static ConnectorRequest request() {
        return new ConnectorRequest("idem-1", "node-1", "TOS lookup", "SITE-1", 7L,
                new VisitIdentity.Visit(42L, "visit-uuid"));
    }

    @Test
    @DisplayName("a POST sends the resolved field mappings as its body")
    void sendsResolvedBody() {
        ConnectorResult result = gateway(definition(url("/api"), "POST", """
                [{"key":"plate","type":"string","source":"SELECTOR","value":"$.a.plate"},
                 {"key":"kind","type":"string","source":"VALUE","value":"lookup"}]
                """, List.of()), Map.of("$.a.plate", "AB-123")).call(request());

        assertThat(lastBody.get()).isEqualTo("{\"plate\":\"AB-123\",\"kind\":\"lookup\"}");
        assertThat(result.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a GET sends no body and resolves its parameters into the URL")
    void getSendsNoBody() {
        gateway(definition(url("/api/visit/{id}"), "GET", null, """
                [{"key":"id","type":"string","source":"SELECTOR","value":"$.a.visit"}]
                """, List.of()), Map.of("$.a.visit", "V 9/1")).call(request());

        assertThat(lastBody.get()).isEmpty();
        assertThat(lastPath.get()).isEqualTo("/api/visit/V%209%2F1");
    }

    @Test
    @DisplayName("a placeholder with no parameter mapping names the gap, not a URI index")
    void unresolvedPlaceholderIsNamed() {
        assertThatThrownBy(() -> gateway(
                definition(url("/api/visit/{id}"), "GET", null, List.of()), Map.of())
                .call(request()))
                .isInstanceOf(UnsupportedConnectorShapeException.class)
                .hasMessageContaining("unresolved URL placeholders");
    }

    @Test
    @DisplayName("the matching response's data_set_mapping is extracted from the body")
    void extractsDatasetForTheMatchedStatus() {
        responseBody = "{\"data\":{\"visitId\":\"V-9\"}}";
        ConnectorResult result = gateway(definition(url("/api"), "POST", "[]", List.of(
                new ConnectorDefinition.ResponseMapping(200, """
                        [{"data_set_key":"visit_id","mapping_value":
                          {"source":"SELECTOR","value":"$.data.visitId","selector_id":""}},
                         {"data_set_key":"state","mapping_value":
                          {"source":"","value":"MATCHED","selector_id":""}}]
                        """),
                new ConnectorDefinition.ResponseMapping(404, """
                        [{"data_set_key":"visit_id","mapping_value":
                          {"source":"","value":"NOT-THIS-ONE","selector_id":""}}]
                        """))), Map.of()).call(request());

        assertThat(result.dataset())
                .containsEntry("visit_id", "V-9")
                .containsEntry("state", "MATCHED");
    }

    @Test
    @DisplayName("an unmatched status falls back to the 000 row, as ORCA intends")
    void unmatchedStatusUsesTheCatchAllRow() {
        status = 503;
        responseBody = "{}";
        ConnectorResult result = gateway(definition(url("/api"), "POST", "[]", List.of(
                new ConnectorDefinition.ResponseMapping(200, """
                        [{"data_set_key":"ok","mapping_value":{"source":"","value":"yes"}}]
                        """),
                new ConnectorDefinition.ResponseMapping(0, """
                        [{"data_set_key":"failed","mapping_value":{"source":"","value":"true"}}]
                        """))), Map.of()).call(request());

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.dataset()).containsOnlyKeys("failed");
    }

    @Test
    @DisplayName("an unreachable connector fails; it never becomes a routable status")
    void transportFailureIsNotAStatus() {
        // Port 1 on loopback: nothing listens, so the connection is refused outright.
        assertThatThrownBy(() -> gateway(
                definition("http://127.0.0.1:1/api", "POST", "[]", List.of()), Map.of())
                .call(request()))
                .isInstanceOf(ConnectorCallFailedException.class)
                .hasMessageContaining("could not be reached");
    }

    @Test
    @DisplayName("a visit with no ORCA row fails rather than sending unresolved selectors")
    void refusesWhenTheVisitIsUnknown() {
        ConnectorRequest noVisit = new ConnectorRequest(
                "idem-1", "node-1", "TOS lookup", "SITE-1", 7L, null);

        assertThatThrownBy(() -> gateway(definition(url("/api"), "POST", "[]", List.of()), Map.of())
                .call(noVisit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ORCA visit");
    }

    @Test
    @DisplayName("an XML connector refuses rather than sending a JSON body to a SOAP endpoint")
    void refusesXmlConnectors() {
        ConnectorDefinition xml = new ConnectorDefinition("SOAP", "SITE-1", url("/api"), "POST",
                "XML", null, null, null, "OUTBOUND", null, "[]", null, List.of());

        assertThatThrownBy(() -> gateway(xml, Map.of()).call(request()))
                .isInstanceOf(UnsupportedConnectorShapeException.class)
                .hasMessageContaining("XML");
    }

    @Test
    @DisplayName("the unbound catalog refuses a call by name — configured nowhere is not configured")
    void theUnboundCatalogRefusesByName() {
        HttpConnectorGateway unbound = new HttpConnectorGateway(ConnectorCatalog.UNBOUND,
                new NoAuthCredentials(), HttpClient.newHttpClient(),
                executionId -> (value, id, uuid) -> null);

        assertThatThrownBy(() -> unbound.call(request()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ConnectorCatalog is not bound");
    }
}
