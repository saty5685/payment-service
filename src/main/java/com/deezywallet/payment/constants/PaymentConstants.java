package com.deezywallet.payment.constants;

import java.math.BigDecimal;

/**
 * Compile-time constants for Payment Service.
 */
public final class PaymentConstants {

	private PaymentConstants() {}

	// ── Kafka topics ──────────────────────────────────────────────────────────

	/** All payment events published by this service */
	public static final String TOPIC_PAYMENT_EVENTS  = "payment.events";

	// ── Redis key prefixes ────────────────────────────────────────────────────

	/** Idempotency lock: pay:lock:{idempotencyKey} */
	public static final String REDIS_PAYMENT_LOCK_PREFIX = "pay:lock:";

	/**
	 * 3DS session state: pay:3ds:{paymentId} → redirect URL + expiry metadata.
	 * TTL matches PENDING_3DS_TIMEOUT_MINUTES.
	 */
	public static final String REDIS_3DS_SESSION_PREFIX  = "pay:3ds:";

	/** Webhook dedup lock: pay:webhook:{gatewayEventId} — prevents duplicate processing */
	public static final String REDIS_WEBHOOK_LOCK_PREFIX = "pay:webhook:";

	// ── Business limits ───────────────────────────────────────────────────────

	/** Minimum top-up amount */
	public static final BigDecimal MIN_TOPUP_AMOUNT       = new BigDecimal("100.00");

	/** Maximum single top-up amount */
	public static final BigDecimal MAX_TOPUP_AMOUNT       = new BigDecimal("100000.00");

	/** Minimum withdrawal amount */
	public static final BigDecimal MIN_WITHDRAWAL_AMOUNT  = new BigDecimal("100.00");

	/** Maximum single withdrawal amount */
	public static final BigDecimal MAX_WITHDRAWAL_AMOUNT  = new BigDecimal("200000.00");

	// ── Timeouts ──────────────────────────────────────────────────────────────

	/** Minutes before a PENDING_3DS payment is marked EXPIRED */
	public static final int PENDING_3DS_TIMEOUT_MINUTES   = 15;

	/** Seconds for the Redis idempotency lock TTL */
	public static final int IDEMPOTENCY_LOCK_TTL_SECONDS  = 3600;  // 1 hour

	/** Seconds for webhook dedup lock TTL */
	public static final int WEBHOOK_LOCK_TTL_SECONDS      = 86400; // 24 hours

	// ── Gateway ───────────────────────────────────────────────────────────────

	/** Timeout for Stripe/Razorpay API calls */
	public static final int GATEWAY_TIMEOUT_MS            = 10000; // 10 seconds

	/** Timeout for Fraud Service call during payment */
	public static final int FRAUD_SERVICE_TIMEOUT_MS      = 3000;

	/** Max fraud score to approve a payment */
	public static final double FRAUD_SCORE_THRESHOLD      = 0.75;

	// ── API paths ─────────────────────────────────────────────────────────────

	public static final String API_PAYMENT_BASE   = "/api/v1/payments";
	public static final String API_ADMIN_BASE     = "/api/v1/admin/payments";
	public static final String API_INTERNAL_BASE  = "/internal/v1/payments";
	public static final String ACTUATOR_HEALTH    = "/actuator/health";

	// ── Roles ─────────────────────────────────────────────────────────────────

	public static final String ROLE_USER             = "ROLE_USER";
	public static final String ROLE_ADMIN            = "ROLE_ADMIN";
	public static final String ROLE_INTERNAL_SERVICE = "ROLE_INTERNAL_SERVICE";

	// ── JWT claims (must match User Service) ──────────────────────────────────

	public static final String JWT_CLAIM_ROLES      = "roles";
	public static final String JWT_CLAIM_EMAIL      = "email";
	public static final String JWT_CLAIM_KYC_STATUS = "kycStatus";

	// ── Webhook header names ──────────────────────────────────────────────────

	/** Stripe webhook signature header */
	public static final String STRIPE_SIGNATURE_HEADER   = "Stripe-Signature";

	/** Razorpay webhook signature header */
	public static final String RAZORPAY_SIGNATURE_HEADER = "X-Razorpay-Signature";

	// ── Scheduling ────────────────────────────────────────────────────────────

	/** How often the 3DS expiry job runs (milliseconds) */
	public static final int EXPIRY_JOB_INTERVAL_MS = 60000; // 1 minute
}
