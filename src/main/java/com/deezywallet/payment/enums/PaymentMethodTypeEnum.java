package com.deezywallet.payment.enums;

/**
 * Type of saved payment method in the token vault.
 *
 * CARD:         Tokenised credit/debit card. PAN never stored — only the
 *               gateway token (e.g. Stripe's pm_xxx or Razorpay's token_xxx).
 * UPI:          UPI VPA (Virtual Payment Address) — e.g. user@okicici.
 *               Not a sensitive credential; stored as-is.
 * BANK_ACCOUNT: Bank account number + IFSC for NEFT/IMPS withdrawals.
 *               Stored encrypted at rest.
 * NETBANKING:   NetBanking token from Razorpay. Gateway manages the bank
 *               session; we store only the token reference.
 */
public enum PaymentMethodTypeEnum {
	CARD(1),
	UPI(2),
	BANK_ACCOUNT(3),
	NETBANKING(4);

	private final int id;

	PaymentMethodTypeEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static PaymentMethodTypeEnum getEnumById(int id) {
		switch (id) {
			case 1 -> { return CARD; }
			case 2 -> { return UPI; }
			case 3 -> { return BANK_ACCOUNT; }
			case 4 -> { return NETBANKING; }
			default -> { return null; }
		}
	}
}

