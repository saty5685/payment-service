package com.deezywallet.payment.dto.request;

import java.math.BigDecimal;

import com.deezywallet.payment.enums.PaymentGatewayEnum;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wallet top-up request — external card/UPI/NetBanking → user wallet.
 *
 * paymentMethodId: references a saved payment_methods row.
 *   The client never submits raw card data here — the card was tokenised
 *   earlier via the gateway SDK directly (Stripe.js / Razorpay checkout).
 *   This endpoint only receives the opaque token reference.
 *
 * gateway: which gateway to route through.
 *   Client knows this because payment methods are tagged with their gateway.
 *   Validated at service layer: paymentMethodId.gateway must match this field.
 *
 * idempotencyKey: UUID — required on all mutation endpoints.
 *   On retry with the same key: return existing payment (200), not 409.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopUpRequest {

	@NotBlank(message = "Payment method ID is required")
	private String paymentMethodId;

	@NotNull(message = "Amount is required")
	@DecimalMin(value = "100.00", message = "Minimum top-up amount is ₹100.00")
	@DecimalMax(value = "100000.00", message = "Maximum top-up amount is ₹1,00,000.00")
	@Digits(integer = 15, fraction = 4)
	private BigDecimal amount;

	@NotNull(message = "Gateway is required")
	private PaymentGatewayEnum gateway;

	@NotBlank(message = "Idempotency key is required")
	@Pattern(
			regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
			message = "Idempotency key must be a valid UUID"
	)
	private String idempotencyKey;
}
