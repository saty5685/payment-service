package com.deezywallet.payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.deezywallet.payment.enums.WithdrawalStatusEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bank withdrawal request — wallet → user's bank account.
 *
 * SEPARATE TABLE from payments because withdrawals have a fundamentally
 * different lifecycle: they involve Wallet Service (debit first) and then
 * NPCI/bank transfer (credit externally). Mixing them into the payments table
 * would require many nullable columns for NPCI-specific data.
 *
 * neftImpsReference:
 *   The UTR (Unique Transaction Reference) returned by NPCI after initiating
 *   the bank transfer. Used for reconciliation with bank statements.
 *   NULL until the NPCI transfer is initiated.
 *
 * paymentMethodId → payment_methods.id (bank account record).
 *   The IFSC and encrypted account number are on the PaymentMethod entity.
 */
@Entity
@Table(
		name = "withdrawal_requests",
		indexes = {
				@Index(name = "idx_wr_user_id",   columnList = "user_id"),
				@Index(name = "idx_wr_status",    columnList = "status"),
				@Index(name = "idx_wr_created_at",columnList = "created_at")
		}
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WithdrawalRequest {

	@Id
	@Column(length = 36, updatable = false, nullable = false)
	private String id;

	@Column(name = "user_id", nullable = false, length = 36)
	private String userId;

	@Column(name = "wallet_id", nullable = false, length = 36)
	private String walletId;

	@Column(name = "payment_method_id", nullable = false, length = 36)
	private String paymentMethodId;  // Bank account to credit

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	@Builder.Default
	private String currency = "INR";

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	@Builder.Default
	private WithdrawalStatusEnum status = WithdrawalStatusEnum.WALLET_DEBIT_PENDING;

	/** UTR from NPCI — NULL until bank transfer initiated */
	@Column(name = "neft_imps_reference", length = 50)
	private String neftImpsReference;

	/** Idempotency key from the client request */
	@Column(name = "idempotency_key", nullable = false, unique = true, length = 36)
	private String idempotencyKey;

	@Column(name = "failure_reason", length = 200)
	private String failureReason;

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Version
	@Column(nullable = false)
	@Builder.Default
	private Long version = 0L;
}
