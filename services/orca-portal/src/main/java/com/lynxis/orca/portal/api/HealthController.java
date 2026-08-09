package com.lynxis.orca.portal.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.portal.api.generated.HealthApi;
import com.lynxis.orca.portal.api.generated.model.HealthEnvelope;
import com.lynxis.orca.portal.api.generated.model.ServiceHealth;

/**
 * Hand-written, and it <strong>implements a generated interface</strong>.
 *
 * <p>That is what makes contract-first a constraint rather than documentation
 * and enforced by the compiler. {@code HealthApi} is generated from
 * {@code src/main/resources/openapi/orca-portal.yaml} on every build, so changing
 * the route, the operation id or the response schema breaks this class until it
 * is brought back into line. Nobody has to notice; the compiler does.
 *
 * <p>The envelope it returns composes the shared one from
 * {@code platform/web/src/main/resources/openapi/_shared.yaml} — which is why
 * its {@code status}, {@code errors} and {@code page} fields are
 * platform/web's own types rather than a seventh copy of them.
 *
 * <p>There is deliberately no other controller in this service. The current on-site scope builds no
 * business logic.
 */
@RestController
public class HealthController implements HealthApi {

	@Override
	public ResponseEntity<HealthEnvelope> health() {
		return ResponseEntity.ok(new HealthEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new ServiceHealth()
						.service("orca-portal")
						.status(ServiceHealth.StatusEnum.UP)));
	}
}
