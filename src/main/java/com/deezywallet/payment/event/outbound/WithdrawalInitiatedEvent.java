package com.deezywallet.payment.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published when a withdrawal bank transfer is initiated with NPCI.
 *
 * Consumed by:
 *   Notification Service → sends "Your withdrawal of ₹X is being processed.
 *                           UTR: XXXXXXXXXX. Funds arrive within 2 hours."
 *
 * The UTR (neftImpsReference) is included so the user can share it with
 * their bank if the funds don't arrive. It's the key reconciliation reference
 * for NEFT/IMPS transfers.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalInitiatedEvent {

	private String               eventId;
	private PaymentEventTypeEnum eventType;  // WITHDRAWAL_INITIATED

	private String               withdrawalId;
	private String               userId;
	private String               walletId;
	private BigDecimal           amount;
	private String               currency;
	private String               neftImpsReference;  // UTR for user reference
	private LocalDateTime        occurredAt;
}
