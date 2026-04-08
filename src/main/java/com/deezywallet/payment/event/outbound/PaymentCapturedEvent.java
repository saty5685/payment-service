package com.deezywallet.payment.event.outbound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentEventTypeEnum;
import com.deezywallet.payment.enums.PaymentGatewayEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Published to payment.events when a payment is successfully captured.
 *
 * Consumed by:
 *   Wallet Service   → credits the user's wallet (PAYMENT_CAPTURED triggers top-up credit)
 *   Ledger Service   → records DEBIT external / CREDIT wallet
 *   Notification Svc → sends receipt push + email
 *
 * WHY include gatewayChargeId in the event?
 *   Ledger Service stores this for reconciliation against gateway settlement reports.
 *   Without it, Ledger cannot verify which gateway charge corresponds to which
 *   ledger entry during end-of-month reconciliation.
 *
 * WHY include walletId?
 *   Wallet Service needs the walletId to credit the right wallet.
 *   It consumes this event via a @KafkaListener — having the walletId in the
 *   event avoids a synchronous lookup call to User Service.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCapturedEvent {

	private String             eventId;
	private PaymentEventTypeEnum eventType;  // PAYMENT_CAPTURED

	private String             paymentId;
	private String             userId;
	private String             walletId;       // target wallet to credit

	private BigDecimal         amount;
	private String             currency;

	private PaymentGatewayEnum gateway;
	private String             gatewayChargeId; // for Ledger reconciliation

	private LocalDateTime      capturedAt;
	private LocalDateTime      occurredAt;
}
