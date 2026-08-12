package com.lynxis.orca.runtime.execution.selector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The named quirks: each of these READS like a bug and is load-bearing
 * estate behavior. They exist so the next engineer sees intent, not an accident — the
 * conformance corpus proves the behavior wholesale, these tests name it. Do not "fix" any
 * of them without a ratified divergence entry.
 */
class SelectorQuirksTest {

    private static final SelectorDataProvider NO_DATA = new SelectorDataProvider() {
        @Override
        public Object fetchDataSets(String key, int workflowExecutionId) {
            // The estate stores dataset values as stringified JSON sub-documents
            return "[{\"weight\":\"38000\"}]";
        }

        @Override
        public Object fetchNodeConfigurationDetails() {
            throw new AssertionError("not used");
        }

        @Override
        public Object fetchWorkflowExecutionData(String columnName, int workflowExecutionId) {
            throw new AssertionError("not used");
        }

        @Override
        public int getPrimaryKeyByUuid(String tableName, String idColumnName, String uuidColumnName,
                String uuidValue) {
            return 1;
        }

        @Override
        public String getResourceConfigurations(int resourceId, String resourceType, String configKey) {
            throw new AssertionError("not used");
        }

        @Override
        public Map<String, Object> fetchNodeExecution(int workflowId, String nodeUuid,
                int workflowExecutionId) {
            throw new AssertionError("not used");
        }

        @Override
        public int getPreviousWorkflowExecutionId(int workflowId, int workflowExecutionId) {
            return workflowExecutionId;
        }

        @Override
        public Object executeQuery(String query) {
            throw new AssertionError("not used");
        }

        @Override
        public String extractLanePropertiesByLaneCode(String laneCode, String field) {
            throw new AssertionError("not used");
        }

        @Override
        public String fetchDeviceUrlForLane(String laneCode, String deviceAlias, String column) {
            throw new AssertionError("not used");
        }

        @Override
        public WorkflowRef getWorkflowIdByAliasName(String workflowAliasName, int workflowExecutionId) {
            throw new AssertionError("not used");
        }

        @Override
        public String getNodeUuidByAliasName(int workflowId, String nodeAliasName) {
            throw new AssertionError("not used");
        }
    };

    private final SelectorEvaluator evaluator =
            new SelectorEvaluator(NO_DATA, Clock.systemUTC(), "quirks");

    /**
     * QUIRK: {@code $.workflow.dataset.*} unmarshals stringified {@code scan_data}
     * sub-documents — the dataset arrives from the store as a JSON <em>string</em> and the
     * deep dive silently parses and navigates INTO it. The node-selector path does no such
     * parsing. Fixture lineage: TestSelectorService_ResolveSelectors dataset cases.
     */
    @Test
    void datasetDeepDiveParsesStringifiedSubDocuments() throws Exception {
        Object value = evaluator.resolveSelectors(
                "$.workflow.dataset.scan_data[0].weight",
                "$.wf-uuid.dataset.scan_data[0].weight",
                "exec-uuid", 1, "", 0);
        assertThat(value).isEqualTo("38000");
    }

    /**
     * QUIRK: a failed numeric conversion in an updation evaluates to {@code ""} — never an
     * error. A workflow that increments a non-numeric string gets an empty string and keeps
     * walking. (The comparison-side twin lives in {@link OrcaComparisonsTest}.)
     */
    @Test
    void failedNumericConversionYieldsEmptyStringNotError() {
        Object value = evaluator.updateSelectorValue("", "not-a-number", "id", "increment",
                "exec-uuid", 1, "", 0);
        assertThat(value).isEqualTo("");
    }

    /**
     * QUIRK: {@code UpdateSelectorValue} falls through to {@code ""} for any unrecognized
     * updation — the original value is DISCARDED, not preserved.
     */
    @Test
    void unknownUpdationDiscardsTheValue() {
        Object value = evaluator.updateSelectorValue("", "precious", "id", "no-such-updation",
                "exec-uuid", 1, "", 0);
        assertThat(value).isEqualTo("");
    }

    /**
     * QUIRK: every resolved string value passes a base64 auto-decode heuristic — a value
     * that merely LOOKS like padded base64 of printable text is silently decoded. Estate
     * payloads (device scans) rely on it; container numbers etc. survive only because they
     * fail the padding/length heuristics.
     */
    @Test
    void resolvedStringsAreAutoBase64Decoded() {
        assertThat(SelectorEvaluator.tryDecodeBase64("aGVsbG8=")).isEqualTo("hello");
        assertThat(SelectorEvaluator.tryDecodeBase64("MSKU1234567")).isEqualTo("MSKU1234567");
    }

    /**
     * QUIRK: exists returns the STRING {@code "true"}/{@code "false"}, not a boolean —
     * downstream comparisons in the estate compare against the string literals.
     */
    @Test
    void existsHelperReturnsAStringNotABoolean() throws Exception {
        Object value = evaluator.resolveSelectors(
                "$.helper.exists($.workflow.dataset.scan_data)",
                "$.helper.exists($.wf-uuid.dataset.scan_data)",
                "exec-uuid", 1, "", 0);
        assertThat(value).isEqualTo("true");
    }
}
