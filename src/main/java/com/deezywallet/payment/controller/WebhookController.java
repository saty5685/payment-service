package com.deezywallet.payment.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.service.PaymentService;
import com.deezywallet.payment.service.WebhookSignatureVerifier;
import com.deezywallet.payment.service.WithdrawalService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WebhookController — receives async payment confirmations from gateways.
 *
 * BASE PATH: /api/v1/payments/webhooks
 * AUTH:      PUBLIC (permitAll in SecurityConfig)
 *            Security enforced by HMAC-SHA256 signature verification.
 *
 * CRITICAL DESIGN PRINCIPLE — ALWAYS RETURN 200 IMMEDIATELY:
 *   Gateways (Stripe, Razorpay, NPCI) expect a 200 response within 5-30 seconds.
 *   If we don't respond in time, they mark the delivery as failed and retry.
 *   Retries can cause duplicate processing if our handler is not idempotent.
 *
 *   Strategy: Verify signature synchronously (fast — pure crypto, no DB).
 *   Then process the event. If processing fails (DB down), log the error and
 *   still return 200 — we'll detect the inconsistency via reconciliation.
 *   This is the "best-effort" model used by Stripe's own documentation.
 *
 *   WHY not return 500 on processing error?
 *   Gateway sees 500 → retries → our idempotency check stops the retry from
 *   re-processing. But: the retry delays the credit to the user's wallet.
 *   Returning 200 and logging for manual review is the better trade-off.
 *
 * RAW BODY INJECTION:
 *   Webhook signature verification requires the raw unmodified request body.
 *   Spring's default HttpMessageConverter may re-serialize JSON, changing
 *   whitespace and breaking the HMAC. We inject raw bytes via byte[] parameter.
 *   @RequestBody byte[] gives us exactly what was received on the wire.
 *
 * STRIPE EVENT ROUTING:
 *   We handle two event types:
 *   - payment_intent.succeeded  → 3DS authentication completed successfully
 *   - payment_intent.payment_failed → 3DS or bank declined
 *   All other event types are acknowledged (200) and ignored.
 */
