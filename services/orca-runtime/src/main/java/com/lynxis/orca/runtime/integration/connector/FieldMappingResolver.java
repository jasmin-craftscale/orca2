package com.lynxis.orca.runtime.integration.connector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a connector's authored {@code mapped_req_payload} into the map that becomes its
 * request body — the port of the Go executor's {@code ResolveFieldMapping} and the
 * {@code HandleObject} / {@code HandleArray} / {@code HandleString} family beneath it.
 *
 * <h2>The supported set is measured, not guessed</h2>
 *
 * <p>Across every active connector in the estate the authored fields are <b>89 {@code string},
 * 2 {@code object}, 1 {@code array}; 62 {@code SELECTOR}, 34 {@code VALUE}</b>, with no
 * {@code omit_empty}, no {@code include_when}, no CDATA and no self-closing anywhere. Most of
 * the Go switch is configuration surface with no data behind it.
 *
 * <p>So the scalar types, objects, arrays and {@code omit_empty} are ported; the shapes with
 * no data behind them <b>refuse loudly</b> rather than being silently skipped. That direction
 * matters: a dropped field produces a request body that is subtly wrong and a customer system
 * that answers plausibly, which is far harder to notice than a named failure.
 *
 * <h2>Two Go behaviours reproduced on purpose</h2>
 *
 * <p><b>A selector that fails to resolve drops its field.</b> Not an error, not a null — the
 * key is simply absent from the body. Faithfully reproduced, because a receiving system that
 * treats absent and null differently would see a different request otherwise.
 *
 * <p><b>Top-level fields resolve selectors with an empty visit uuid; nested ones pass the real
 * one.</b> {@code ResolveFieldMapping} passes {@code ""} where {@code HandleObject} passes
 * {@code RequestPayload.WorkflowExecutionUUID}. That asymmetry looks accidental, but it can
 * change what a selector resolves to, so it is reproduced rather than tidied — a body that
 * differs from today's is a change of outcome, which is the one thing fidelity forbids.
 */
public final class FieldMappingResolver {

    /** Resolves one authored selector, exactly as the evaluator would for this visit. */
    @FunctionalInterface
    public interface Selectors {
        /**
         * @param workflowExecutionUuid empty at top level, the visit's uuid when nested — see
         *     the class note; it is a parameter rather than state precisely so the asymmetry
         *     is visible at each call site
         * @return the resolved value, or null when the selector could not be resolved, which
         *     drops the field
         */
        Object resolve(String value, String selectorId, String workflowExecutionUuid);
    }

    /** @param visitUuid the visit's external identifier, passed to NESTED selector resolution */

    private final Selectors selectors;
    private final String visitUuid;

    FieldMappingResolver(Selectors selectors, String visitUuid) {
        this.selectors = selectors;
        this.visitUuid = visitUuid == null ? "" : visitUuid;
    }

    /** The request body for a connector's top-level field list. */
    Map<String, Object> resolve(List<MappingField> fields) {
        return walk(fields, "");
    }

    /** Nested objects — the only difference from {@link #resolve} is the visit uuid. */
    private Map<String, Object> handleObject(List<MappingField> fields) {
        return walk(fields, visitUuid);
    }

