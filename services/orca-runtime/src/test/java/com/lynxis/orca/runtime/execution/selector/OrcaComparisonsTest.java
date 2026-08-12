package com.lynxis.orca.runtime.execution.selector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Seed tests for the verbatim port — the extracted fixture suite (333 cases, run against
 * the Go evaluator first) supersedes these as the spec. Each case here is one the shadow
 * run proved matters on PLT traffic.
 */
class OrcaComparisonsTest {

    @Test
    void numericComparisonBeatsLexicographic() {
        // JUEL would say 'zzz' > '38000' is true; ORCA parses numbers and says false.
        assertThat(OrcaComparisons.compare(OrcaComparisons.GT, "zzz", "38000")).isFalse();
        assertThat(OrcaComparisons.compare(OrcaComparisons.GT, "39000", "38000")).isTrue();
    }

    @Test
    void equalityIsNumericWhenBothSidesParse() {
        assertThat(OrcaComparisons.compare(OrcaComparisons.EQ, "1", "1.0")).isTrue();
        assertThat(OrcaComparisons.compare(OrcaComparisons.NE, "1", "1.0")).isFalse();
        assertThat(OrcaComparisons.compare(OrcaComparisons.EQ, "abc", "abc")).isTrue();
    }

    @Test
    void goParseFloatQuirksAreMirrored() {
        assertThat(OrcaComparisons.num(" 1")).isNull();   // Go does not trim
        assertThat(OrcaComparisons.num("1.0f")).isNull(); // Java-only suffix
        assertThat(OrcaComparisons.num("1.0d")).isNull();
        // Known divergence from Go strconv (which parses "Inf"): the proven shadow
        // implementation returns null here; the extracted fixture suite is the arbiter.
        assertThat(OrcaComparisons.num("Inf")).isNull();
        assertThat(OrcaComparisons.num("")).isNull();
    }

    @Test
    void stringOperatorsStayStringOperators() {
        assertThat(OrcaComparisons.compare(OrcaComparisons.STARTS, "MSKU123", "MSKU")).isTrue();
        assertThat(OrcaComparisons.compare(OrcaComparisons.ENDS, "MSKU123", "123")).isTrue();
        assertThat(OrcaComparisons.compare(OrcaComparisons.CONTAINS, "MSKU123", "KU1")).isTrue();
    }

    @Test
    void unknownOperatorsAreFalseNotErrors() {
        assertThat(OrcaComparisons.compare("resembles", "a", "a")).isFalse();
    }
}
