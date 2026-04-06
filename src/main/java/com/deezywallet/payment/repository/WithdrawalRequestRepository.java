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

import com.deezywallet.payment.entity.WithdrawalRequest;
import com.deezywallet.payment.enums.WithdrawalStatusEnum;

/**
 * Withdrawal request repository.
 *
 * WITHDRAWAL LIFECYCLE QUERIES:
 *
 * findByNeftImpsReference:
 *   NPCI delivers withdrawal confirmations by UTR (neft_imps_reference).
 *   The webhook handler uses this to look up which withdrawal to confirm.
 *
 * findByStatusAndCreatedAtBefore:
 *   The withdrawal timeout job detects BANK_TRANSFER_PENDING withdrawals
 *   that haven't received NPCI confirmation within 24 hours.
 *   NEFT can take up to 2 hours; IMPS is near-instant.
 *   If 24 hours pass with no NPCI webhook, we flag for manual review.
 *
 * updateStatus targeted UPDATE:
 *   Same pattern as PaymentRepository — version check for optimistic concurrency.
 *   NPCI webhook handler and the timeout job race to update withdrawal status.
 */
@Repository
public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, String> {

	// ── Idempotency ───────────────────────────────────────────────────────────

	Optional<WithdrawalRequest> findByIdempotencyKey(String idempotencyKey);

	boolean existsByIdempotencyKey(String idempotencyKey);

	// ── NPCI reference lookup (for webhook handler) ───────────────────────────

	Optional<WithdrawalRequest> findByNeftImpsReference(String neftImpsReference);

	// ── User-scoped queries ───────────────────────────────────────────────────

	Page<WithdrawalRequest> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	// ── Timeout detection ─────────────────────────────────────────────────────

	/**
	 * Finds BANK_TRANSFER_PENDING withdrawals older than cutoff.
	 * Used by the withdrawal timeout job for manual-review flagging.
	 *
	 * WHY BANK_TRANSFER_PENDING not WALLET_DEBIT_PENDING?
	 *   WALLET_DEBIT_PENDING = waiting for Wallet Service to confirm debit.
	 *   The Wallet Service debit happens synchronously in our flow — if it's
	 *   still WALLET_DEBIT_PENDING after hours, something has gone seriously wrong
	 *   and it already would have been caught by other monitoring.
	 *   BANK_TRANSFER_PENDING = NPCI transfer initiated, awaiting bank webhook.
	 *   This is the genuinely async step that can take up to 2h for NEFT.
	 */
	@Query("""
           SELECT w FROM WithdrawalRequest w
           WHERE w.status = 'BANK_TRANSFER_PENDING'
             AND w.createdAt < :cutoff
           ORDER BY w.createdAt ASC
           """)
	List<WithdrawalRequest> findStaleTransfers(@Param("cutoff") LocalDateTime cutoff);

	// ── Targeted status updates ───────────────────────────────────────────────

	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE WithdrawalRequest w
           SET w.status = :newStatus, w.version = w.version + 1
           WHERE w.id = :withdrawalId AND w.version = :expectedVersion
           """)
	int updateStatus(
			@Param("withdrawalId")    String               withdrawalId,
			@Param("newStatus")       WithdrawalStatusEnum newStatus,
			@Param("expectedVersion") long                 expectedVersion);

	/**
	 * Records the NPCI UTR reference when a bank transfer is initiated.
	 * Moves status WALLET_DEBIT_PENDING → BANK_TRANSFER_PENDING atomically.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE WithdrawalRequest w
           SET w.status = 'BANK_TRANSFER_PENDING',
               w.neftImpsReference = :utr,
               w.version = w.version + 1
           WHERE w.id = :withdrawalId AND w.version = :expectedVersion
           """)
	int updateStatusToBankTransferPending(
			@Param("withdrawalId")    String withdrawalId,
			@Param("utr")             String utr,
			@Param("expectedVersion") long   expectedVersion);

	/**
	 * Marks withdrawal COMPLETED — sets completedAt timestamp.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE WithdrawalRequest w
           SET w.status = 'COMPLETED',
               w.completedAt = :completedAt,
               w.version = w.version + 1
           WHERE w.id = :withdrawalId AND w.version = :expectedVersion
           """)
	int updateStatusToCompleted(
			@Param("withdrawalId")    String        withdrawalId,
			@Param("completedAt")     LocalDateTime completedAt,
			@Param("expectedVersion") long          expectedVersion);

	/**
	 * Marks withdrawal FAILED with reason.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
           UPDATE WithdrawalRequest w
           SET w.status = 'FAILED',
               w.failureReason = :reason,
               w.version = w.version + 1
           WHERE w.id = :withdrawalId AND w.version = :expectedVersion
           """)
	int updateStatusToFailed(
			@Param("withdrawalId")    String withdrawalId,
			@Param("reason")          String reason,
			@Param("expectedVersion") long   expectedVersion);
}
