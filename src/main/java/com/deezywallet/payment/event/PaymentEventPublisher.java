package com.deezywallet.payment.event;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.entity.Payment;
import com.deezywallet.payment.entity.WithdrawalRequest;
import com.deezywallet.payment.enums.PaymentEventTypeEnum;
import com.deezywallet.payment.event.outbound.PaymentCapturedEvent;
import com.deezywallet.payment.event.outbound.PaymentFailedEvent;
import com.deezywallet.payment.event.outbound.WithdrawalCompletedEvent;
import com.deezywallet.payment.event.outbound.WithdrawalFailedEvent;
import com.deezywallet.payment.event.outbound.WithdrawalInitiatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes all payment domain events to payment.events Kafka topic.
 *
 * All events keyed by paymentId or withdrawalId → same ordering guarantee
 * per payment on the same partition.
 *
 * Fire-and-forget — never throws. All failures are logged with enough
 * context for a manual replay script.
 *
 * PAYMENT_CAPTURED is the most critical event — it triggers Wallet Service
 * to credit the user's wallet. If this publish fails, the user has been
 * charged but their wallet balance has not been updated.
 * Production hardening: implement the Outbox Pattern — write the event
 * to a DB outbox table inside the same transaction as the payment status
 * update, then poll-and-publish. This guarantees exactly-once semantics.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	// ── Payment events ────────────────────────────────────────────────────────

	public void publishPaymentCaptured(Payment payment) {
		PaymentCapturedEvent event = PaymentCapturedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(PaymentEventTypeEnum.PAYMENT_CAPTURED)
				.paymentId(payment.getId())
				.userId(payment.getUserId())
				.walletId(payment.getWalletId())
				.amount(payment.getAmount())
				.currency(payment.getCurrency())
				.gateway(payment.getGateway())
				.gatewayChargeId(payment.getGatewayChargeId())
				.capturedAt(payment.getCapturedAt())
				.occurredAt(LocalDateTime.now())
				.build();

		publish(payment.getId(), event, PaymentEventTypeEnum.PAYMENT_CAPTURED);
	}

	public void publishPaymentFailed(Payment payment, String failureReason) {
		PaymentFailedEvent event = PaymentFailedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(PaymentEventTypeEnum.PAYMENT_FAILED)
				.paymentId(payment.getId())
				.userId(payment.getUserId())
				.amount(payment.getAmount())
				.currency(payment.getCurrency())
				.failureReason(failureReason)
				.occurredAt(LocalDateTime.now())
				.build();

		publish(payment.getId(), event, PaymentEventTypeEnum.PAYMENT_FAILED);
	}

	// ── Withdrawal events ─────────────────────────────────────────────────────

	public void publishWithdrawalInitiated(WithdrawalRequest withdrawal) {
		WithdrawalInitiatedEvent event = WithdrawalInitiatedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(PaymentEventTypeEnum.WITHDRAWAL_INITIATED)
				.withdrawalId(withdrawal.getId())
				.userId(withdrawal.getUserId())
				.walletId(withdrawal.getWalletId())
				.amount(withdrawal.getAmount())
				.currency(withdrawal.getCurrency())
				.neftImpsReference(withdrawal.getNeftImpsReference())
				.occurredAt(LocalDateTime.now())
				.build();

		publish(withdrawal.getId(), event, PaymentEventTypeEnum.WITHDRAWAL_INITIATED);
	}

	public void publishWithdrawalCompleted(WithdrawalRequest withdrawal) {
		WithdrawalCompletedEvent event = WithdrawalCompletedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(PaymentEventTypeEnum.WITHDRAWAL_COMPLETED)
				.withdrawalId(withdrawal.getId())
				.userId(withdrawal.getUserId())
				.walletId(withdrawal.getWalletId())
				.amount(withdrawal.getAmount())
				.currency(withdrawal.getCurrency())
				.neftImpsReference(withdrawal.getNeftImpsReference())
				.completedAt(withdrawal.getCompletedAt())
				.occurredAt(LocalDateTime.now())
				.build();

		publish(withdrawal.getId(), event, PaymentEventTypeEnum.WITHDRAWAL_COMPLETED);
	}

	public void publishWithdrawalFailed(WithdrawalRequest withdrawal, String reason) {
		WithdrawalFailedEvent event = WithdrawalFailedEvent.builder()
				.eventId(UUID.randomUUID().toString())
				.eventType(PaymentEventTypeEnum.WITHDRAWAL_FAILED)
				.withdrawalId(withdrawal.getId())
				.userId(withdrawal.getUserId())
				.walletId(withdrawal.getWalletId())
				.amount(withdrawal.getAmount())
				.failureReason(reason)
				.occurredAt(LocalDateTime.now())
				.build();

		publish(withdrawal.getId(), event, PaymentEventTypeEnum.WITHDRAWAL_FAILED);
	}

	// ── Internal publish ──────────────────────────────────────────────────────

	private void publish(String key, Object event, PaymentEventTypeEnum type) {
		try {
			kafkaTemplate.send(PaymentConstants.TOPIC_PAYMENT_EVENTS, key, event);
			log.debug("Published {}: key={}", type, key);
		} catch (Exception e) {
			log.error("KAFKA PUBLISH FAILED: type={} key={} error={}",
					type, key, e.getMessage(), e);
		}
	}
}
