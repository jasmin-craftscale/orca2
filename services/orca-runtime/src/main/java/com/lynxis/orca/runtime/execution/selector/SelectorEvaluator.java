package com.lynxis.orca.runtime.execution.selector;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The ORCA selector evaluator — deliberately the one pure bug-for-bug port
 * in this codebase ("Improvement over Go: none intended"). Control flow,
 * string surgery, quirks and all mirror the Go {@code SelectorService}; the extracted
 * fixture corpus recorded from the Go evaluator is the spec, and anything that reads like a
 * bug here probably has a fixture proving the estate depends on it.
 *
 * <p>Known preserved quirks (do not "fix" without a ratified divergence):
 * <ul>
 *   <li>{@code UpdateSelectorValue} falls through to {@code ""}, not the original value.</li>
 *   <li>An unresolvable selector inside {@code ProcessMap} blanks the value to {@code ""}.</li>
 *   <li>Failed numeric conversions yield {@code ""} — never an error.</li>
 *   <li>Resolved string values get a base64 auto-decode heuristic applied.</li>
 * </ul>
 */
public final class SelectorEvaluator {

    // utils/enums.go — the selector grammar's keywords
    private static final String WORKFLOW = "workflow";
    private static final String PARENT_WORKFLOW = "parent_workflow";
    private static final String PARENT = "parent";
    private static final String NODE = "node";
    private static final String HELPER = "helper";
    private static final String TIMESTAMP = "currentTimeStamp";
    private static final String GETCDLDATA = "getcdldata";
    private static final String CONFIGURATION = "configuration";
    private static final String DATASET = "dataset";
    private static final String WORKFLOW_EXECUTION = "workflowExecution";
    private static final String REFERENCE_DATA = "ReferenceData";
    private static final String LANE_SELECTOR = "$.lane";
    private static final String DEVICE_SELECTOR = "$.device";
    private static final String WORKFLOW_ALIAS_PREFIX = "workflow_alias";
    private static final String NODE_ALIAS_PREFIX = "node_alias";
    private static final String COUNT = "count";
    private static final String EXISTS = "exists";
    private static final String INCREMENT = "increment";
    private static final String DECREMENT = "decrement";
    private static final String REMOVESPACES = "removespaces";
    private static final String PARSE = "parse";
    private static final String TO_UPPER_CASE = "toUpper";

    private static final String SOURCE_KEY = "source";
    private static final String SELECTOR_ID_KEY = "selector_id";
    private static final String VALUE_KEY = "value";
    private static final String TYPE_KEY = "type";
    private static final String SELECTOR_TYPE = "SELECTOR";
    private static final String FIELD_MAPPING = "field_mapping";
    private static final String DATA_SET_MAPPING = "data_set_mapping";
    private static final String SCREEN_KEY = "screen";

    // error_codes.go values that surface in APIResponse payloads
    private static final String CODE_VALIDATION = "WORK_FLOW_EXECUTOR_VAL_001";
    private static final String CODE_NOT_FOUND = "WORK_FLOW_EXECUTOR_SQL_001";
    private static final String CODE_INTERNAL = "WORK_FLOW_EXECUTOR_SQL_006";

    private final SelectorDataProvider provider;
    private final Clock clock;
    private final String requestId;

    public SelectorEvaluator(SelectorDataProvider provider, Clock clock, String requestId) {
        this.provider = provider;
        this.clock = clock;
        this.requestId = requestId;
    }

    // ------------------------------------------------------------------ traversal layer

    public Map<String, Object> processSelectors(Map<String, Object> request, String workflowExecutionUuid,
            int workflowExecutionId, String parentWorkflowUuid, int parentExecutionWorkflowId) {
        traverseAndProcess(request, workflowExecutionUuid, workflowExecutionId,
                parentWorkflowUuid, parentExecutionWorkflowId);
        convertFieldMappings(request);
        return request;
    }

    /** Returns the Go {@code *dto.Error} position: null means success. */
    @SuppressWarnings("unchecked")
    public String traverseAndProcess(Object data, String workflowExecutionUuid, int workflowExecutionId,
            String parentWorkflowUuid, int parentExecutionWorkflowId) {
        if (data instanceof Map<?, ?> map) {
            return processMap((Map<String, Object>) map, workflowExecutionUuid, workflowExecutionId,
                    parentWorkflowUuid, parentExecutionWorkflowId);
        }
        if (data instanceof List<?> list && !(data instanceof MapSlice)) {
            for (Object item : list) {
                String err = traverseAndProcess(item, workflowExecutionUuid, workflowExecutionId,
                        parentWorkflowUuid, parentExecutionWorkflowId);
                if (err != null) {
                    return err;
                }
            }
        }
        return null;
    }

