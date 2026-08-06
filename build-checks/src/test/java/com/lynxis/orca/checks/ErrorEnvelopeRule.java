package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * <strong>Check 4 · Error envelope.</strong> A controller returns a shape other
 * than the envelope, and the build stops.
 *
 * <p>§B8: "One response shape everywhere, with a machine-readable code a caller
 * can branch on." One controller returning a bare DTO is all it takes for that to
 * become "one response shape almost everywhere", which is a different and much
 * less useful promise.
 *
 * <p><strong>How "the envelope" is recognised.</strong> Either the returned type
 * is {@code platform/web}'s own {@code ApiResponse}, or it is a generated model
 * that declares a field of type {@code com.lynxis.orca.platform.web.ApiStatus}.
 * The second case is not a heuristic: that field type appears only when the
 * schema composed {@code _shared.yaml}'s {@code ApiResponse} and the generator's
 * schemaMapping bound it to the platform type. A service that invented its own
 * envelope schema would get its own status enum, and would fail here.
 *
 * <p>{@code ResponseEntity<T>} is unwrapped to T from the generic signature
 * rather than from the erased return type — checking the erasure would accept
 * {@code ResponseEntity<anything>} and prove nothing at all.
 */
class ErrorEnvelopeRule {

	private static final String API_RESPONSE = "com.lynxis.orca.platform.web.ApiResponse";
	private static final String API_STATUS = "com.lynxis.orca.platform.web.ApiStatus";
	private static final String RESPONSE_ENTITY = "org.springframework.http.ResponseEntity";
	private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";

	@Test
	@DisplayName("every controller method returns the shared envelope")
	void controllersReturnTheEnvelope() {
		methods()
				.that().areDeclaredInClassesThat().areAnnotatedWith(REST_CONTROLLER)
				.and().arePublic()
				.and().areNotStatic()
				.should(returnTheEnvelope())
				.because("a caller must be able to branch on a machine-readable code without reading the "
						+ "message, on every route of every service. One controller returning a bare DTO "
						+ "turns that guarantee into a convention.")
				.check(OrcaClasses.production());
	}

	private static ArchCondition<JavaMethod> returnTheEnvelope() {
		return new ArchCondition<>("return the shared response envelope") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				JavaClass body = responseBodyType(method);
				if (isVoid(body) || isEnvelope(body)) {
					return;
				}
				events.add(SimpleConditionEvent.violated(method,
						method.getFullName() + " returns " + body.getName()
								+ ", which is not the shared envelope. Return ApiResponse, or a generated "
								+ "model whose schema composes _shared.yaml's ApiResponse."));
			}
		};
	}

	/** The type actually serialised: T for {@code ResponseEntity<T>}, otherwise the return type. */
	private static JavaClass responseBodyType(JavaMethod method) {
		JavaType returnType = method.getReturnType();
		if (returnType instanceof JavaParameterizedType parameterized
				&& RESPONSE_ENTITY.equals(parameterized.toErasure().getFullName())
				&& !parameterized.getActualTypeArguments().isEmpty()) {
			return parameterized.getActualTypeArguments().getFirst().toErasure();
		}
		return returnType.toErasure();
	}

	private static boolean isVoid(JavaClass type) {
		String name = type.getFullName();
		return "void".equals(name) || "java.lang.Void".equals(name) || "java.lang.Object".equals(name);
	}

	private static boolean isEnvelope(JavaClass type) {
		return API_RESPONSE.equals(type.getFullName()) || declaresApiStatusField(type);
	}

	private static boolean declaresApiStatusField(JavaClass type) {
		return type.getAllFields().stream()
				.anyMatch(field -> API_STATUS.equals(field.getRawType().getFullName()));
	}
}
