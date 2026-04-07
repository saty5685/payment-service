package com.deezywallet.payment.dto.response;

import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.enums.PaymentMethodTypeEnum;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Saved payment method response — safe subset for client display.
 *
 * NEVER returns:
 *   gatewayToken       — opaque gateway credential, must not be leaked
 *   bankAccountEncrypted — encrypted sensitive data
 *
 * Returns:
 *   cardLast4, cardBrand, expiry   — display metadata ("Visa ending 4242")
 *   upiVpa                          — not sensitive (it's the user's address)
 *   accountHolderName, ifscCode     — not sensitive (public bank identifiers)
 *
 * The client uses this to show "Pay with Visa •••• 4242 (expires 09/26)".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentMethodResponse {

	private String              methodId;
	private PaymentMethodTypeEnum type;
	private PaymentGatewayEnum  gateway;
	private boolean             isDefault;
	private String              nickname;

	// ── Card display ──────────────────────────────────────────────────────────
	private String              cardLast4;
	private String              cardBrand;
	private Integer             cardExpiryMonth;
	private Integer             cardExpiryYear;
	private boolean             cardExpired;       // computed — not stored

	// ── UPI ───────────────────────────────────────────────────────────────────
	private String              upiVpa;

	// ── Bank account ──────────────────────────────────────────────────────────
	private String              accountHolderName;
	private String              ifscCode;
	// NOTE: actual account number is never returned — only display info

	private LocalDateTime       createdAt;
}
