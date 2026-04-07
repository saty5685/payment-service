package com.deezywallet.payment.dto.request;

import java.math.BigDecimal;

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
 * Bank withdrawal request — user wallet → bank account via NPCI.
 *
 * bankAccountMethodId: references a BANK_ACCOUNT type payment_methods row.
 *   The bank account number + IFSC are stored encrypted on that row.
 *   Never submitted in the request body — pulled from the vault.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalRequest {

	@NotBlank(message = "Bank account method ID is required")
	private String bankAccountMethodId;

	@NotNull(message = "Amount is required")
	@DecimalMin(value = "100.00", message = "Minimum withdrawal amount is ₹100.00")
	@DecimalMax(value = "200000.00", message = "Maximum withdrawal amount is ₹2,00,000.00")
	@Digits(integer = 15, fraction = 4)
	private BigDecimal amount;

	@NotBlank(message = "Idempotency key is required")
	@Pattern(
			regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
			message = "Idempotency key must be a valid UUID"
	)
	private String idempotencyKey;
}
