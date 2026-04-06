package com.deezywallet.payment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.deezywallet.payment.entity.Payment;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.enums.PaymentTypeEnum;

/**
 * Payment repository.
 *
 * KEY DESIGN DECISIONS:
 *
 * findByGatewayEventId:
 *   Used by the webhook handler to detect duplicate deliveries BEFORE
 *   acquiring any locks. If an event ID is already in the DB, return it
 *   and respond 200 immediately — no further processing.
 *   The UNIQUE constraint on gateway_event_id is the authoritative guard;
 *   this query is the fast-path check.
 *
 * findByGatewayChargeId:
 *   Used during refund processing — admin submits a paymentId, we look up
 *   the gatewayChargeId to call the gateway refund API.
 *   Also used for reconciliation: batch jobs match gateway charge reports
 *   against our DB by charge ID.
 *
 * findPending3DsOlderThan:
 *   The 3DS expiry job queries this every 60 seconds.
 *   Composite index idx_pay_pending_3ds (status, created_at) makes this
 *   O(expired payments) not O(all payments).
 *   Returns at most a small batch — 3DS sessions rarely expire in production.
 *
 * updateStatus — targeted @Modifying UPDATE with version check:
 *   Webhook handler and the 3DS timeout job can both attempt to update
 *   the same payment (late webhook arrives as the timeout fires).
 *   AND version = :expectedVersion is the optimistic concurrency guard.
 *   If 0 rows updated — version changed, caller retries.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {

	// ── Idempotency lookups ───────────────────────────────────────────────────

	Optional<Payment> findByIdempotencyKey(String idempotencyKey);

	boolean existsByIdempotencyKey(String idempotencyKey);

	// ── Webhook dedup ─────────────────────────────────────────────────────────

	Optional<Payment> findByGatewayEventId(String gatewayEventId);

	boolean existsByGatewayEventId(String gatewayEventId);

	// ── Gateway charge lookup (for refunds + reconciliation) ──────────────────

	Optional<Payment> findByGatewayChargeId(String gatewayChargeId);

	// ── User-scoped queries ───────────────────────────────────────────────────

	Page<Payment> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	Page<Payment> findByUserIdAndStatusOrderByCreatedAtDesc(
			String userId, PaymentStatusEnum status, Pageable pageable);

	Page<Payment> findByUserIdAndTypeOrderByCreatedAtDesc(
			String userId, PaymentTypeEnum type, Pageable pageable);

	// ── Admin queries ─────────────────────────────────────────────────────────

	Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Page<Payment> findByStatusOrderByCreatedAtDesc(PaymentStatusEnum status, Pageable pageable);

	// ── 3DS expiry job ────────────────────────────────────────────────────────

	/**
	 * Finds PENDING_3DS payments older than the given cutoff.
	 * Called by ThreeDsExpiryJob every 60 seconds.
	 *
	 * Uses EnumType.STRING — JPQL compares the stored VARCHAR value
	 * against the enum name. @Enumerated(EnumType.STRING) handles this.
	 *
	 * WHY List instead of Page?
	 *   Expired 3DS sessions are rare in production — typically 0-5 per minute.
	 *   A Page adds ORDER BY + COUNT(*) overhead for what is almost always
	 *   an empty or near-empty result. Plain List is cheaper here.
	 */
	@Query("""
           SELECT p FROM Payment p
           WHERE p.status = 'PENDING_3DS'
             AND p.createdAt < :cutoff
           ORDER BY p.createdAt ASC
           """)
	List<Payment> findPending3DsOlderThan(@Param("cutoff") LocalDateTime cutoff);

	// ── Targeted status updates with optimistic concurrency ───────────────────

	/**
	 * Updates payment status with version check for optimistic concurrency.
	 *
	 * WHY include version in WHERE clause?
	 *   @Modifying JPQL bypasses Hibernate's standard @Version check —
	 *   we enforce it manually. Returns 0 if version has changed (lost race).
	 *
	 * EnumType.STRING: JPQL literal 'CAPTURED' matches the stored VARCHAR.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Payment p
           SET p.status = :newStatus, p.version = p.version + 1
           WHERE p.id = :paymentId AND p.version = :expectedVersion
           """)
	int updateStatus(
			@Param("paymentId")       String          paymentId,
			@Param("newStatus")       PaymentStatusEnum newStatus,
			@Param("expectedVersion") long            expectedVersion);

	/**
	 * Updates status + gatewayChargeId when a sync charge succeeds.
	 * Atomically records both the status change and the gateway reference.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Payment p
           SET p.status = :newStatus,
               p.gatewayChargeId = :chargeId,
               p.capturedAt = :capturedAt,
               p.version = p.version + 1
           WHERE p.id = :paymentId AND p.version = :expectedVersion
           """)
	int updateStatusWithChargeId(
			@Param("paymentId")       String            paymentId,
			@Param("newStatus")       PaymentStatusEnum newStatus,
			@Param("chargeId")        String            chargeId,
			@Param("capturedAt")      LocalDateTime     capturedAt,
			@Param("expectedVersion") long              expectedVersion);

	/**
	 * Updates status + 3DS redirect URL when gateway requires authentication.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Payment p
           SET p.status = 'PENDING_3DS',
               p.gatewayChargeId = :chargeId,
               p.threeDsRedirectUrl = :redirectUrl,
               p.version = p.version + 1
           WHERE p.id = :paymentId AND p.version = :expectedVersion
           """)
	int updateStatusTo3DS(
			@Param("paymentId")       String paymentId,
			@Param("chargeId")        String chargeId,
			@Param("redirectUrl")     String redirectUrl,
			@Param("expectedVersion") long   expectedVersion);

	/**
	 * Records failure with gateway decline code.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Payment p
           SET p.status = 'FAILED',
               p.failureReason = :reason,
               p.gatewayDeclineCode = :declineCode,
               p.version = p.version + 1
           WHERE p.id = :paymentId AND p.version = :expectedVersion
           """)
	int updateStatusToFailed(
			@Param("paymentId")       String paymentId,
			@Param("reason")          String reason,
			@Param("declineCode")     String declineCode,
			@Param("expectedVersion") long   expectedVersion);

	/**
	 * Records a refund — increments refundedAmount.
	 * If refundedAmount reaches amount, caller updates status to REFUNDED.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE Payment p
           SET p.refundedAmount = p.refundedAmount + :refundAmount,
               p.version = p.version + 1
           WHERE p.id = :paymentId AND p.version = :expectedVersion
           """)
	int incrementRefundedAmount(
			@Param("paymentId")       String         paymentId,
			@Param("refundAmount")    java.math.BigDecimal refundAmount,
			@Param("expectedVersion") long           expectedVersion);
}
