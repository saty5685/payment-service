package com.deezywallet.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.enums.PaymentTypeEnum;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Extended payment response for admin endpoints.
 * Includes internal fields: fraudScore, gatewayChargeId, gatewayDeclineCode,
 * idempotencyKey, version — all hidden from regular users.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminPaymentResponse {

	private String            paymentId;
	private String            userId;
	private String            walletId;
	private PaymentTypeEnum   type;
	private PaymentStatusEnum status;
	private String            statusDescription;

	private BigDecimal        amount;
	private String            currency;
	private BigDecimal        refundedAmount;

	private PaymentGatewayEnum gateway;
	private String            paymentMethodId;
	private String            gatewayChargeId;    // internal — for refunds + reconciliation
	private String            gatewayEventId;     // internal — webhook event that confirmed
	private String            gatewayDeclineCode; // internal — raw decline code from gateway

	private BigDecimal        fraudScore;         // internal — never shown to users
	private String            failureReason;
	private String            idempotencyKey;
	private String            threeDsRedirectUrl;

	private LocalDateTime     createdAt;
	private LocalDateTime     capturedAt;
	private Long              version;            // for debugging optimistic lock conflicts
}
