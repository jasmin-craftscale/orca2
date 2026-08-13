package com.lynxis.orca.runtime.execution.selector;

/**
 * The string-left comparison branch of the Go executor's {@code evaluateChildCondition} —
 * what compiled selector conditions produce. Ported verbatim from the shadow rig's proven
 * implementation (live PLT parity, 7 Aug 2026); the extracted fixture suite is its spec.
 *
 * <p>The semantics are deliberately Go's, not Java's or JUEL's: plain JUEL compares two
 * Strings lexicographically, so {@code 'zzz' > '38000'} is true, while ORCA requires the
 * numeric conversion and yields false. Every weight and confidence threshold in the PLT
 * workflows depends on this difference.
 */
public final class OrcaComparisons {

    public static final String EQ = "is equal to";
    public static final String NE = "is not equal to";
    public static final String GT = "is greater than";
    public static final String GE = "is greater than or equal to";
    public static final String LT = "is lesser than";
    public static final String LE = "is lesser than or equal to";
    public static final String STARTS = "starts with";
    public static final String ENDS = "ends with";
    public static final String CONTAINS = "contains";

    private OrcaComparisons() {
    }

    /** @return the ORCA-coerced comparison; {@code false} for unknown operators or unparseable numbers. */
    public static boolean compare(String op, String left, String right) {
        switch (op) {
            case GT, GE, LT, LE -> {
                Double l = num(left);
                Double r = num(right);
                if (l == null || r == null) {
                    return false;
                }
                return switch (op) {
                    case GT -> l > r;
                    case GE -> l >= r;
                    case LT -> l < r;
                    default -> l <= r;
                };
            }
            case EQ, NE -> {
                Double l = num(left);
                Double r = num(right);
                if (l != null && r != null) {
                    return op.equals(EQ) ? l.doubleValue() == r.doubleValue() : l.doubleValue() != r.doubleValue();
                }
                return op.equals(EQ) ? left.equals(right) : !left.equals(right);
            }
            case STARTS -> {
                return left.startsWith(right);
            }
            case ENDS -> {
                return left.endsWith(right);
            }
            case CONTAINS -> {
                return left.contains(right);
            }
            default -> {
                return false; // Unknown string operator: Go's default branch
            }
        }
    }

    /**
     * Mirrors Go strconv.ParseFloat where it matters on real data: no trailing f/d suffix,
     * no leading/trailing space. Known divergence, deliberately kept until the fixture
     * suite rules on it: Go parses {@code "Inf"}, {@code Double.valueOf} wants
     * {@code "Infinity"} — so {@code "Inf"} is null here (as it was in the shadow that
     * ran parity on live PLT traffic).
     */
    public static Double num(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (!t.equals(s)) {
            return null; // Go does not trim
        }
        char last = t.charAt(t.length() - 1);
        if (last == 'f' || last == 'F' || last == 'd' || last == 'D') {
            // Java accepts 1.0f / 1.0d; Go does not
            return null;
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
