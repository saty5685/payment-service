package com.deezywallet.payment.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published when a bank transfer fails.
 *
 * Consumed by:
 *   Notification Service → alerts user, tells them funds will be reversed
 *
 * The wallet credit reversal (returning funds) is handled separately —
 * WithdrawalService publishes a WALLET_CREDIT_CMD (TODO: implement).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalFailedEvent {

	private String               eventId;
	private PaymentEventTypeEnum eventType;  // WITHDRAWAL_FAILED

	private String               withdrawalId;
	private String               userId;
	private String               walletId;
	private BigDecimal           amount;
	private String               failureReason;
	private LocalDateTime        occurredAt;
}