    public String processMap(Map<String, Object> dataMap, String workflowExecutionUuid,
            int workflowExecutionId, String parentWorkflowUuid, int parentExecutionWorkflowId) {
        String source = dataMap.get(SOURCE_KEY) instanceof String s ? s : null;
        String selectorId = dataMap.get(SELECTOR_ID_KEY) instanceof String s ? s : null;
        String selector = dataMap.get(VALUE_KEY) instanceof String s ? s : null;
        String typeKey = dataMap.get(TYPE_KEY) instanceof String s ? s : null;

        if (selectorId != null && selector != null && !selectorId.isEmpty() && !selector.isEmpty()) {
            Object resolved;
            try {
                resolved = resolveSelectors(selector, selectorId, workflowExecutionUuid,
                        workflowExecutionId, parentWorkflowUuid, parentExecutionWorkflowId);
            } catch (DtoErrorException e) {
                dataMap.put(VALUE_KEY, "");
                resolved = null;
            }
            if (resolved == null) {
                dataMap.put(VALUE_KEY, "");
                return null;
            }
            if ("string".equals(typeKey)) {
                dataMap.put(VALUE_KEY, handleString(resolved, "string"));
            } else {
                dataMap.put(VALUE_KEY, resolved);
            }
        }

        if (SELECTOR_TYPE.equals(source) && selector != null && !selector.isEmpty()
                && selectorId != null && selectorId.isEmpty()) {
            dataMap.put(VALUE_KEY, selector);
            return null;
        }

        for (Object value : new ArrayList<>(dataMap.values())) {
            String err = traverseAndProcess(value, workflowExecutionUuid, workflowExecutionId,
                    parentWorkflowUuid, parentExecutionWorkflowId);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public String convertFieldMappings(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> typed = (Map<String, Object>) map;
            for (Map.Entry<String, Object> entry : new ArrayList<>(typed.entrySet())) {
                if (FIELD_MAPPING.equals(entry.getKey())) {
                    typed.put(entry.getKey(), convertArrayToObject(entry.getValue()));
                } else if (DATA_SET_MAPPING.equals(entry.getKey())) {
                    typed.put(entry.getKey(), convertArrayToObjectDataSet(entry.getValue()));
                } else {
                    String err = convertFieldMappings(entry.getValue());
                    if (err != null) {
                        return err;
                    }
                }
            }
        } else if (data instanceof List<?> list && !(data instanceof MapSlice)) {
            for (Object item : list) {
                String err = convertFieldMappings(item);
                if (err != null) {
                    return err;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Object convertArrayToObject(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> result.put(k, convertArrayToObject(v)));
            return result;
        }
        if (!(data instanceof List<?> list) || data instanceof MapSlice) {
            return data;
        }
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        for (Object field : list) {
            if (!(field instanceof Map<?, ?> rawProps)) {
                continue;
            }
            Map<String, Object> fieldProps = (Map<String, Object>) rawProps;
            if (!(fieldProps.get("key") instanceof String fieldKey)) {
                continue;
            }
            if (!fieldProps.containsKey("value")) {
                continue;
            }
            Object fieldValue = fieldProps.get("value");
            if (!(fieldProps.get("type") instanceof String fieldType)) {
                continue;
            }
            List<String> parts = fieldValue instanceof String sel ? splitSelector(sel) : List.of();
            if (parts.isEmpty() || !SCREEN_KEY.equals(parts.get(0))) {
                switch (fieldType) {
                    case "object" -> {
                        if (fieldProps.get("fields") instanceof List<?> nestedFields
                                && !(fieldProps.get("fields") instanceof MapSlice)) {
                            fieldMap.put(fieldKey, convertArrayToObject(nestedFields));
                        } else {
                            fieldMap.put(fieldKey, fieldValue);
                        }
                    }
                    case "array" -> {
                        if (fieldProps.get("items") instanceof Map<?, ?> items) {
                            fieldMap.put(fieldKey, processArrayItems(
                                    (List<Object>) fieldProps.get("fields"), (Map<String, Object>) items));
                        } else {
                            fieldMap.put(fieldKey, new ArrayList<>());
                        }
                    }
                    case "boolean", "bool" -> fieldMap.put(fieldKey, coerceBoolean(fieldValue));
                    default -> fieldMap.put(fieldKey, fieldValue);
                }
            } else {
                fieldMap.put(fieldKey, fieldValue);
            }
        }
        return fieldMap;
    }

    private static Object coerceBoolean(Object fieldValue) {
        if (fieldValue instanceof String s) {
            return s.toLowerCase(Locale.ROOT).equals("true");
        }
        if (fieldValue instanceof Boolean b) {
            return b;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public Object convertArrayToObjectDataSet(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> result.put(k, convertArrayToObjectDataSet(v)));
            return result;
        }
        if (!(data instanceof List<?> list) || data instanceof MapSlice) {
            return data;
        }
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        for (Object field : list) {
            if (field instanceof Map<?, ?> fieldProps
                    && fieldProps.get("data_set_key") instanceof String fieldKey
                    && fieldProps.get("mapping_value") instanceof Map<?, ?> mappingValue
                    && mappingValue.containsKey("value")) {
                fieldMap.put(fieldKey, mappingValue.get("value"));
            }
        }
        return fieldMap;
    }

    @SuppressWarnings("unchecked")
    public List<Object> processArrayItems(List<Object> fields, Map<String, Object> items) {
        List<Object> arrayValues = new ArrayList<>();
        if (!(items.get("field") instanceof List<?> itemFields) || items.get("field") instanceof MapSlice) {
            return arrayValues;
        }
        Map<String, Object> combinedItem = new LinkedHashMap<>();
        for (Object field : itemFields) {
            if (!(field instanceof Map<?, ?> rawProps)) {
                continue;
            }
            Map<String, Object> fieldProps = (Map<String, Object>) rawProps;
            String fieldKey = fieldProps.get("key") instanceof String s ? s : null;
            boolean hasValue = fieldProps.containsKey("value");
            Object fieldValue = fieldProps.get("value");
            String fieldType = fieldProps.get("type") instanceof String s ? s : null;
            if (fieldKey == null || !hasValue) {
                continue;
            }
            if (fieldType != null) {
                switch (fieldType) {
                    case "object" -> {
                        if (fieldProps.get("fields") instanceof List<?> nestedFields
                                && !(fieldProps.get("fields") instanceof MapSlice)) {
                            Map<String, Object> wrapper = new LinkedHashMap<>();
                            wrapper.put("field", nestedFields);
                            List<Object> nested = processArrayItems(null, wrapper);
                            if (!nested.isEmpty()) {
                                combinedItem.put(fieldKey, nested.get(0));
                            }
                        } else {
                            combinedItem.put(fieldKey, fieldValue);
                        }
                    }
                    case "array" -> {
                        if (fieldProps.get("items") instanceof Map<?, ?> nestedItems) {
                            combinedItem.put(fieldKey, processArrayItems(
                                    (List<Object>) fieldProps.get("fields"), (Map<String, Object>) nestedItems));
                        } else {
                            combinedItem.put(fieldKey, new ArrayList<>());
                        }
                    }
                    case "boolean", "bool" -> combinedItem.put(fieldKey, coerceBoolean(fieldValue));
                    default -> combinedItem.put(fieldKey, fieldValue);
                }
            } else {
                combinedItem.put(fieldKey, fieldValue);
            }
        }
        arrayValues.add(combinedItem);
        return arrayValues;
    }

    // ------------------------------------------------------------------ selector resolution

    /** parseSelector: split off an {@code @updation} suffix. */
    private static String[] parseSelectorAt(String selector) {
        if (selector.contains("@")) {
            String[] parts = selector.split("@", -1);
            if (parts.length == 2) {
                return new String[] {parts[0], parts[1]};
            }
        }
        return new String[] {selector, ""};
    }

    public List<String> splitSelector(String selector) {
        String trimmed = selector.startsWith("$.") ? selector.substring(2) : selector;
        return List.of(trimmed.split("\\.", -1));
    }

    public Object resolveSelectors(String selector, String selectorId, String workflowExecutionUuid,
            int workflowExecutionsId, String parentWorkflowUuid, int parentExecutionWorkflowId)
            throws DtoErrorException {

        // A Go string parameter cannot be nil, so the reference implementation's only possible
        // behaviour for "no parent" is the empty string. A Java caller CAN pass null, and did:
        // the estate's cross-workflow selectors then died on a NullPointerException deep in the
        // alias path rather than resolving. Normalising here reproduces the only answer Go can
        // give, at the one place every entry passes through.
        selector = selector == null ? "" : selector;
        selectorId = selectorId == null ? "" : selectorId;
        workflowExecutionUuid = workflowExecutionUuid == null ? "" : workflowExecutionUuid;
        parentWorkflowUuid = parentWorkflowUuid == null ? "" : parentWorkflowUuid;

        if (selectorId.contains(LANE_SELECTOR)) {
            return resolveLaneSelector(selector, selectorId, workflowExecutionUuid,
                    workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
        }
        if (selectorId.contains(DEVICE_SELECTOR)) {
            return resolveDeviceSelector(selector, selectorId, workflowExecutionsId);
        }
        if (selector.startsWith("$." + WORKFLOW_ALIAS_PREFIX + "(")) {
            String[] aliasParsed = parseSelectorAt(selector);
            Object value = resolveWorkflowAliasSelector(aliasParsed[0], selectorId, workflowExecutionUuid,
                    workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
            if (value == null) {
                return null;
            }
            if (!aliasParsed[1].isEmpty()) {
                value = updateSelectorValue(selector, value, selectorId, aliasParsed[1],
                        workflowExecutionUuid, workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
            }
            return value;
        }

        List<String> selectorParts = splitSelector(selector);
        if (selectorParts.size() < 2) {
            throw new DtoErrorException("Invalid selector type");
        }

        if (selectorParts.get(0).equals(HELPER) && selectorParts.get(1).equals("regex")) {
            List<String> regexSelectorParts = splitNestedRegexSelector(selector);
            selectorId = resolveRegexNestedSelectors(selector, selectorId, workflowExecutionUuid,
                    workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
            RegexResult regex = processRegexInput(selectorId);
            if (regexSelectorParts.get(2).contains("ismatch")) {
                return regex.matched();
            } else if (regexSelectorParts.get(2).contains("getmatches")) {
                if (regexSelectorParts.size() > 3) {
                    List<Object> interfaceValues = regex.matches() == null
                            ? new ArrayList<>() : new ArrayList<>(regex.matches());
                    Object result = getValueByIndexNotation(regexSelectorParts.get(3), interfaceValues);
                    if (result != null) {
                        return result;
                    }
                }
                return regex.matches();
            }
        }

        if (selectorParts.get(0).equals(HELPER) && selectorParts.get(1).contains(REMOVESPACES)) {
            selectorId = resolveNestedHelperSelectors(selector, selectorId, workflowExecutionUuid,
                    workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
            try {
                return removeSpacesFunction(selectorParts, selectorId);
            } catch (GoErrorException e) {
                throw new DtoErrorException(e.getMessage());
            }
        }

        if (selectorParts.get(0).equals(HELPER) && selectorParts.get(1).contains(TO_UPPER_CASE)) {
            selectorId = resolveNestedHelperSelectors(selector, selectorId, workflowExecutionUuid,
                    workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
            try {
                return toUpperCaseFunction(selectorParts, selectorId);
            } catch (GoErrorException e) {
                throw new DtoErrorException(e.getMessage());
            }
        }

        String updation = "";
        String selectorUpdation = "";
        if (!selectorParts.get(0).equals(HELPER) && !selectorParts.get(1).contains(COUNT)) {
            String[] selParsed = parseSelectorAt(selector);
            selector = selParsed[0];
            selectorUpdation = selParsed[1];
            String[] idParsed = parseSelectorAt(selectorId);
            selectorId = idParsed[0];
            updation = idParsed[1];
        }

        NestedResolution nested = resolveNestedSelectors(selector, selectorId, workflowExecutionUuid,
                workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
        selectorId = nested.selectorId();
        Object nestedSelectorValue = nested.value();

        if (selectorParts.get(0).equals(HELPER) && selectorParts.get(1).contains(COUNT)) {
            try {
                return countHelperFunction(selectorId, nestedSelectorValue);
            } catch (GoErrorException e) {
                throw new DtoErrorException(e.getMessage());
            }
        }

        if (selectorParts.get(0).equals(HELPER) && selectorParts.get(1).contains(EXISTS)) {
            try {
                boolean exists = existsHelperFunction(nestedSelectorValue, selectorId,
                        workflowExecutionUuid, workflowExecutionsId);
                return exists ? "true" : "false";
            } catch (GoErrorException e) {
                throw new DtoErrorException(e.getMessage());
            }
        }

        List<String> parts = splitSelector(selectorId);
        if (parts.size() < 2) {
            throw new DtoErrorException("Invalid selector type");
        }

        Object value = null;
        int workflowId = 0;

        if (parts.get(0).equals(REFERENCE_DATA)) {
            Object refValue;
            try {
                refValue = executeReferenceData(selectorId);
            } catch (DtoErrorException e) {
                return "";
            }
            if (refValue == null || "".equals(refValue)) {
                return "";
            }
            if (refValue instanceof MapSlice) {
                value = refValue;
            } else {
                return "";
            }
        }

        if (parts.get(0).equals(HELPER)) {
            if (parts.get(1).equals(TIMESTAMP)) {
                return generateTimeStamp("");
            } else if (parts.get(1).startsWith(TIMESTAMP + "(") && parts.get(1).endsWith(")")) {
                String inner = parts.get(1).substring(TIMESTAMP.length() + 1, parts.get(1).length() - 1);
                return generateTimeStamp(inner);
            } else if (parts.get(1).contains(GETCDLDATA)) {
                return fetchCdlScanData(selectorParts, GoFmt.v(nestedSelectorValue), parts);
            }
        }

        if (!parts.get(0).equals(CONFIGURATION) && !parts.get(0).equals(REFERENCE_DATA)) {
            String workflowUuid = parts.get(0);
            if (!parentWorkflowUuid.isEmpty() && selectorParts.get(0).equals(WORKFLOW)) {
                workflowUuid = parentWorkflowUuid;
            }
            try {
                workflowId = provider.getPrimaryKeyByUuid("workflow", "workflow_id", "workflow_uuid", workflowUuid);
            } catch (GoErrorException keyErr) {
                throw new DtoErrorException(keyErr.getMessage());
            }
        }

        int workflowExecutionId;
        try {
            workflowExecutionId = provider.getPrimaryKeyByUuid("workflow_executions",
                    "workflow_execution_id", "workflow_execution_uuid", workflowExecutionUuid);
        } catch (GoErrorException ignored) {
            workflowExecutionId = 0; // Go discards this error position outright
        }
        if (workflowExecutionId == 0) {
            workflowExecutionId = workflowExecutionsId;
        }

        if (selectorParts.get(0).equals(PARENT_WORKFLOW) && selectorParts.get(1).equals(WORKFLOW)) {
            workflowExecutionId = parentExecutionWorkflowId;
            selectorParts = selectorParts.subList(1, selectorParts.size());
            parts = parts.subList(1, parts.size());
        } else if (!selectorParts.get(0).equals(WORKFLOW) && !parts.get(0).equals(CONFIGURATION)
                && !parts.get(0).equals(REFERENCE_DATA) && !selectorParts.get(0).equals(PARENT_WORKFLOW)) {
            workflowExecutionId = provider.getPreviousWorkflowExecutionId(workflowId, workflowExecutionId);
        }

        if (!parts.get(0).equals(REFERENCE_DATA)) {
            if (parts.size() <= 6 && parts.get(0).equals(CONFIGURATION)) {
                value = handleConfigurationSelector(parts);
            } else if (selectorParts.get(0).equals(WORKFLOW) && selectorParts.get(1).equals(PARENT)
                    && parts.get(2).equals(DATASET)) {
                value = deepDiveParentDatasets(workflowExecutionId, parts);
            } else if (parts.get(1).equals(NODE)) {
                value = handleNodeSelector(workflowId, parts, selectorParts, workflowExecutionId);
            } else if (parts.get(1).equals(DATASET)) {
                value = deepDiveDatasets(workflowExecutionId, parts);
            } else if (parts.size() == 3 && parts.get(1).equals(WORKFLOW_EXECUTION)) {
                value = provider.fetchWorkflowExecutionData(parts.get(2), workflowExecutionId);
            } else {
                return null;
            }
        }

        if (!updation.isEmpty()) {
            value = updateSelectorValue(selectorUpdation, value, selectorId, updation,
                    workflowExecutionUuid, workflowExecutionsId, parentWorkflowUuid, parentExecutionWorkflowId);
        }

        if (parts.get(1).equals(DATASET) && value instanceof Boolean b) {
            value = b ? "true" : "false";
        }

        if (value instanceof String s) {
            value = tryDecodeBase64(s);
        } else if (value instanceof Map || value instanceof List) {
            value = processMapValues(value);
        }
        return value;
    }

    private record NestedResolution(String selectorId, Object value) {
    }

    private static final Pattern NESTED_SELECTOR = Pattern.compile("\\(\\$\\.[^()]+\\)");

    private NestedResolution resolveNestedSelectors(String selector, String selectorId,
            String workflowExecutionUuid, int workflowExecutionId, String parentWorkflowUuid,
            int parentExecutionWorkflowId) {
        Matcher selMatcher = NESTED_SELECTOR.matcher(selector);
        Matcher idMatcher = NESTED_SELECTOR.matcher(selectorId);
        String selectorMatch = selMatcher.find() ? selMatcher.group() : null;
        String selectorIdMatch = idMatcher.find() ? idMatcher.group() : null;

        if (selectorMatch == null || selectorIdMatch == null) {
            String balancedSel = extractBalancedNestedSelector(selector);
            String balancedId = extractBalancedNestedSelector(selectorId);
            if (balancedSel.isEmpty() || balancedId.isEmpty()) {
                return new NestedResolution(selectorId, null);
            }
            selectorMatch = balancedSel;
            selectorIdMatch = balancedId;
        }

        String innerSelector = selectorMatch.substring(1, selectorMatch.length() - 1);
        String innerSelectorId = selectorIdMatch.substring(1, selectorIdMatch.length() - 1);
        Object value = null;
        try {
            value = resolveSelectors(innerSelector, innerSelectorId, workflowExecutionUuid,
                    workflowExecutionId, parentWorkflowUuid, parentExecutionWorkflowId);
        } catch (DtoErrorException ignored) {
            // Go logs and proceeds with the nil value
        }
        String replaced = replaceFirst(selectorId, selectorIdMatch, GoFmt.v(value));
        return new NestedResolution(replaced, value);
    }

    private static String replaceFirst(String s, String target, String replacement) {
        int idx = s.indexOf(target);
        if (idx < 0) {
            return s;
        }
        return s.substring(0, idx) + replacement + s.substring(idx + target.length());
    }

    private static String extractBalancedNestedSelector(String s) {
        int idx = s.indexOf("($.");
        if (idx == -1) {
            return "";
        }
        int depth = 0;
        for (int i = idx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return s.substring(idx, i + 1);
                }
            }
        }
        return "";
    }

    private static String[] extractBalancedHelperArgs(String s) {
        int idx = s.indexOf('(');
        if (idx == -1) {
            return new String[] {"", ""};
        }
        int depth = 0;
        for (int i = idx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return new String[] {s.substring(idx, i + 1), s.substring(idx + 1, i).strip()};
                }
            }
        }
        return new String[] {"", ""};
    }

    /** formatValue — how a resolved value re-enters a selector string. */
    private static String formatValue(Object value) {
        if (value instanceof MapSlice slice) {
            return GoJson.marshal(slice);
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object elem : list) {
                if (elem instanceof String s) {
                    parts.add(GoFmt.q(s));
                } else if (elem instanceof Map) {
                    parts.add(GoJson.marshal(elem));
                } else {
                    parts.add(GoFmt.q(GoFmt.v(elem)));
                }
            }
            return "[" + String.join(",", parts) + "]";
        }
        return GoFmt.v(value);
    }

    private static final Pattern HELPER_ARGS = Pattern.compile("\\(\\s*([^)]+)\\s*\\)");

    private String resolveNestedHelperSelectors(String selector, String selectorId,
            String workflowExecutionUuid, int workflowExecutionId, String parentWorkflowUuid,
            int parentExecutionWorkflowId) {
        String[] selMatch = null;
        String[] idMatch = null;

        boolean containsAlias = selector.contains(WORKFLOW_ALIAS_PREFIX + "(")
                || selector.contains(NODE_ALIAS_PREFIX + "(");

        if (!containsAlias) {
            Matcher m1 = HELPER_ARGS.matcher(selector);
            Matcher m2 = HELPER_ARGS.matcher(selectorId);
            if (m1.find()) {
                selMatch = new String[] {m1.group(0), m1.group(1)};
            }
            if (m2.find()) {
                idMatch = new String[] {m2.group(0), m2.group(1)};
            }
        }

        if (selMatch == null || idMatch == null) {
            String[] balancedSel = extractBalancedHelperArgs(selector);
            String[] balancedId = extractBalancedHelperArgs(selectorId);
            if (balancedSel[0].isEmpty() || balancedId[0].isEmpty()) {
                return selectorId;
            }
            selMatch = balancedSel;
            idMatch = balancedId;
        }

        String innerSelectorContent = selMatch[1];
        String fullMatch = idMatch[0];
        String innerContent = idMatch[1];

        String[] selectorParts = innerSelectorContent.split(",", 2);
        String firstSelectorArg = selectorParts[0].strip();
        String[] parts = innerContent.split(",", 2);
        String firstArg = parts[0].strip();

        String restParams = "";
        if (parts.length > 1 && selectorParts.length > 1) {
            restParams = parts[1].strip();
        }

        Object value = null;
        try {
            value = resolveSelectors(firstSelectorArg, firstArg, workflowExecutionUuid,
                    workflowExecutionId, parentWorkflowUuid, parentExecutionWorkflowId);
        } catch (DtoErrorException ignored) {
            // Go discards the error here
        }
        String formatted = formatValue(value);
        String replacement = restParams.isEmpty()
                ? "(" + formatted + ")" : "(" + formatted + ", " + restParams + ")";
        return replaceFirst(selectorId, fullMatch, replacement);
    }

    // ------------------------------------------------------------------ dataset deep dives

    private Object deepDiveParentDatasets(int workflowExecutionId, List<String> parts) throws DtoErrorException {
        if (parts.size() < 4) {
            throw new DtoErrorException("Invalid path: not enough parts");
        }
        return deepDiveFrom(workflowExecutionId, parts, 3);
    }

    private Object deepDiveDatasets(int workflowExecutionId, List<String> parts) throws DtoErrorException {
        if (parts.size() < 3) {
            throw new DtoErrorException("Invalid path: not enough parts");
        }
        return deepDiveFrom(workflowExecutionId, parts, 2);
    }

    private Object deepDiveFrom(int workflowExecutionId, List<String> parts, int keyIndex)
            throws DtoErrorException {
        KeyAndIndex first;
        try {
            first = extractKeyAndIndex(parts.get(keyIndex));
        } catch (GoErrorException e) {
            throw new DtoErrorException("Failed to Extract Key and Value");
        }

        Object response = provider.fetchDataSets(first.key(), workflowExecutionId);

        Object value = response;
        if (response instanceof String s) {
            Object parsed = GoJson.parse(s);
            if (parsed != GoJson.INVALID) {
                value = parsed;
            }
        }
        Object finalResponse = (value instanceof Map || (value instanceof List)) ? value : response;

        if (first.index() != -1) {
            try {
                finalResponse = navigateStructure(finalResponse, "", first.index());
            } catch (GoErrorException e) {
                throw new DtoErrorException(e.getMessage());
            }
        }

        for (int i = keyIndex + 1; i < parts.size(); i++) {
            KeyAndIndex ki;
            try {
                ki = extractKeyAndIndex(parts.get(i));
            } catch (GoErrorException e) {
                throw new DtoErrorException("Invalid path: "
                        + String.join(".", parts.subList(keyIndex + 1, parts.size())));
            }
            try {
                finalResponse = navigateStructure(finalResponse, ki.key(), ki.index());
            } catch (GoErrorException e) {
                throw new DtoErrorException(e.getMessage());
            }
        }
        return finalResponse;
    }

    static Object navigateStructure(Object value, String key, int index) throws GoErrorException {
        if (value instanceof Map<?, ?> map) {
            if (key.isEmpty()) {
                throw new GoErrorException("cannot use index on a map");
            }
            if (!map.containsKey(key)) {
                throw new GoErrorException("key not found: " + key);
            }
            Object val = map.get(key);
            if (index != -1) {
                if (val instanceof List<?> list && !(val instanceof MapSlice)) {
                    return list.size() > index ? list.get(index) : "";
                }
                return "";
            }
            return val;
        }
        if (value instanceof List<?> list && !(value instanceof MapSlice)) {
            if (index != -1) {
                if (index >= list.size()) {
                    throw new GoErrorException("index out of range: " + index);
                }
                return list.get(index);
            }
            if (!key.isEmpty()) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> mapItem && mapItem.containsKey(key)) {
                        return mapItem.get(key);
                    }
                }
                throw new GoErrorException("Key not found in array: " + key);
            }
            throw new GoErrorException("Cannot access array without an index");
        }
        throw new GoErrorException("Unexpected type: " + goTypeName(value));
    }

    /** Go's %T for the JSON value universe — appears inside navigateStructure errors. */
    private static String goTypeName(Object v) {
        if (v == null) {
            return "<nil>";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "bool";
        }
        if (v instanceof Double) {
            return "float64";
        }
        if (v instanceof Integer || v instanceof Long) {
            return "int";
        }
        if (v instanceof MapSlice) {
            return "[]map[string]interface {}";
        }
        if (v instanceof List) {
            return "[]interface {}";
        }
        if (v instanceof Map) {
            return "map[string]interface {}";
        }
        return v.getClass().getSimpleName();
    }

    public record KeyAndIndex(String key, int index) {
    }

    private static final Pattern KEY_AND_INDEX = Pattern.compile("^(.*?)\\[(\\d+)\\]$");
    private static final Pattern INDEX_ONLY = Pattern.compile("^\\[(\\d+)\\]$");

    public static KeyAndIndex extractKeyAndIndex(String input) throws GoErrorException {
        Matcher m = KEY_AND_INDEX.matcher(input);
        if (m.matches()) {
            Long idx = GoFmt.parseInt(m.group(2));
            if (idx == null) {
                throw new GoErrorException("failed to convert index to integer");
            }
            return new KeyAndIndex(m.group(1), idx.intValue());
        }
        Matcher mi = INDEX_ONLY.matcher(input);
        if (mi.matches()) {
            Long idx = GoFmt.parseInt(mi.group(1));
            if (idx == null) {
                throw new GoErrorException("failed to convert index to integer");
            }
            return new KeyAndIndex("", idx.intValue());
        }
        return new KeyAndIndex(input, -1);
    }

    // ------------------------------------------------------------------ configuration & node

    private Object handleConfigurationSelector(List<String> parts) {
        Map<String, Object> response;
        switch (parts.size()) {
            case 6 -> response = getResourceConfigurations(parts.get(4), "device", parts.get(5));
            case 5 -> response = getResourceConfigurations(parts.get(3), "lane", parts.get(4));
            case 4 -> response = getResourceConfigurations(parts.get(2), "area", parts.get(3));
            case 3 -> response = getResourceConfigurations(parts.get(1), "site", parts.get(2));
            default -> {
                return "";
            }
        }
        if (response.get("errors") == null) {
            return response.get("data");
        }
        return "";
    }

    private Object handleNodeSelector(int workflowId, List<String> parts, List<String> selectorParts,
            int workflowExecutionId) throws DtoErrorException {
        if (selectorParts.size() == 3 && parts.size() == 3) {
            return parts.get(2);
        }
        Map<String, Object> nodeExecution = provider.fetchNodeExecution(workflowId, parts.get(2), workflowExecutionId);
        String payload = nodeExecution != null && nodeExecution.get("ExecutionPayload") instanceof String s ? s : "";
        Object parsed = GoJson.parse(payload);
        if (parsed == GoJson.INVALID || !(parsed instanceof Map)) {
            return "";
        }
        return extractValue(parsed, parts.subList(4, parts.size()));
    }

    /**
     * The Go method returns a full {@code dto.APIResponse}; here it is the same shape as a
     * map with the Go JSON tag names, so the conformance corpus compares structurally.
     */
    public Map<String, Object> getResourceConfigurations(String resourceUuid, String resourceType,
            String configKey) {
        record TableRef(String table, String idColumn, String uuidColumn) {
        }
        TableRef ref = switch (resourceType.toLowerCase(Locale.ROOT)) {
            case "site" -> new TableRef("sites", "site_id", "site_uuid");
            case "area" -> new TableRef("areas", "area_id", "area_uuid");
            case "lane" -> new TableRef("lanes_and_portals", "lane_id", "lane_uuid");
            case "device" -> new TableRef("devices", "device_id", "device_uuid");
            default -> null;
        };
        if (ref == null) {
            return apiError("Invalid resource type",
                    List.of(errorEntry("Invalid resource type", CODE_NOT_FOUND)));
        }
        String mappedType = switch (resourceType.toLowerCase(Locale.ROOT)) {
            case "site" -> "SITE";
            case "area" -> "AREA";
            case "lane" -> "LANE";
            case "device" -> "DEVICE";
            default -> resourceType;
        };

        int resourceId;
        try {
            resourceId = provider.getPrimaryKeyByUuid(ref.table(), ref.idColumn(), ref.uuidColumn(), resourceUuid);
        } catch (GoErrorException e) {
            if (e.isNotFound()) {
                return apiError("Resource UUID not found",
                        List.of(errorEntry(e.getMessage(), CODE_NOT_FOUND)));
            }
            return apiError("Failed to retrieve resource ID from UUID",
                    List.of(errorEntry(e.getMessage(), CODE_INTERNAL)));
        }

        String configuration;
        try {
            configuration = provider.getResourceConfigurations(resourceId, mappedType, configKey);
        } catch (DtoErrorException e) {
            return apiError("Failed to retrieve resource configurations",
                    List.of(errorEntry(e.getMessage(), "")));
        }

        Object data = configuration != null ? configuration : "";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "Success");
        response.put("code", 0);
        response.put("message", "Resource configurations retrieved successfully");
        response.put("data", data);
        response.put("request_id", requestId);
        response.put("timestamp", "");
        return response;
    }

    private Map<String, Object> apiError(String message, List<Map<String, Object>> errors) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "Error");
        response.put("code", 0);
        response.put("message", message);
        response.put("errors", errors);
        response.put("request_id", requestId);
        response.put("timestamp", "");
        return response;
    }

    private static Map<String, Object> errorEntry(String message, String code) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("message", message);
        entry.put("code", code);
        return entry;
    }

    // ------------------------------------------------------------------ value plumbing

    public Object extractValue(Object data, List<String> path) {
        Object current = data;
        for (String segment : path) {
            int arrayIndex = 0;
            String key;
            boolean isArray = false;
            if (segment.contains("[") && segment.contains("]")) {
                String[] parts = segment.split("\\[", 2);
                key = parts[0];
                String idxStr = parts[1].endsWith("]")
                        ? parts[1].substring(0, parts[1].length() - 1) : parts[1];
                Long idx = GoFmt.parseInt(idxStr);
                if (idx == null) {
                    return "";
                }
                arrayIndex = idx.intValue();
                isArray = true;
            } else {
                key = segment;
            }

            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(key)) {
                    return "";
                }
                current = map.get(key);
                if (isArray) {
                    if (!(current instanceof List<?> array) || current instanceof MapSlice) {
                        return "";
                    }
                    current = arrayIndex >= array.size() ? null : array.get(arrayIndex);
                }
            } else if (current instanceof List<?> list && !(current instanceof MapSlice)) {
                if (!isArray) {
                    return "";
                }
                current = arrayIndex >= list.size() ? null : list.get(arrayIndex);
            } else {
                return "";
            }
        }
        return current;
    }

    public long generateTimeStamp(String part) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        return switch (part) {
            case "" -> clock.millis();
            case "y" -> now.getYear();
            case "M" -> now.getMonthValue();
            case "d" -> now.getDayOfMonth();
            case "h" -> now.getHour();
            case "m" -> now.getMinute();
            case "s" -> now.getSecond();
            default -> -1;
        };
    }

    @SuppressWarnings("unchecked")
    public Object updateSelectorValue(String selector, Object selectorValue, String selectorId,
            String updation, String workflowExecutionUuid, int workflowExecutionId,
            String parentWorkflowUuid, int parentExecutionWorkflowId) {
        if (selectorValue instanceof MapSlice slice) {
            if (updation.equals(COUNT)) {
                return slice.size();
            }
        } else if (selectorValue instanceof List<?> list) {
            if (updation.equals(COUNT)) {
                return list.size();
            }
        } else if (selectorValue instanceof Map) {
            if (updation.equals(COUNT)) {
                return 0;
            }
        } else if (selectorValue instanceof Integer i) {
            if (updation.equals(INCREMENT)) {
                return i + 1;
            }
            if (updation.equals(DECREMENT)) {
                return i - 1;
            }
        } else if (selectorValue instanceof Long l) {
            if (updation.equals(INCREMENT)) {
                return l + 1;
            }
            if (updation.equals(DECREMENT)) {
                return l - 1;
            }
        } else if (selectorValue instanceof Double d) {
            if (updation.equals(INCREMENT)) {
                return d + 1;
            }
            if (updation.equals(DECREMENT)) {
                return d - 1;
            }
        } else if (selectorValue instanceof String sv) {
            if (updation.contains(PARSE)) {
                Object parsed = GoJson.parse(sv);
                if (parsed == GoJson.INVALID) {
                    return selectorValue;
                }
                String[] updatedParseParts = updation.split("\\.", -1);
                if (updatedParseParts.length > 1) {
                    try {
                        return deepDiveData(List.of(updatedParseParts).subList(1, updatedParseParts.length), parsed);
                    } catch (GoErrorException e) {
                        return parsed;
                    }
                }
                return parsed;
            }
            switch (updation) {
                case INCREMENT -> {
                    Long val = GoFmt.parseInt(sv);
                    return val == null ? "" : val + 1;
                }
                case DECREMENT -> {
                    Long val = GoFmt.parseInt(sv);
                    return val == null ? "" : val - 1;
                }
                case REMOVESPACES -> {
                    return removeExtraSpaces(sv);
                }
                case COUNT -> {
                    return 0;
                }
                default -> {
                    // falls through to the shared prepend/push handling below
                }
            }
        }

        if (updation.startsWith("prepend(")) {
            String charToPrepend = updation.substring("prepend(".length());
            if (charToPrepend.endsWith(")")) {
                charToPrepend = charToPrepend.substring(0, charToPrepend.length() - 1);
            }
            if (selectorValue instanceof String sv) {
                return prependCharacterToContainerNumber(sv, charToPrepend);
            }
            if (selectorValue instanceof List<?> list && !(selectorValue instanceof MapSlice)) {
                List<Object> padded = null;
                for (Object item : list) {
                    if (item instanceof String strItem) {
                        if (padded == null) {
                            padded = new ArrayList<>();
                        }
                        padded.add(prependCharacterToContainerNumber(strItem, charToPrepend));
                    }
                }
                return padded;
            }
            return selectorValue;
        }

        if (updation.contains("push")) {
            String pushArg = updation.startsWith("push(") ? updation.substring("push(".length()) : updation;
            String firstPart = pushArg.split("\\)", -1)[0];
            String selArg = selector.startsWith("push(") ? selector.substring("push(".length()) : selector;
            String firstSelPart = selArg.split("\\)", -1)[0];

            Object resolvedValue;
            try {
                resolvedValue = resolveSelectors(firstSelPart, firstPart, workflowExecutionUuid,
                        workflowExecutionId, parentWorkflowUuid, parentExecutionWorkflowId);
            } catch (DtoErrorException e) {
                return selectorValue;
            }

            List<Object> result = new ArrayList<>();
            if (selectorValue instanceof List<?> list && !(selectorValue instanceof MapSlice)) {
                result.addAll(list);
            } else if (selectorValue != null && !"".equals(selectorValue)) {
                result.add(selectorValue);
            }
            if (resolvedValue instanceof List<?> rlist && !(resolvedValue instanceof MapSlice)) {
                result.addAll(rlist);
            } else {
                result.add(resolvedValue);
            }
            return result;
        }

        return "";
    }

    public Object handleString(Object value, String types) {
        switch (types) {
            case "string" -> {
                if (value instanceof Boolean b) {
                    return b ? "true" : "false";
                }
                if (value instanceof Integer || value instanceof Long) {
                    return value.toString();
                }
                if (value instanceof Double d) {
                    return GoFmt.f6(d);
                }
            }
            case "number" -> {
                if (value instanceof Double d) {
                    return (long) (double) d; // Go int(float64): truncation toward zero
                }
                if (value instanceof String s) {
                    Long parsed = GoFmt.parseInt(s);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
            case "float32", "float64" -> {
                if (value instanceof Double) {
                    return value;
                }
                if (value instanceof String s) {
                    Double parsed = GoFmt.parseFloat(s);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
            case "bool", "boolean" -> {
                if (value instanceof Boolean) {
                    return value;
                }
                if (value instanceof String s) {
                    Boolean parsed = GoFmt.parseBool(s);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
            default -> {
                // fall through: unknown types return the value unchanged
            }
        }
        return value;
    }

    public Object executeReferenceData(String selectorParts) throws DtoErrorException {
        try {
            String[] splitParts = selectorParts.split("&", -1);
            List<String> listName = splitSelector(splitParts[0]);
            String fieldsInner = splitParts[1].startsWith("fields(")
                    ? splitParts[1].substring("fields(".length()) : splitParts[1];
            String fields = fieldsInner.split("\\)", -1)[0];

            String query;
            if (splitParts.length > 2) {
                String conditionsInner = splitParts[2].startsWith("filter(")
                        ? splitParts[2].substring("filter(".length()) : splitParts[2];
                String where = convertConditions(conditionsInner.split("\\)", -1)[0]);
                query = "SELECT " + fields + " FROM " + listName.get(1) + " where " + where;
            } else {
                query = "SELECT " + fields + " FROM " + listName.get(1);
            }
            try {
                return provider.executeQuery(query);
            } catch (DtoErrorException e) {
                return "";
            }
        } catch (RuntimeException panic) {
            // Go recovers the panic, logs it, and the deferred recover leaves (nil, nil)
            return null;
        }
    }

    public String convertCondition(String condition) {
        // Iterated in a FIXED order where Go iterates a map randomly: with a single operator
        // per condition (the estate's shape) the result is identical; with several, Go
        // itself is nondeterministic and no order can be "the" faithful one.
        String[][] conditionMap = {
                {" eq ", " = "}, {" neq ", " != "}, {" lt ", " < "}, {" le ", " <= "},
                {" gt ", " > "}, {" ge ", " >= "}, {" like ", " LIKE "},
        };
        for (String[] entry : conditionMap) {
            if (condition.contains(entry[0])) {
                String[] parts = condition.split(Pattern.quote(entry[0]), -1);
                if (parts.length == 2) {
                    return parts[0] + entry[1] + "'" + parts[1] + "'";
                }
            }
        }
        return condition;
    }

    private static final Pattern AND_OR = Pattern.compile("\\s+(and|or)\\s+", Pattern.CASE_INSENSITIVE);

    public String convertConditions(String conditions) {
        List<String> parts = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        Matcher m = AND_OR.matcher(conditions);
        int last = 0;
        while (m.find()) {
            parts.add(conditions.substring(last, m.start()));
            operators.add(m.group());
            last = m.end();
        }
        parts.add(conditions.substring(last));

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                result.append(' ').append(operators.get(i - 1).strip().toUpperCase(Locale.ROOT)).append(' ');
            }
            result.append(convertCondition(parts.get(i)));
        }
        return result.toString();
    }

    public Object deepDiveData(List<String> parts, Object value) throws GoErrorException {
        Object extracted = value;
        for (String part : parts) {
            KeyAndIndex ki;
            try {
                ki = extractKeyAndIndex(part);
            } catch (GoErrorException e) {
                throw new GoErrorException("Invalid path: " + String.join(".", parts));
            }
            extracted = navigateStructure(extracted, ki.key(), ki.index());
        }
        return extracted;
    }

    // ------------------------------------------------------------------ CDL scan data

    public Object fetchCdlScanData(List<String> valueParts, String value, List<String> selectorIdParts) {
        Map<String, String> data = parseDlPdf417(value);
        if (data == null) {
            return "";
        }
        if (selectorIdParts.size() > 2) {
            String field = selectorIdParts.get(selectorIdParts.size() - 1);
            return switch (field) {
                case "last_name", "first_name", "middle_name", "license_number", "expiration_date",
                     "date_of_birth", "issue_date", "sex", "eye_color", "height", "address_street",
                     "address_city", "address_state", "address_zip", "hair_color",
                     "document_discriminator", "country", "under_18_until" ->
                        data.getOrDefault(field, "");
                case "organ_donor" -> ""; // Go returns the zero-valued bool field only when set — never set here
                case "jurisdiction_specific_data" -> "";
                default -> "";
            };
        }
        return "";
    }

    private static final Pattern NON_PRINTABLE = Pattern.compile("[^\\x20-\\x7E\\n\\r\\t]");
    private static final Pattern DIGITS_ONLY = Pattern.compile("[^0-9]");

    /** parseDLPDF417 — returns the populated field map, or null on the Go error paths. */
    private Map<String, String> parseDlPdf417(String rawData) {
        if (rawData.isEmpty()) {
            return null;
        }
        Map<String, String> data = new LinkedHashMap<>();
        String normalized = java.text.Normalizer.normalize(rawData, java.text.Normalizer.Form.NFC);
        String cleaned = NON_PRINTABLE.matcher(normalized).replaceAll("");
        cleaned = cleaned.replace("DAQ", "\nDAQ");
        String[] lines = cleaned.split("\n", -1);
        for (String rawLine : lines) {
            String segment = rawLine.strip();
            if (segment.length() < 3) {
                continue;
            }
            String fieldId = segment.substring(0, 3);
            String value = cleanSegmentValue(segment.substring(3), fieldId);
            switch (fieldId) {
                case "DCS" -> data.put("last_name", value.toUpperCase(Locale.ROOT));
                case "DAC" -> data.put("first_name", value.toUpperCase(Locale.ROOT));
                case "DAD" -> data.put("middle_name", value.toUpperCase(Locale.ROOT));
                case "DAQ" -> data.put("license_number", value);
                case "DBA" -> {
                    String date = parseDate(value);
                    if (date != null) {
                        data.put("expiration_date", date);
                    }
                }
                case "DAJ" -> data.put("address_state", value);
                case "DCF" -> data.put("document_discriminator", value);
                case "DCG" -> data.put("country", value);
                default -> {
                    // all other AAMVA fields are commented out in the Go parser
                }
            }
        }
        return data;
    }

    private static String cleanSegmentValue(String value, String fieldId) {
        value = value.strip();
        if (fieldId.equals("DBA") || fieldId.equals("DBB") || fieldId.equals("DBD")) {
            return DIGITS_ONLY.matcher(value).replaceAll("");
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char r = value.charAt(i);
            if (Character.isDigit(r) || Character.isLetter(r)
                    || r == ' ' || r == '/' || r == '-' || r == '.') {
                builder.append(r);
            }
        }
        return builder.toString();
    }

    /** parseDate with futureDate=true — the only live call site (DBA/expiration). */
    private static String parseDate(String value) {
        if (value.length() < 6) {
            return null;
        }
        String month = value.substring(0, 2);
        String day = value.substring(2, 4);
        String year = value.substring(4);
        switch (year.length()) {
            case 2 -> year = "20" + year;
            case 4 -> {
                // already a full year
            }
            default -> {
                return null;
            }
        }
        return month + "/" + day + "/" + year;
    }

    private static final Pattern CONTAINER_NUMBER = Pattern.compile("([A-Za-z]+)(\\d+)");

    static String prependCharacterToContainerNumber(String containerNumber, String charToPrepend) {
        Matcher m = CONTAINER_NUMBER.matcher(containerNumber);
        if (!m.find()) {
            return containerNumber;
        }
        String alphaPart = m.group(1);
        String numPart = m.group(2);
        if (numPart.length() < 10) {
            numPart = charToPrepend.repeat(10 - numPart.length()) + numPart;
        }
        return alphaPart + numPart;
    }

    // ------------------------------------------------------------------ string helpers

    public static String removeExtraSpaces(String value) {
        return value.replace(" ", "");
    }

    public static Object removeSpacesFunction(List<String> selectorParts, String selector)
            throws GoErrorException {
        return spacesHelper(selector, "removespaces(", REMOVESPACES, "No removespaces call found");
    }

    public static Object toUpperCaseFunction(List<String> selectorParts, String selector)
            throws GoErrorException {
        return spacesHelper(selector, "toUpper(", TO_UPPER_CASE, "No uppercase call found");
    }

    private static Object spacesHelper(String selector, String call, String updation, String missingMessage)
            throws GoErrorException {
        int start = selector.indexOf(call);
        if (start == -1) {
            throw new GoErrorException(missingMessage);
        }
        String argsStr = extractArguments(selector.substring(start + call.length()));
        if (argsStr.isEmpty()) {
            throw new GoErrorException("No arguments found");
        }
        List<String> args = splitArguments(argsStr);
        if (args.isEmpty()) {
            throw new GoErrorException("No arguments parsed");
        }
        return switch (args.size()) {
            case 1 -> processSingleArgument(args.get(0), updation);
            case 2 -> processTwoArguments(args.get(0), args.get(1), updation);
            default -> throw new GoErrorException("Unsupported number of arguments");
        };
    }

    static String extractArguments(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                count++;
            } else if (ch == ')') {
                if (count == 0) {
                    return s.substring(0, i);
                }
                count--;
            }
        }
        int last = s.lastIndexOf(')');
        if (last != -1) {
            return s.substring(0, last);
        }
        return s;
    }

    static List<String> splitArguments(String s) {
        s = s.strip();
        if (!s.contains(",")) {
            return List.of(s);
        }
        List<String> args = new ArrayList<>();
        int braces = 0;
        int brackets = 0;
        boolean inQuotes = false;
        boolean escapeNext = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escapeNext) {
                escapeNext = false;
                continue;
            }
            switch (ch) {
                case '\\' -> escapeNext = true;
                case '"' -> inQuotes = !inQuotes;
                case '{' -> braces += inQuotes ? 0 : 1;
                case '}' -> braces -= inQuotes ? 0 : 1;
                case '[' -> brackets += inQuotes ? 0 : 1;
                case ']' -> brackets -= inQuotes ? 0 : 1;
                case ',' -> {
                    if (!inQuotes && braces == 0 && brackets == 0) {
                        args.add(s.substring(start, i).strip());
                        start = i + 1;
                    }
                }
                default -> {
                    // any other character is part of the current argument
                }
            }
        }
        String lastArg = s.substring(start).strip();
        if (!lastArg.isEmpty()) {
            args.add(lastArg);
        }
        return args;
    }

    private static Object processSingleArgument(String arg, String updation) throws GoErrorException {
        arg = arg.strip();
        Object value = null;

        if (arg.startsWith("[") && !GoJson.isValid(arg)) {
            arg = quoteUnquotedStringsInArray(arg);
        }

        boolean quoted = arg.startsWith("\"") && arg.endsWith("\"");
        if (!quoted && !arg.contains("[") && !arg.contains("{")) {
            value = updation.equals(REMOVESPACES) ? removeSpaces(arg)
                    : updation.equals(TO_UPPER_CASE) ? toUpper(arg) : value;
        }

        Object parsed = GoJson.parse(arg);
        if (parsed instanceof String strVal) {
            value = updation.equals(REMOVESPACES) ? removeSpaces(strVal)
                    : updation.equals(TO_UPPER_CASE) ? toUpper(strVal) : value;
        }

        if (parsed instanceof List<?> list && list.stream().allMatch(e -> e instanceof String)) {
            List<String> strArray = new ArrayList<>();
            for (Object e : list) {
                String s = (String) e;
                strArray.add(updation.equals(REMOVESPACES) ? removeSpaces(s)
                        : updation.equals(TO_UPPER_CASE) ? toUpper(s) : s);
            }
            value = strArray;
        }

        if (parsed instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(e -> e instanceof Map)) {
            throw new GoErrorException("Array of objects provided but no keys to clean spaces");
        }

        return value;
    }

    private static final Pattern QUOTED_OR_COMPLEX = Pattern.compile("^\\s*(\".*\"|\\{.*}|\\[.*])\\s*$");

    static String quoteUnquotedStringsInArray(String s) {
        String content = s.strip();
        if (content.length() < 2) {
            return s;
        }
        content = content.substring(1, content.length() - 1);
        List<String> parts = splitCsv(content);
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.strip();
            if (!QUOTED_OR_COMPLEX.matcher(trimmed).matches()) {
                trimmed = "\"" + trimmed + "\"";
            }
            quoted.add(trimmed);
        }
        return "[" + String.join(",", quoted) + "]";
    }

    static List<String> splitCsv(String s) {
        List<String> result = new ArrayList<>();
        int braces = 0;
        int brackets = 0;
        boolean inQuotes = false;
        boolean escapeNext = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escapeNext) {
                escapeNext = false;
                continue;
            }
            switch (ch) {
                case '\\' -> escapeNext = true;
                case '"' -> inQuotes = !inQuotes;
                case '{' -> braces += inQuotes ? 0 : 1;
                case '}' -> braces -= inQuotes ? 0 : 1;
                case '[' -> brackets += inQuotes ? 0 : 1;
                case ']' -> brackets -= inQuotes ? 0 : 1;
                case ',' -> {
                    if (!inQuotes && braces == 0 && brackets == 0) {
                        result.add(s.substring(start, i));
                        start = i + 1;
                    }
                }
                default -> {
                    // part of the current field
                }
            }
        }
        result.add(s.substring(start));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object processTwoArguments(String arg1, String arg2, String updation)
            throws GoErrorException {
        String arg2Sanitized = arg2.replace("'", "\"");
        Object keysParsed = GoJson.parse(arg2Sanitized);
        if (!(keysParsed instanceof List<?> keysList)
                || !keysList.stream().allMatch(e -> e instanceof String)) {
            throw new GoErrorException("error parsing second argument as array of keys");
        }
        List<String> keys = (List<String>) keysList;

        MapSlice objArray;
        Object parsed = GoJson.parse(arg1);
        if (parsed instanceof List<?> list && list.stream().allMatch(e -> e instanceof Map)) {
            objArray = new MapSlice();
            list.forEach(e -> objArray.add((Map<String, Object>) e));
        } else {
            objArray = parseGoMapString(arg1);
        }

        for (Map<String, Object> obj : objArray) {
            for (String key : keys) {
                if (obj.get(key) instanceof String strVal) {
                    obj.put(key, removeSpaces(strVal));
                    if (updation.equals(REMOVESPACES)) {
                        obj.put(key, removeSpaces(strVal));
                    } else if (updation.equals(TO_UPPER_CASE)) {
                        obj.put(key, toUpper(strVal));
                    }
                }
            }
        }
        return objArray;
    }

    /** parseGoMapString — the Go fallback parser for "map[k:v ...]" debug strings. */
    static MapSlice parseGoMapString(String s) {
        s = s.strip();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("map[") && s.endsWith("]")) {
            s = s.substring(4, s.length() - 1);
        }
        String[] parts = s.strip().isEmpty() ? new String[0] : s.strip().split("\\s+");
        Map<String, Object> m = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentValParts = new ArrayList<>();
        boolean parsingValue = false;
        for (String part : parts) {
            if (part.contains(":") && !parsingValue) {
                String[] kv = part.split(":", 2);
                currentKey = kv[0];
                String valPart = kv[1];
                if (!valPart.isEmpty()) {
                    currentValParts = new ArrayList<>(List.of(valPart));
                    parsingValue = true;
                } else {
                    m.put(currentKey, "");
                    parsingValue = false;
                }
            } else if (parsingValue) {
                if (part.contains(":")) {
                    m.put(currentKey, String.join(" ", currentValParts));
                    String[] kv = part.split(":", 2);
                    currentKey = kv[0];
                    String valPart = kv[1];
                    if (!valPart.isEmpty()) {
                        currentValParts = new ArrayList<>(List.of(valPart));
                        parsingValue = true;
                    } else {
                        m.put(currentKey, "");
                        parsingValue = false;
                    }
                } else {
                    currentValParts.add(part);
                }
            }
        }
        if (parsingValue && currentKey != null) {
            m.put(currentKey, String.join(" ", currentValParts));
        }
        MapSlice slice = new MapSlice();
        slice.add(m);
        return slice;
    }

    private static String removeSpaces(String s) {
        return s.replace(" ", "");
    }

    private static String toUpper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    public static int countHelperFunction(Object selectorId, Object value) throws GoErrorException {
        if (value instanceof MapSlice slice) {
            return slice.size();
        }
        if (value instanceof List<?> list) {
            return list.size();
        }
        throw new GoErrorException("Incompatible data type to fetch count");
    }

    public boolean existsHelperFunction(Object value, Object selectorId, String workflowExecutionUuid,
            int workflowExecutionId) throws GoErrorException {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Integer i) {
            return i != 0;
        }
        if (value instanceof Long l) {
            return l != 0;
        }
        if (value instanceof Double d) {
            return d != 0;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        throw new GoErrorException("unsupported type: " + value.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------ regex helpers

    public record RegexResult(boolean matched, List<String> matches) {
    }

    public static RegexResult processRegexInput(String input) {
        int startIndex = input.indexOf('(');
        if (startIndex == -1) {
            return new RegexResult(false, null);
        }
        String functionCall = input.substring(0, startIndex);
        String parameters = input.substring(startIndex + 1, input.length() - 1);
        String[] functionParts = functionCall.split("\\.", -1);
        if (functionParts.length < 3) {
            return new RegexResult(false, null);
        }
        String functionality = functionParts[3]; // Go indexes [3] after a <3 check — 3-part input panics there too
        List<String> paramParts = splitParameters(parameters);
        if (paramParts.size() < 2) {
            return new RegexResult(false, null);
        }
        String regexPattern = trimQuotes(paramParts.get(0).strip());
        String value = trimQuotes(paramParts.get(1).strip());
        return switch (functionality) {
            case "ismatch" -> new RegexResult(isMatch(regexPattern, value), null);
            case "getmatches" -> new RegexResult(false, getMatches(regexPattern, value));
            default -> new RegexResult(false, null);
        };
    }

    private static String trimQuotes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '"' || s.charAt(start) == '\'')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '"' || s.charAt(end - 1) == '\'')) {
            end--;
        }
        return s.substring(start, end);
    }

    static List<String> splitParameters(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> {
                    inQuotes = !inQuotes;
                    current.append(ch);
                }
                case ',' -> {
                    if (inQuotes) {
                        current.append(ch);
                    } else {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                }
                default -> current.append(ch);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    static boolean isMatch(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** Go's regexp.MustCompile panics on a bad pattern — mirrored as a runtime exception. */
    static List<String> getMatches(String pattern, String value) {
        Matcher m = Pattern.compile(pattern).matcher(value);
        List<String> matches = null;
        while (m.find()) {
            if (matches == null) {
                matches = new ArrayList<>();
            }
            matches.add(m.group());
        }
        return matches; // null when nothing matched — Go returns a nil slice
    }

    public static Object getValueByIndexNotation(String indexNotation, List<Object> values) {
        if (indexNotation.length() < 3 || indexNotation.charAt(0) != '['
                || indexNotation.charAt(indexNotation.length() - 1) != ']') {
            return null;
        }
        Long index = GoFmt.parseInt(indexNotation.substring(1, indexNotation.length() - 1));
        if (index == null) {
            return null;
        }
        if (index < 0 || index >= values.size()) {
            return null;
        }
        return values.get(index.intValue());
    }

    public List<String> splitNestedRegexSelector(String selector) {
        if (selector.contains("$.helper.regex") && selector.contains("(")) {
            String baseSelector = selector;
            int idx = selector.indexOf('(');
            if (idx > 0) {
                baseSelector = selector.substring(0, idx);
            }
            String trimmed = baseSelector.startsWith("$.") ? baseSelector.substring(2) : baseSelector;
            String[] parts = trimmed.split("\\.", -1);
            if (parts.length >= 2 && parts[0].equals("helper") && parts[1].equals("regex")) {
                String functionWithParams = selector.substring(baseSelector.lastIndexOf('.') + 1);
                if (selector.contains("[") && selector.contains("]")) {
                    int indexStart = selector.lastIndexOf('[');
                    int indexEnd = selector.lastIndexOf(']');
                    if (indexStart > -1 && indexEnd > indexStart) {
                        return List.of("helper", "regex", functionWithParams,
                                selector.substring(indexStart, indexEnd + 1));
                    }
                }
                return List.of("helper", "regex", functionWithParams);
            }
        }
        String trimmed = selector.startsWith("$.") ? selector.substring(2) : selector;
        List<String> parts = new ArrayList<>(List.of(trimmed.split("\\.", -1)));
        if (selector.contains("[") && selector.contains("]")) {
            int indexStart = selector.lastIndexOf('[');
            int indexEnd = selector.lastIndexOf(']');
            if (indexStart > -1 && indexEnd > indexStart) {
                parts.add(selector.substring(indexStart, indexEnd + 1));
            }
        }
        return parts;
    }

    private String resolveRegexNestedSelectors(String selector, String selectorId,
            String workflowExecutionUuid, int workflowExecutionId, String parentWorkflowUuid,
            int parentExecutionWorkflowId) {
        int funcStart = selector.indexOf('(');
        int funcEnd = selector.lastIndexOf(')');
        if (funcStart < 0 || funcEnd < 0 || funcEnd <= funcStart) {
            return selectorId;
        }
        String paramsStr = selector.substring(funcStart + 1, funcEnd);
        List<String> nestedSelectors = extractNestedSelectors(paramsStr);

        int idFuncStart = selectorId.indexOf('(');
        int idFuncEnd = selectorId.lastIndexOf(')');
        if (idFuncStart < 0 || idFuncEnd < 0 || idFuncEnd <= idFuncStart) {
            return selectorId;
        }
        String selectorIdParams = selectorId.substring(idFuncStart + 1, idFuncEnd);
        List<String> nestedSelectorIds = extractNestedSelectors(selectorIdParams);

        List<String> resolvedParams = new ArrayList<>();
        for (int i = 0; i < nestedSelectors.size(); i++) {
            if (i < nestedSelectorIds.size()) {
                Object value;
                try {
                    value = resolveSelectors(nestedSelectors.get(i), nestedSelectorIds.get(i),
                            workflowExecutionUuid, workflowExecutionId, parentWorkflowUuid,
                            parentExecutionWorkflowId);
                } catch (DtoErrorException e) {
                    resolvedParams.add(null); // Go leaves the slot's zero value and continues
                    continue;
                }
                resolvedParams.add("\"" + GoFmt.v(value) + "\"");
            } else {
                resolvedParams.add(nestedSelectors.get(i));
            }
        }
        for (int i = 0; i < resolvedParams.size(); i++) {
            if (i < nestedSelectorIds.size() && resolvedParams.get(i) != null) {
                selectorId = replaceFirst(selectorId, nestedSelectorIds.get(i), resolvedParams.get(i));
            }
        }
        return selectorId;
    }

    private static final Pattern NESTED_IN_PARAMS =
            Pattern.compile("\\$\\.[a-zA-Z0-9._%+-]+(?:\\.[a-zA-Z0-9._%+-]+)*");

    static List<String> extractNestedSelectors(String params) {
        List<String> matches = new ArrayList<>();
        Matcher m = NESTED_IN_PARAMS.matcher(params);
        while (m.find()) {
            matches.add(m.group());
        }
        for (int i = 0; i < params.length() - 2; i++) {
            if (params.charAt(i) == '$' && params.charAt(i + 1) == '.') {
                String rest = params.substring(i);
                if (rest.startsWith("$.workflow_alias(") || rest.startsWith("$.node_alias(")) {
                    String result = extractFullAliasSelector(rest);
                    if (!result.isEmpty() && !matches.contains(result)) {
                        matches.add(result);
                    }
                }
            }
        }
        return matches;
    }

    static String extractFullAliasSelector(String s) {
        if (!s.startsWith("$.")) {
            return "";
        }
        int depth = 0;
        for (int i = 2; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                if (depth > 0) {
                    depth--;
                } else {
                    return s.substring(0, i);
                }
            } else if ((ch == ',' || ch == ' ') && depth == 0) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ lane / device / alias

    private static final java.util.Set<String> ALLOWED_DEVICE_COLUMNS =
            java.util.Set.of("device_url", "device_host", "ip_address", "port", "protocol");

    private record DeviceSelectorRef(String alias, String column) {
    }

    private static DeviceSelectorRef parseDeviceSelector(String s) {
        String prefix = "$.device(";
        int start = s.indexOf(prefix);
        if (start == -1) {
            return null;
        }
        int open = start + prefix.length();
        int rel = s.indexOf(')', open);
        if (rel == -1) {
            return null;
        }
        String alias = s.substring(open, rel);
        String rest = s.substring(rel + 1);
        if (!rest.startsWith(".") || rest.length() < 2) {
            return null;
        }
        String column = rest.substring(1);
        int dot = column.indexOf('.');
        if (dot != -1) {
            column = column.substring(0, dot);
        }
        if (alias.isEmpty() || column.isEmpty()) {
            return null;
        }
        return new DeviceSelectorRef(alias, column);
    }

    public Object resolveDeviceSelector(String selector, String selectorId, int workflowExecutionId) {
        DeviceSelectorRef ref = parseDeviceSelector(selectorId);
        if (ref == null) {
            ref = parseDeviceSelector(selector);
        }
        if (ref == null) {
            return "";
        }
        if (!ALLOWED_DEVICE_COLUMNS.contains(ref.column())) {
            return "";
        }
        Object laneCodeVal;
        try {
            laneCodeVal = provider.fetchWorkflowExecutionData("lane_code", workflowExecutionId);
        } catch (DtoErrorException e) {
            return "";
        }
        if (!(laneCodeVal instanceof String laneCode) || laneCode.isEmpty()) {
            return "";
        }
        try {
            return provider.fetchDeviceUrlForLane(laneCode, ref.alias(), ref.column());
        } catch (DtoErrorException e) {
            return "";
        }
    }

    private static String extractLaneInner(String s) {
        String lanePrefix = "$.lane(";
        String workflowExecPrefix = "$.workflow_execution(";
        if (!s.startsWith(lanePrefix)) {
            return null;
        }
        int start = lanePrefix.length();
        int openParens = 1;
        int end = -1;
        for (int i = start; i < s.length() && end == -1; i++) {
            char c = s.charAt(i);
            if (c == '(') {
                openParens++;
            } else if (c == ')') {
                openParens--;
                if (openParens == 0) {
                    end = i;
                }
            }
        }
        if (end == -1) {
            return null;
        }
        String laneInner = s.substring(start, end);
        int pos = laneInner.indexOf(workflowExecPrefix);
        if (pos != -1) {
            start = pos + workflowExecPrefix.length();
            openParens = 1;
            end = -1;
            for (int i = start; i < laneInner.length() && end == -1; i++) {
                char c = laneInner.charAt(i);
                if (c == '(') {
                    openParens++;
                } else if (c == ')') {
                    openParens--;
                    if (openParens == 0) {
                        end = i;
                    }
                }
            }
            if (end == -1) {
                return null;
            }
            return laneInner.substring(start, end);
        }
        return laneInner;
    }

    public Object resolveLaneSelector(String selector, String selectorId, String workflowExecutionUuid,
            int workflowExecutionId, String parentWorkflowUuid, int parentExecutionWorkflowId) {
        String workflowSelector = selector.startsWith("$.lane(") ? extractLaneInner(selector) : "";
        if (workflowSelector == null) {
            return "";
        }
        String workflowSelectorId = selectorId.startsWith("$.lane(") ? extractLaneInner(selectorId) : "";
        if (workflowSelectorId == null) {
            return "";
        }

        int lastDot = selectorId.lastIndexOf('.');
        if (lastDot == -1 || lastDot + 1 >= selectorId.length()) {
            return "";
        }
        String finalSelectorField = selectorId.substring(lastDot + 1);

        Object executionUuid;
        try {
            executionUuid = resolveSelectors(workflowSelector, workflowSelectorId, workflowExecutionUuid,
                    workflowExecutionId, parentWorkflowUuid, parentExecutionWorkflowId);
        } catch (DtoErrorException e) {
            return "";
        }
        if (!(executionUuid instanceof String executionUuidStr)) {
            return "";
        }

        int executionId;
        try {
            executionId = provider.getPrimaryKeyByUuid("workflow_executions",
                    "workflow_execution_id", "workflow_execution_uuid", executionUuidStr);
        } catch (GoErrorException e) {
            return "";
        }

        Object laneCode;
        try {
            laneCode = provider.fetchWorkflowExecutionData("lane_code", executionId);
        } catch (DtoErrorException e) {
            return "";
        }
        if (!(laneCode instanceof String laneCodeString)) {
            return "";
        }

        try {
            return provider.extractLanePropertiesByLaneCode(laneCodeString, finalSelectorField);
        } catch (DtoErrorException e) {
            return "";
        }
    }

    public Object resolveWorkflowAliasSelector(String selector, String selectorId,
            String workflowExecutionUuid, int workflowExecutionId, String parentWorkflowUuid,
            int parentExecutionWorkflowId) throws DtoErrorException {
        String[] aliasAndPath = extractAliasAndPath(selector, WORKFLOW_ALIAS_PREFIX);
        String workflowAliasName = aliasAndPath[0];
        String remainingPath = aliasAndPath[1];
        if (workflowAliasName.isEmpty()) {
            return "";
        }

        int currentWorkflowExecId;
        try {
            currentWorkflowExecId = provider.getPrimaryKeyByUuid("workflow_executions",
                    "workflow_execution_id", "workflow_execution_uuid", workflowExecutionUuid);
        } catch (GoErrorException ignored) {
            currentWorkflowExecId = 0;
        }
        if (currentWorkflowExecId == 0) {
            currentWorkflowExecId = workflowExecutionId;
        }

        SelectorDataProvider.WorkflowRef workflow;
        try {
            workflow = provider.getWorkflowIdByAliasName(workflowAliasName, currentWorkflowExecId);
        } catch (DtoErrorException e) {
            return "";
        }

        int resolvedWorkflowExecId;
        try {
            resolvedWorkflowExecId = provider.getPreviousWorkflowExecutionId(workflow.id(), currentWorkflowExecId);
        } catch (DtoErrorException e) {
            return "";
        }

        String[] remainingParts = remainingPath.split("\\.", -1);

        if (remainingParts[0].startsWith(NODE_ALIAS_PREFIX + "(")) {
            String nodeAliasName = extractParenContent(remainingParts[0], NODE_ALIAS_PREFIX);
            if (nodeAliasName.isEmpty()) {
                return "";
            }
            String nodeUuid;
            try {
                nodeUuid = provider.getNodeUuidByAliasName(workflow.id(), nodeAliasName);
            } catch (DtoErrorException e) {
                return "";
            }
            List<String> pathAfterNode = remainingParts.length > 1
                    ? List.of(remainingParts).subList(1, remainingParts.length) : List.of();
            List<String> parts = new ArrayList<>(List.of(workflow.uuid(), NODE, nodeUuid, "response"));
            parts.addAll(pathAfterNode);
            List<String> selectorParts = new ArrayList<>(List.of(WORKFLOW, NODE, nodeAliasName, "response"));
            selectorParts.addAll(pathAfterNode);
            return handleNodeSelector(workflow.id(), parts, selectorParts, resolvedWorkflowExecId);
        }

        if (remainingParts[0].equals(DATASET)) {
            List<String> parts = new ArrayList<>(List.of(workflow.uuid(), DATASET));
            parts.addAll(List.of(remainingParts).subList(1, remainingParts.length));
            return deepDiveDatasets(resolvedWorkflowExecId, parts);
        }

        if (remainingParts[0].equals(WORKFLOW_EXECUTION) && remainingParts.length >= 2) {
            return provider.fetchWorkflowExecutionData(remainingParts[1], resolvedWorkflowExecId);
        }

        return "";
    }

    private static String[] extractAliasAndPath(String selector, String prefix) {
        int start = selector.indexOf("$." + prefix + "(");
        if (start == -1) {
            return new String[] {"", ""};
        }
        int parenStart = start + ("$." + prefix + "(").length();
        int parenEnd = selector.indexOf(')', parenStart);
        if (parenEnd == -1) {
            return new String[] {"", ""};
        }
        String aliasName = selector.substring(parenStart, parenEnd);
        String remaining = "";
        if (parenEnd + 1 < selector.length() && selector.charAt(parenEnd + 1) == '.') {
            remaining = selector.substring(parenEnd + 2);
        }
        return new String[] {aliasName, remaining};
    }

    private static String extractParenContent(String s, String prefix) {
        int start = s.indexOf(prefix + "(");
        if (start == -1) {
            return "";
        }
        int contentStart = start + (prefix + "(").length();
        int end = s.indexOf(')', contentStart);
        if (end == -1) {
            return "";
        }
        return s.substring(contentStart, end);
    }

    // ------------------------------------------------------------------ base64 auto-decode

    static boolean isBase64(String s) {
        if (s.length() % 4 != 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (byte b : decoded) {
            int cp = b & 0xFF; // Go converts each byte to a rune — Latin-1 code points
            boolean printable = (cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA1 && cp <= 0xFF && cp != 0xAD);
            boolean space = cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D
                    || cp == 0x20 || cp == 0x85 || cp == 0xA0;
            if (!printable && !space) {
                return false;
            }
        }
        return s.endsWith("=");
    }

    static String tryDecodeBase64(String s) {
        if (!isBase64(s)) {
            return s;
        }
        try {
            return new String(Base64.getDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    static Object processMapValues(Object data) {
        if (data instanceof String s) {
            return tryDecodeBase64(s);
        }
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> typed = (Map<String, Object>) map;
            for (Map.Entry<String, Object> e : new ArrayList<>(typed.entrySet())) {
                typed.put(e.getKey(), processMapValues(e.getValue()));
            }
            return typed;
        }
        if (data instanceof MapSlice slice) {
            for (Map<String, Object> item : slice) {
                processMapValues(item);
            }
            return slice;
        }
        if (data instanceof List<?> list) {
            List<Object> typed = (List<Object>) list;
            for (int i = 0; i < typed.size(); i++) {
                typed.set(i, processMapValues(typed.get(i)));
            }
            return typed;
        }
        return data;
    }
}
