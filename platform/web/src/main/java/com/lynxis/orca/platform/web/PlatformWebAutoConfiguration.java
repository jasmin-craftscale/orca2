package com.lynxis.orca.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * A service adds {@code platform/web} as a dependency and gets the envelope, the
 * error handling and the request id — without registering anything.
 *
 * <p>That invisibility is the point. The correct thing has to be the easy thing,
 * or six services will each end up with five-sixths of it.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformWebAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ApiExceptionHandler apiExceptionHandler() {
		return new ApiExceptionHandler();
	}

	@Bean
	@ConditionalOnMissingBean
	public org.springframework.boot.web.servlet.FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
		var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new RequestIdFilter());
		// Ahead of the security filter chain, so an authentication failure is
		// logged and answered with the same request id as everything else. A trace
		// that starts only after the request is authenticated is missing the
		// requests most worth tracing.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}
}