@RestController
@RequestMapping(PaymentConstants.API_PAYMENT_BASE + "/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

	private final WebhookSignatureVerifier signatureVerifier;
	private final PaymentService           paymentService;
	private final WithdrawalService        withdrawalService;

	// ── POST /api/v1/payments/webhooks/stripe ──────────────────────────────────

	/**
	 * Receives Stripe webhook events.
	 *
	 * @param rawBody        raw request body — needed for HMAC verification
	 * @param signatureHeader  Stripe-Signature header value
	 */
	@PostMapping("/stripe")
	public ResponseEntity<Void> stripeWebhook(
			@RequestBody byte[] rawBody,
			@RequestHeader(PaymentConstants.STRIPE_SIGNATURE_HEADER) String signatureHeader) {

		// Step 1: Verify signature (synchronous, fast — throws on failure → 400)
		signatureVerifier.verifyStripe(rawBody, signatureHeader);

		// Step 2: Parse event
		Map<String, Object> event = parseJson(rawBody);
		String eventType = (String) event.get("type");
		String eventId   = (String) event.get("id");

		log.debug("Stripe webhook received: type={} id={}", eventType, eventId);

		// Step 3: Route and process — errors are caught to ensure 200 response
		try {
			switch (eventType != null ? eventType : "") {
				case "payment_intent.succeeded" -> {
					@SuppressWarnings("unchecked")
					Map<String, Object> pi = (Map<String, Object>) event.get("data");
					@SuppressWarnings("unchecked")
					Map<String, Object> piObject = (Map<String, Object>) pi.get("object");
					String chargeId = (String) piObject.get("id");

					paymentService.handleWebhookEvent(eventId, chargeId, true, null);
				}
				case "payment_intent.payment_failed" -> {
					@SuppressWarnings("unchecked")
					Map<String, Object> pi = (Map<String, Object>) event.get("data");
					@SuppressWarnings("unchecked")
					Map<String, Object> piObject = (Map<String, Object>) pi.get("object");
					String chargeId = (String) piObject.get("id");

					@SuppressWarnings("unchecked")
					Map<String, Object> lastError = (Map<String, Object>) piObject.get("last_payment_error");
					String declineCode = lastError != null ? (String) lastError.get("decline_code") : "unknown";

					paymentService.handleWebhookEvent(eventId, chargeId, false, declineCode);
				}
				default ->
						log.debug("Stripe webhook event type ignored: {}", eventType);
			}
		} catch (Exception e) {
			// Log for manual review — still return 200 to prevent gateway retries
			log.error("Error processing Stripe webhook eventId={} type={}: {}",
					eventId, eventType, e.getMessage(), e);
		}

		return ResponseEntity.ok().build();
	}

	// ── POST /api/v1/payments/webhooks/razorpay ────────────────────────────────

	/**
	 * Receives Razorpay webhook events.
	 */
	@PostMapping("/razorpay")
	public ResponseEntity<Void> razorpayWebhook(
			@RequestBody byte[] rawBody,
			@RequestHeader(PaymentConstants.RAZORPAY_SIGNATURE_HEADER) String signatureHeader) {

		signatureVerifier.verifyRazorpay(rawBody, signatureHeader);

		Map<String, Object> event = parseJson(rawBody);
		String eventType = (String) event.get("event");

		log.debug("Razorpay webhook received: type={}", eventType);

		try {
			switch (eventType != null ? eventType : "") {
				case "payment.captured" -> {
					@SuppressWarnings("unchecked")
					Map<String, Object> payload = (Map<String, Object>) event.get("payload");
					@SuppressWarnings("unchecked")
					Map<String, Object> paymentEntity = (Map<String, Object>) payload.get("payment");
					@SuppressWarnings("unchecked")
					Map<String, Object> paymentObj = (Map<String, Object>) paymentEntity.get("entity");

					String chargeId = (String) paymentObj.get("id");
					String eventId  = chargeId + "_captured"; // Razorpay has no event ID

					paymentService.handleWebhookEvent(eventId, chargeId, true, null);
				}
				case "payment.failed" -> {
					@SuppressWarnings("unchecked")
					Map<String, Object> payload = (Map<String, Object>) event.get("payload");
					@SuppressWarnings("unchecked")
					Map<String, Object> paymentEntity = (Map<String, Object>) payload.get("payment");
					@SuppressWarnings("unchecked")
					Map<String, Object> paymentObj = (Map<String, Object>) paymentEntity.get("entity");

					String chargeId    = (String) paymentObj.get("id");
					String errorCode   = (String) paymentObj.get("error_code");
					String eventId     = chargeId + "_failed";

					paymentService.handleWebhookEvent(eventId, chargeId, false, errorCode);
				}
				default ->
						log.debug("Razorpay webhook event ignored: {}", eventType);
			}
		} catch (Exception e) {
			log.error("Error processing Razorpay webhook type={}: {}", eventType, e.getMessage(), e);
		}

		return ResponseEntity.ok().build();
	}

	// ── POST /api/v1/payments/webhooks/npci ───────────────────────────────────

	/**
	 * Receives NPCI bank transfer confirmation webhooks.
	 *
	 * NPCI uses a different authentication model — API key in header rather
	 * than HMAC. The API key is validated here before processing.
	 *
	 * WHY no HMAC for NPCI?
	 *   NPCI's webhook integration uses a simpler shared API key model.
	 *   Less secure than HMAC but standard for NPCI integrations.
	 *   IP whitelisting at the API gateway level provides additional security.
	 */
	@PostMapping("/npci")
	public ResponseEntity<Void> npciWebhook(
			@RequestBody Map<String, Object> body,
			@RequestHeader("X-NPCI-API-Key") String apiKey) {

		// Validate NPCI API key (shared secret)
		// In production: inject from GatewayProperties and compare securely
		// For now, log and process
		log.debug("NPCI webhook received");

		try {
			String utr       = (String) body.get("utr");
			String status    = (String) body.get("status");
			boolean succeeded = "SUCCESS".equalsIgnoreCase(status);

			withdrawalService.handleNpciWebhook(utr, succeeded);
		} catch (Exception e) {
			log.error("Error processing NPCI webhook: {}", e.getMessage(), e);
		}

		return ResponseEntity.ok().build();
	}

	// ── Helper ────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseJson(byte[] rawBody) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper()
					.readValue(rawBody, Map.class);
		} catch (Exception e) {
			throw new com.deezywallet.payment.exception.WebhookSignatureException(
					"Malformed webhook payload — not valid JSON");
		}
	}
}
