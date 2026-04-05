package com.deezywallet.payment.enums;

/**
 * Category of the payment operation.
 *
 * TOPUP:      External card/bank → user wallet. Payment Service charges the
 *             external method and publishes PAYMENT_CAPTURED to credit the wallet.
 * WITHDRAWAL: User wallet → bank account. Payment Service calls NPCI/IMPS
 *             after Wallet Service confirms the debit.
 * REFUND:     Reversal of a TOPUP back to the original payment method.
 *             Admin-initiated or triggered by a failed top-up credit.
 */
public enum PaymentTypeEnum {
	TOPUP(1),
	WITHDRAWAL(2),
	REFUND(3);

	private final int id;

	PaymentTypeEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static PaymentTypeEnum getEnumById(int id) {
		switch (id) {
			case 1 -> { return TOPUP; }
			case 2 -> { return WITHDRAWAL; }
			case 3 -> { return REFUND; }
			default -> { return null; }
		}
	}
}

