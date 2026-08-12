package com.lynxis.orca.runtime.execution.selector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The selector conformance suite. Runs the SAME fixture corpus the Go runner validates
 * (`selector_conformance_test.go` in work-flow-executor-service): inputs extracted from the
 * Go evaluator's 337 tests, expected outputs recorded from the Go evaluator itself. Green
 * here means the port preserves Go semantics case-for-case; a failure names the exact
 * fixture and the Go-recorded truth it missed.
 */
class SelectorConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CORPUS = Path.of("src", "test", "resources", "fixtures", "corpus");

    @TestFactory
    Stream<DynamicTest> conformance() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(CORPUS)) {
            files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("fixture corpus present").isNotEmpty();

        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : files) {
            JsonNode fixtures = MAPPER.readTree(Files.readString(file));
            for (JsonNode fixture : fixtures) {
                String name = file.getFileName() + "/" + fixture.path("source").asString();
                tests.add(DynamicTest.dynamicTest(name, () -> runFixture(fixture)));
            }
        }
        return tests.stream();
    }

    private void runFixture(JsonNode fixture) {
        if (fixture.has("skip")) {
            throw new TestAbortedException("skipped by fixture: " + fixture.get("skip").asString());
        }
        JsonNode expected = fixture.get("expected");
        assertThat(expected).as("fixture has a recorded expected outcome").isNotNull();

        Outcome actual = dispatch(fixture);

        JsonNode expectedError = expected.get("error");
        boolean expectError = expectedError != null && !expectedError.isNull();

        if (fixture.path("volatile").asBoolean(false)) {
            assertThat(actual.error() != null)
                    .as("volatile fixture: error presence")
                    .isEqualTo(expectError);
            return;
        }

        if (expectError) {
            assertThat(actual.error())
                    .as("expected an error, got value: " + actual.value())
                    .isNotNull();
            if (expectedError.has("dto")) {
                // dto messages are ORCA-authored constants — they must port exactly
                assertThat(actual.error().dto())
                        .as("error kind (dto vs go)")
                        .isTrue();
                assertThat(actual.error().message()).isEqualTo(expectedError.get("dto").asString());
            }
            // go-error text is Go stdlib wording — presence is the contract (fixtures README)
            return;
        }

        assertThat(actual.error())
                .as("unexpected error: " + (actual.error() == null ? "" : actual.error().message()))
                .isNull();
        assertThat(canonical(actual.value()))
                .isEqualTo(canonicalNode(expected.get("value")));
    }

    private record FixtureError(String message, boolean dto) {
    }

    private record Outcome(Object value, FixtureError error) {
    }

    private static Outcome ok(Object value) {
        return new Outcome(value, null);
    }

    private static Outcome dtoErr(Object value, String message) {
        return message == null ? new Outcome(value, null)
                : new Outcome(value, new FixtureError(message, true));
    }

    // ---------------------------------------------------------------------- dispatch

    private Outcome dispatch(JsonNode fixture) {
        JsonNode args = fixture.path("args");
        SelectorEvaluator evaluator = new SelectorEvaluator(
                new CannedProvider(fixture.get("repo"), fixture.path("source").asString()),
                Clock.systemUTC(), "fixture-conformance");

        String kind = fixture.path("kind").asString();
        try {
            return switch (kind) {
                case "splitSelector" -> ok(evaluator.splitSelector(str(args, "selector")));
                case "extractValue" -> ok(evaluator.extractValue(any(args, "data"), strs(args, "path")));
                case "deepDiveData" -> {
                    try {
                        yield ok(evaluator.deepDiveData(strs(args, "parts"), any(args, "value")));
                    } catch (GoErrorException e) {
                        // Go wraps deep-dive failures in dto.Error before they reach the outcome
                        yield new Outcome(null, new FixtureError(e.getMessage(), false));
                    }
                }
                case "handleString" -> ok(evaluator.handleString(any(args, "value"), str(args, "types")));
                case "convertCondition" -> ok(evaluator.convertCondition(str(args, "condition")));
                case "convertConditions" -> ok(evaluator.convertConditions(str(args, "conditions")));
                case "removeSpacesFunction" -> goCall(() ->
                        SelectorEvaluator.removeSpacesFunction(strs(args, "selectorParts"), str(args, "selector")));
                case "toUpperCaseFunction" -> goCall(() ->
                        SelectorEvaluator.toUpperCaseFunction(strs(args, "selectorParts"), str(args, "selector")));
                case "extractKeyAndIndex" -> goCall(() -> {
                    SelectorEvaluator.KeyAndIndex ki = SelectorEvaluator.extractKeyAndIndex(str(args, "input"));
                    Map<String, Object> composite = new LinkedHashMap<>();
                    composite.put("key", ki.key());
                    composite.put("index", ki.index());
                    return composite;
                });
                case "getValueByIndexNotation" -> ok(SelectorEvaluator.getValueByIndexNotation(
                        str(args, "indexNotation"), list(args, "values")));
                case "convertArrayToObject" -> ok(evaluator.convertArrayToObject(any(args, "data")));
                case "convertArrayToObjectDataSet" -> ok(evaluator.convertArrayToObjectDataSet(any(args, "data")));
                case "processArrayItems" -> ok(evaluator.processArrayItems(
                        list(args, "fields"), map(args, "items")));
                case "convertFieldMappings" -> {
                    Object data = any(args, "data");
                    yield dtoErr(data, evaluator.convertFieldMappings(data));
                }
                case "splitNestedRegexSelector" -> ok(evaluator.splitNestedRegexSelector(str(args, "selector")));
                case "processRegexInput" -> {
                    SelectorEvaluator.RegexResult r = SelectorEvaluator.processRegexInput(str(args, "input"));
                    Map<String, Object> composite = new LinkedHashMap<>();
                    composite.put("matched", r.matched());
                    composite.put("matches", r.matches());
                    yield ok(composite);
                }
                case "existsHelperFunction" -> goCall(() -> evaluator.existsHelperFunction(
                        any(args, "value"), any(args, "selectorID"),
                        str(args, "workflowExecutionUUID"), intArg(args, "workflowExecutionID")));
                case "countHelperFunction" -> goCall(() -> SelectorEvaluator.countHelperFunction(
                        any(args, "selectorID"), any(args, "value")));
                case "generateTimeStamp" -> ok(evaluator.generateTimeStamp(str(args, "part")));
                case "removeExtraSpaces" -> ok(SelectorEvaluator.removeExtraSpaces(str(args, "value")));
                case "fetchCDLScanData" -> ok(evaluator.fetchCdlScanData(
                        strs(args, "valueParts"), str(args, "value"), strs(args, "selectorIDParts")));
                case "processSelectors" -> ok(evaluator.processSelectors(map(args, "request"),
                        str(args, "workflowExecutionUUID"), intArg(args, "workflowExecutionID"),
                        str(args, "parentWorkflowUUID"), intArg(args, "parentExecutionWorkflowID")));
                case "traverseAndProcess" -> {
                    Object data = any(args, "data");
                    String err = evaluator.traverseAndProcess(data,
                            str(args, "workflowExecutionUUID"), intArg(args, "workflowExecutionID"),
                            str(args, "parentWorkflowUUID"), intArg(args, "parentExecutionWorkflowID"));
                    yield dtoErr(data, err);
                }
                case "processMap" -> {
                    // A Go nil map ranges as empty and marshals as null — preserve the nil
                    boolean nil = any(args, "dataMap") == null;
                    Map<String, Object> dataMap = map(args, "dataMap");
                    String err = evaluator.processMap(dataMap,
                            str(args, "workflowExecutionUUID"), intArg(args, "workflowExecutionID"),
                            str(args, "parentWorkflowUUID"), intArg(args, "parentExecutionWorkflowID"));
                    yield dtoErr(nil ? null : dataMap, err);
                }
                case "resolveSelectors" -> {
                    try {
                        yield ok(evaluator.resolveSelectors(str(args, "selector"), str(args, "selectorID"),
                                str(args, "workflowExecutionUUID"), intArg(args, "workflowExecutionID"),
                                str(args, "parentWorkflowUUID"), intArg(args, "parentExecutionWorkflowID")));
                    } catch (DtoErrorException e) {
                        yield new Outcome(null, new FixtureError(e.getMessage(), true));
                    }
                }
                case "updateSelectorValue" -> ok(evaluator.updateSelectorValue(
                        str(args, "selector"), any(args, "selectorValue"), str(args, "selectorID"),
                        str(args, "updation"), str(args, "workflowExecutionUUID"),
                        intArg(args, "workflowExecutionID"), str(args, "parentWorkflowUUID"),
                        intArg(args, "parentExecutionWorkflowID")));
                case "resolveLaneSelector" -> ok(evaluator.resolveLaneSelector(
                        str(args, "selector"), str(args, "selectorID"), str(args, "workflowExecutionUUID"),
                        intArg(args, "workflowExecutionID"), str(args, "parentWorkflowUUID"),
                        intArg(args, "parentExecutionWorkflowID")));
                case "resolveDeviceSelector" -> ok(evaluator.resolveDeviceSelector(
                        str(args, "selector"), str(args, "selectorID"), intArg(args, "workflowExecutionID")));
                case "resolveWorkflowAliasSelector" -> {
                    try {
                        yield ok(evaluator.resolveWorkflowAliasSelector(
                                str(args, "selector"), str(args, "selectorID"),
                                str(args, "workflowExecutionUUID"), intArg(args, "workflowExecutionID"),
                                str(args, "parentWorkflowUUID"), intArg(args, "parentExecutionWorkflowID")));
                    } catch (DtoErrorException e) {
                        yield new Outcome(null, new FixtureError(e.getMessage(), true));
                    }
                }
                case "getResourceConfigurations" -> ok(evaluator.getResourceConfigurations(
                        str(args, "resourceUUID"), str(args, "resourceType"), str(args, "configKey")));
                default -> throw new AssertionError("unknown fixture kind: " + kind);
            };
        } catch (DtoErrorException e) {
            return new Outcome(null, new FixtureError(e.getMessage(), true));
        }
    }

    @FunctionalInterface
    private interface GoCall {
        Object call() throws GoErrorException, DtoErrorException;
    }

    private static Outcome goCall(GoCall call) throws DtoErrorException {
        try {
            return ok(call.call());
        } catch (GoErrorException e) {
            return new Outcome(null, new FixtureError(e.getMessage(), false));
        }
    }

    // ---------------------------------------------------------------------- arg decoding

    private static String str(JsonNode args, String name) {
        JsonNode node = args.get(name);
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static int intArg(JsonNode args, String name) {
        JsonNode node = args.get(name);
        return node == null || node.isNull() ? 0 : node.intValue();
    }

    private static List<String> strs(JsonNode args, String name) {
        JsonNode node = args.get(name);
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(e -> out.add(e.isNull() ? null : e.asString()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(JsonNode args, String name) {
        Object v = any(args, name);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode args, String name) {
        Object v = any(args, name);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static Object any(JsonNode args, String name) {
        JsonNode node = args.get(name);
        return node == null ? null : decode(node);
    }

    /** JSON → the evaluator's value universe: Double numbers, {"$int": N} → Long. */
    private static Object decode(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            node.forEach(e -> out.add(decode(e)));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (node.size() == 1 && node.has("$int")) {
            return node.get("$int").longValue();
        }
        node.properties().forEach(e -> out.put(e.getKey(), decode(e.getValue())));
        return out;
    }

    // ---------------------------------------------------------------------- comparison

    /** Normalizes numbers to Double and maps to sorted form so Go/Java trees compare. */
    private static Object canonical(Object value) {
        if (value instanceof Integer i) {
            return (double) i;
        }
        if (value instanceof Long l) {
            return (double) l;
        }
        if (value instanceof Double) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), canonical(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(e -> out.add(canonical(e)));
            return out;
        }
        return value;
    }

    private static Object canonicalNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            node.forEach(e -> out.add(canonicalNode(e)));
            return out;
        }
        TreeMap<String, Object> out = new TreeMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), canonicalNode(e.getValue())));
        return out;
    }

    // ---------------------------------------------------------------------- canned provider

    /**
     * The fixture "repo" replayed with the same semantics as the Go runner's fixtureRepo:
     * an object entry replays indefinitely, an array entry replays each element once, and
     * an uncanned call fails the fixture loudly (extraction gap, never a silent zero).
     */
    private static final class CannedProvider implements SelectorDataProvider {

        private final Map<String, Deque<JsonNode>> queues = new LinkedHashMap<>();
        private final Map<String, JsonNode> forever = new LinkedHashMap<>();
        private final String source;

        CannedProvider(JsonNode repo, String source) {
            this.source = source;
            if (repo != null && repo.isObject()) {
                repo.properties().forEach(e -> {
                    if (e.getValue().isArray()) {
                        Deque<JsonNode> queue = new ArrayDeque<>();
                        e.getValue().forEach(queue::add);
                        queues.put(e.getKey(), queue);
                    } else {
                        forever.put(e.getKey(), e.getValue());
                    }
                });
            }
        }

        private List<JsonNode> next(String method, int wantReturns) {
            JsonNode entry;
            Deque<JsonNode> queue = queues.get(method);
            if (queue != null && !queue.isEmpty()) {
                entry = queue.poll();
            } else {
                entry = forever.get(method);
            }
            if (entry == null || !entry.has("returns") || entry.get("returns").size() != wantReturns) {
                throw new AssertionError("fixture " + source + ": evaluator called provider."
                        + method + " but the fixture cans nothing usable for it");
            }
            List<JsonNode> returns = new ArrayList<>();
            entry.get("returns").forEach(returns::add);
            return returns;
        }

        private static void throwDto(JsonNode err) throws DtoErrorException {
            if (err == null || err.isNull()) {
                return;
            }
            throw new DtoErrorException(err.path("dto").asString());
        }

        private static void throwGo(JsonNode err) throws GoErrorException {
            if (err == null || err.isNull()) {
                return;
            }
            if (err.path("gorm_not_found").asBoolean(false)) {
                throw new GoErrorException("record not found", true);
            }
            throw new GoErrorException(err.path("go").asString());
        }

        @Override
        public Object fetchDataSets(String key, int workflowExecutionId) throws DtoErrorException {
            List<JsonNode> ret = next("FetchDataSets", 2);
            throwDto(ret.get(1));
            return decode(ret.get(0));
        }

        @Override
        public Object fetchNodeConfigurationDetails() throws DtoErrorException {
            List<JsonNode> ret = next("FetchNodeConfigurationDetails", 2);
            throwDto(ret.get(1));
            return decode(ret.get(0));
        }

        @Override
        public Object fetchWorkflowExecutionData(String columnName, int workflowExecutionId)
                throws DtoErrorException {
            List<JsonNode> ret = next("FetchWorkflowExecutionData", 2);
            throwDto(ret.get(1));
            return decode(ret.get(0));
        }

        @Override
        public int getPrimaryKeyByUuid(String tableName, String idColumnName, String uuidColumnName,
                String uuidValue) throws GoErrorException {
            List<JsonNode> ret = next("GetPrimaryKeyByUUID", 2);
            // Go returns the int alongside the error and some call sites read it anyway
            int value = ret.get(0).isNumber() ? ret.get(0).intValue() : 0;
            throwGo(ret.get(1));
            return value;
        }

        @Override
        public String getResourceConfigurations(int resourceId, String resourceType, String configKey)
                throws DtoErrorException {
            List<JsonNode> ret = next("GetResourceConfigurations", 2);
            throwDto(ret.get(1));
            return ret.get(0).isNull() ? null : ret.get(0).asString();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> fetchNodeExecution(int workflowId, String nodeUuid,
                int workflowExecutionId) throws DtoErrorException {
            List<JsonNode> ret = next("FetchNodeExecution", 2);
            throwDto(ret.get(1));
            Object decoded = decode(ret.get(0));
            return decoded instanceof Map ? (Map<String, Object>) decoded : new LinkedHashMap<>();
        }

        @Override
        public int getPreviousWorkflowExecutionId(int workflowId, int workflowExecutionId)
                throws DtoErrorException {
            List<JsonNode> ret = next("GetPreviousWorkflowExecutionID", 2);
            throwDto(ret.get(1));
            return ret.get(0).isNumber() ? ret.get(0).intValue() : 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object executeQuery(String query) throws DtoErrorException {
            List<JsonNode> ret = next("ExecuteQuery", 2);
            throwDto(ret.get(1));
            Object decoded = decode(ret.get(0));
            // Mirror the Go runner: an array of objects is a row set — []map[string]interface{}
            if (decoded instanceof List<?> list && !list.isEmpty()
                    && list.stream().allMatch(e -> e instanceof Map)) {
                MapSlice slice = new MapSlice();
                list.forEach(e -> slice.add((Map<String, Object>) e));
                return slice;
            }
            return decoded;
        }

        @Override
        public String extractLanePropertiesByLaneCode(String laneCode, String field)
                throws DtoErrorException {
            List<JsonNode> ret = next("ExtractLanePropertiesByLaneCode", 2);
            throwDto(ret.get(1));
            return ret.get(0).isNull() ? "" : ret.get(0).asString();
        }

        @Override
        public String fetchDeviceUrlForLane(String laneCode, String deviceAlias, String column)
                throws DtoErrorException {
            List<JsonNode> ret = next("FetchDeviceURLForLane", 2);
            throwDto(ret.get(1));
            return ret.get(0).isNull() ? "" : ret.get(0).asString();
        }

        @Override
        public WorkflowRef getWorkflowIdByAliasName(String workflowAliasName, int workflowExecutionId)
                throws DtoErrorException {
            List<JsonNode> ret = next("GetWorkflowIDByAliasName", 3);
            throwDto(ret.get(2));
            return new WorkflowRef(ret.get(0).isNumber() ? ret.get(0).intValue() : 0,
                    ret.get(1).isNull() ? "" : ret.get(1).asString());
        }

        @Override
        public String getNodeUuidByAliasName(int workflowId, String nodeAliasName)
                throws DtoErrorException {
            List<JsonNode> ret = next("GetNodeUUIDByAliasName", 2);
            throwDto(ret.get(1));
            return ret.get(0).isNull() ? "" : ret.get(0).asString();
        }
    }
}
