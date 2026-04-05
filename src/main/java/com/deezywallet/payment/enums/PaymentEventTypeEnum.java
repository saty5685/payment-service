package com.deezywallet.payment.enums;

/**
 * Event types published to payment.events Kafka topic.
 *
 * Consumed by:
 *   PAYMENT_CAPTURED     → Wallet Service (credit wallet), Ledger, Notification
 *   PAYMENT_FAILED       → Notification (failure alert to user)
 *   WITHDRAWAL_INITIATED → Notification (withdrawal confirmation)
 *   WITHDRAWAL_COMPLETED → Ledger (record withdrawal), Notification
 *   WITHDRAWAL_FAILED    → Notification (failure alert), triggers wallet refund
 */
public enum PaymentEventTypeEnum {
	PAYMENT_CAPTURED(1),
	PAYMENT_FAILED(2),
	WITHDRAWAL_INITIATED(3),
	WITHDRAWAL_COMPLETED(4),
	WITHDRAWAL_FAILED(5);

	private final int id;

	PaymentEventTypeEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static PaymentEventTypeEnum getEnumById(int id) {
		switch (id) {
			case 1 -> { return PAYMENT_CAPTURED; }
			case 2 -> { return PAYMENT_FAILED; }
			case 3 -> { return WITHDRAWAL_INITIATED; }
			case 4 -> { return WITHDRAWAL_COMPLETED; }
			case 5 -> { return WITHDRAWAL_FAILED; }
			default -> { return null; }
		}
	}
}

