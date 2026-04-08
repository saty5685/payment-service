package com.deezywallet.payment.service;

import com.deezywallet.payment.config.GatewayProperties;
import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.exception.WebhookSignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * WebhookSignatureVerifier — HMAC-SHA256 verification for gateway webhooks.
 *
 * STRIPE SIGNATURE FORMAT:
 *   Stripe-Signature header: "t=1696862345,v1=abc123...,v0=xyz..."
 *   Signed payload: timestamp + "." + raw request body
 *   Algorithm: HMAC-SHA256 with the Stripe webhook endpoint secret
 *
 * RAZORPAY SIGNATURE FORMAT:
 *   X-Razorpay-Signature header: hex-encoded HMAC-SHA256
 *   Signed payload: raw request body
 *   Algorithm: HMAC-SHA256 with the Razorpay webhook secret
 *
 * WHY verify signatures?
 *   Webhooks are unauthenticated HTTP requests from the internet.
 *   Without signature verification, any actor who discovers our webhook URL
 *   can POST a fake "payment succeeded" event and trigger wallet credits.
 *   Signature verification proves the request originated from Stripe/Razorpay.
 *
 * TIMING ATTACK PROTECTION:
 *   Uses MessageDigest.isEqual() for constant-time comparison.
 *   String.equals() short-circuits on the first mismatch — timing differences
 *   can reveal how many characters of the signature match.
 *   MessageDigest.isEqual() always compares all bytes regardless of mismatch.
 *
 * REPLAY ATTACK PROTECTION (Stripe):
 *   The Stripe signature includes a timestamp. We reject webhooks where the
 *   timestamp is more than 5 minutes old. Even if an attacker captures a valid
 *   signed webhook, they cannot replay it after the tolerance window.
 *   Razorpay does not include a timestamp — replay protection is via the
 *   gatewayEventId uniqueness constraint in the DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookSignatureVerifier {

	private final GatewayProperties gatewayProperties;

	/** Maximum age of a Stripe webhook timestamp before rejection (seconds) */
	private static final int STRIPE_TIMESTAMP_TOLERANCE_SECONDS = 300; // 5 minutes

	// ── Stripe ────────────────────────────────────────────────────────────────

	/**
	 * Verifies a Stripe webhook signature.
	 *
	 * @param payload       raw request body bytes
	 * @param signatureHeader  value of the Stripe-Signature header
	 * @throws WebhookSignatureException if verification fails
	 */
	public void verifyStripe(byte[] payload, String signatureHeader) {
		if (signatureHeader == null || signatureHeader.isBlank()) {
			throw new WebhookSignatureException(
					"Missing " + PaymentConstants.STRIPE_SIGNATURE_HEADER + " header");
		}

		// Parse header: t=1696862345,v1=abc123def456...
		String timestamp = null;
		String v1Signature = null;

		for (String part : signatureHeader.split(",")) {
			if (part.startsWith("t=")) {
				timestamp = part.substring(2);
			} else if (part.startsWith("v1=")) {
				v1Signature = part.substring(3);
			}
		}

		if (timestamp == null || v1Signature == null) {
			throw new WebhookSignatureException("Malformed Stripe-Signature header");
		}

		// Replay protection: reject webhooks older than tolerance window
		long webhookEpoch = Long.parseLong(timestamp);
		long nowEpoch     = System.currentTimeMillis() / 1000;
		if (Math.abs(nowEpoch - webhookEpoch) > STRIPE_TIMESTAMP_TOLERANCE_SECONDS) {
			log.warn("Stripe webhook rejected: timestamp too old. webhookEpoch={} nowEpoch={}",
					webhookEpoch, nowEpoch);
			throw new WebhookSignatureException(
					"Webhook timestamp is outside tolerance window");
		}

		// Compute expected signature: HMAC-SHA256(timestamp + "." + payload)
		String signedPayload = timestamp + "." + new String(payload, StandardCharsets.UTF_8);
		String expectedSig   = hmacSha256(
				signedPayload.getBytes(StandardCharsets.UTF_8),
				gatewayProperties.getStripe().getWebhookSecret().getBytes(StandardCharsets.UTF_8)
		);

		// Constant-time comparison
		if (!constantTimeEquals(expectedSig, v1Signature)) {
			log.warn("Stripe webhook signature mismatch");
			throw new WebhookSignatureException("Invalid Stripe webhook signature");
		}

		log.debug("Stripe webhook signature verified");
	}

	// ── Razorpay ──────────────────────────────────────────────────────────────

	/**
	 * Verifies a Razorpay webhook signature.
	 *
	 * @param payload          raw request body bytes
	 * @param signatureHeader  value of the X-Razorpay-Signature header
	 * @throws WebhookSignatureException if verification fails
	 */
	public void verifyRazorpay(byte[] payload, String signatureHeader) {
		if (signatureHeader == null || signatureHeader.isBlank()) {
			throw new WebhookSignatureException(
					"Missing " + PaymentConstants.RAZORPAY_SIGNATURE_HEADER + " header");
		}

		String expectedSig = hmacSha256(
				payload,
				gatewayProperties.getRazorpay().getWebhookSecret().getBytes(StandardCharsets.UTF_8)
		);

		if (!constantTimeEquals(expectedSig, signatureHeader)) {
			log.warn("Razorpay webhook signature mismatch");
			throw new WebhookSignatureException("Invalid Razorpay webhook signature");
		}

		log.debug("Razorpay webhook signature verified");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private String hmacSha256(byte[] data, byte[] key) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			byte[] result = mac.doFinal(data);
			return HexFormat.of().formatHex(result);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}

	/**
	 * Constant-time string comparison to prevent timing attacks.
	 * Uses MessageDigest.isEqual() which always compares all bytes.
	 */
	private boolean constantTimeEquals(String a, String b) {
		byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
		byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(aBytes, bBytes);
	}
}
