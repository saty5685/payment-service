package com.deezywallet.payment.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.enums.PaymentMethodTypeEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tokenised payment method stored in the vault.
 *
 * PCI DSS REQUIREMENTS:
 *   - Raw PAN (card number) is NEVER stored here or anywhere in our system.
 *   - CVV is NEVER stored — Stripe/Razorpay discard it after tokenisation.
 *   - gatewayToken is the opaque reference issued by the gateway (pm_xxx, token_xxx).
 *   - Card metadata (last4, brand, expiry) is stored for display purposes only —
 *     this data is in the "account data" category (not CHD) and may be stored
 *     per PCI DSS if the gateway provides it.
 *
 * SOFT DELETE (isActive flag):
 *   Payment methods are never hard-deleted — historical payments reference them.
 *   Deleting a payment_method row would orphan the FK on the payments table.
 *   Instead, isActive=false hides the method from the user's payment method list
 *   while preserving the FK integrity for historical records.
 *
 * isDefault:
 *   One method per user can be the default. Enforced at application layer
 *   (not DB constraint) to allow atomic default-switching.
 *
 * bankAccountEncrypted:
 *   For BANK_ACCOUNT type, the account number is AES-256 encrypted at rest.
 *   IFSC code is stored plain (not sensitive — publicly listed bank codes).
 */
@Entity
@Table(
		name = "payment_methods",
		indexes = {
				@Index(name = "idx_pm_user_id", columnList = "user_id"),
				@Index(name = "idx_pm_active",  columnList = "user_id, is_active")
		}
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentMethod {

	@Id
	@Column(length = 36, updatable = false, nullable = false)
	private String id;

	@Column(name = "user_id", nullable = false, length = 36)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentMethodTypeEnum type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentGatewayEnum gateway;

	/**
	 * Opaque gateway token — Stripe pm_xxx, Razorpay token_xxx, etc.
	 * This is the only "card credential" we store. Cannot be reversed to PAN.
	 */
	@Column(name = "gateway_token", nullable = false, length = 200)
	private String gatewayToken;

	// ── Card metadata (display only, not CHD per PCI DSS) ────────────────────

	/** Last 4 digits — safe to store, used for "Card ending in 4242" display */
	@Column(name = "card_last4", length = 4)
	private String cardLast4;

	/** Card brand: VISA, MASTERCARD, AMEX, RUPAY */
	@Column(name = "card_brand", length = 20)
	private String cardBrand;

	/** Expiry month (1-12) */
	@Column(name = "card_expiry_month")
	private Integer cardExpiryMonth;

	/** Expiry year (4-digit) */
	@Column(name = "card_expiry_year")
	private Integer cardExpiryYear;

	// ── Bank account fields (for BANK_ACCOUNT type) ───────────────────────────

	/** AES-256 encrypted bank account number — null for non-bank methods */
	@Column(name = "bank_account_encrypted", length = 500)
	private String bankAccountEncrypted;

	/** IFSC code — not sensitive, publicly listed */
	@Column(name = "ifsc_code", length = 11)
	private String ifscCode;

	/** Account holder name */
	@Column(name = "account_holder_name", length = 100)
	private String accountHolderName;

	// ── UPI fields ────────────────────────────────────────────────────────────

	/** UPI Virtual Payment Address, e.g. user@okicici */
	@Column(name = "upi_vpa", length = 100)
	private String upiVpa;

	// ── Status ────────────────────────────────────────────────────────────────

	@Column(name = "is_active", nullable = false)
	@Builder.Default
	private boolean isActive = true;

	@Column(name = "is_default", nullable = false)
	@Builder.Default
	private boolean isDefault = false;

	@Column(name = "nickname", length = 50)
	private String nickname;  // User-assigned label: "My HDFC Savings"

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	// ── Business helper ───────────────────────────────────────────────────────

	@Transient
	public boolean isCardExpired() {
		if (cardExpiryYear == null || cardExpiryMonth == null) return false;
		LocalDateTime now = LocalDateTime.now();
		return (cardExpiryYear < now.getYear()) ||
				(cardExpiryYear.equals(now.getYear()) && cardExpiryMonth < now.getMonthValue());
	}
}
