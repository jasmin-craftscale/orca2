package com.lynxis.orca.runtime.execution.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiler's own BPMN model — deliberately NOT Flowable's {@code BpmnModel}. The
 * canonical bytes (T2's byte-identity gate) must not depend on a library's serializer
 * ordering, and the compiler must stay engine-free. Elements serialize in insertion order;
 * the emitter inserts in authored order, which is what makes compilation deterministic.
 */
final class BpmnIr {

    sealed interface Element permits StartEvent, EndEvent, PassThroughTask, ServiceTask,
            ReceiveTask, UserTask, ExclusiveGateway, CallActivity, TimerCatchEvent, SequenceFlow {
        String id();
    }

    record StartEvent(String id, String name) implements Element {
    }

    record EndEvent(String id, String name) implements Element {
    }

    /**
     * A TERMINATOR with outgoing links: closes its work-item scope and continues the visit.
     * Serializes as a plain BPMN {@code task} — auto-completing, no delegate. The recorder
     * maps activityType {@code task} back to TERMINATOR: this compiler emits plain tasks for
     * exactly this construct and nothing else.
     */
    record PassThroughTask(String id, String name) implements Element {
    }

    /** An effect: delegateExpression, plus the orca: classification attributes (I7). */
    record ServiceTask(String id, String name, String delegateExpression,
            Map<String, String> orcaAttributes) implements Element {
    }

    /** A wait: the token parks here until the real world reports in (D3's other half). */
    record ReceiveTask(String id, String name, Map<String, String> orcaAttributes) implements Element {
    }

    record UserTask(String id, String name, String candidateGroup) implements Element {
    }

    record ExclusiveGateway(String id, String name, String defaultFlowId) implements Element {
    }

    record CallActivity(String id, String name, String calledElement) implements Element {
    }

    record TimerCatchEvent(String id, String name, String isoDuration) implements Element {
    }

    /**
     * @param fieldSidecar JUEL id → selector path, the {@code orca:field} elements (I8) —
     *     empty for unconditional flows
     */
    record SequenceFlow(String id, String from, String to, String condition,
            List<Map.Entry<String, String>> fieldSidecar) implements Element {
    }

    private final String processKey;
    private final String processName;
    private final Map<String, Element> elements = new LinkedHashMap<>();

    BpmnIr(String processKey, String processName) {
        this.processKey = processKey;
        this.processName = processName;
    }

    void add(Element element) {
        if (elements.putIfAbsent(element.id(), element) != null) {
            throw new CompileException(CompileException.Invariant.I5_NCNAME_IDS,
                    "duplicate element id " + element.id());
        }
    }

    /** Replace an element in place (gateway default-flow backpatching), order preserved. */
    void replace(Element element) {
        if (!elements.containsKey(element.id())) {
            throw new IllegalStateException("no element " + element.id() + " to replace");
        }
        elements.put(element.id(), element);
    }

    Element get(String id) {
        return elements.get(id);
    }

    String processKey() {
        return processKey;
    }

    String processName() {
        return processName;
    }

    List<Element> elements() {
        return new ArrayList<>(elements.values());
    }
}
