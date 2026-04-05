package com.deezywallet.payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.enums.PaymentTypeEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Core payment record — one row per payment attempt.
 *
 * DESIGN DECISIONS:
 *
 * gatewayChargeId:
 *   The charge/payment intent ID returned by Stripe (ch_xxx or pi_xxx) or
 *   Razorpay (pay_xxx). Stored for:
 *   - Idempotency: if the gateway call fails after charge succeeds (network drop),
 *     we can detect the duplicate on retry by checking this field.
 *   - Refunds: admin refund calls require the original charge ID.
 *   - Reconciliation: settlement team matches this against gateway reports.
 *   NULL until the gateway call returns successfully.
 *
 * gatewayEventId:
 *   The webhook event ID from Stripe (evt_xxx) or Razorpay.
 *   UNIQUE constraint — second webhook delivery for same event is a no-op.
 *   NULL for synchronous charges (no webhook involved).
 *
 * threeDsRedirectUrl:
 *   Stored in DB (not just Redis) so it survives a service restart during
 *   the 15-minute 3DS window. Redis TTL-based cleanup is the fast path;
 *   DB is the authoritative state.
 *
 * refundedAmount:
 *   Tracks partial refunds. Zero initially; incremented on each refund.
 *   When refundedAmount == amount, status transitions to REFUNDED.
 *
 * PCI DSS NOTE:
 *   NO columns for raw card data (PAN, CVV, full card number).
 *   paymentMethodId references the payment_methods table which stores only
 *   gateway tokens — opaque strings that cannot be reverse-engineered to
 *   obtain the original card number.
 *
 * @Version for optimistic locking:
 *   The 3DS timeout job and the webhook handler can both update the same
 *   payment (timeout racing with a late webhook arrival). @Version ensures
 *   only one wins — same pattern as Transaction.
 */
@Entity
@Table(
		name = "payments",
		indexes = {
				@Index(name = "idx_pay_user_id",          columnList = "user_id"),
				@Index(name = "idx_pay_status",            columnList = "status"),
				@Index(name = "idx_pay_idempotency_key",   columnList = "idempotency_key"),
				@Index(name = "idx_pay_gateway_charge_id", columnList = "gateway_charge_id"),
				@Index(name = "idx_pay_created_at",        columnList = "created_at"),
				@Index(name = "idx_pay_pending_3ds",       columnList = "status, created_at")
		}
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

	@Id
	@Column(length = 36, updatable = false, nullable = false)
	private String id;

	// ── Parties ───────────────────────────────────────────────────────────────

	@Column(name = "user_id", nullable = false, length = 36)
	private String userId;

	/** Wallet to be credited (for top-ups) or debited (for withdrawals) */
	@Column(name = "wallet_id", nullable = false, length = 36)
	private String walletId;

	// ── Payment details ───────────────────────────────────────────────────────

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentTypeEnum type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	@Builder.Default
	private PaymentStatusEnum status = PaymentStatusEnum.PENDING;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	@Builder.Default
	private String currency = "INR";

	// ── Payment method ────────────────────────────────────────────────────────

	/** FK to payment_methods.id — the tokenised card or bank account used */
	@Column(name = "payment_method_id", length = 36)
	private String paymentMethodId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentGatewayEnum gateway;

	// ── Gateway data ──────────────────────────────────────────────────────────

	/** Charge ID returned by gateway (ch_xxx, pay_xxx, pi_xxx) — NULL until charged */
	@Column(name = "gateway_charge_id", length = 100)
	private String gatewayChargeId;

	/**
	 * Webhook event ID — UNIQUE to prevent double-processing.
	 * NULL for synchronous charges without webhook confirmation.
	 */
	@Column(name = "gateway_event_id", unique = true, length = 100)
	private String gatewayEventId;

	/** 3DS redirect URL — NULL unless status is PENDING_3DS */
	@Column(name = "three_ds_redirect_url", length = 1000)
	private String threeDsRedirectUrl;

	// ── Refund tracking ───────────────────────────────────────────────────────

	@Column(name = "refunded_amount", precision = 19, scale = 4)
	@Builder.Default
	private BigDecimal refundedAmount = BigDecimal.ZERO;

	// ── Failure context ───────────────────────────────────────────────────────

	@Column(name = "failure_reason", length = 200)
	private String failureReason;

	/** Gateway-specific decline code (e.g. "insufficient_funds", "do_not_honor") */
	@Column(name = "gateway_decline_code", length = 100)
	private String gatewayDeclineCode;

	// ── Idempotency ───────────────────────────────────────────────────────────

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 36)
	private String idempotencyKey;

	// ── Fraud ─────────────────────────────────────────────────────────────────

	@Column(name = "fraud_score", precision = 5, scale = 4)
	private BigDecimal fraudScore;

	// ── Timestamps ────────────────────────────────────────────────────────────

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "captured_at")
	private LocalDateTime capturedAt;

	// ── Optimistic locking ────────────────────────────────────────────────────

	@Version
	@Column(nullable = false)
	@Builder.Default
	private Long version = 0L;

	// ── Business helpers ──────────────────────────────────────────────────────

	@Transient
	public boolean isRefundable() {
		return this.status == PaymentStatusEnum.CAPTURED ||
				this.status == PaymentStatusEnum.PARTIALLY_REFUNDED;
	}

	@Transient
	public BigDecimal getRefundableAmount() {
		return amount.subtract(refundedAmount);
	}
}