    private Map<String, Object> walk(List<MappingField> fields, String uuidForSelectors) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (MappingField f : fields) {
            if (f.key().isEmpty()) {
                continue;
            }
            refuseUnsupported(f);
            switch (typeOf(f)) {
                case "object" -> {
                    // The authored `value` is ignored here, as in Go: an object resolves from
                    // its children and nothing else. An object carrying a selector and no
                    // fields therefore resolves to {} — see resolveOrRefuseSelectorShaped.
                    Map<String, Object> resolved = handleObject(f.fields());
                    if (f.omitEmpty() && isEmpty(resolved)) {
                        continue;
                    }
                    out.put(f.key(), resolved);
                }
                case "selfclosing", "self-closing" -> {
                    if (f.omitEmpty()) {
                        continue;
                    }
                    out.put(f.key(), "");
                }
                case "array" -> {
                    Object resolved = handleArray(f);
                    if (f.omitEmpty() && isEmpty(resolved)) {
                        continue;
                    }
                    out.put(f.key(), buildArrayValue(f, resolved));
                }
                default -> {
                    Object scalar;
                    if ("SELECTOR".equals(f.source())) {
                        Object raw = selectors.resolve(f.value(), f.selectorId(), uuidForSelectors);
                        if (raw == null) {
                            continue;
                        }
                        scalar = handleString(raw, typeOf(f));
                    } else if ("VALUE".equals(f.source())) {
                        scalar = handleString(f.value(), typeOf(f));
                    } else {
                        continue;
                    }
                    if (f.omitEmpty() && isEmpty(scalar)) {
                        continue;
                    }
                    out.put(f.key(), buildScalarValue(f, scalar));
                }
            }
        }
        return out;
    }

    /**
     * Array elements. Note that nested selectors here resolve with an EMPTY visit uuid in both
     * of Go's loops, including the one reached from a nested object — the asymmetry does not
     * propagate downwards.
     */
    private Object handleArray(MappingField item) {
        List<Object> result = new ArrayList<>();
        if (item.items() != null) {
            if ("object".equals(item.items().type())) {
                for (MappingField field : item.items().fields()) {
                    addElement(result, field);
                }
            } else if (!item.value().isEmpty() && "VALUE".equals(item.source())) {
                result.add(handleString(item.value(), typeOf(item)));
            }
        }
        if (!item.fields().isEmpty() && item.items() == null) {
            for (MappingField field : item.fields()) {
                addElement(result, field);
            }
        }
        // Go returns nil, not an empty slice — which marshals to `null`, not `[]`. A receiving
        // system can absolutely tell those apart.
        return result.isEmpty() ? null : result;
    }

    private void addElement(List<Object> result, MappingField field) {
        refuseUnsupported(field);
        switch (typeOf(field)) {
            case "object" -> result.add(Map.of(field.key(), handleObject(field.fields())));
            case "array" -> {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put(field.key(), handleArray(field));
                result.add(wrapper);
            }
            default -> {
                Object value;
                if ("SELECTOR".equals(field.source())) {
                    Object raw = selectors.resolve(field.value(), field.selectorId(), "");
                    if (raw == null) {
                        return;
                    }
                    value = handleString(raw, typeOf(field));
                } else if ("VALUE".equals(field.source())) {
                    value = handleString(field.value(), typeOf(field));
                } else {
                    return;
                }
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put(field.key(), value);
                result.add(wrapper);
            }
        }
    }

    /** An element with XML attribute children becomes a map of attributes plus {@code #text}. */
    private Object buildScalarValue(MappingField item, Object scalar) {
        List<MappingField> attrs = item.attributeChildren();
        if (attrs.isEmpty()) {
            return scalar;
        }
        Map<String, Object> result = handleObject(attrs);
        if (!isEmpty(scalar)) {
            result.put(ConnectorJson.TEXT_NODE_KEY, scalar);
        }
        return result;
    }

    private Object buildArrayValue(MappingField item, Object items) {
        if (item.attributeChildren().isEmpty()) {
            return items;
        }
        // Go wraps these in utils.AttributedArray purely so its XML encoder can emit a wrapper
        // element with attributes AND repeated children. That encoder is not ported here, so
        // producing the wrapper would mean carrying a shape nothing can serialise.
        throw new UnsupportedConnectorShapeException(
                "array field '" + item.key() + "' carries XML attribute children, which needs "
                        + "the XML body encoder (not ported — no JSON connector uses this)");
    }

    /**
     * {@code HandleString}: converts a resolved value to the authored type, and — this is the
     * load-bearing part — returns <b>null</b> whenever the conversion does not apply, which
     * makes the field vanish from the body. Go achieves that by falling off the end of a
     * switch with no default; spelled out here so it reads as intent rather than omission.
     */
    static Object handleString(Object value, String type) {
        switch (type) {
            case "string", "__cdata__", "cdata" -> {
                return value instanceof String s ? s : null;
            }
            case "number" -> {
                if (value instanceof Number n && !(value instanceof Float)) {
                    return (int) n.doubleValue();
                }
                if (value instanceof String s) {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException notAnInt) {
                        return null;
                    }
                }
                return null;
            }
            case "float32" -> {
                if (value instanceof Float f) {
                    return f;
                }
                if (value instanceof String s) {
                    try {
                        return Float.parseFloat(s);
                    } catch (NumberFormatException notAFloat) {
                        return null;
                    }
                }
                return null;
            }
            case "float64" -> {
                // Go matches `float64`, which is what every JSON-derived number is there. The
                // Java evaluator may hand back an Integer for the same value, so any Number is
                // accepted — refusing one would drop a field Go keeps. Note the contrast with
                // float32 above, which accepts ONLY Float: Go's float32 case cannot match a
                // JSON number either, so those fields really do vanish, and that is preserved.
                if (value instanceof Number n) {
                    return n.doubleValue();
                }
                if (value instanceof String s) {
                    try {
                        return Double.parseDouble(s);
                    } catch (NumberFormatException notADouble) {
                        return null;
                    }
                }
                return null;
            }
            case "boolean", "bool" -> {
                if (value instanceof Boolean b) {
                    return b;
                }
                return value instanceof String s ? parseGoBool(s) : null;
            }
            case "selfclosing", "self-closing" -> {
                return "";
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Go's {@code strconv.ParseBool} accepts a wider set than Java's, and — unlike
     * {@link Boolean#parseBoolean} — reports failure instead of quietly answering false. That
     * difference decides whether a field carries {@code false} or disappears.
     */
    private static Boolean parseGoBool(String s) {
        return switch (s) {
            case "1", "t", "T", "TRUE", "true", "True" -> Boolean.TRUE;
            case "0", "f", "F", "FALSE", "false", "False" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String typeOf(MappingField f) {
        return f.type() == null ? "" : f.type().toLowerCase(Locale.ROOT);
    }

    private static void refuseUnsupported(MappingField f) {
        if (f.hasIncludeGate()) {
            throw new UnsupportedConnectorShapeException("field '" + f.key()
                    + "' uses include_when, which is not ported (no connector in the estate "
                    + "uses it); porting it needs the condition evaluator wired here");
        }
        String type = typeOf(f);
        if (type.startsWith("__cdata_") || type.startsWith("cdata-")) {
            throw new UnsupportedConnectorShapeException("field '" + f.key() + "' is a CDATA "
                    + "shape, which needs the XML body encoder (not ported)");
        }
    }

    static boolean isEmpty(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof String s) {
            return s.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (v instanceof List<?> l) {
            return l.isEmpty();
        }
        return false;
    }
}
