package com.deezywallet.payment.enums;

/**
 * Lifecycle states for a payment attempt (top-up or withdrawal).
 *
 * STATE MACHINE:
 * ─────────────────────────────────────────────────────────────────────
 *  PENDING ──── sync charge success ──────→ CAPTURED   (terminal ✓)
 *  PENDING ──── 3DS required ─────────────→ PENDING_3DS
 *  PENDING ──── charge failed ─────────────→ FAILED    (terminal ✗)
 *  PENDING_3DS ─ webhook success ──────────→ CAPTURED  (terminal ✓)
 *  PENDING_3DS ─ webhook failure ──────────→ FAILED    (terminal ✗)
 *  PENDING_3DS ─ 15-min timeout ───────────→ EXPIRED   (terminal ✗)
 *  CAPTURED ─── admin refund ─────────────→ REFUNDED  (terminal)
 *  CAPTURED ─── partial refund ───────────→ PARTIALLY_REFUNDED
 * ─────────────────────────────────────────────────────────────────────
 *
 * WHY separate PENDING and PENDING_3DS?
 *   PENDING = gateway call not yet made (fraud check running or gateway call in flight).
 *   PENDING_3DS = gateway call made, charge ID exists, waiting for cardholder auth.
 *   The distinction matters for the timeout job: PENDING_3DS timeouts require
 *   a gateway cancel call; plain PENDING timeouts do not (nothing to cancel).
 */
public enum PaymentStatusEnum {

	/** Initial state — fraud check + gateway call not yet made */
	PENDING(1),

	/** Gateway returned a 3DS redirect URL — waiting for cardholder authentication */
	PENDING_3DS(2),

	/** Charge successful — PAYMENT_CAPTURED event published */
	CAPTURED(3),

	/** Charge failed (declined, insufficient funds, fraud block) */
	FAILED(4),

	/** PENDING_3DS timed out — cardholder did not complete authentication */
	EXPIRED(5),

	/** Full refund processed back to original payment method */
	REFUNDED(6),

	/** Partial refund processed — balance still captured */
	PARTIALLY_REFUNDED(7);

	private final int id;

	PaymentStatusEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static PaymentStatusEnum getEnumById(int id) {
		switch (id) {
			case 1 -> { return PENDING; }
			case 2 -> { return PENDING_3DS; }
			case 3 -> { return CAPTURED; }
			case 4 -> { return FAILED; }
			case 5 -> { return EXPIRED; }
			case 6 -> { return REFUNDED; }
			case 7 -> { return PARTIALLY_REFUNDED; }
			default -> { return null; }
		}
	}

	public boolean isTerminal() {
		return switch (this) {
			case CAPTURED, FAILED, EXPIRED, REFUNDED -> true;
			default -> false;
		};
	}

	public boolean isSuccessful() {
		return this == CAPTURED || this == PARTIALLY_REFUNDED;
	}
}

