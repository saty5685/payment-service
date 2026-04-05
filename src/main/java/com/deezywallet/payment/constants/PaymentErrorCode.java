package com.deezywallet.payment.constants;

/**
 * Stable error codes for Payment Service.
 * Returned in ErrorResponse.errorCode — never rename after deployment.
 */
public final class PaymentErrorCode {

	private PaymentErrorCode() {}

	// ── Payment validation ────────────────────────────────────────────────────
	public static final String INVALID_AMOUNT            = "INVALID_AMOUNT";
	public static final String INVALID_PAYMENT_METHOD    = "INVALID_PAYMENT_METHOD";
	public static final String PAYMENT_METHOD_NOT_FOUND  = "PAYMENT_METHOD_NOT_FOUND";
	public static final String PAYMENT_METHOD_EXPIRED    = "PAYMENT_METHOD_EXPIRED";
	public static final String KYC_REQUIRED              = "KYC_REQUIRED";
	public static final String USER_NOT_ACTIVE           = "USER_NOT_ACTIVE";

	// ── Gateway ───────────────────────────────────────────────────────────────
	public static final String GATEWAY_DECLINED          = "GATEWAY_DECLINED";
	public static final String GATEWAY_TIMEOUT           = "GATEWAY_TIMEOUT";
	public static final String GATEWAY_ERROR             = "GATEWAY_ERROR";
	public static final String WEBHOOK_SIGNATURE_INVALID = "WEBHOOK_SIGNATURE_INVALID";

	// ── Fraud ─────────────────────────────────────────────────────────────────
	public static final String FRAUD_SCORE_EXCEEDED      = "FRAUD_SCORE_EXCEEDED";
	public static final String FRAUD_SERVICE_UNAVAILABLE = "FRAUD_SERVICE_UNAVAILABLE";

	// ── Idempotency ───────────────────────────────────────────────────────────
	public static final String DUPLICATE_PAYMENT         = "DUPLICATE_PAYMENT";

	// ── Payment state ─────────────────────────────────────────────────────────
	public static final String PAYMENT_NOT_FOUND         = "PAYMENT_NOT_FOUND";
	public static final String PAYMENT_NOT_REFUNDABLE    = "PAYMENT_NOT_REFUNDABLE";
	public static final String PAYMENT_ALREADY_TERMINAL  = "PAYMENT_ALREADY_TERMINAL";
	public static final String PAYMENT_EXPIRED           = "PAYMENT_EXPIRED";

	// ── Withdrawal ────────────────────────────────────────────────────────────
	public static final String WITHDRAWAL_NOT_FOUND      = "WITHDRAWAL_NOT_FOUND";
	public static final String INSUFFICIENT_WALLET_BALANCE = "INSUFFICIENT_WALLET_BALANCE";

	// ── Generic ───────────────────────────────────────────────────────────────
	public static final String VALIDATION_FAILED         = "VALIDATION_FAILED";
	public static final String INTERNAL_ERROR            = "INTERNAL_ERROR";
	public static final String ACCESS_DENIED             = "ACCESS_DENIED";
}
