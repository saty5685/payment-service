package com.deezywallet.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.WithdrawalStatusEnum;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Withdrawal request response.
 *
 * neftImpsReference — included once the bank transfer is initiated.
 * Users can use this UTR to verify with their bank if funds don't arrive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WithdrawalResponse {

	private String               withdrawalId;
	private WithdrawalStatusEnum status;
	private String               statusDescription;

	private BigDecimal           amount;
	private String               currency;

	private String               bankAccountMethodId;
	private String               neftImpsReference;  // UTR — present once transfer initiated

	private LocalDateTime        createdAt;
	private LocalDateTime        completedAt;
}
