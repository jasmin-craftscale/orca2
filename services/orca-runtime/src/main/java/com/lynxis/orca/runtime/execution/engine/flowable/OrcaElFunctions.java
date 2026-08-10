package com.lynxis.orca.runtime.execution.engine.flowable;

import com.lynxis.orca.runtime.execution.selector.OrcaComparisons;
import java.lang.reflect.Method;
import java.util.List;
import org.flowable.common.engine.impl.el.AbstractFlowableFunctionDelegate;
import org.flowable.common.engine.api.delegate.FlowableFunctionDelegate;

/**
 * Registers {@code orca:cmp} and {@code orca:grp} with the engine's expression manager, so a
 * compiled ORCA condition evaluates with ORCA's own coercion rules rather than JUEL's
 * (semantics live in {@link OrcaComparisons}, the ported selector comparisons — this class is only the
 * Flowable binding).
 *
 * <p>Registration is asserted at startup by {@link FlowableStartupGuard}: the shadow's
 * hardest-to-see failure was an engine that booted happily <em>without</em> these functions
 * and then killed every PLT decision at first evaluation.
 */
public final class OrcaElFunctions {

    private OrcaElFunctions() {
    }

    /** Every delegate the engine must register — the guard probes exactly this list. */
    public static List<FlowableFunctionDelegate> all() {
        return List.of(new Cmp(), new Grp());
    }

    /** {@code orca:cmp(operator, left, right)} — the string-left branch of evaluateChildCondition. */
    public static boolean cmp(Object op, Object left, Object right) {
        if (op == null || left == null || right == null) {
            return false;
        }
        return OrcaComparisons.compare(String.valueOf(op), String.valueOf(left), String.valueOf(right));
    }

    /** {@code orca:grp(expr)} — an identity marker that keeps group boundaries recoverable. */
    public static boolean grp(Object v) {
        return Boolean.TRUE.equals(v);
    }

    public static final class Cmp extends AbstractFlowableFunctionDelegate {
        @Override
        public String prefix() {
            return "orca";
        }

        @Override
        public String localName() {
            return "cmp";
        }

        @Override
        public Class<?> functionClass() {
            return OrcaElFunctions.class;
        }

        @Override
        public Method functionMethod() {
            return getThreeObjectParameterMethod();
        }
    }

    public static final class Grp extends AbstractFlowableFunctionDelegate {
        @Override
        public String prefix() {
            return "orca";
        }

        @Override
        public String localName() {
            return "grp";
        }

        @Override
        public Class<?> functionClass() {
            return OrcaElFunctions.class;
        }

        @Override
        public Method functionMethod() {
            return getSingleObjectParameterMethod();
        }
    }
}
