package com.deezywallet.payment.service;

import com.deezywallet.payment.config.GatewayProperties;
import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.exception.GatewayDeclinedException;
import com.deezywallet.payment.exception.GatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.Map;

/**
 * GatewayService — abstracts Stripe and Razorpay behind a single interface.
 *
 * WHY an abstraction layer?
 *   Both Stripe and Razorpay have different API shapes, auth schemes,
 *   and response formats. The abstraction lets PaymentService call
 *   chargeCard(gateway, token, amount) without knowing which gateway
 *   is being used. This also makes it straightforward to add a third
 *   gateway (e.g. PayU) by implementing the same methods.
 *
 * CHARGE RESULT:
 *   Contains three possible outcomes:
 *   - SUCCESS: charge processed, chargeId returned
 *   - REQUIRES_3DS: gateway returned a redirect URL for cardholder auth
 *   - DECLINED: card declined, declineCode indicates why
 *
 * AMOUNT CONVERSION:
 *   Stripe and Razorpay accept amounts in the smallest currency unit.
 *   For INR: ₹100.00 → 10000 paise.
 *   We store amounts as DECIMAL(19,4) with 4 decimal places.
 *   Conversion: amount × 100, rounded to nearest integer.
 *
 * ERROR HANDLING:
 *   HTTP 4xx from gateway (card declined, invalid token) → GatewayDeclinedException
 *   HTTP 5xx or timeout → GatewayException (retryable)
 *   Network timeout → GatewayException with GATEWAY_TIMEOUT code
 *
 * NOTE: This is a simplified gateway integration. In production:
 *   - Use the official Stripe Java SDK (com.stripe:stripe-java)
 *   - Use the official Razorpay Java SDK (com.razorpay:razorpay-java)
 *   The SDKs handle retries, webhook validation, and idempotency keys natively.
 *   RestTemplate calls here represent the conceptual structure of those SDK calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayService {

	private final GatewayProperties gatewayProperties;

	@Qualifier("gatewayRestTemplate")
	private final RestTemplate gatewayRestTemplate;

	// ── Charge result carrier ─────────────────────────────────────────────────

	public enum ChargeOutcome { SUCCESS, REQUIRES_3DS, DECLINED }

	@lombok.Value
	@lombok.Builder
	public static class ChargeResult {
		ChargeOutcome outcome;
		String        chargeId;        // gateway charge/payment-intent ID
		String        threeDsUrl;      // only when outcome = REQUIRES_3DS
		String        declineCode;     // only when outcome = DECLINED
		String        declineMessage;  // human-readable decline reason
	}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Charges a tokenised payment method.
	 *
	 * @param gateway        which gateway to use (STRIPE or RAZORPAY)
	 * @param gatewayToken   opaque token from the gateway SDK
	 * @param amount         amount in INR (DECIMAL with 4 dp)
	 * @param idempotencyKey our payment ID — sent to gateway as idempotency key
	 * @return ChargeResult with SUCCESS, REQUIRES_3DS, or DECLINED
	 * @throws GatewayException on 5xx or network timeout (retryable)
	 */
	public ChargeResult charge(PaymentGatewayEnum gateway,
			String             gatewayToken,
			BigDecimal         amount,
			String             idempotencyKey) {
		return switch (gateway) {
			case STRIPE   -> chargeStripe(gatewayToken, amount, idempotencyKey);
			case RAZORPAY -> chargeRazorpay(gatewayToken, amount, idempotencyKey);
			case NPCI     -> throw new IllegalArgumentException(
					"NPCI is not used for card charges — use initiateNpciTransfer()");
		};
	}

	/**
	 * Initiates a refund against an existing charge.
	 *
	 * @param gateway        the gateway that processed the original charge
	 * @param chargeId       the gateway's charge/payment-intent ID
	 * @param refundAmount   amount to refund (can be partial)
	 * @return the gateway's refund ID for audit trail
	 * @throws GatewayException on failure
	 */
	public String refund(PaymentGatewayEnum gateway, String chargeId, BigDecimal refundAmount) {
		return switch (gateway) {
			case STRIPE   -> refundStripe(chargeId, refundAmount);
			case RAZORPAY -> refundRazorpay(chargeId, refundAmount);
			case NPCI     -> throw new IllegalArgumentException("NPCI refunds use reversal API");
		};
	}

	/**
	 * Initiates a bank transfer via NPCI (NEFT/IMPS) for wallet withdrawals.
	 *
	 * @param accountNumber  decrypted bank account number
	 * @param ifscCode       IFSC code of destination bank
	 * @param amount         transfer amount in INR
	 * @param referenceId    our withdrawal request ID (for NPCI reference)
	 * @return UTR (Unique Transaction Reference) from NPCI
	 * @throws GatewayException on failure
	 */
	public String initiateNpciTransfer(String     accountNumber,
			String     ifscCode,
			BigDecimal amount,
			String     referenceId) {
		log.info("Initiating NPCI transfer: amount={} ifsc={} ref={}",
				amount, ifscCode, referenceId);
		try {
			HttpHeaders headers = npciHeaders();
			Map<String, Object> body = Map.of(
					"account_number", accountNumber,
					"ifsc",           ifscCode,
					"amount",         toPaise(amount),
					"reference_id",   referenceId,
					"remarks",        "DeezyWallet withdrawal " + referenceId
			);

			ResponseEntity<Map> response = gatewayRestTemplate.exchange(
					gatewayProperties.getNpci().getBaseUrl() + "/v1/transfers",
					HttpMethod.POST,
					new HttpEntity<>(body, headers),
					Map.class
			);

			@SuppressWarnings("unchecked")
			Map<String, Object> resp = response.getBody();
			if (resp == null || !resp.containsKey("utr")) {
				throw new GatewayException(PaymentErrorCode.GATEWAY_ERROR,
						"NPCI transfer response missing UTR");
			}

			String utr = (String) resp.get("utr");
			log.info("NPCI transfer initiated: ref={} utr={}", referenceId, utr);
			return utr;

		} catch (ResourceAccessException e) {
			log.error("NPCI timeout for ref={}: {}", referenceId, e.getMessage());
			throw new GatewayException(PaymentErrorCode.GATEWAY_TIMEOUT,
					"NPCI service is temporarily unavailable. Withdrawal will be retried.");
		} catch (HttpClientErrorException e) {
			log.error("NPCI transfer failed: ref={} status={} body={}",
					referenceId, e.getStatusCode(), e.getResponseBodyAsString());
			throw new GatewayException(PaymentErrorCode.GATEWAY_ERROR,
					"Bank transfer initiation failed. Please try again.");
		}
	}

	// ── Stripe implementation ─────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private ChargeResult chargeStripe(String gatewayToken, BigDecimal amount,
			String idempotencyKey) {
		log.debug("Stripe charge: amount={} idempotencyKey={}", amount, idempotencyKey);
		try {
			HttpHeaders headers = stripeHeaders(idempotencyKey);

			// Stripe PaymentIntent API (simplified — production would use Stripe SDK)
			String body = "amount=" + toPaise(amount) +
					"&currency=inr" +
					"&payment_method=" + gatewayToken +
					"&confirm=true" +
					"&return_url=https://app.deezywallet.com/payments/3ds-callback";

			ResponseEntity<Map> response = gatewayRestTemplate.exchange(
					gatewayProperties.getStripe().getBaseUrl() + "/v1/payment_intents",
					HttpMethod.POST,
					new HttpEntity<>(body, headers),
					Map.class
			);

			Map<String, Object> pi = response.getBody();
			String status = (String) pi.get("status");

			if ("succeeded".equals(status)) {
				return ChargeResult.builder()
						.outcome(ChargeOutcome.SUCCESS)
						.chargeId((String) pi.get("id"))
						.build();
			}

			if ("requires_action".equals(status)) {
				Map<String, Object> nextAction = (Map<String, Object>) pi.get("next_action");
				Map<String, Object> redirectToUrl = (Map<String, Object>) nextAction.get("redirect_to_url");
				return ChargeResult.builder()
						.outcome(ChargeOutcome.REQUIRES_3DS)
						.chargeId((String) pi.get("id"))
						.threeDsUrl((String) redirectToUrl.get("url"))
						.build();
			}

			// Unexpected status
			throw new GatewayException(PaymentErrorCode.GATEWAY_ERROR,
					"Unexpected Stripe PaymentIntent status: " + status);

		} catch (HttpClientErrorException e) {
			// 4xx from Stripe — card declined or invalid
			Map<String, Object> errorBody = parseStripeError(e.getResponseBodyAsString());
			String declineCode = (String) errorBody.getOrDefault("decline_code", "generic_decline");
			log.warn("Stripe charge declined: idempotencyKey={} declineCode={}", idempotencyKey, declineCode);
			throw new GatewayDeclinedException(
					PaymentErrorCode.GATEWAY_DECLINED, declineCode,
					"Card declined: " + declineCode);
		} catch (ResourceAccessException e) {
			log.error("Stripe timeout: idempotencyKey={}: {}", idempotencyKey, e.getMessage());
			throw new GatewayException(PaymentErrorCode.GATEWAY_TIMEOUT,
					"Payment processing is temporarily unavailable. Please try again.");
		}
	}

	@SuppressWarnings("unchecked")
	private String refundStripe(String chargeId, BigDecimal amount) {
		log.info("Stripe refund: chargeId={} amount={}", chargeId, amount);
		try {
			HttpHeaders headers = stripeHeaders(null);
			String body = "payment_intent=" + chargeId +
					"&amount=" + toPaise(amount);

			ResponseEntity<Map> response = gatewayRestTemplate.exchange(
					gatewayProperties.getStripe().getBaseUrl() + "/v1/refunds",
					HttpMethod.POST,
					new HttpEntity<>(body, headers),
					Map.class
			);

			Map<String, Object> refund = response.getBody();
			return (String) refund.get("id");

		} catch (HttpClientErrorException | ResourceAccessException e) {
			log.error("Stripe refund failed for chargeId={}: {}", chargeId, e.getMessage());
			throw new GatewayException(PaymentErrorCode.GATEWAY_ERROR,
					"Refund processing failed. Please try again or contact support.");
		}
	}

	// ── Razorpay implementation ───────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private ChargeResult chargeRazorpay(String gatewayToken, BigDecimal amount,
			String idempotencyKey) {
		log.debug("Razorpay charge: amount={} idempotencyKey={}", amount, idempotencyKey);
		try {
			HttpHeaders headers = razorpayHeaders();
			Map<String, Object> body = Map.of(
					"amount",   toPaise(amount),
					"currency", "INR",
					"token",    gatewayToken,
					"receipt",  idempotencyKey
			);

			ResponseEntity<Map> response = gatewayRestTemplate.exchange(
					gatewayProperties.getRazorpay().getBaseUrl() + "/v1/payments/create/ajax",
					HttpMethod.POST,
					new HttpEntity<>(body, headers),
					Map.class
			);

			Map<String, Object> payment = response.getBody();
			String status = (String) payment.get("status");

			if ("captured".equals(status) || "authorized".equals(status)) {
				return ChargeResult.builder()
						.outcome(ChargeOutcome.SUCCESS)
						.chargeId((String) payment.get("id"))
						.build();
			}

			if ("created".equals(status) && payment.containsKey("authentication")) {
				@SuppressWarnings("unchecked")
				Map<String, Object> auth = (Map<String, Object>) payment.get("authentication");
				return ChargeResult.builder()
						.outcome(ChargeOutcome.REQUIRES_3DS)
						.chargeId((String) payment.get("id"))
						.threeDsUrl((String) auth.get("url"))
						.build();
			}

			throw new GatewayException(PaymentErrorCode.GATEWAY_ERROR,
					"Unexpected Razorpay payment status: " + status);

		} catch (HttpClientErrorException e) {
			log.warn("Razorpay charge declined: idempotencyKey={}", idempotencyKey);
			throw new GatewayDeclinedException(
					PaymentErrorCode.GATEWAY_DECLINED, "razorpay_declined",
					"Payment declined by your bank. Please try a different payment method.");
		} catch (ResourceAccessException e) {
			throw new GatewayException(PaymentErrorCode.GATEWAY_TIMEOUT,
					"Payment processing is temporarily unavailable. Please try again.");
		}
	}

	@SuppressWarnings("unchecked")
	private String refundRazorpay(String chargeId, BigDecimal amount) {
		try {
			HttpHeaders headers = razorpayHeaders();
			Map<String, Object> body = Map.of("amount", toPaise(amount));

			ResponseEntity<Map> response = gatewayRestTemplate.exchange(
					gatewayProperties.getRazorpay().getBaseUrl() + "/v1/payments/" + chargeId + "/refund",
					HttpMethod.POST,
					new HttpEntity<>(body, headers),
					Map.class
			);

			Map<String, Object> refund = response.getBody();
			return (String) refund.get("id");

		} catch (Exception e) {
			throw new GatewayException(PaymentErrorCode.GATEWAY_ERROR,
					"Refund processing failed. Please try again.");
		}
	}

	// ── Header builders ───────────────────────────────────────────────────────

	private HttpHeaders stripeHeaders(String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		String encoded = Base64.getEncoder().encodeToString(
				(gatewayProperties.getStripe().getSecretKey() + ":").getBytes());
		headers.set("Authorization", "Basic " + encoded);
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		if (idempotencyKey != null) {
			headers.set("Idempotency-Key", idempotencyKey);
		}
		return headers;
	}

	private HttpHeaders razorpayHeaders() {
		HttpHeaders headers = new HttpHeaders();
		String credentials = gatewayProperties.getRazorpay().getKeyId() + ":" +
				gatewayProperties.getRazorpay().getKeySecret();
		headers.set("Authorization", "Basic " +
				Base64.getEncoder().encodeToString(credentials.getBytes()));
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private HttpHeaders npciHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-API-Key", gatewayProperties.getNpci().getApiKey());
		headers.set("X-Merchant-Id", gatewayProperties.getNpci().getMerchantId());
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Convert INR BigDecimal to paise (smallest unit). ₹100.00 → 10000 */
	private long toPaise(BigDecimal amount) {
		return amount.multiply(BigDecimal.valueOf(100))
				.setScale(0, RoundingMode.HALF_UP)
				.longValue();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseStripeError(String body) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
		} catch (Exception e) {
			return Map.of();
		}
	}
}
