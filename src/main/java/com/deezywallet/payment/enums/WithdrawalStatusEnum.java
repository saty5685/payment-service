package com.deezywallet.payment.enums;

/**
 * Status of a bank withdrawal request.
 *
 * Withdrawals follow a different lifecycle than top-ups:
 *   1. User requests withdrawal → wallet is debited first (via Wallet Service command)
 *   2. Payment Service receives WALLET_DEBITED event → initiates bank transfer via NPCI
 *   3. NPCI responds asynchronously (minutes to hours for NEFT)
 *   4. Webhook/polling confirms bank credit → COMPLETED
 *
 * WALLET_DEBIT_PENDING: Withdrawal created, waiting for Wallet Service to confirm debit
 * BANK_TRANSFER_PENDING: Wallet debited, NPCI/IMPS transfer initiated
 * COMPLETED:  Bank account credited — terminal ✓
 * FAILED:     Bank transfer failed — wallet credit reversal needed
 * REVERSED:   Bank transfer failed, wallet balance restored — terminal ✗
 */
public enum WithdrawalStatusEnum {
	WALLET_DEBIT_PENDING(1),
	BANK_TRANSFER_PENDING(2),
	COMPLETED(3),
	FAILED(4),
	REVERSED(5);

	private final int id;

	WithdrawalStatusEnum(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static WithdrawalStatusEnum getEnumById(int id) {
		switch (id) {
			case 1 -> { return WALLET_DEBIT_PENDING; }
			case 2 -> { return BANK_TRANSFER_PENDING; }
			case 3 -> { return COMPLETED; }
			case 4 -> { return FAILED; }
			case 5 -> { return REVERSED; }
			default -> { return null; }
		}
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == REVERSED;
	}
}

