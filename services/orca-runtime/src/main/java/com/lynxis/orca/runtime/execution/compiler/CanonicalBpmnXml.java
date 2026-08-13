package com.lynxis.orca.runtime.execution.compiler;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The owned serializer: IR → BPMN 2.0 XML with fixed ordering, fixed
 * indentation, fixed attribute order — same input, same bytes, on every JVM and every
 * Flowable version. T2's byte-identity gate hangs off this class; changing ANY output
 * detail invalidates every frozen golden, deliberately.
 */
final class CanonicalBpmnXml {

    static final String ORCA_NS = "http://lynxis.com/orca";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    private CanonicalBpmnXml() {
    }

    static byte[] serialize(BpmnIr ir) {
        StringBuilder out = new StringBuilder(8 * 1024);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n");
        out.append("             xmlns:flowable=\"").append(FLOWABLE_NS).append("\"\n");
        out.append("             xmlns:orca=\"").append(ORCA_NS).append("\"\n");
        out.append("             targetNamespace=\"").append(ORCA_NS).append("\">\n");
        out.append("  <process id=\"").append(esc(ir.processKey()))
                .append("\" name=\"").append(esc(ir.processName()))
                .append("\" isExecutable=\"true\">\n");
        for (BpmnIr.Element element : ir.elements()) {
            append(out, element);
        }
        out.append("  </process>\n");
        out.append("</definitions>\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder out, BpmnIr.Element element) {
        switch (element) {
            case BpmnIr.StartEvent e -> out.append("    <startEvent id=\"").append(esc(e.id()))
                    .append("\" name=\"").append(esc(e.name())).append("\"/>\n");
            case BpmnIr.EndEvent e -> out.append("    <endEvent id=\"").append(esc(e.id()))
                    .append("\" name=\"").append(esc(e.name())).append("\"/>\n");
            case BpmnIr.PassThroughTask e -> out.append("    <task id=\"").append(esc(e.id()))
                    .append("\" name=\"").append(esc(e.name())).append("\"/>\n");
            case BpmnIr.ServiceTask e -> {
                out.append("    <serviceTask id=\"").append(esc(e.id()))
                        .append("\" name=\"").append(esc(e.name()))
                        .append("\" flowable:delegateExpression=\"${").append(e.delegateExpression())
                        .append("}\"");
                for (Map.Entry<String, String> a : e.orcaAttributes().entrySet()) {
                    out.append(" orca:").append(a.getKey()).append("=\"").append(esc(a.getValue())).append('"');
                }
                out.append("/>\n");
            }
            case BpmnIr.ReceiveTask e -> {
                out.append("    <receiveTask id=\"").append(esc(e.id()))
                        .append("\" name=\"").append(esc(e.name())).append('"');
                for (Map.Entry<String, String> a : e.orcaAttributes().entrySet()) {
                    out.append(" orca:").append(a.getKey()).append("=\"").append(esc(a.getValue())).append('"');
                }
                out.append("/>\n");
            }
            case BpmnIr.UserTask e -> out.append("    <userTask id=\"").append(esc(e.id()))
                    .append("\" name=\"").append(esc(e.name()))
                    .append("\" flowable:candidateGroups=\"").append(esc(e.candidateGroup()))
                    .append("\"/>\n");
            case BpmnIr.ExclusiveGateway e -> {
                out.append("    <exclusiveGateway id=\"").append(esc(e.id()))
                        .append("\" name=\"").append(esc(e.name())).append('"');
                if (e.defaultFlowId() != null) {
                    out.append(" default=\"").append(esc(e.defaultFlowId())).append('"');
                }
                out.append("/>\n");
            }
            case BpmnIr.CallActivity e -> out.append("    <callActivity id=\"").append(esc(e.id()))
                    .append("\" name=\"").append(esc(e.name()))
                    .append("\" calledElement=\"").append(esc(e.calledElement()))
                    // I4: an ORCA subflow reads the caller's dataset through plain selector
                    // paths; without inheritance every condition inside falls to its default
                    // branch, silently.
                    .append("\" flowable:inheritVariables=\"true\"/>\n");
            case BpmnIr.TimerCatchEvent e -> {
                out.append("    <intermediateCatchEvent id=\"").append(esc(e.id()))
                        .append("\" name=\"").append(esc(e.name())).append("\">\n");
                out.append("      <timerEventDefinition>\n");
                out.append("        <timeDuration>").append(esc(e.isoDuration())).append("</timeDuration>\n");
                out.append("      </timerEventDefinition>\n");
                out.append("    </intermediateCatchEvent>\n");
            }
            case BpmnIr.SequenceFlow e -> {
                out.append("    <sequenceFlow id=\"").append(esc(e.id()))
                        .append("\" sourceRef=\"").append(esc(e.from()))
                        .append("\" targetRef=\"").append(esc(e.to())).append('"');
                if (e.condition() == null && e.fieldSidecar().isEmpty()) {
                    out.append("/>\n");
                    return;
                }
                out.append(">\n");
                if (!e.fieldSidecar().isEmpty()) {
                    out.append("      <extensionElements>\n");
                    for (Map.Entry<String, String> binding : e.fieldSidecar()) {
                        out.append("        <orca:field var=\"").append(esc(binding.getKey()))
                                .append("\" selector=\"").append(esc(binding.getValue())).append("\"/>\n");
                    }
                    out.append("      </extensionElements>\n");
                }
                if (e.condition() != null) {
                    out.append("      <conditionExpression xsi:type=\"tFormalExpression\" ")
                            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
                            .append(esc(e.condition())).append("</conditionExpression>\n");
                }
                out.append("    </sequenceFlow>\n");
            }
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
