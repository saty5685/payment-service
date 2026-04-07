package com.deezywallet.payment.service;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.entity.Payment;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.event.PaymentEventPublisher;
import com.deezywallet.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ThreeDsExpiryJob — marks PENDING_3DS payments as EXPIRED after 15 minutes.
 *
 * Runs every 60 seconds. Finds payments where:
 *   status = 'PENDING_3DS' AND created_at < NOW() - 15 minutes
 *
 * For each expired payment:
 *   1. Mark status → EXPIRED (using optimistic version check)
 *   2. Publish PAYMENT_FAILED event → Notification Service alerts user
 *
 * WHY mark EXPIRED separately from FAILED?
 *   EXPIRED: user didn't complete authentication within the time window.
 *   FAILED:  authentication was attempted but rejected by the bank.
 *   The distinction helps the UI show "Your session expired — please try again"
 *   vs "Your bank declined the payment."
 *
 * VERSION CHECK:
 *   updateStatus uses AND version = :expectedVersion.
 *   If a late webhook arrives simultaneously and marks the payment CAPTURED,
 *   the version will have changed — updateStatus returns 0 rows, we skip it.
 *   The webhook wins; the expiry job silently no-ops. Correct behaviour.
 *
 * DISTRIBUTED SAFETY:
 *   Multiple Payment Service replicas all run this job.
 *   The version check ensures only the first replica to update a row wins.
 *   Others find 0 rows updated and skip. No double-expiry, no double-event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThreeDsExpiryJob {

	private final PaymentRepository     paymentRepository;
	private final PaymentEventPublisher eventPublisher;

	@Scheduled(fixedDelayString = "${payment.expiry-job.interval-ms:60000}")
	public void expireStalePending3DsPayments() {
		LocalDateTime cutoff = LocalDateTime.now()
				.minusMinutes(PaymentConstants.PENDING_3DS_TIMEOUT_MINUTES);

		List<Payment> expired = paymentRepository.findPending3DsOlderThan(cutoff);

		if (expired.isEmpty()) return;

		log.info("ThreeDsExpiryJob: expiring {} stale PENDING_3DS payments", expired.size());

		for (Payment payment : expired) {
			try {
				expirePayment(payment);
			} catch (Exception e) {
				log.error("Error expiring paymentId={}: {}", payment.getId(), e.getMessage(), e);
			}
		}
	}

	@Transactional
	protected void expirePayment(Payment payment) {
		int updated = paymentRepository.updateStatus(
				payment.getId(),
				PaymentStatusEnum.EXPIRED,
				payment.getVersion()
		);

		if (updated == 0) {
			// Version changed — webhook arrived simultaneously and updated the payment.
			// The webhook result (CAPTURED or FAILED) takes precedence.
			log.debug("Payment id={} was updated by webhook — skipping expiry", payment.getId());
			return;
		}

		payment.setStatus(PaymentStatusEnum.EXPIRED);
		eventPublisher.publishPaymentFailed(payment,
				"Payment session expired — please try again");

		log.info("Payment expired (3DS timeout): id={}", payment.getId());
	}
}
