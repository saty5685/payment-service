package com.deezywallet.payment.enums;

/**
 * External payment gateway used for the charge.
 *
 * Stored on the payment record — determines:
 *   - Which gateway SDK/API to call
 *   - Which webhook secret to use for signature verification
 *   - Which refund API to invoke for admin refunds
 *
 * STRIPE:    International cards, recurring charges, 3DS2.
 * RAZORPAY:  Indian market, UPI, NetBanking, domestic card routing.
 * NPCI:      NEFT/IMPS for bank withdrawals. Not used for top-ups.
 *
 * WHY store gateway on the payment record?
 *   Refunds must go through the same gateway as the original charge.
 *   Webhook routing uses this field to apply the correct signature verification.
 */
public enum PaymentGatewayEnum {
	STRIPE(1),
	RAZORPAY(2),
	NPCI(3);

	private final int id;

	PaymentGatewayEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static PaymentGatewayEnum getEnumById(int id) {
		switch (id) {
			case 1 -> { return STRIPE; }
			case 2 -> { return RAZORPAY; }
			case 3 -> { return NPCI; }
			default -> { return null; }
		}
	}
}

