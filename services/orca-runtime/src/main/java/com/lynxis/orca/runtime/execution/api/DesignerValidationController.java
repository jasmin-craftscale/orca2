package com.lynxis.orca.runtime.execution.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.runtime.api.generated.DesignerApi;
import com.lynxis.orca.runtime.api.generated.model.NamespaceDatasetKey;
import com.lynxis.orca.runtime.api.generated.model.NamespaceNode;
import com.lynxis.orca.runtime.api.generated.model.NamespaceWorkflow;
import com.lynxis.orca.runtime.api.generated.model.SelectorNamespace;
import com.lynxis.orca.runtime.api.generated.model.SelectorNamespaceEnvelope;
import com.lynxis.orca.runtime.api.generated.model.ValidationFinding;
import com.lynxis.orca.runtime.api.generated.model.ValidationReport;
import com.lynxis.orca.runtime.api.generated.model.ValidationReportEnvelope;
import com.lynxis.orca.runtime.execution.internal.selector.NamespaceService;

import tools.jackson.databind.ObjectMapper;

/**
 * What the builder calls while somebody is editing a workflow.
 *
 * <p><b>The compiler is the definition of valid, and this is the only way to ask it.</b> The
 * alternative — reimplementing the invariants in the frontend — drifts within a release, and
 * a drifted validator blesses a workflow the deploy then rejects, which teaches authors to
 * ignore it. Here the verdict comes from really compiling the draft, so validation and
 * deployment cannot disagree, and {@code CompilerLintAgreementTest} holds the findings to the
 * same standard across the whole estate.
 *
 * <p>Nothing is stored and nothing is deployed: a draft is compiled in memory and thrown
 * away. That makes this safe to call on every keystroke's worth of debounce.
 *
 * <p>Hand-written against a generated interface, like every controller here: change
 * {@code /api/v1/designer/*} in {@code orca-runtime.yaml} and this class stops compiling
 * until it matches.
 */
@RestController
public class DesignerValidationController implements DesignerApi {

	/** Re-serialises the draft for the compiler, which consumes the JSON text itself. */
	private static final ObjectMapper JSON = new ObjectMapper();

	private final CompilationFacade compiler;
	private final NamespaceService namespace;

	public DesignerValidationController(CompilationFacade compiler, NamespaceService namespace) {
		this.compiler = compiler;
		this.namespace = namespace;
	}

	@Override
	public ResponseEntity<ValidationReportEnvelope> validateDraft(Map<String, Object> designerDraft) {
		com.lynxis.orca.runtime.execution.api.dto.ValidationReport report;
		try {
			report = compiler.validate(JSON.writeValueAsString(designerDraft));
		}
		catch (RuntimeException unreadable) {
			// A payload the compiler cannot even parse is still an answer the builder
			// can draw: not publishable, and here is why. Never an internal detail.
			report = new com.lynxis.orca.runtime.execution.api.dto.ValidationReport(false,
					List.of(new com.lynxis.orca.runtime.execution.api.dto.ValidationFinding(
							"MALFORMED_INPUT", null, null,
							"This workflow could not be read as a publish payload.")));
		}
		return ResponseEntity.ok(new ValidationReportEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(toModel(report)));
	}

	@Override
	public ResponseEntity<SelectorNamespaceEnvelope> draftNamespace(Map<String, Object> designerDraft,
			String nodeUuid) {
		com.lynxis.orca.runtime.execution.api.dto.SelectorNamespace found;
		try {
			found = namespace.forDraft(JSON.writeValueAsString(designerDraft), nodeUuid);
		}
		catch (RuntimeException unreadable) {
			throw new ApiException(ExecutionErrorCode.DRAFT_UNREADABLE,
					"This workflow could not be read as a publish payload.");
		}
		return ResponseEntity.ok(new SelectorNamespaceEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(toModel(found)));
	}

	private static ValidationReport toModel(
			com.lynxis.orca.runtime.execution.api.dto.ValidationReport report) {
		return new ValidationReport()
				.compiles(report.compiles())
				.findings(report.findings().stream()
						.map(f -> new ValidationFinding()
								.invariant(f.invariant())
								.subjectId(f.subjectId())
								.subjectName(f.subjectName())
								.message(f.message()))
						.toList());
	}

	private static SelectorNamespace toModel(
			com.lynxis.orca.runtime.execution.api.dto.SelectorNamespace found) {
		return new SelectorNamespace()
				.nodes(found.nodes().stream()
						.map(n -> new NamespaceNode()
								.uuid(n.uuid())
								.name(n.name())
								.type(n.type())
								.selector(n.selector())
								.runsBefore(n.runsBefore()))
						.toList())
				.datasetKeys(found.datasetKeys().stream()
						.map(k -> new NamespaceDatasetKey()
								.key(k.key())
								.selector(k.selector())
								.provenance(k.provenance()))
						.toList())
				.workflows(found.workflows().stream()
						.map(w -> new NamespaceWorkflow()
								.workflowUuid(w.workflowUuid())
								.workflowId(w.workflowId())
								.name(w.name())
								.selector(w.selector()))
						.toList())
				.helpers(found.helpers());
	}
}
