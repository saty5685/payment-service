package com.deezywallet.payment.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentEventTypeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published when a payment fails or expires.
 *
 * Consumed by:
 *   Notification Service → sends failure alert to user
 *
 * failureReason: human-readable — safe to show to user.
 *   Does NOT include raw gateway decline codes (e.g. "do_not_honor").
 *   PaymentService translates those into user-friendly messages before publishing.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailedEvent {

	private String               eventId;
	private PaymentEventTypeEnum eventType;  // PAYMENT_FAILED

	private String               paymentId;
	private String               userId;
	private BigDecimal           amount;
	private String               currency;
	private String               failureReason;  // user-safe message
	private LocalDateTime        occurredAt;
}
