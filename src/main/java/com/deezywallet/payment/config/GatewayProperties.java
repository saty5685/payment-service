package com.deezywallet.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * External payment gateway credentials — bound from application.yml.
 *
 * WHY @ConfigurationProperties instead of @Value per field?
 *   Stripe and Razorpay each have multiple credentials (API key, webhook secret,
 *   publishable key). @ConfigurationProperties groups them by gateway namespace
 *   and provides type-safe access with IDE autocomplete.
 *
 * PCI DSS NOTE: These properties must only be set via environment variables
 *   or Vault in production. Never committed to source control as plaintext.
 *   In local/docker: ${STRIPE_SECRET_KEY} from .env file.
 *   In prod: Kubernetes Secret → environment variable.
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateways")
public class GatewayProperties {

	private Stripe    stripe    = new Stripe();
	private Razorpay  razorpay  = new Razorpay();
	private Npci      npci      = new Npci();

	@Data
	public static class Stripe {
		/** Secret key — server-side only, never exposed to clients */
		private String secretKey;
		/** Webhook endpoint secret — for HMAC-SHA256 signature verification */
		private String webhookSecret;
		/** Base URL — overridable for testing (Stripe test mode URL) */
		private String baseUrl = "https://api.stripe.com";
	}

	@Data
	public static class Razorpay {
		private String keyId;
		private String keySecret;
		private String webhookSecret;
		private String baseUrl = "https://api.razorpay.com";
	}

	@Data
	public static class Npci {
		/** NPCI merchant ID for NEFT/IMPS batch transfers */
		private String merchantId;
		private String apiKey;
		private String baseUrl = "https://api.npci.org.in";
	}
}
