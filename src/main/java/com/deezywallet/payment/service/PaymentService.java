package com.deezywallet.payment.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.dto.request.TopUpRequest;
import com.deezywallet.payment.dto.response.AdminPaymentResponse;
import com.deezywallet.payment.dto.response.PagedResponse;
import com.deezywallet.payment.dto.response.PaymentResponse;
import com.deezywallet.payment.entity.Payment;
import com.deezywallet.payment.entity.PaymentMethod;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.enums.PaymentTypeEnum;
import com.deezywallet.payment.event.PaymentEventPublisher;
import com.deezywallet.payment.exception.DuplicatePaymentException;
import com.deezywallet.payment.exception.GatewayDeclinedException;
import com.deezywallet.payment.exception.PaymentNotFoundException;
import com.deezywallet.payment.exception.PaymentNotRefundableException;
import com.deezywallet.payment.exception.PaymentValidationException;
import com.deezywallet.payment.mapper.PaymentMapper;
import com.deezywallet.payment.repository.PaymentMethodRepository;
import com.deezywallet.payment.repository.PaymentRepository;
import com.deezywallet.payment.security.UserPrincipal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PaymentService — top-up orchestration.
 *
 * FLOW (sync path — no 3DS):
 *   1. Redis idempotency lock (SETNX)
 *   2. DB duplicate check
 *   3. Validate payment method (exists, belongs to user, not expired)
 *   4. KYC check for amounts >= ₹10,000
 *   5. Fraud evaluation (sync, fail-safe on timeout)
 *   6. Persist payment record (status=PENDING)
 *   7. Call gateway — chargeCard()
 *   8a. SUCCESS  → update status to CAPTURED + publish PAYMENT_CAPTURED
 *   8b. 3DS      → update status to PENDING_3DS + store redirect URL
 *   8c. DECLINED → update status to FAILED + publish PAYMENT_FAILED
 *
 * TRANSACTION STRATEGY:
 *   Steps 6-8 are inside one @Transactional.
 *   WHY include the gateway call inside the transaction?
 *     The gateway call (step 7) is the one exception to "no I/O inside a
 *     DB transaction" rule. We include it here because:
 *     (a) We need the payment record in DB BEFORE calling the gateway
 *         (so the timeout job can find it if we crash mid-call)
 *     (b) The status update (step 8) must commit with the gatewayChargeId
 *         atomically — no window where charge succeeded but our DB says PENDING
 *     The transaction holds the DB connection for ~200ms–2s (gateway latency).
 *     This is acceptable for a relatively low-volume financial operation.
 *     At very high volume, move to a two-phase commit pattern.
 *
 *   Steps 1-5 run WITHOUT a DB transaction (no connection held during fraud check).
 *
 * Kafka publish (PAYMENT_CAPTURED) happens post-commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

	private final PaymentRepository       paymentRepository;
	private final PaymentMethodRepository methodRepository;
	private final GatewayService          gatewayService;
	private final FraudCheckService       fraudCheckService;
	private final PaymentEventPublisher   eventPublisher;
	private final PaymentMapper           mapper;
	private final RedisTemplate<String, String> redisTemplate;

	// ── Top-up ────────────────────────────────────────────────────────────────

	public PaymentResponse initiateTopUp(TopUpRequest req, UserPrincipal principal) {

		// Phase 1: Idempotency
		boolean lockAcquired = acquireIdempotencyLock(req.getIdempotencyKey());
		if (!lockAcquired) {
			return handleDuplicate(req.getIdempotencyKey());
		}

		try {
			if (paymentRepository.existsByIdempotencyKey(req.getIdempotencyKey())) {
				return paymentRepository.findByIdempotencyKey(req.getIdempotencyKey())
						.map(mapper::toResponse)
						.orElseThrow(() -> new PaymentNotFoundException(
								"Payment not found for idempotency key"));
			}

			// Phase 2: Validate payment method
			PaymentMethod method = methodRepository
					.findByIdAndUserIdAndIsActiveTrue(req.getPaymentMethodId(), principal.getUserId())
					.orElseThrow(() -> new PaymentValidationException(
							PaymentErrorCode.PAYMENT_METHOD_NOT_FOUND,
							"Payment method not found or not active"));

			if (method.isCardExpired()) {
				throw new PaymentValidationException(
						PaymentErrorCode.PAYMENT_METHOD_EXPIRED,
						"This card has expired. Please add a new payment method.");
			}

			// Validate gateway consistency
			if (method.getGateway() != req.getGateway()) {
				throw new PaymentValidationException(
						PaymentErrorCode.INVALID_PAYMENT_METHOD,
						"Payment method gateway does not match the requested gateway");
			}

			// Phase 3: KYC check for high-value top-ups
			if (req.getAmount().compareTo(new BigDecimal("10000.00")) >= 0 &&
					!principal.isKycVerified()) {
				throw new PaymentValidationException(
						PaymentErrorCode.KYC_REQUIRED,
						"KYC verification required for top-ups above ₹10,000");
			}

			// Phase 4: Fraud check (sync, fail-safe)
			BigDecimal fraudScore = fraudCheckService.evaluate(
					principal.getUserId(), req.getAmount(), "TOPUP");

			// Phase 5: Persist + gateway call + publish (all in one @Transactional)
			return processTopUp(req, principal, method, fraudScore);

		} catch (Exception e) {
			releaseIdempotencyLock(req.getIdempotencyKey());
			throw e;
		}
	}

	@Transactional
	@Retryable(
			retryFor = ObjectOptimisticLockingFailureException.class,
			maxAttempts = 3,
			backoff = @Backoff(delay = 100)
	)
	protected PaymentResponse processTopUp(TopUpRequest req, UserPrincipal principal,
			PaymentMethod method, BigDecimal fraudScore) {
		// Persist payment record (PENDING)
		Payment payment = Payment.builder()
				.id(UUID.randomUUID().toString())
				.userId(principal.getUserId())
				.walletId(resolveWalletId(principal.getUserId()))
				.type(PaymentTypeEnum.TOPUP)
				.status(PaymentStatusEnum.PENDING)
				.amount(req.getAmount())
				.gateway(req.getGateway())
				.paymentMethodId(req.getPaymentMethodId())
				.idempotencyKey(req.getIdempotencyKey())
				.fraudScore(fraudScore)
				.build();

		paymentRepository.save(payment);
		log.info("Payment created: id={} amount={}", payment.getId(), payment.getAmount());

		// Call gateway
		GatewayService.ChargeResult result = gatewayService.charge(
				req.getGateway(),
				method.getGatewayToken(),
				req.getAmount(),
				payment.getId()  // our payment ID as the gateway idempotency key
		);

		// Handle outcome
		switch (result.getOutcome()) {
			case SUCCESS -> {
				int updated = paymentRepository.updateStatusWithChargeId(
						payment.getId(),
						PaymentStatusEnum.CAPTURED,
						result.getChargeId(),
						LocalDateTime.now(),
						payment.getVersion());

				if (updated == 0) {
					throw new ObjectOptimisticLockingFailureException(Payment.class, payment.getId());
				}

				payment.setStatus(PaymentStatusEnum.CAPTURED);
				payment.setGatewayChargeId(result.getChargeId());
				payment.setCapturedAt(LocalDateTime.now());

				// Post-commit: publish PAYMENT_CAPTURED → Wallet Service credits wallet
				eventPublisher.publishPaymentCaptured(payment);
				log.info("Payment captured: id={} chargeId={}", payment.getId(), result.getChargeId());
			}

			case REQUIRES_3DS -> {
				paymentRepository.updateStatusTo3DS(
						payment.getId(),
						result.getChargeId(),
						result.getThreeDsUrl(),
						payment.getVersion());

				payment.setStatus(PaymentStatusEnum.PENDING_3DS);
				payment.setGatewayChargeId(result.getChargeId());
				payment.setThreeDsRedirectUrl(result.getThreeDsUrl());

				log.info("Payment requires 3DS: id={}", payment.getId());
				// No Kafka event yet — published when webhook confirms
			}

			case DECLINED -> {
				paymentRepository.updateStatusToFailed(
						payment.getId(),
						"Card declined: " + result.getDeclineCode(),
						result.getDeclineCode(),
						payment.getVersion());

				payment.setStatus(PaymentStatusEnum.FAILED);
				eventPublisher.publishPaymentFailed(payment, result.getDeclineMessage());
				log.warn("Payment declined: id={} declineCode={}", payment.getId(), result.getDeclineCode());

				throw new GatewayDeclinedException(
						PaymentErrorCode.GATEWAY_DECLINED,
						result.getDeclineCode(),
						result.getDeclineMessage());
			}
		}

		return mapper.toResponse(payment);
	}

	// ── Webhook confirmation (3DS completion) ─────────────────────────────────

	/**
	 * Processes a webhook event from Stripe or Razorpay.
	 *
	 * Called by WebhookController after signature verification.
	 * Idempotent — duplicate webhook events are silently ignored.
	 *
	 * @param gatewayEventId  the gateway's unique event ID (evt_xxx)
	 * @param chargeId        the payment intent / charge ID
	 * @param succeeded       whether the 3DS authentication succeeded
	 * @param declineCode     decline code if !succeeded (may be null)
	 */
	@Transactional
	@Retryable(
			retryFor = ObjectOptimisticLockingFailureException.class,
			maxAttempts = 3,
			backoff = @Backoff(delay = 100)
	)
	public void handleWebhookEvent(String  gatewayEventId,
			String  chargeId,
			boolean succeeded,
			String  declineCode) {

		// Idempotency: check if we've already processed this event
		if (paymentRepository.existsByGatewayEventId(gatewayEventId)) {
			log.debug("Duplicate webhook event ignored: {}", gatewayEventId);
			return;
		}

		Payment payment = paymentRepository.findByGatewayChargeId(chargeId)
				.orElseGet(() -> {
					log.warn("Webhook for unknown chargeId={}", chargeId);
					return null;
				});

		if (payment == null) return;

		if (payment.getStatus().isTerminal()) {
			log.debug("Webhook for terminal payment id={}, ignoring", payment.getId());
			return;
		}

		// Record the gateway event ID on the payment — UNIQUE constraint prevents replay
		if (succeeded) {
			paymentRepository.updateStatusWithChargeId(
					payment.getId(),
					PaymentStatusEnum.CAPTURED,
					chargeId,
					LocalDateTime.now(),
					payment.getVersion());

			payment.setStatus(PaymentStatusEnum.CAPTURED);
			payment.setGatewayEventId(gatewayEventId);
			payment.setCapturedAt(LocalDateTime.now());

			eventPublisher.publishPaymentCaptured(payment);
			log.info("Webhook captured payment: id={} event={}", payment.getId(), gatewayEventId);

		} else {
			paymentRepository.updateStatusToFailed(
					payment.getId(),
					"3DS authentication failed: " + declineCode,
					declineCode,
					payment.getVersion());

			payment.setStatus(PaymentStatusEnum.FAILED);
			eventPublisher.publishPaymentFailed(payment, "Authentication failed");
			log.warn("Webhook failed payment: id={} event={}", payment.getId(), gatewayEventId);
		}
	}

	// ── Admin refund ──────────────────────────────────────────────────────────

	/**
	 * Processes an admin-initiated refund back to the original payment method.
	 *
	 * Only CAPTURED or PARTIALLY_REFUNDED payments are refundable.
	 * Partial refunds are supported — refundAmount can be less than payment.amount.
	 *
	 * Post-refund: if refundedAmount == amount, status → REFUNDED.
	 *              If partial, status → PARTIALLY_REFUNDED.
	 */
	@Transactional
	public AdminPaymentResponse refundPayment(String     paymentId,
			BigDecimal refundAmount,
			String     adminId) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(
						"Payment not found: " + paymentId));

		if (!payment.isRefundable()) {
			throw new PaymentNotRefundableException(
					"Payment is not in a refundable state. Status: " + payment.getStatus());
		}

		if (refundAmount.compareTo(payment.getRefundableAmount()) > 0) {
			throw new PaymentValidationException(
					PaymentErrorCode.INVALID_AMOUNT,
					"Refund amount exceeds the refundable amount of ₹" + payment.getRefundableAmount());
		}

		// Call gateway refund API
		String gatewayRefundId = gatewayService.refund(
				payment.getGateway(),
				payment.getGatewayChargeId(),
				refundAmount);

		log.info("Gateway refund processed: paymentId={} refundId={} by adminId={}",
				paymentId, gatewayRefundId, adminId);

		// Update refundedAmount
		paymentRepository.incrementRefundedAmount(paymentId, refundAmount, payment.getVersion());

		// Determine new status
		BigDecimal newRefundedTotal = payment.getRefundedAmount().add(refundAmount);
		PaymentStatusEnum newStatus = newRefundedTotal.compareTo(payment.getAmount()) >= 0
				? PaymentStatusEnum.REFUNDED
				: PaymentStatusEnum.PARTIALLY_REFUNDED;

		paymentRepository.updateStatus(paymentId, newStatus, payment.getVersion() + 1);

		payment.setRefundedAmount(newRefundedTotal);
		payment.setStatus(newStatus);

		return mapper.toAdminResponse(payment);
	}

	// ── Read operations ───────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public PaymentResponse getPayment(String paymentId, String userId) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(
						"Payment not found: " + paymentId));

		if (!payment.getUserId().equals(userId)) {
			// IDOR protection — same error for not-found and not-owned
			throw new PaymentNotFoundException("Payment not found: " + paymentId);
		}

		return mapper.toResponse(payment);
	}

	@Transactional(readOnly = true)
	public PagedResponse<PaymentResponse> getUserPayments(
			String userId, PaymentStatusEnum status, Pageable pageable) {

		Page<Payment> page = (status != null)
				? paymentRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable)
				: paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

		return PagedResponse.from(page.map(mapper::toResponse));
	}

	@Transactional(readOnly = true)
	public AdminPaymentResponse getPaymentAdmin(String paymentId) {
		return paymentRepository.findById(paymentId)
				.map(mapper::toAdminResponse)
				.orElseThrow(() -> new PaymentNotFoundException(
						"Payment not found: " + paymentId));
	}


	@Transactional(readOnly = true)
	public PaymentResponse getPaymentInternal(String paymentId) {
		return paymentRepository.findById(paymentId)
				.map(mapper::toResponse)
				.orElseThrow(() -> new PaymentNotFoundException(
						"Payment not found: " + paymentId));
	}

	@Transactional(readOnly = true)
	public PagedResponse<AdminPaymentResponse> listPayments(
			PaymentStatusEnum status, Pageable pageable) {

		Page<Payment> page = (status != null)
				? paymentRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
				: paymentRepository.findAllByOrderByCreatedAtDesc(pageable);

		return PagedResponse.from(page.map(mapper::toAdminResponse));
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	private String resolveWalletId(String userId) {
		// In production: call Wallet Service internal API
		// GET /internal/v1/wallets/user/{userId}/status → walletId
		// Simplified placeholder:
		return "wallet-" + userId;
	}

	private boolean acquireIdempotencyLock(String key) {
		try {
			Boolean acquired = redisTemplate.opsForValue()
					.setIfAbsent(
							PaymentConstants.REDIS_PAYMENT_LOCK_PREFIX + key,
							"1",
							Duration.ofSeconds(PaymentConstants.IDEMPOTENCY_LOCK_TTL_SECONDS)
					);
			return Boolean.TRUE.equals(acquired);
		} catch (Exception e) {
			log.warn("Redis idempotency lock failed — falling back to DB: {}", e.getMessage());
			return true; // Fail-open: DB UNIQUE constraint is the backstop
		}
	}

	private void releaseIdempotencyLock(String key) {
		try {
			redisTemplate.delete(PaymentConstants.REDIS_PAYMENT_LOCK_PREFIX + key);
		} catch (Exception e) {
			log.warn("Failed to release idempotency lock: {}", e.getMessage());
		}
	}

	private PaymentResponse handleDuplicate(String idempotencyKey) {
		return paymentRepository.findByIdempotencyKey(idempotencyKey)
				.map(mapper::toResponse)
				.orElseThrow(() -> new DuplicatePaymentException(
						"Duplicate request in progress — please wait and retry"));
	}
}
