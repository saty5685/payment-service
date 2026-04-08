package com.deezywallet.payment.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published when NPCI confirms the bank transfer completed.
 *
 * Consumed by:
 *   Notification Service → sends "Your withdrawal of ₹X has been credited to your bank"
 *   Ledger Service       → records DEBIT wallet / CREDIT bank account
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalCompletedEvent {

	private String               eventId;
	private PaymentEventTypeEnum eventType;  // WITHDRAWAL_COMPLETED

	private String               withdrawalId;
	private String               userId;
	private String               walletId;
	private BigDecimal           amount;
	private String               currency;
	private String               neftImpsReference;
	private LocalDateTime        completedAt;
	private LocalDateTime        occurredAt;
}
