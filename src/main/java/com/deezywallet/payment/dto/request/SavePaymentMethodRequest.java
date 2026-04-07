package com.deezywallet.payment.dto.request;

import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.enums.PaymentMethodTypeEnum;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to save a tokenised payment method to the vault.
 *
 * The client submits this AFTER the gateway SDK has tokenised the card.
 * Flow:
 *   1. Client renders Stripe.js / Razorpay checkout widget
 *   2. Card data goes directly to Stripe/Razorpay (never touches our servers)
 *   3. Gateway returns a token (pm_xxx, token_xxx)
 *   4. Client POSTs this request with that token + display metadata
 *
 * gatewayToken: the opaque token from the gateway.
 *   Must be treated as a credential — stored but never logged or returned
 *   in full to the client after saving.
 *
 * cardLast4 / cardBrand / expiryMonth / expiryYear:
 *   Client-submitted display metadata. Not sensitive per PCI DSS.
 *   We trust the client for display fields — the gateway is the authority
 *   on the actual card details. Server-side validation not needed here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavePaymentMethodRequest {

	@NotNull(message = "Payment method type is required")
	private PaymentMethodTypeEnum type;

	@NotNull(message = "Gateway is required")
	private PaymentGatewayEnum gateway;

	@NotBlank(message = "Gateway token is required")
	@Size(max = 200)
	private String gatewayToken;

	// ── Card metadata (for CARD type) ─────────────────────────────────────────

	@Pattern(regexp = "^\\d{4}$", message = "Card last 4 digits must be exactly 4 digits")
	private String cardLast4;

	@Size(max = 20)
	private String cardBrand;  // "VISA", "MASTERCARD", etc.

	@Min(1) @Max(12)
	private Integer cardExpiryMonth;

	@Min(2024)
	private Integer cardExpiryYear;

	// ── UPI (for UPI type) ────────────────────────────────────────────────────

	@Pattern(
			regexp = "^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$",
			message = "Invalid UPI VPA format"
	)
	private String upiVpa;

	// ── Bank account (for BANK_ACCOUNT type) ──────────────────────────────────
	// Raw account number accepted here — encrypted before storage

	@Pattern(
			regexp = "^[0-9]{9,18}$",
			message = "Bank account number must be 9-18 digits"
	)
	private String bankAccountNumber;  // Encrypted before storage, never returned

	@Pattern(
			regexp = "^[A-Z]{4}0[A-Z0-9]{6}$",
			message = "IFSC must be in format XXXX0XXXXXX"
	)
	private String ifscCode;

	@Size(max = 100)
	private String accountHolderName;

	// ── Display ───────────────────────────────────────────────────────────────

	@Size(max = 50)
	private String nickname;

	private boolean setAsDefault;
}
