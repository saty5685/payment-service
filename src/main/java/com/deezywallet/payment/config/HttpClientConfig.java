package com.deezywallet.payment.config;

import com.deezywallet.payment.constants.PaymentConstants;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP client configuration for external gateway calls.
 *
 * TWO RestTemplate beans:
 *
 * gatewayRestTemplate — for Stripe/Razorpay/NPCI calls:
 *   Connect: 5s, Read: 10s (gateway_timeout_ms from constants)
 *   Gateways can be slow on cross-region calls — 10s is the Stripe SLA.
 *
 * internalRestTemplate — for User Service / Fraud Service calls:
 *   Connect: 2s, Read: 3s (faster — internal cluster calls should be <100ms)
 *   Fraud service has a 3s SLA; fail-safe on timeout (reject payment).
 *
 * WHY separate beans?
 *   A single RestTemplate with a single timeout would either:
 *   (a) Use the gateway's 10s timeout for internal calls → fraud check could
 *       hang for 10s before failing, blocking the HTTP thread.
 *   (b) Use the 3s internal timeout for gateways → legitimate slow gateway
 *       responses (cross-region charges) get timed out unnecessarily.
 *   Separate beans with separate timeouts solve both problems.
 */
@Configuration
public class HttpClientConfig {

	@Bean("gatewayRestTemplate")
	public RestTemplate gatewayRestTemplate(RestTemplateBuilder builder) {
		return builder
				.connectTimeout(Duration.ofSeconds(5))
				.readTimeout(Duration.ofMillis(PaymentConstants.GATEWAY_TIMEOUT_MS))
				.build();
	}

	@Bean("internalRestTemplate")
	public RestTemplate internalRestTemplate(RestTemplateBuilder builder) {
		return builder
				.connectTimeout(Duration.ofSeconds(2))
				.readTimeout(Duration.ofMillis(PaymentConstants.FRAUD_SERVICE_TIMEOUT_MS))
				.build();
	}
}
